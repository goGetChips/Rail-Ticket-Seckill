# 秒杀与库存

**读者**：想搞清"秒杀为什么快、为什么不会超卖"的人。
**解决什么问题**：Redis 的 key 怎么设计、Lua 为什么必须原子、什么情况下需要分布式锁、幂等有几层、以及**九条异常路径逐条怎么办**。
**不包含**：业务流程时序（→ [03](03-business-flow.md)）、表结构（→ [06](06-database.md)）。

> ⚠️ **本文的 Redis / MQ 部分仍是设计（计划中 / 阶段 7~9）；MySQL 那两道防线（② 条件 UPDATE、③ CHECK 约束）已在阶段 5 落地。** 实现状态见 [status/development-status.md](status/development-status.md)。
>
> 🔴 **但"落地"在阶段 5 要打个折**：MySQL 侧的**代码全部写完了，
> 一次都没运行过**（写它们的时候本机 MySQL 被 DLP 加密起不来）。
> 所以本文所有阶段 5 的内容都标 🟡，**没有任何实测数字**。
> 唯一 🟢 的是**阶段 2 就验证过的东西**（CHECK 约束会拒绝违规数据、
> 条件 UPDATE 在手工执行时受影响行数是 1 / 0）——
> 那部分**不依赖阶段 5 的 Java 代码**。
>
> 具体到本文：
> - §一 的 ② 条件 UPDATE —— 🟡 **代码已实现**（`SeatInventoryMapper.deductStock`）；**SQL 语义**本身 🟢（阶段 2 D1/D2）
> - §一 的 ③ CHECK 约束 —— 🟢 阶段 2 已实测
> - §二（Redis）、§三 的第一层、§四（MQ）、§五 中依赖 Redis/MQ 的条目 —— **仍是设计**
> - §五 的 **E6（状态机 CAS）代码已在阶段 5 落地**（`payOrder` 用 `WHERE status=0` 做条件更新，不需要分布式锁）—— 🟡 未运行
> - §六 的验证手段 —— 手段 1、2 的代码与脚本都已就位，**但本文不记录任何实测数字**，理由见 §六 末尾

---

## 一、不超卖的三道防线

| 防线 | 位置 | 状态 | 失效场景 | 详见 |
| --- | --- | --- | --- | --- |
| ① **Lua 原子扣减** | Redis | ⏳ 阶段 7 | Redis 挂了 / 数据丢失 / 主从切换 | 本文 §2 |
| ② **条件 UPDATE（CAS）** | MySQL | 🟡 **SQL 语义已实测（阶段 2 D1/D2）；应用层链路未实测** | — | [06-database.md §4](06-database.md) |
| ③ **CHECK 约束** | MySQL | 🟢 阶段 2 已建（**约束行为已实测**） | — | [06-database.md §4](06-database.md) |

> ⚠️ **② 为什么是 🟡 而不是 🟢** —— 它测过一半，**别把这一半当成全部**：
>
> | | 状态 |
> | --- | --- |
> | 这条 SQL **本身的语义**（有余票 → 1 行，售罄 → 0 行） | 🟢 [sql/99_verify.sql](../sql/99_verify.sql) 的 D1/D2，阶段 2 在 mysql 客户端里手工跑过 |
> | **Java 应用有没有用对它**（0 行 → 409？并发下会不会超卖？catch 住异常后事务提交还是回滚？） | 🟡 代码写完，**一次都没跑过** |
>
> **后一行才是阶段 5 的判据。** 标记约定见 [01-project-guide.md §十](01-project-guide.md)。

**为什么三道都要**：① 是性能层（挡掉绝大多数无效请求），②③ 是正确性层。**只有数据库层是"无论上游怎么乱来，都一定成立"的。**

⭐ **阶段 5 交付的是 ②** —— 全项目唯一一句会修改库存的 SQL：

