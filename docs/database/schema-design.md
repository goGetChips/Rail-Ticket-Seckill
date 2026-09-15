# 数据库设计（阶段 2）

- **状态**：✅ 已完成并实测验证（2026-09-15）
- **建表脚本**：[sql/](../../sql/)
- **验证证据**：[scripts/env/verify-mysql-output.txt](../../scripts/env/verify-mysql-output.txt)

> 本文只记录**决策与理由**。每张表逐字段的详细设计说明写在**建表脚本自身的注释里**——
> 那里离代码最近，改表结构时不会忘记同步。DDL 文件本身才是权威。

---

## 一、总览

| schema | owner 服务 | 表 | 说明 |
| --- | --- | --- | --- |
| `rail_user` | rail-user-service | `t_user` | 用户 |
| `rail_train` | rail-train-service | `t_station`、`t_train`、`t_train_station` | 车站、时刻表、经停站 |
| `rail_inventory` | rail-inventory-service | `t_seat_inventory`、`t_stock_flow` | **库存权威数据**、库存流水 |
| `rail_order` | rail-order-service | `t_order`、`t_order_item`、`t_local_message` | 订单头、票、本地消息表 |

**9 张表，没有更多。** 每张表都能回答"去掉它会怎样"。

### 环境实测信息

| 项 | 值 | 影响 |
| --- | --- | --- |
| MySQL | 8.4.8 | CHECK 约束被真正强制执行（需 ≥ 8.0.16） |
| 字符集 | `utf8mb4` / `utf8mb4_0900_ai_ci` | 全库统一 |
| `lower_case_table_names` | **1** | ⚠️ 见第五节 |
| 事务隔离级别 | `REPEATABLE-READ` | MySQL 默认 |
| `sql_mode` | 含 `ONLY_FULL_GROUP_BY`、`STRICT_TRANS_TABLES` | 写聚合查询需注意 |

---

## 二、已拍板的四个设计决策

### 决策 1：库存粒度 = 车次 + 日期 + 席别（**不做区间票**）

```sql
UNIQUE KEY uk_train_date_seat (train_id, travel_date, seat_type)
```

**真实铁路是区间票**：北京南→上海虹桥 的一等座会占用「北京南→济南西」和「济南西→上海虹桥」两段运力。本项目刻意不做。

**理由**：引入区间后，Lua 脚本从「扣**一个** key」变成「扣一段区间内**所有** key」，原子性论证从"一次 DECR"退化成"区间扣减 + 部分失败如何回滚"——**那是另一道题，会把注意力从主线上拽走**。

**⚠️ 代价（面试必须主动说，否则被追问会露馅）**：北京南→济南西 和 北京南→上海虹桥 **抢同一份库存**。现实中这是错的——只坐一站的乘客不该占用全程运力。

**🔵 正确做法是什么**（被追问时要有答案）：库存按 `(train_id, travel_date, seat_type, from_order, to_order)` 存**每一段**的余票；下单时对 `[from_order, to_order)` 区间内每一段都要扣减；查余票要取沿途各段的最小值。

### 决策 2：唯一索引做正确性兜底，且**建在 `t_order_item` 上**

```sql
-- rail_order.t_order_item
UNIQUE KEY uk_user_train_date_seat (user_id, train_id, travel_date, seat_type)
```

**为什么建在明细表而不是订单表**：约束要建在**它真正约束的那个实体**上。被约束的是"票"，票在 `t_order_item`。

如果建在 `t_order` 上，语义会变成"一个**下单人**同车次同日期同席别只能下一单"——将来支持代人购票时，这条约束会**错误地阻止** A 一次给 B 和 C 各买一张。

**代价**：`user_id` 需要在两张表各存一份。这是**由约束驱动的冗余**，理由充分。请与"凭感觉冗余字段"区分——**冗余必须有明确的服务对象和唯一的写入者**。

### 决策 3：订单主键与订单号分离

- `id BIGINT UNSIGNED AUTO_INCREMENT` —— InnoDB 聚簇索引，顺序写避免页分裂
- `order_no VARCHAR(32)` —— 对外暴露的业务单号，独立生成

**理由**：自增 ID 会**泄露业务量**。今天下单返回 1024、明天 2048，竞对连续下两单就能推算你的日订单量。自增 ID 只做主键，**永不出现在任何对外接口里**。

