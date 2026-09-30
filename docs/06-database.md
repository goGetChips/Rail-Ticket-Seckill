# 数据库设计

**读者**：要改表、加索引、或想知道"这张表为什么这么设计"的人。
**解决什么问题**：库/表的划分、关键设计决策的理由、不超卖的数据库侧防线、以及本机 MySQL 的环境差异。
**不包含**：Redis 侧的设计（→ [05](05-seckill.md)）、服务拆分理由（→ [02](02-architecture.md)）。

> **权威来源是建表脚本本身。** 本文只记录**决策与理由**，每张表逐字段的详细说明写在 [sql/](../sql/) 脚本自身的注释里——那里离代码最近，改表结构时不会忘记同步。
> **如果本文与 DDL 冲突，以 DDL 为准。**

- **状态**：✅ 已完成并实测验证（2026-09-15）
- **验证证据**：[sql/99_verify.sql](../sql/99_verify.sql) 的执行输出存档于 [scripts/env/verify-mysql-output.txt](../scripts/env/verify-mysql-output.txt)

---

## 一、总览

**9 张表，没有更多。** 每张表都能回答"去掉它会怎样"。

| schema | owner 服务 | 表 | 说明 |
| --- | --- | --- | --- |
| `rail_user` | rail-user-service | `t_user` | 用户 |
| `rail_train` | rail-train-service | `t_station`、`t_train`、`t_train_station` | 车站、车次时刻表、经停站 |
| `rail_inventory` | rail-inventory-service | `t_seat_inventory`、`t_stock_flow` | **库存权威数据**、库存流水 |
| `rail_order` | rail-order-service | `t_order`、`t_order_item`、`t_local_message` | 订单头、票、本地消息表（阶段 9 启用） |

### 全库统一的取值约定

| 字段 | 取值 |
| --- | --- |
| 席别 `seat_type` | 1=商务座 2=一等座 3=二等座 |
| 车次类型 `train_type` | 1=高铁 2=动车 3=普快 |
| 车次状态 `t_train.status` | 1=正常 0=停运 |
| 用户状态 `t_user.status` | 1=正常 0=禁用 |
| 订单状态 `t_order.status` | 0=待支付 1=已支付 2=已取消 |
| 本地消息 `t_local_message.status` | 0=待发送 1=已发送 2=已确认 |
| 库存流水 `t_stock_flow.change_type` | 1=预扣 2=确认扣减 3=回补 |

> **用 TINYINT 而非字符串枚举**是为了省空间和保证索引效率；代价是**可读性差**，必须在代码里定义对应的枚举类，**禁止裸写数字**。

### ⭐ 枚举只在服务端内部用，**对外仍然是 1/2/3**

阶段 5 创建了这三个枚举（[`enums/`](../rail-train-service/src/main/java/com/railseckill/train/enums/)）：

| 枚举 | 取值 | 阶段 5 用到哪些 |
| --- | --- | --- |
| `SeatType` | 商务座 1 / 一等座 2 / 二等座 3 | 全部（下单入参、余票出参） |
| `OrderStatus` | 待支付 0 / 已支付 1 / 已取消 2 | 0 和 1（2 只能靠手工 SQL 构造 —— 阶段 5 没有取消接口） |
| `StockChangeType` | 预扣 1 / 确认扣减 2 / 回补 3 | **只用到 2** |

**这是刻意的，不是省事**：

- 对外的 JSON 契约里 `seatType` / `status` 一直是**数字**（`SeatAvailability.seatType` 从阶段 4 起就是这样），
  换成字符串会让**阶段 4 的接口契约静默改变**。
- 枚举的价值是"**在 Java 代码里不能裸写数字**"，这个价值在服务端内部就实现了。
  对外再包一层字符串，等于让调用方也依赖我们的枚举名 —— 那是把**内部实现**升格成**公开契约**。
