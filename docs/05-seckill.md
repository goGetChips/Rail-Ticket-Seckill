# 秒杀与库存

**读者**：想搞清"秒杀为什么快、为什么不会超卖"的人。
**解决什么问题**：Redis 的 key 怎么设计、Lua 为什么必须原子、什么情况下需要分布式锁、幂等有几层、以及**九条异常路径逐条怎么办**。
**不包含**：业务流程时序（→ [03](03-business-flow.md)）、表结构（→ [06](06-database.md)）。

> ⚠️ **本文除 Lua 脚本与 Redis 命令行为外，其余均为设计（计划中 / 阶段 5~9）。** 当前代码只实现了车站查询。实现状态见 [status/development-status.md](status/development-status.md)。

---

## 一、不超卖的三道防线

| 防线 | 位置 | 失效场景 | 详见 |
| --- | --- | --- | --- |
| ① **Lua 原子扣减** | Redis | Redis 挂了 / 数据丢失 / 主从切换 | 本文 §2 |
| ② **条件 UPDATE（CAS）** | MySQL | — | [06-database.md §4](06-database.md) |
| ③ **CHECK 约束** | MySQL | — | [06-database.md §4](06-database.md) |

**为什么三道都要**：① 是性能层（挡掉绝大多数无效请求），②③ 是正确性层。**只有数据库层是"无论上游怎么乱来，都一定成立"的。**

---

## 二、Redis 方案

### 2.1 Key 设计

| Key | 类型 | TTL | 用途 |
| --- | --- | --- | --- |
| `rail:stock:{trainId}:{date}:{seatType}` | String(int) | 到发车日 + 1 天 | **可售余票**（原子扣减对象） |
| `rail:stock:init:{trainId}:{date}` | String | 1 天 | 预热完成标记（防重复预热） |
| `rail:train:{trainId}` | Hash/JSON | 30 min ± 随机 | 车次详情缓存 |
| `rail:train:empty:{trainId}` | String("1") | 5 min | **空值缓存**（防穿透） |
| `rail:seckill:result:{userId}:{trainId}:{date}` | String | 1 天 | 秒杀结果（供客户端轮询，避免查 DB） |

**TTL 加随机值的理由（防雪崩）**：如果 100 个热点车次的缓存都在同一秒过期，那一瞬间所有请求都会穿透到 DB。给 TTL 加 ±10% 的随机抖动，让过期时间分散开。

### 2.2 Lua 脚本：原子扣减（核心）

脚本设计稿见 [scripts/env/lua/stock_deduct.lua](../scripts/env/lua/stock_deduct.lua)（阶段 7 会移入 `src/main/resources/lua/`）。

```lua
-- KEYS[1] = 库存 key        KEYS[2] = 用户去重 key (Set)      ARGV[1] = userId
-- 返回: >=0 扣减后的余票 | -1 库存不足 | -2 用户重复 | -3 未预热
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -3 end          -- key 不存在 = 未预热/被清空
if stock <= 0 then return -1 end            -- 库存不足
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2                               -- 该用户已抢过
end
redis.call('DECR', KEYS[1])                 -- 扣减
redis.call('SADD', KEYS[2], ARGV[1])        -- 记录用户
return stock - 1
```

**为什么整个逻辑必须写在同一个 Lua 脚本里**：Redis 执行 Lua 脚本时是**单线程、不可中断**的。如果把这四步拆成四次客户端调用，"判断库存"和"扣减库存"之间就会被其他请求插入——这正是 [01-project-guide.md §2](01-project-guide.md) 描述的超卖场景。**Lua 把"判断 + 扣减 + 去重"变成一个原子操作，这是不超卖的第一道、也是最关键的保证。**

**为什么库存 key 不存在时返回 -3 而不是当作 0**：这两种情况的处理完全不同——`0` 是正常售罄（返回"已售完"），key 不存在是**系统故障**（未预热或 Redis 被清空），应该告警并拒绝服务。**如果把故障当成售罄，用户会以为票卖完了，而实际上是系统坏了——这是最危险的一类 bug。**

**为什么先判断后扣减，而不是先 DECR 再回滚**：DECR 会把库存短暂改成负数，对账任务或监控一旦在这个瞬间读到，就会误报。

**为什么用户去重放在扣库存之前**：顺序反过来的话，重复请求会先把库存扣掉再被拒绝，需要额外回补，多一次写操作。

**⚠️ 已知限制（必须诚实说明）**：这个脚本的正确性依赖「**单节点 Redis**」。如果配置了主从，主节点扣减成功后**在同步到从节点之前宕机**，从节点升主后库存会"回滚"到旧值 → **超卖**。Redis 官方文档明确承认异步复制有这个窗口。本项目用单节点规避，**但面试被问到"Redis 宕机怎么办"时必须主动讲这个限制**，而不是说"Redis 不会宕机"。