```sql
UPDATE rail_inventory.t_seat_inventory
   SET sold_count = sold_count + 1
 WHERE train_id = ? AND travel_date = ? AND seat_type = ?
   AND sold_count < total_count          -- ← 这一行就是不超卖
```

**受影响的三种结果必须被区分**（`sql/03_rail_inventory.sql` §4 定下的规格）：

| 受影响行数 | 含义 | 怎么处理 |
| --- | --- | --- |
| `1` | 扣减成功 | 继续写订单 |
| `0` | **正常业务失败**（售罄） | 返回 **409**，不是异常、不是 500 |
| 抛异常 | **系统失败、结果未知** | 回滚，返回 500 |

⚠️ 实现手法上有一个**很容易写错**的点：自增必须是数据库算的
（`SET sold_count = sold_count + 1`），**绝不能是 Java 算好再写进去**
（`SET sold_count = #{javaValue}`）。后者是"丢失更新"：
两个事务都读到 99、都写 100 → 卖出 2 张票但库存只 +1 → **少卖**。

⚠️ 「0 行是**正常**业务失败」这个判断很容易被写成"返回 false / 抛异常"。
所以 Mapper 的返回值是 `int` 而不是 `boolean` —— `boolean` 会把 0 压成 `false`，
读起来像"失败了"，恰好抹掉这条规格。0 / 1 / 抛异常是三态，必须能被调用方看见。

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

> ⚠️⚠️ **E4 的「当作成功直接 ack」有一个前提，阶段 5 实地踩过了。**
>
> 那句话说的是**消息**被 ack（这条消息确实处理完了，不该重投），
> **不是**"把事务也提交掉"。两者在代码里长得极像，但后果完全不同：
>
> ```
> catch (DuplicateKeyException e) {
>     return;          // ⛔ 如果此前已经写过别的东西 → 那些写入会被 COMMIT
> }                    //    接口/消费端都"看起来正常"，数据却是半完成的
> ```
>
> **判据：`catch` 块里只能 `throw`，不能 `return`** —— 除非你能保证
> 走到这个 catch 时**本次事务还没有写过任何东西**。
>
> 阶段 5 的具体案例：`OrderService.createOrder` 的顺序是
> ①扣库存 → ②写订单 → ③写票 → ③撞上唯一索引。此时①的扣减**已经执行过了**，
> 一 `return` 就会把那次扣减提交、订单回滚 → **静默「少卖」**。
> 所以那里只能 `throw`，让整个事务连①一起回滚。
>
> ⭐ 这条纪律值得记住：**"幂等"和"回滚"是两件事**。
> 幂等说的是"重复执行不产生额外副作用"；
> 而 catch-return 的问题是"把已经发生的副作用留下来了"。

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

> **可直接执行**：整套校验（含建 fixture 与清理）在
> [sql/12_verify_stage5_order.sql](../sql/12_verify_stage5_order.sql)。
> 下面的 SQL 是它的摘要，改动时**两处必须一起改**。