- ⚠️ **但 `StockChangeType` 的三个常量必须都在**，即使阶段 5 只用 `2`：
  只定义用得到的那个，就等于给"裸写 `1` / `3`"留了后门。**"禁止裸写数字"这条规则，
  对一张表的所有取值一视同仁** —— 预留的取值也是取值。

> 📌 对外契约如果哪天要改成字符串，那是一次**有意的版本变更**（要改文档、要通知调用方），
> 不是"顺手用枚举的 name()"。

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
- `order_no VARCHAR(32)`（唯一索引 `uk_order_no`）—— 对外暴露的业务单号，独立生成

**理由**：自增 ID 会**泄露业务量**。今天下单返回 1024、明天 2048，竞对连续下两单就能推算你的日订单量。自增 ID 只做主键，**永不出现在任何对外接口里**。

**🔴 生成本项目先用「时间戳 + 随机数」**，阶段 8 跨服务后再评估是否需要雪花算法。**不提前引入分布式 ID 组件**——当前量级不需要。

### 决策 4：`t_stock_flow` 库存流水表

**它的唯一键是整个阶段 9 的伏笔**：

```sql
UNIQUE KEY uk_biz_id_type (biz_id, change_type)
```

同一条 MQ 消息被投递两次时，第二次插入流水会撞唯一键而失败 → 整个事务回滚 → 库存不会被重复扣减。**这是「MQ 重复消费怎么办」的第一道答案。**

**为什么需要它**：「Redis 说扣了 1，MySQL 说扣了 0」这件事**必须有个地方能查**。没有流水表，对账只能发现"不一致"，却回答不了"哪一笔不一致、为什么"。

它同时还是「扣了库存但订单创建失败」时的补偿依据：扫描"有预扣流水但没有对应订单"的记录，就能找出所有需要回补的库存。

> **代价**：每次库存变更多一次 INSERT，在高并发下这是真实的写入放大。**教学项目接受这个代价换取可追溯性**；生产环境可能会改为异步写流水（用 MQ）或只对异常情况记录。

---

## 三、几处值得单独说明的表设计

### 3.1 `t_train` 冗余存 `start_station_id` / `end_station_id`

这两个字段完全可以从 `t_train_station` 推导（`station_order` 最小/最大）。冗余的理由是**查询模式**："查北京→上海的车次"是最核心的查询，每次都去 JOIN 一张有 10~20 行经停站的表，代价远大于读两个字段。

**冗余的前提是它有唯一的写入者**：只有车次录入这一个场景会写它，且与 `t_train_station` 在同一事务里。**没有这个前提就不该冗余。**

### 3.2 刻意没有「车次日历表」

真实铁路里 `G1234` 是一个**运行图**（每天都有），而 `2026-10-01 的 G1234` 是一个**具体列车**，可能临时调整。真实系统会再拆一张车次日历表。

**本项目不拆**，因为：

- 秒杀关注的是「某个车次在某一天还有多少票」，这正好由 `t_seat_inventory` 的 `(train_id, travel_date)` 承载
- 拆出日历表后，查余票要先查日历再查库存，多一次 JOIN，换不来任何本项目需要的能力

**代价：无法表达"某天停运"。** `t_train.status` 是车次级的，不是日期级的。**这是明确的简化，面试被问到应主动说明。**

### 3.3 `t_seat_inventory` 存 `total_count + sold_count`，不是 `available_count`

`available_count = total_count - sold_count` 是**冗余字段**，而冗余字段是数据漂移的来源：两个字段都可能被写错，不一致时无法判断谁对。

存 total 和 sold 则天然自洽：total 几乎不变（改票额是极低频操作），sold 有清晰语义。

**最关键的**：「不超卖」这个不变式可以直接写成 CHECK 约束 `sold_count <= total_count`。如果只有一个 `available_count`，"不为负"这个约束的表达力就弱得多。

### 3.4 `price` 存在 `t_seat_inventory` 与 `t_order_item` 两处

`t_seat_inventory.price` 是该车次该日期该席别的票价（下单时取用）；`t_order_item.price` 是**下单那一刻的价格快照**。