### 2.3 缓存穿透 / 击穿 / 雪崩

| 问题 | 场景 | 本项目做法 | 需要分布式锁吗 |
| --- | --- | --- | --- |
| **穿透** | 查询不存在的车次，请求全部打到 DB | 空值缓存（`rail:train:empty:*`，TTL 5min） | 不需要 |
| **击穿** | 某热点车次缓存刚好过期，瞬间大量请求重建 | **逻辑过期 + 异步重建**：缓存不设物理 TTL，value 里存逻辑过期时间，发现过期后由一个线程异步重建、其余请求返回旧值 | 不需要（用"只让一个线程重建"的标志位即可） |
| **雪崩** | 大量 key 同时过期 | TTL 加 ±10% 随机抖动 | 不需要 |

> **为什么本项目"不需要"布隆过滤器**：车次数据量小（几千条），空值缓存已经足够，布隆过滤器的引入成本（数据同步、误判处理）大于收益。**面试时要说清："我知道布隆过滤器，但这个项目的数据量和穿透特征用空值缓存性价比更高。"** 这比硬加一个用不上的组件更能体现判断力。

### 2.4 分布式锁：这里到底需不需要

**结论：主链路不需要，只有两个地方需要。**

| 场景 | 需要锁吗 | 理由 |
| --- | --- | --- |
| 秒杀扣库存 | ❌ **不需要** | Lua 脚本本身原子，加锁是**画蛇添足**，还会引入锁超时、锁误删、看门狗续期等一堆新问题 |
| 用户去重 | ❌ **不需要** | 已在同一个 Lua 脚本内完成 |
| 订单创建 | ❌ 不需要 | DB 唯一索引即可 |
| **库存预热** | ✅ **需要** | 多实例同时启动时，必须保证只有一个实例把 MySQL 库存灌入 Redis，否则会**重复累加**导致超卖 |
| **对账/补偿任务** | ✅ **需要** | 定时任务在多实例下会重复执行 |

**预热用的锁**：

```
SET rail:lock:warmup:{trainId}:{date} <uuid> NX PX 30000
```

- `NX`：不存在才设置（互斥）
- `PX 30000`：30 秒自动过期，**防止持锁实例崩溃后死锁**
- `<uuid>`：**释放锁时必须校验是自己的**

**为什么释放锁要用 Lua 校验 uuid**：`if GET(key) == uuid then DEL(key)`。如果直接 `DEL`，会出现：A 拿锁 → A 卡住 30 秒 → 锁自动过期 → B 拿到锁 → A 醒来执行 `DEL` → **删掉了 B 的锁**。这是分布式锁最经典的 bug，也是面试高频考点。

---

## 三、幂等的三层设计

| 层级 | 手段 | 挡住什么 | 定位 |
| --- | --- | --- | --- |
| 第一层 | Redis Lua 内 `SISMEMBER` 去重 | 99% 的重复点击 | **性能优化**（快，但不是保证） |
| 第二层 | `t_local_message` 的唯一键 `uk_biz_id_type` | MQ 重复投递 | 阶段 9 的机制保证 |
| 第三层 | DB 唯一索引 `uk_user_train_date_seat`（建在 `t_order_item` 上） | **所有漏网的重复** | **正确性底线** |

**为什么必须有第三层**：Redis 会被清空、会重启、主从切换会丢数据；MQ 的"至少一次投递"语义意味着重复是**必然**而非意外。**只有数据库的唯一索引是"无论上游怎么乱来，都一定成立"的。**

> 核心认知：**幂等是消费端的责任，不是 MQ 的责任。** RocketMQ 和 RabbitMQ 都只保证"至少一次"投递。指望 MQ 不重复投递，等于把正确性交给一个它从未承诺过的保证。

索引定义与验证证据见 [06-database.md](06-database.md)。

---

## 四、MQ 消息设计（阶段 9）

| 消息 | 生产者 | 消费者 | 幂等键 |
| --- | --- | --- | --- |
| `ORDER_CREATE` | order-service（秒杀请求线程） | order-service（消费者） | `order_no` |
| `STOCK_DEDUCT` | order-service | inventory-service | `order_no` |
| `ORDER_TIMEOUT`（延时消息） | order-service（下单时发，延时 30 min） | order-service | `order_no` |
| `STOCK_RELEASE` | order-service（关单时） | inventory-service | `order_no` + `RELEASE` |

---

## 五、异常路径矩阵 ⭐

**这张表是本项目的核心资产。** 面试官问"XX 情况怎么办"，答案就在这里。