```sql
-- ① 超卖检查：必须返回 0 行
SELECT * FROM rail_inventory.t_seat_inventory WHERE sold_count > total_count;

-- ② 重复购票检查：必须返回 0 行
SELECT user_id, train_id, travel_date, seat_type, COUNT(*) c
FROM rail_order.t_order_item
GROUP BY user_id, train_id, travel_date, seat_type
HAVING c > 1;

-- ③ 订单 ↔ 扣减流水 对账：必须返回 0 行（原版见下方说明，那条是错的）
SELECT g.train_id, g.travel_date, g.seat_type,
       g.order_cnt, COALESCE(f.flow_sum, 0) AS flow_sum
FROM (SELECT train_id, travel_date, seat_type, COUNT(*) AS order_cnt
      FROM rail_order.t_order_item o
      JOIN rail_order.t_order t ON t.id = o.order_id AND t.status <> 2
      GROUP BY train_id, travel_date, seat_type) g
LEFT JOIN (SELECT train_id, travel_date, seat_type, SUM(change_count) AS flow_sum
           FROM rail_inventory.t_stock_flow WHERE change_type = 2
           GROUP BY train_id, travel_date, seat_type) f
       ON f.train_id = g.train_id AND f.travel_date = g.travel_date AND f.seat_type = g.seat_type
WHERE g.order_cnt <> COALESCE(f.flow_sum, 0);

-- ④ Redis 与 MySQL 对账：Redis 余票 + MySQL 已售 必须等于总票额
--    ⏳ **阶段 7 起有效** —— 阶段 5 没有 Redis，这条无从执行。
--    Redis: GET rail:stock:{trainId}:{date}:{seatType}
--    MySQL: SELECT total_count - sold_count FROM t_seat_inventory WHERE ...

-- ⑤ 少卖检查：必须返回 0 行（阶段 5 新增）
SELECT o.order_no, f.id, f.change_count
FROM rail_order.t_order o
LEFT JOIN rail_inventory.t_stock_flow f
       ON f.biz_id = o.order_no AND f.change_type = 2
WHERE o.status <> 2 AND (f.id IS NULL OR f.change_count <> 1);
```

#### ⚠️ ③ 的原版是错的，这里说明它错在哪

原版是：

```sql
SELECT (SELECT COUNT(*) FROM rail_order.t_order WHERE status <> 2) AS order_cnt,
       (SELECT SUM(sold_count) FROM rail_inventory.t_seat_inventory) AS sold_cnt;
-- 然后要求 order_cnt = sold_cnt
```

**这两个数从第一次运行起就不可能相等**，所以它是一个「必然失败的检查」——
而必然失败的检查等于没有检查（没人会去看一个永远亮的红灯）。

原因很具体：

- `SUM(sold_count)` 把**种子数据里本来就卖掉的几百张**全算进来了
  （`sql/11_seed_inventory.sql` 写 sold_count 时，`t_order` 还是 0 行）
- `t_order` 的计数从 **0** 开始

于是 `sold_cnt` 永远大于 `order_cnt`，差额是那几百张历史销量。

**正确的问法**：不要问"全库总共卖了多少张"（这个问题没有答案，因为
基线数据不可考），要问"**同一趟车、同一天、同一席别，卖出的票数
和扣下的库存是否一致**"。后者与种子数据无关，因为种子数据没有订单，
也不会参与 `t_order_item` 的分组。

**为什么拿「流水」当基准，而不是拿 `sold_count` 当基准**：
`sold_count` 含着一个未知的"种子基线"，而 `t_stock_flow` 只记录
**本应用产生的每一次扣减**。流水是我们自己的账本，可以和订单逐笔对上。

⚠️ 由此引出一条**建 fixture 时必须遵守的规则**：测试用的库存行
`sold_count` 必须从 **0** 开始。否则"库存增量"就不再等于"本应用卖出的票数"，
③ 也就失去意义。见 `sql/12_verify_stage5_order.sql` 文末的 fixture 段。

#### ⚠️ ② 通过的原因和你想的不一样

② 永远返回 0 行，**不是因为业务代码写对了**，而是因为
`uk_user_train_date_seat` 这个唯一索引让 `COUNT(*) > 1` **物理上不可能**。

所以它的真实作用是：**证明那个唯一索引真的建了**。
（如果哪天有人把 `UNIQUE KEY` 改成 `KEY`，② 会立刻变红 —— 那正是它的价值。
像上面 `sql/03_rail_inventory.sql` 里那张表所提醒的：索引定义一改，
"最多命中一行"这个前提就没了，而错误是静默的。）

#### ⭐ ⑤ 是阶段 5 新增的，也是 `t_stock_flow` 这张表存在的全部理由

CHECK 约束 `ck_sold_not_exceed_total` 只能证明「**没有超卖**」。
它**看不见「少卖」**：扣了库存但订单没写成功时，
`sold_count` 增加、订单数不变，两条 CHECK 约束**都是满足的**。