**这不是冗余**：票价会调，订单必须记录成交价。**交易数据必须快照，不能引用会变的数据。**

### 3.5 金额一律 `DECIMAL(10,2)`，绝不用 `FLOAT` / `DOUBLE`

`0.1 + 0.2` 在二进制浮点里不等于 `0.3`，累积误差会导致对账时"差了一分钱"这种极难排查的问题。DECIMAL 是精确的定点小数。**这是钱相关字段的铁律。**

### 3.6 全项目一律不加外键

| 理由 | 说明 |
| --- | --- |
| a | 跨服务的外键在微服务架构里是禁止的（数据库都不在同一个实例上） |
| b | 外键会在写入时加额外的锁，秒杀场景下会放大锁竞争 |
| c | 一旦分库分表，外键根本无法跨分片生效——现在依赖它，将来要全部拆掉 |

数据完整性改由「应用层逻辑 + 唯一索引 + CHECK 约束」保证。**这是一个可以争论的选择，面试时应当能说出两面。**

---

## 四、不超卖的数据库侧防线

> Redis/Lua 侧的防线（第一道）见 [05-seckill.md §1](05-seckill.md)。这里只讲**数据库侧的两道**。

### ② 条件 UPDATE（CAS）—— 阶段 5 的核心手法

```sql
UPDATE t_seat_inventory
   SET sold_count = sold_count + 1
 WHERE train_id = ? AND travel_date = ? AND seat_type = ?
   AND sold_count < total_count;      -- ← 关键在这一句
-- 受影响 1 行 = 成功；0 行 = 已售罄
```

**为什么这样就是安全的**：InnoDB 执行 UPDATE 时会对匹配的行加**排他锁**，且加锁和判断是同一步。两个并发事务不可能同时通过 `sold_count < total_count` 的判断：后到的那个必须等前一个提交，提交后重新读到的值已经变了，于是 WHERE 不再成立，受影响行数为 0。

**它比「先 SELECT 判断，再 UPDATE」好在哪**：

```
朴素写法： SELECT sold, total FROM ...   -- 两边都读到 99/100
           if (sold < total) UPDATE ...  -- 两边都执行，卖成 101
```

把判断和写入**合并成一条 SQL**，窗口就消失了。本质上是把「应用层两步骤」压成「数据库层一步」——**和在 Redis 里用 Lua 合并判断与扣减，是同一个思路。**

**为什么这里不需要分布式锁**：InnoDB 的行锁已经解决了"多个进程同时改一份数据"，粒度更细、代价更低。再加一层 Redis 分布式锁，等于给已经上锁的门再加一把锁——增加一次网络往返和一个故障点，却没有任何正确性收益。

#### 🟢 阶段 5 的实现（`SeatInventoryMapper.deductStock`）

```sql
<update id="deductStock">
    UPDATE rail_inventory.t_seat_inventory
       SET sold_count = sold_count + 1        <!-- ⚠️ 自增必须由数据库算 -->
     WHERE train_id = #{trainId} AND travel_date = #{travelDate} AND seat_type = #{seatType}
       AND sold_count &lt; total_count          <!-- ⚠️ XML 里 &lt; 必须转义 -->
</update>
```

三个必须写进注释的判断（完整论证见 [ADR-004](decisions/ADR-004-stock-deduction-mysql-cas.md)）：

| # | 判断 | 为什么 |
| --- | --- | --- |
| 1 | **`SET sold_count = sold_count + 1`，绝不能写成 `SET sold_count = #{javaComputed}`** | 后者是"先读再算再写"，仍然是丢失更新 → **少卖**。低并发下它也能"跑通"，这才是危险的地方 |
| 2 | **返回 `int`（受影响行数），不返回 `boolean`** | 0 / 1 / 抛异常是**三态**，压成 `boolean` 会把"售罄"（正常业务）和"执行失败"（系统故障）混成同一个 `false`，恰好抹掉下面那张表 |
| 3 | **`<` 必须写成 `&lt;`** | 不转义是**启动时 XML 解析失败** —— 好在它响亮地失败，不会静默 |