| # | 异常场景 | 处理方式 | 残留风险 |
| --- | --- | --- | --- |
| E1 | Redis 扣减成功，MQ 发送失败 | 本地消息表 `PENDING` + 补偿任务扫描重发 | 写 Redis 成功但写本地消息表也失败（极小概率）→ 靠对账发现 |
| E2 | MQ 发送成功，消费者宕机 | MQ 未收到 ack → 重投 → 消费端幂等（唯一索引） | 无（至少一次 + 幂等 = 效果等价于恰好一次） |
| E3 | 消费者成功，DB 写失败 | 不 ack，消息重投；重投后仍失败 → 进入死信队列 | 死信需人工/定时任务介入 |
| E4 | MQ 重复投递（同一消息投 3 次） | 唯一索引 `uk_user_train_date_seat` 拦截，捕获重复键异常后**当作成功直接 ack** | 无 |
| E5 | 用户超时未支付 | 延时消息触发关单 → 发 `STOCK_RELEASE` | 见 E6 |
| E6 | **支付与超时关单的竞态** | 关单前用 `UPDATE t_order SET status=2 WHERE order_no=? AND status=0`；**支付同理** `WHERE status=0`。**靠状态机 CAS 保证只有一个成功**，失败方回滚自己的操作 | 无（本项目竞态处理的核心手法） |
| E7 | 消息积压 | 增加消费者并行度；先定位是消费慢还是生产突增 | 积压期间用户看到"排队中"时间变长 |
| E8 | Redis 整体宕机 | **快速失败，不降级到 DB**（返回 503） | 秒杀完全不可用，但 DB 不会被打挂 |
| E9 | 服务重启后 Redis 库存丢失 | 启动时扫描未结束的车次重新预热；`rail:stock:init:*` 标记防重复 | 预热窗口内该车次不可抢 |

> **E6 值得单独强调**：这是"最后一张票被两个人抢到"的**支付版本**。用 `WHERE status=0` 做条件更新，利用**单条 SQL 的原子性**做状态机 CAS——**不需要分布式锁**。数据库的条件更新本身就是原子的，这是很多人忽略的简单答案。

**⚠️ 由 E6 引出的一个必答追问**：**永远不要"先回滚库存再看订单状态"**。必须先原子地确认订单确实被取消了（受影响行数 = 1），再回滚库存。顺序反了就会把一张已经付款的票卖两次——**后果是超卖 + 资金纠纷**。

---

## 六、如何证明"不会超卖"

面试官问"你怎么证明不会超卖"时，答案是**这套可复现的验证**，不是"我用了 Redis 所以不会超卖"。

### 手段 1：并发单元测试

```java
// 200 个线程同时抢 100 张票，断言恰好卖出 100 张
CountDownLatch start = new CountDownLatch(1);   // 让所有线程同时起跑
CountDownLatch done  = new CountDownLatch(threads);
start.countDown();  done.await();
// 断言：① 成功数 == 100   ② 库存表 sold_count <= total_count   ③ 无重复订单
```

### 手段 2：压测后数据校验（SQL）

```sql
-- ① 超卖检查：必须返回 0 行
SELECT * FROM rail_inventory.t_seat_inventory WHERE sold_count > total_count;

-- ② 重复购票检查：必须返回 0 行
SELECT user_id, train_id, travel_date, seat_type, COUNT(*) c
FROM rail_order.t_order_item
GROUP BY user_id, train_id, travel_date, seat_type
HAVING c > 1;

-- ③ 订单数与库存一致性：两列必须相等
SELECT (SELECT COUNT(*) FROM rail_order.t_order WHERE status <> 2) AS order_cnt,
       (SELECT SUM(sold_count) FROM rail_inventory.t_seat_inventory) AS sold_cnt;

-- ④ Redis 与 MySQL 对账：Redis 余票 + MySQL 已售 必须等于总票额
--    Redis: GET rail:stock:{trainId}:{date}:{seatType}
--    MySQL: SELECT total_count - sold_count FROM t_seat_inventory WHERE ...
```

### 手段 3：对账定时任务

每 5 分钟跑一次上述 SQL，发现不一致就告警并记录。**这也是 Redis 与 MySQL 最终一致性的兜底。**

> 手段 2 中的 ① 和 ② 已经**在本机实测过**（用违规数据确认数据库真的拒绝），见 [06-database.md §5](06-database.md)。手段 1、3 待阶段 5~9 实施。

---

## 七、相关文档

- [03-business-flow.md](03-business-flow.md) —— 秒杀主链路时序、验收标准 A1~A6
- [06-database.md](06-database.md) —— 条件 UPDATE 与 CHECK 约束的 DDL、实测证据
- [07-risks.md](07-risks.md) —— 上面每条机制的失效场景
- [scripts/env/lua/stock_deduct.lua](../scripts/env/lua/stock_deduct.lua) —— Lua 脚本设计稿