**🔴 生成本项目先用「时间戳 + 随机数」**，阶段 8 跨服务后再评估是否需要雪花算法。**不提前引入分布式 ID 组件**——当前量级不需要。

### 决策 4：`t_stock_flow` 库存流水表

**它的唯一键是整个阶段 9 的伏笔**：

```sql
UNIQUE KEY uk_biz_id_type (biz_id, change_type)
```

同一条 MQ 消息被投递两次时，第二次插入流水会撞唯一键而失败 → 整个事务回滚 → 库存不会被重复扣减。**这是「MQ 重复消费怎么办」的第一道答案。**

**为什么需要它**：「Redis 说扣了 1，MySQL 说扣了 0」这件事**必须有个地方能查**。没有流水表，对账只能发现"不一致"，却回答不了"哪一笔不一致、为什么"。

---

## 三、⭐ 三层幂等防线（面试核心）

```
第一层  Redis Lua 里的 SISMEMBER
        └─ 挡住 99% 的重复请求
        └─ 定位：性能优化（快，但不是保证）

第二层  t_local_message 的唯一键 uk_biz_id_type
        └─ 保证消息不重复投递
        └─ 定位：阶段 9 的机制保证

第三层  MySQL 唯一索引 uk_user_train_date_seat
        └─ 前两层都失效时的最终保证
        └─ 定位：正确性兜底
```

**为什么必须有第三层**：Redis 会被清空、会重启、主从切换会丢数据；MQ 的"至少一次投递"语义意味着重复是**必然**而非意外。

**只有数据库的唯一索引是"无论上游怎么乱来，都一定成立"的。**

> 少任何一层，你在面试里都只能说"正常情况下不会重复"——**那就是没有保证**。

---

## 四、不超卖的三道防线

| 防线 | 位置 | 失效场景 |
| --- | --- | --- |
| ① Lua 原子扣减 | Redis | Redis 挂了 / 数据丢失 |
| ② 条件 UPDATE（CAS） | MySQL | — |
| ③ CHECK 约束 | MySQL | — |

```sql
-- ② 条件 UPDATE：把「判断」和「写入」压成一条原子 SQL
UPDATE t_seat_inventory
   SET sold_count = sold_count + 1
 WHERE train_id = ? AND travel_date = ? AND seat_type = ?
   AND sold_count < total_count;      -- ← 关键在这一句
-- 受影响 1 行 = 成功；0 行 = 已售罄
```

```sql
-- ③ CHECK 约束：就算应用逻辑全写错了，数据库也会拒绝
CONSTRAINT ck_sold_non_negative     CHECK (sold_count >= 0),
CONSTRAINT ck_sold_not_exceed_total CHECK (sold_count <= total_count)
```

**⚠️ 必须区分三种结果**（这是常见 bug 来源）：

| 结果 | 含义 | 处理 |
| --- | --- | --- |
| 受影响 1 行 | 扣减成功 | 继续 |
| 受影响 **0 行** | 票卖完了，**正常业务失败** | 直接返回"已售罄" |
| 抛异常 / 超时 | **系统失败**，结果未知 | 必须回滚 / 补偿 |

把后两者混为一谈是常见错误：把 0 行当异常 → 售罄时大量报警，掩盖真故障；把异常当售罄 → 系统故障时告诉用户没票了，库存白白浪费。

---

## 五、环境相关的坑

### 5.1 `lower_case_table_names = 1`（Windows 默认）

- **本机行为**：表名存储为小写、比较不区分大小写。所以 `SELECT * FROM T_USER` 也能跑
- **陷阱**：Linux 默认是 `0`（区分大小写）。**本机跑得好好的代码，部署到 Linux 会直接报「表不存在」**
- **对策**：表名、列名**一律小写**。已加进验证脚本（检查 4）自动检查

### 5.2 mysql 客户端必须显式指定字符集

Windows 中文环境下，mysql 客户端的默认字符集是 **GBK**。直接导入 UTF-8 的建表脚本，**中文表注释会全部变成乱码**（而且不报错，非常隐蔽）。

```bash
mysql --default-character-set=utf8mb4 ... < sql/01_rail_user.sql
```