**为什么"受影响恰好 1 行"是有保证的**：`uk_train_date_seat (train_id, travel_date, seat_type)`
是 **UNIQUE**，所以最多只有一行能被匹配到。
**"1 = 成功"这个契约建立在索引上，不是建立在 SQL 写得巧上** ——
哪天那个索引被改成普通 KEY，这段代码的语义就悄悄变了（这正是对账 SQL ② 要守住的东西）。

### ③ CHECK 约束 —— 就算应用逻辑全写错了，数据库也会拒绝

```sql
CONSTRAINT ck_sold_non_negative     CHECK (sold_count >= 0),
CONSTRAINT ck_sold_not_exceed_total CHECK (sold_count <= total_count)
```

#### ⚠️ 别指望用异常处理来接住 CHECK 违反　🔴 未实测

写代码时确认（**尚未在本机触发过**）：CHECK 违反抛出的错误码是 **`3819`**，
而 **Spring 的异常翻译表里没有它** —— 所以它**不会被翻译**成
`DataIntegrityViolationException`，而是以一个裸的 `PersistenceException` 冒上来。

**这条推论有两个实际后果**：

1. **不存在"顺手兜住 CHECK 约束"这条退路。** 有人会想"我在 service 里 catch 一下就行"，
   那个 `catch` 捕不到你以为的类型。
2. **捕唯一索引撞车时必须捕 `DuplicateKeyException`，不能捕父类
   `DataIntegrityViolationException`** —— 父类会连带吞掉 `1364`（NOT NULL 违规）等
   **代码 bug**，那些必须 500。捕成 409 会把"必现的编码错误"伪装成"正常的业务拒绝"。

### ⚠️ 必须区分三种结果（常见 bug 来源）

| 结果 | 含义 | 处理 | 阶段 5 的对应 |
| --- | --- | --- | --- |
| 受影响 1 行 | 扣减成功 | 继续 | 200 + 订单号 |
| 受影响 **0 行** | 票卖完了，**正常业务失败** | 直接返回"已售罄" | **409**（不是 500，不是异常） |
| 抛异常 / 超时 | **系统失败**，结果未知 | 必须回滚 / 补偿 | **500** + 完整堆栈日志 |

把后两者混为一谈是常见错误：把 0 行当异常 → 售罄时大量报警，掩盖真故障；把异常当售罄 → 系统故障时告诉用户没票了，库存白白浪费。

⭐ **阶段 5 起，"抛异常"这一行第一次成为真实可能的结局**（阶段 0~4 全是只读，没有写入就没有"结果未知"）。
所以阶段 5 补了 `@ExceptionHandler(Exception.class)` 的 catch-all —— 不是"顺手统一错误体"，
而是让**结果未知**这类结局能被日志抓到（原先它会走 Boot 的 `/error`，**不经过我们的日志**）。

⚠️ **还有第四种情况，它不属于上面三行**：**库存行本身不存在**（这一天还没放票）。
这时 `deductStock` 也会返回 0 行，但原因和"售罄"完全不同 ——
所以 `OrderService` 在 UPDATE **之前**先读一次库存行，把这两种 0 行**分类**：

| ① 读到库存行？ | ② 受影响行数 | 含义 | 响应 |
| --- | --- | --- | --- |
| ❌ 没有 | 不执行 | **这一天还没放票** | 409「尚未放票」→ 换个日期 |
| ✅ 有 | `1` | 抢到 | 200 |
| ✅ 有 | `0` | **售罄** | 409「已售罄」→ 换车次 |

⭐ **①的那次读不会退化成"先查后改"**，判据是：**① 的结果从不作为扣减的充分条件** ——
即使 ① 读到有票，也照样执行 ②，由 ② 的受影响行数说了算。
① 只用来 (a) 取 `price`、(b) 给 0 行**分类**。见 [ADR-004 §3.3](decisions/ADR-004-stock-deduction-mysql-cas.md)。

---