⑤ 就是为这一半准备的。它红了的最可能原因，是
`OrderService` 里把唯一索引冲突 **catch 住之后 `return` 了**：

> catch 块里一旦 `return`，Spring 的事务拦截器会认为方法成功结束 → **COMMIT**
> → 那次库存扣减被提交，而订单被回滚。
> 结果是：库存少了一张可卖的票、票没卖出去、接口规规矩矩返回 409、
> **没有任何日志和告警**。只有对账能发现。
>
> **判据：`catch` 块里只能 `throw`，不能 `return`。**

### 手段 3：对账定时任务

每 5 分钟跑一次上述 SQL，发现不一致就告警并记录。**这也是 Redis 与 MySQL 最终一致性的兜底。**
⏳ 阶段 5 尚未实现定时任务；⑤ 这条 SQL 是它的雏形。

> **实测状态（阶段 5）—— 分成两层说，因为一层测过、一层没测过：**
>
> | 层 | 状态 | 证据 |
> | --- | --- | --- |
> | **SQL 层的 CAS 语义**（有余票 → 受影响 1 行；已售罄 → 受影响 **0 行**） | 🟢 **已实测** | [sql/99_verify.sql](../sql/99_verify.sql) 的 **D1 / D2** 两条，阶段 2 跑过。⚠️ 注意这是**手工在 mysql 客户端里**执行的，**不是通过 Java 应用** |
> | **SQL 层的唯一索引 / CHECK 约束会拒绝违规数据** | 🟢 **已实测** | 同上的 A/B/C 各条 |
> | **应用层的整条链路**（事务边界、并发、`catch` 不 `return`、跨库回滚） | 🟡 **未实测** | 代码已写完（`OrderService`），**一次都没跑过**（MySQL 被 DLP 加密） |
>
> ⭐ **为什么要分两层**：把"SQL 语句本身是对的"和"我的应用用对了这条语句"当成一件事，
> 是阶段 5 最容易犯的自欺。D1/D2 证明了 **InnoDB 的条件 UPDATE 语义符合预期**；
> 它**完全没有**回答"`OrderService` 有没有把这 0 行正确处理成 409 而不是异常"、
> "并发 100 线程时连接池和行锁会怎样"、"catch 住唯一索引异常后事务是提交还是回滚"。
> **后三个问题才是阶段 5 的判据。**
>
> 手段 1（并发单元测试）的代码已写完（`OrderConcurrencyTest`），
> 手段 2 的校验脚本已写完（`sql/12_verify_stage5_order.sql`），
> **但本文不记录任何实测数字** —— 理由见下。
>
> ⚠️ **为什么不写数字**：阶段 5 的 mapper 日志还开着 `debug`，
> 此时测出的耗时没有意义（阶段 6 的事）；而"恰好卖出 20 张"这类
> 计数结果**必须从真实运行里抄**，不能凭设计推断着写。
> 本文档的规则是「**禁止编造数字**」，所以这里留空：
>
> | 待回填的数字 | 从哪来 |
> | --- | --- |
> | 100 线程抢 20 张 → 成功数 / 售罄数 | `OrderConcurrencyTest` 的控制台输出（`[并发实测]` 那几行） |
> | 50 线程同一用户 → 最终 `sold_count` | 同上 |
> | 观测到的死锁次数 | 同上（**未观测到就写"未观测到"，不能写"不会有"**） |
> | JMeter 50 线程的错误率 | `scripts/perf/stage5-order.jmx` 的聚合报告 |

---

## 七、相关文档

- [03-business-flow.md](03-business-flow.md) —— 秒杀主链路时序、验收标准 A1~A6
- [06-database.md](06-database.md) —— 条件 UPDATE 与 CHECK 约束的 DDL、实测证据
- [07-risks.md](07-risks.md) —— 上面每条机制的失效场景
- [scripts/env/lua/stock_deduct.lua](../scripts/env/lua/stock_deduct.lua) —— Lua 脚本设计稿