这与 [scripts/env/README.md §6.2](../../scripts/env/README.md) 记录的 JVM `file.encoding=GBK` 是**同一类问题的两个面**：**中文 Windows 上，字符集必须处处显式指定，不能依赖默认值。**

### 5.3 CHECK 约束需要 MySQL ≥ 8.0.16

8.0.16 之前 CHECK 是"解析但忽略"。本机 8.4.8 没问题，但如果换到 5.7 环境，第三道防线会**静默消失**。验证脚本的检查 3 就是为了确认它真的登记在案。

---

## 六、验证证据（实测，非推断）

执行 `sql/99_verify.sql`（用**应用账号 `rail`** 而非 root，同时验证权限是否够用）。

**结构检查 5/5 PASS**：4 个库、9 张表、2 个 CHECK 约束、表名全小写、字符集统一。

**行为验证 4/4 通过**——不是"执行成功"，而是**故意制造违规，确认数据库真的拦住了**：

| 测试 | 制造的行为 | 实际结果 |
| --- | --- | --- |
| A 唯一索引 | 同一用户同车次日期席别插第二条 | ✅ `ERROR 1062 Duplicate entry ... for key 't_order_item.uk_user_train_date_seat'` |
| B 负库存 | `UPDATE ... SET sold_count = -1` | ✅ `ERROR 3819 Check constraint 'ck_sold_non_negative' is violated` |
| C 超卖 | `UPDATE ... SET sold_count = 101`（total=100） | ✅ `ERROR 3819 Check constraint 'ck_sold_not_exceed_total' is violated` |
| D CAS 行为 | 有余票 / 已售罄时分别执行条件 UPDATE | ✅ 受影响行数分别为 **1** 和 **0**，与设计预期完全一致 |

> **测试 D 的意义**：它提前验证了**阶段 5 才会用到的**核心扣减手法。
> "不用分布式锁也能不超卖"这个论断，现在有实测支撑，而不是"理论上应该行"。

完整原始输出存档于 [scripts/env/verify-mysql-output.txt](../../scripts/env/verify-mysql-output.txt)。

---

## 七、已知简化清单（面试时主动交代）

诚实标注本设计中**刻意做的简化**。主动说出来，比被追问出来强得多。

| # | 简化 | 真实系统怎么做 | 影响 |
| --- | --- | --- | --- |
| 1 | **不做区间票** | 按乘车区间分段存余票，查余票取各段最小值 | 短途乘客占用了全程运力 |
| 2 | **没有车次日历表** | `t_train_schedule` 区分"运行图"和"某天的具体列车" | 无法表达"某天停运" |
| 3 | **一个订单对应一张票** | 支持一个订单多个乘车人 | `t_order` / `t_order_item` 的拆分在当前场景下显得冗余 |
| 4 | **不加外键约束** | 服务内部可加；跨服务永远不加 | 数据完整性全靠应用层 + 约束 |
| 5 | **四个服务共用一个 `rail` 账号** | 每服务独立账号，只授权自己那个库 | 本地开发便利，生产不应如此 |

**第 3 条是本设计中最值得质疑的一处**：如果确定永远只做"一人一单一张票"，把 `t_order` 和 `t_order_item` 合成一张表是**更简单、写入更少**的正确选择（少一次 INSERT，阶段 6 压测时差别看得见）。

我选择拆开，是因为它把"下单人"和"乘车人"分开了，而**这个分离正是限购约束必须落在明细表上的原因**。如果你觉得不划算，可以推翻它——**这正是写 DDL 才会暴露出来的问题**。

---

## 八、相关文件

| 文件 | 内容 |
| --- | --- |
| [sql/00_init.sql](../../sql/00_init.sql) | 建库 + 建应用账号 |
| [sql/01_rail_user.sql](../../sql/01_rail_user.sql) | `t_user` |
| [sql/02_rail_train.sql](../../sql/02_rail_train.sql) | `t_station` / `t_train` / `t_train_station` |
| [sql/03_rail_inventory.sql](../../sql/03_rail_inventory.sql) | `t_seat_inventory` / `t_stock_flow` ⭐ |
| [sql/04_rail_order.sql](../../sql/04_rail_order.sql) | `t_order` / `t_order_item` / `t_local_message` |
| [sql/99_verify.sql](../../sql/99_verify.sql) | 验证脚本（**故意制造违规**） |