## 五、环境相关的坑

### 5.1 `lower_case_table_names = 1`（Windows 默认）

- **本机行为**：表名存储为小写、比较不区分大小写。所以 `SELECT * FROM T_USER` 也能跑
- **陷阱**：Linux 默认是 `0`（区分大小写）。**本机跑得好好的代码，部署到 Linux 会直接报「表不存在」**
- **对策**：表名、列名**一律小写**。已加进验证脚本（检查 4）自动检查

### 5.2 mysql 客户端必须显式指定字符集

Windows 中文环境下 mysql 客户端默认字符集是 **GBK**。直接导入 UTF-8 的建表脚本，**中文表注释会全部变成乱码**（而且不报错，非常隐蔽）。

```bash
mysql --default-character-set=utf8mb4 ... < sql/01_rail_user.sql
```

详见 [04-technology.md §6.2](04-technology.md)。

### 5.3 CHECK 约束需要 MySQL ≥ 8.0.16

8.0.16 之前 CHECK 是"解析但忽略"。本机 8.4.8 没问题，但如果换到 5.7 环境，第三道防线会**静默消失**。验证脚本的检查 3 就是为了确认它真的登记在案。

### 5.4 时区：Windows 版 MySQL 不带时区数据

`mysql.time_zone_name` 表是**空的**（实测 0 行），因为填充它需要的 `mysql_tzinfo_to_sql` 工具只在 Unix 上提供。所以 JDBC 连接串里**必须用数字偏移 `+08:00` 而不是 `Asia/Shanghai`**，否则连接直接失败。完整排查过程见 [troubleshooting/README.md 案例 2](troubleshooting/README.md)。

### 5.5 单实例多 schema ⇒ 跨 schema 写能进**一个本地事务**　🟢 阶段 5 起

**这是"本机环境"带来的一个能力，不是 MySQL 的通用结论。** 阶段 5 的下单事务：
数据源 URL 指向 `rail_train`，但要同时写 `rail_inventory`（扣减 + 流水）和 `rail_order`（订单 + 明细）。

**它成立的原因是**：

> **InnoDB 的事务是服务器级的，不是库级的。**
> `rail_train` / `rail_inventory` / `rail_order` 是**同一个 MySQL 实例上的三个 schema**
> （MySQL 里 schema ≡ database）。一条连接上的 `START TRANSACTION` 同时覆盖它们
> —— 所以这是**本地事务**，不是分布式事务。

**实现手法**：**全限定表名**，例如
`@TableName("rail_inventory.t_seat_inventory")`。
⚠️ 漏写库名前缀的症状很隐蔽：**应用启动正常、其它接口全正常，只有第一次下单才报
`Table 'rail_train.t_order' doesn't exist`**。

**🔴 这个结论的前提是一条部署事实，它会在阶段 8 消失**：

| 阶段 | 三个 schema 在哪 | 能不能一个本地事务 |
| --- | --- | --- |
| 5 ~ 7 | 同一个 MySQL 实例 | ✅ 能（现在就是这么做的） |
| 8+ | 分属不同进程的数据源 | ❌ 不能 —— 拆服务后"扣库存"和"写订单"之间出现网络调用 |

⚠️ **别把这句话背成"跨库事务不可能"** —— 那是 [02-architecture.md §四](02-architecture.md)
里已被修正的一句事实错误。判据是：
**取决于有几个 MySQL 实例 / 几个进程，不取决于有几个 schema。**

📌 **实现时必须自己实测一遍**（不能只读代码推）：同一事务里
UPDATE `rail_inventory` + INSERT `rail_order`，ROLLBACK 后**两处同时回滚**。
⚠️ 这一步**尚未执行**（MySQL 被 DLP 加密），已列入阶段 5 的验证清单。

---

## 六、验证证据（实测，非推断）

执行 [sql/99_verify.sql](../sql/99_verify.sql)（用**应用账号 `rail`** 而非 root，同时验证权限是否够用）。

**结构检查 5/5 PASS**：4 个库、9 张表、2 个 CHECK 约束、表名全小写、字符集统一。

**行为验证 4/4 通过**——不是"执行成功"，而是**故意制造违规，确认数据库真的拦住了**：

| 测试 | 制造的行为 | 实际结果 |
| --- | --- | --- |
| A 唯一索引 | 同一用户同车次日期席别插第二条明细 | ✅ `ERROR 1062 Duplicate entry ... for key 't_order_item.uk_user_train_date_seat'` |
| B 负库存 | `UPDATE ... SET sold_count = -1` | ✅ `ERROR 3819 Check constraint 'ck_sold_non_negative' is violated` |
| C 超卖 | `UPDATE ... SET sold_count = 101`（total=100） | ✅ `ERROR 3819 Check constraint 'ck_sold_not_exceed_total' is violated` |
| D CAS 行为 | 有余票 / 已售罄时分别执行条件 UPDATE | ✅ 受影响行数分别为 **1** 和 **0**，与设计预期完全一致 |

> **测试 D 的意义**：它提前验证了**阶段 5 才会用到的**核心扣减手法。**"不用分布式锁也能不超卖"这个论断，现在有实测支撑，而不是"理论上应该行"。**

完整原始输出存档于 [scripts/env/verify-mysql-output.txt](../scripts/env/verify-mysql-output.txt)。

---

## 七、索引失效与慢查询预防

| ❌ 会走全表扫描的写法 | ✅ 正确写法 | 原因 |
| --- | --- | --- |
| `WHERE DATE(create_time) = '2026-09-15'` | `WHERE create_time >= '2026-09-15' AND create_time < '2026-09-16'` | 对索引列做函数运算 → 索引失效 |
| `WHERE order_no LIKE '%123%'` | `WHERE order_no = '123'` 或 `LIKE '123%'` | 前导通配符无法用索引 |
| `WHERE user_id = 1 OR 1=1` | 参数化查询 | — |
| `WHERE status != 2` | 业务上改为枚举正向值 | `!=` 通常无法有效用索引 |

**验证方式**：所有查询上线前用 `EXPLAIN` 检查 `type` 列，出现 `ALL` 就必须优化。**这是压测之外必做的功课**，也是面试可以讲的实操细节。

**最左前缀原则**：联合索引只能从最左列开始匹配。例如 `t_train.idx_start_end (start_station_id, end_station_id)` 对 `WHERE end_station_id = ?` 这样的单列查询**无效**——将来出现"按到达站查车次"的需求时要单独建索引。

---

## 八、已知简化清单（面试时主动交代）

诚实标注本设计中**刻意做的简化**。主动说出来，比被追问出来强得多。

| # | 简化 | 真实系统怎么做 | 影响 |
| --- | --- | --- | --- |
| 1 | **不做区间票** | 按乘车区间分段存余票，查余票取各段最小值 | 短途乘客占用了全程运力 |
| 2 | **没有车次日历表** | 拆出 `t_train_schedule` 区分"运行图"和"某天的具体列车" | 无法表达"某天停运" |
| 3 | **一个订单对应一张票** | 支持一个订单多个乘车人 | `t_order` / `t_order_item` 的拆分在当前场景下显得冗余 |
| 4 | **不加外键约束** | 服务内部可加；跨服务永远不加 | 数据完整性全靠应用层 + 约束 |
| 5 | **四个服务共用一个 `rail` 账号** | 每服务独立账号，只授权自己那个库 | 本地开发便利，生产不应如此 |
| 6 | **下单接口不支持"中途上车"** | 按乘车区间查可售车次、按区间扣减 | 🟡 **查询接口支持、下单接口不支持** —— 见下 |
| 7 | **`t_order_item` 的站字段恒等于车次的始发/终到站** | 由下单时传入的实际区间决定 | 与第 6 条是同一件事的两面 |

### ⚠️ 第 6 条必须解释清楚：**查询接口支持中途上车，下单接口不支持**

这不是疏漏，是**阶段 5 刻意留下的缺口**，而且它会造成一个真实的用户体验断层：

```
① GET /api/train/trains/search?from=JNK&to=NJH     → 查到 G1（中途上车，✅ 支持）
② POST /api/order/orders { trainNo: "G1", ... }    → 下单成功
   但票上写的是 VNP → AOH（全程），不是你查的 济南西 → 南京南
```

**为什么会这样**：库存粒度是**整段**（[决策 1](#决策-1库存粒度--车次--日期--席别不做区间票)），
所以服务端在 `t_order_item` 里填的 `from_station_id` / `to_station_id`
只能是**车次的始发站和终到站** —— 它没有别的正确答案可填。

**为什么不让客户端传这两个字段**：传了就必须校验
"from 在 to 之前"、"两个站都在这趟车的经停站里"、"区间属于该车次" ——
**三条内部一致性校验，换不来任何业务能力**（票本来就是全程票）。
**让调用方为一个不存在的功能提供参数，比不提供这个参数更糟。**

**为什么不对查询接口也关掉中途上车**：那个能力是**真实有用且已经实测通过**的
（阶段 4，`JNK→NJH → G1+G3`），关掉是倒退。**缺口在"查得到、买不了"，
不在"查不到"。**

📌 **正确的修法**（不在本项目范围内）：库存表增加 `from_order` / `to_order`
两个维度，变成**区间库存**（[决策 1 的 🔵 正确做法](#决策-1库存粒度--车次--日期--席别不做区间票)）。
那时"中途上车"才有对应的库存语义，下单接口也才能收这两个字段。

**第 3 条是本设计中最值得质疑的一处**：如果确定永远只做"一人一单一张票"，把 `t_order` 和 `t_order_item` 合成一张表是**更简单、写入更少**的正确选择（少一次 INSERT，阶段 6 压测时差别看得见）。

我选择拆开，是因为它把"下单人"（`t_order.user_id`）和"乘车人"（`t_order_item.user_id`）分开了，而**这个分离正是限购约束必须落在明细表上的原因**。如果你觉得不划算，可以推翻它——**这正是写 DDL 才会暴露出来的问题**。

---

## 九、相关文件

| 文件 | 内容 |
| --- | --- |
| [sql/00_init.sql](../sql/00_init.sql) | 建库 + 建应用账号（只跑一次，可重复执行） |
| [sql/01_rail_user.sql](../sql/01_rail_user.sql) | `t_user` |
| [sql/02_rail_train.sql](../sql/02_rail_train.sql) | `t_station` / `t_train` / `t_train_station` |
| [sql/03_rail_inventory.sql](../sql/03_rail_inventory.sql) | `t_seat_inventory` / `t_stock_flow` ⭐ |
| [sql/04_rail_order.sql](../sql/04_rail_order.sql) | `t_order` / `t_order_item` / `t_local_message` |
| [sql/10_seed_train.sql](../sql/10_seed_train.sql) | 种子数据（14 个车站 + 车次 + 经停站） |
| [sql/11_seed_inventory.sql](../sql/11_seed_inventory.sql) | 种子数据（库存行）⚠️ 日期是**执行它那天**的 +1/+2/+3 |
| [sql/12_verify_stage5_order.sql](../sql/12_verify_stage5_order.sql) | **阶段 5 校验**：超卖 / 重复 / 三方对账 / 少卖 / 非负（**只读**） |
| [sql/99_verify.sql](../sql/99_verify.sql) | 验证脚本（**故意制造违规**） |

> **`99_verify.sql` 与 `12_verify_stage5_order.sql` 的分工**：
> 前者验**建表结果**（阶段 2：库在不在、约束在不在 —— 用违规数据主动验证约束会拒绝）；
> 后者验**运行时结果**（阶段 5：并发跑完之后**数字对不对**）。
> 这是两类完全不同的问题，所以是两个脚本。

> ⚠️ `01`~`04` 的建表脚本**会先 DROP TABLE**，重复执行会丢数据，仅用于开发环境。
