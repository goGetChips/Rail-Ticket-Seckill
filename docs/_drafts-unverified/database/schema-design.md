# 数据库设计文档（Rail-Ticket-Seckill）

本文档描述抢票秒杀系统的数据库设计：库表怎么分、每张表为什么长这样、索引为什么这么建、
唯一索引和幂等的关系、哪些写法会让索引失效、以及上线前必须跑哪些校验 SQL。

阅读前提：本文假设读者了解 InnoDB 的聚簇索引、B+ 树、行锁与 MVCC 的基本概念。
所有 DDL 均为 MySQL 8.x 语法，全部使用 InnoDB。

本文的写法约定：每做一个决策，都会写清楚四件事——
**做了什么 / 为什么这么做 / 不这么做会出什么问题 / 对比了哪些替代方案、为什么没选**。
没有实测过的数字一律标注「待压测」或「待验证」，不写任何没有依据的性能结论。

---

## 目录

1. [结论摘要（先看这里）](#1-结论摘要先看这里)
2. [库 / 表划分策略](#2-库--表划分策略)
3. [命名、类型与通用约定](#3-命名类型与通用约定)
4. [表设计逐表说明](#4-表设计逐表说明)
5. [唯一索引与幂等的关系](#5-唯一索引与幂等的关系)
6. [索引失效与慢查询预防](#6-索引失效与慢查询预防)
7. [校验 SQL 清单](#7-校验-sql-清单)
8. [明确留下的技术债与未决问题](#8-明确留下的技术债与未决问题)
9. [参考文档](#9-参考文档)

---

## 1. 结论摘要（先看这里）

| 决策点 | 结论 | 一句话理由 |
| --- | --- | --- |
| 部署形态 | 本机：**单 MySQL 实例 + 4 个 schema**（逻辑隔离） | 教学项目单机跑得动，schema 能演示分域边界，又不引入分布式事务 |
| 生产拆分 | 按域拆实例：用户库 / 车次库 / 订单库 / 支付库；库存与订单**同库同分片** | 扣库存与写订单必须在同一本地事务里，拆开就要引入分布式事务 |
| 分片键 | `train_id`（订单 + 库存 + 流水同键） | 写路径是"按车次扣库存再下单"，同键才能保证单库本地事务 |
| 主键 | `BIGINT UNSIGNED AUTO_INCREMENT` | 顺序插入不产生页分裂；对外只暴露 `order_no`，不暴露自增 id |
| 金额类型 | `DECIMAL(10,2)` | 票价是精确十进制，绝不能用 FLOAT/DOUBLE |
| 时间类型 | `DATETIME(3)` | 秒杀要判"开售那一毫秒"，且 `TIMESTAMP` 有 2038 与隐式时区转换问题 |
| 防超卖 | `UPDATE ... SET available_qty = available_qty - ? WHERE id = ? AND available_qty >= ?` | 条件更新 + 行锁是一条原子语句，天然无"先查后改"竞态 |
| 防重复下单 | **唯一索引** `(user_id, train_id, travel_date, seat_type, active_flag)` | 正确性必须由数据库约束兜底，锁只能降低冲突概率 |
| 分布式锁 | 用来削峰、减少无效请求，**不用来保证不重复** | 锁会过期、会因主从切换丢失，不能承担正确性 |
| 外键 | 不建外键 | 分库后外键无意义，且外键检查会加锁、DDL 变更困难；一致性由应用 + 校验 SQL 兜底 |
| 字符集 | 全库 `utf8mb4 / utf8mb4_0900_ai_ci` | 统一字符集避免 JOIN 时隐式转换导致索引失效 |

---

## 2. 库 / 表划分策略

### 2.1 本机形态：单实例多 schema

本机（开发 / 演示）跑**一个 MySQL 实例**，里面建 4 个 schema：

| schema | 归属域 | 包含表 | 写特征 |
| --- | --- | --- | --- |
| `rail_user` | 用户域 | `t_user`、`t_passenger` | 低频写，读多 |
| `rail_biz` | 车次与库存域 | `t_station`、`t_train`、`t_train_stock`、`t_seckill_activity` | 车次是低频写超高频读；**库存是超高频写** |
| `rail_order` | 交易域 | `t_order`、`t_pay_record`、`t_stock_flow` | 高频写，写后读 |
| `rail_common` | 基础设施域 | `t_idempotent_record`、`t_local_message` | 高频写，扫描型读 |

**为什么是 schema 而不是 4 个实例？**

- 教学项目的数据量与并发量都在单机能覆盖的范围内，拆实例只会让本地启动、调试、
  看 binlog、跑对账 SQL 的成本翻几倍。
- schema 是 MySQL 里最轻量的逻辑边界：它把"这几张表属于同一个域、将来会一起搬走"这件事
  写进了表名里，迁移时 `mysqldump` 一个 schema 就能搬走一个域。
- 代码里用 `@TableName("rail_order.t_order")` 或 MyBatis 的 schema 前缀指向，
  迁移到独立实例时只改连接串，不改 SQL 结构。

**为什么不这么做会出问题？**

如果所有表都塞进一个 `rail_ticket` schema，那么"哪些表属于同一事务边界"这个信息就丢了。
半年后新增一张表，很容易把它放到错误的域里，等到真要拆库时才发现它跨了两个域，
只能停机重写。表名里带域前缀，是让错误的成本变高的最便宜手段。

**必须说清楚的代价**：schema 只是**逻辑隔离，不是资源隔离**。
它们共用一个 buffer pool、一套连接数、一块磁盘 IO。
如果库存热点行的行锁竞争把实例的 CPU 打满，订单表的写入同样会被拖慢。
这就是"本机可以这么干，生产不能这么干"的根本原因——待压测验证单实例下库存热点对订单写入的实际影响。

### 2.2 生产应该怎么拆

| 阶段 | 拆法 | 触发条件（不写数字，只写判断维度） |
| --- | --- | --- |
| 第一步 | 车次库独立成实例（`rail_biz` 的 `t_station`/`t_train`） | 车次查询是全站读最多的路径，且它和交易几乎无写冲突，拆出去收益最干净 |
| 第二步 | `t_train_stock` 与 `t_order`、`t_stock_flow` 同库同分片 | 见下方"为什么不拆开"，这三张表必须本地事务 |
| 第三步 | 用户库独立（`rail_user`） | 用户表与交易表之间没有强事务，只有"这个用户存不存在"的读，拆开只损失一次跨库查询 |
| 第四步 | 订单/库存按 `train_id` 水平分片 | 单表写入成为瓶颈时 |
| 第五步 | 历史订单归档到冷库 | 热表数据量让索引高度增长到影响查询时 |

**为什么 `t_train_stock` 和 `t_order` 必须同库同分片？**

下单的核心写路径是一条 4 步序列：

1. `UPDATE t_train_stock SET available_qty = available_qty - 1 WHERE ... AND available_qty >= 1`
2. `INSERT INTO t_order (...)`
3. `INSERT INTO t_stock_flow (...)`
4. `INSERT INTO t_local_message (...)`

这 4 步必须在**同一个本地事务**里，否则会出现"库存扣了但订单没写"或反过来。
跨实例就无法用本地事务，只能上 Seata 这类分布式事务——它引入额外的协调者、额外的 undo 日志表、
额外的失败分支，对"库存扣减"这种强一致要求的场景是**用复杂度换了一个本来免费的东西**。

所以拆分的边界不是"按业务高低频"，而是"按事务边界"。`t_seckill_activity` 虽然属于运营域，
但它和库存表在同一个"改价 + 校验活动状态"的读路径上，本设计把它留在 `rail_biz`，
不参与分片（它是运营低频写表，可以做成每个分片的广播表）。

**分片键为什么是 `train_id` 而不是 `user_id`？**

| 候选分片键 | 优点 | 致命问题 |
| --- | --- | --- |
| `user_id` | "我的订单"列表天然单分片；用户维度的查询全走单库 | **扣库存要跨所有分片**——同一车次的库存行只在一个分片，但请求来自任意用户；要么库存表另按 `train_id` 路由（变成两个分片键，跨库事务回来了），要么每个分片各存一份库存（超卖风险） |
| `order_no` | 分布最均匀，绝对无热点 | 任何按用户 / 按车次的查询都要扫全部分片，业务上不可用 |
| `train_id` | 扣库存、写订单、写流水在同一个分片，一个本地事务搞定；热门车次天然集中在一台机器上，便于单独扩容 | 单趟热门车次的数据全压一个分片，会有**分片热点**；"我的订单"要扫全部分片 |

结论：**写路径的一致性优先**，选 `train_id`。
分片热点是真实存在的问题，补救手段有两个，都不需要改分片键：
热门车次的库存**不落 DB 竞争**（Redis 预扣 + 消息队列削峰，见第 5 节），
DB 只承接削峰后的写入；"我的订单"扫全分片则通过**用户库冗余一份订单索引**解决（本项目未实现，标注为扩展点）。

**本项目不实现分片**：表名不带分片后缀，也不引入 ShardingSphere。
理由是分片中间件会改变"SQL 能不能执行"的边界（跨分片 JOIN、跨分片事务都不可用），
在教学项目里引入它，会让前 90% 的功能调试成本上升，而后 10% 的功能又因为没有真实数据量而验证不了。
表结构按"将来能分"来设计（每张交易表都带 `train_id`），这就是本设计能给出的最大兼容性。

### 2.3 表划分的另外三条原则

1. **大字段与热字段分离**：`t_local_message.payload` 是变长文本，InnoDB 的 DYNAMIC 行格式
   会把超长字段溢出到 off-page，行内只留 20 字节指针，所以它和其他热字段同表是可接受的。
   但**不要把支付回调的完整报文（可能几 KB）放进订单表**，那会让订单表的行变长、
   单页容纳的行数下降，直接放大订单表的 IO。报文只存摘要 + traceId，原文进日志/对象存储。
2. **高频写表不放冗余统计列**：`t_train_stock` 只存 `total_qty` 和 `available_qty`，
   不存 `sold_qty`。因为一旦存了第三个数，就有 `total = available + sold` 这个需要额外校验的等式，
   而校验本身又要成本。能算出来的数不要存。
3. **不建物理外键**：理由见第 3.4 节。

---

## 3. 命名、类型与通用约定

### 3.1 命名约定

| 对象 | 规则 | 例子 |
| --- | --- | --- |
| 表 | `t_` 前缀 + 小写下划线 + 单数 | `t_order` |
| 主键 | 一律 `id` | `id` |
| 唯一索引 | `uk_` + 列名缩写 | `uk_order_no` |
| 普通索引 | `idx_` + 列名缩写 | `idx_user_create` |
| 普通字段 | 小写下划线，布尔型用 `_flag` 结尾，时间型用 `_time` 结尾 | `active_flag`、`pay_time` |
| 状态字段 | 一律 `status`，`TINYINT UNSIGNED` | `status` |

**为什么布尔型不叫 `is_deleted` 而叫 `_flag`？**
只是为了让"枚举型"和"布尔型"在字段名上可区分：`status` 是多值枚举，`*_flag` 是二值。
如果两者都叫 `is_xxx` / `xxx_status`，读 SQL 的人要回去翻 DDL 才知道能不能写 2。

### 3.2 类型选择与理由

| 场景 | 选型 | 为什么是它 / 对比过的替代方案 |
| --- | --- | --- |
| 主键 | `BIGINT UNSIGNED AUTO_INCREMENT` | 顺序写入，B+ 树只在右侧追加，不产生页分裂。对比：UUID（36 字符、随机分布导致页分裂、每个二级索引都要存 36 字节主键，索引膨胀数倍）——待压测具体膨胀比；雪花 ID（全局唯一、利于将来分片，但需要处理时钟回拨，且低位随机会打散插入顺序）。**本设计用自增**，对外不暴露 id 只暴露 `order_no`，将来切雪花时业务无感 |
| 金额 | `DECIMAL(10,2)` | 票价是精确的十进制小数，`FLOAT`/`DOUBLE` 是二进制浮点，`0.1 + 0.2` 类误差在"对账必须分毫不差"的场景里不可接受。对比：`BIGINT` 存"分"（省空间、运算快、无精度问题），代价是所有出入参和前端都要做单位转换，且铁路票价历史上出现过带分的定价，将来若出现"厘"就要改所有代码——**本设计选 DECIMAL**，并把这一步作为面试可讨论的取舍点 |
| 时间点 | `DATETIME(3)` | 毫秒精度：秒杀要判断"是否已到开售时刻"，也用 `create_time` 做订单排序，秒级精度会出现大量同秒并列。对比：`TIMESTAMP` 受 `time_zone` 影响会隐式转换，且有 2038 上限；`BIGINT` 存毫秒时间戳（无时区问题、程序处理方便，但不可读、不能用 MySQL 的日期函数做范围校验）——本设计选 `DATETIME(3)`，并约定**所有时间由应用写入**，不依赖 DB 默认值（见 3.3） |
| 纯日期维度 | `DATE` | 乘车日期是"哪一天"而不是"哪一刻"。用 `DATETIME` 存会把时间部分带进唯一索引，同一车次同一天会因时分秒不同产生多条库存行，唯一索引形同虚设 |
| 枚举状态 | `TINYINT UNSIGNED` | 1 字节，取值 0–255，够用；比 `VARCHAR` 省索引空间、比较快；比 `ENUM` 好——`ENUM` 增删值要 `ALTER TABLE` 重建/改元数据，且不同数据库实现差异大。取值含义写在列 `COMMENT` 和本文档里 |
| 业务编码（站码、车次号） | `VARCHAR` | 长度不固定（车次号 1–16 字符），用 `CHAR` 会补空格，比较时尾空格语义容易踩坑 |
| 定长摘要（SHA-256 hex） | `CHAR(64)` | SHA-256 十六进制恒为 64 字符，定长，`CHAR` 无长度前缀开销，且不会因尾空格问题影响等值比较 |
| 数量 | `INT UNSIGNED`（库存）/ `INT`（流水变动量） | 库存没有负数，用无符号让 DB 帮我挡住"扣成负数"这种低级错误；流水变动量有正负（扣减为负），必须带符号 |
| 可变长文本 | `VARCHAR(n)` 优先，确需长文本才 `TEXT` | `VARCHAR` 参与行内存储，读取不额外跳页；`TEXT` 走 off-page，多一次 IO |

### 3.3 三个通用字段约定

```sql
`create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，应用显式写入',
`update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，应用显式写入',
```

- **保留 DB 默认值，但应用必须显式写入**。原因：分库后各实例时钟可能不一致，
  "订单创建时间"这种用于排序和判超时的字段，如果依赖各库的 `NOW()`，跨库排序会出现乱序。
  保留默认值只是为了兜底和方便手工插数据。
- `ON UPDATE CURRENT_TIMESTAMP` 在业务代码里是"会被忽略的兜底"。注意：
  如果一条 UPDATE 没有真正改变任何列的值，MySQL 不会更新 `update_time`——
  这一点在排查"为什么这行更新时间是老的"时会用到，属于 MySQL 的既有行为（待验证本机版本表现）。

### 3.4 为什么不建物理外键

| 维度 | 建外键 | 不建外键（本设计） |
| --- | --- | --- |
| 引用完整性 | 数据库强制保证 | 应用保证 + 第 7 节校验 SQL 事后兜底 |
| 写入开销 | 每次写子表要检查父表，**并在父行上加共享锁** | 无额外锁 |
| 分库分表 | 跨实例外键根本不存在 | 无影响 |
| DDL / 数据迁移 | 删父表、改父表结构、导入顺序都受约束 | 无约束 |
| 高并发秒杀 | 订单表插入时对库存行加共享锁，与扣减的行排他锁互相等待 | 无此问题 |

结论：抢票场景里"每一次下单都多一次加锁检查"是不可接受的。
一致性靠三件事保证：应用层的状态机（第 4.6 节）、DB 的一些唯一约束、以及**上线前和巡检时跑的校验 SQL**（第 7 节）。
这是一种"把正确性检查从写路径挪到后台巡检"的取舍，代价是数据不一致存在的时间窗口变长。

---

## 4. 表设计逐表说明

### 4.1 rail_user.t_user（用户）

```sql
CREATE TABLE `rail_user`.`t_user` (
  `id`            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '用户主键',
  `username`      VARCHAR(32)      NOT NULL                COMMENT '登录名，全局唯一',
  `phone`         VARCHAR(20)      NOT NULL                COMMENT '手机号，全局唯一，登录/通知用',
  `password_hash` VARCHAR(100)     NOT NULL                COMMENT '口令摘要，不存明文',
  `real_name`     VARCHAR(64)          NULL                COMMENT '实名信息-姓名，未实名时为空',
  `id_card_hash`  CHAR(64)             NULL                COMMENT '证件号 SHA-256 十六进制，用于等值查询与唯一判定',
  `id_card_cipher` VARBINARY(256)      NULL                COMMENT '证件号密文，仅展示用',
  `status`        TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '0正常 1冻结 2注销',
  `create_time`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
```

| 字段 | 为什么存在 / 为什么是这个类型 |
| --- | --- |
| `id` | 主键。选自增而不是 UUID 的理由见 3.2。用户量远小于订单量，`BIGINT` 是留余量 |
| `username` | 登录凭据之一。唯一索引不是"为了查得快"，而是**为了挡住并发注册同名**——先 `SELECT` 再 `INSERT` 在并发下必然漏 |
| `phone` | 手机号既是登录凭据也是通知通道。同样需要唯一索引挡重复注册 |
| `password_hash` | 数据库绝不能存明文口令。长度给 `VARCHAR(100)` 而不是精确的 60，是为了**将来换算法不用改表**：当前主流的口令摘要算法输出固定长度，但算法会演进，留余量比精确匹配划算 |
| `real_name` | 铁路是实名制，出票必须有人名。这里存的是"账号本人的实名信息"，和订单上的乘客快照（`t_order.passenger_name`）是两回事 |
| `id_card_hash` | **关键设计点**：证件号是敏感信息，不能明文存不能建索引，但业务需要"判断这个证件号是否已绑定过某个账号"这种等值查询。SHA-256 后是定长 64 字符，可以建唯一索引，且不可逆（除非证件号空间能被穷举——身份证号空间可枚举，所以严格说 SHA-256 不加盐是可被暴力反查的哈希，生产应加固定盐或改 HMAC，**本项目标注为待加固项**） |
| `id_card_cipher` | 展示用（如"身份证 110***********1234"需要还原出后四位），用对称加密存密文，`VARBINARY` 而非 `VARCHAR`——密文是二进制，用字符集列存会引入编码转换风险 |
| `status` | 冻结 / 注销是运营必需。注销不删行（订单要能追溯到用户），只是改状态 |

索引理由：

- `uk_username`、`uk_phone`：唯一约束 + 登录查询走索引，一举两得。登录是最高频的读之一，
  没有索引就是全表扫描。
- `idx_create_time`：运营侧"某段时间的注册量"类查询。如果实际没有这类查询，**这个索引应该删掉**——
  每个二级索引都要在写入时维护，是纯成本。本设计保留它并在此标注：**上线前按真实查询清单裁剪**。

### 4.2 rail_user.t_passenger（常用乘客）

```sql
CREATE TABLE `rail_user`.`t_passenger` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '乘客主键',
  `user_id`        BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
  `passenger_name` VARCHAR(64)     NOT NULL                COMMENT '乘客姓名',
  `id_card_hash`   CHAR(64)        NOT NULL                COMMENT '证件号 SHA-256，等值查询用',
  `id_card_cipher` VARBINARY(256)  NOT NULL                COMMENT '证件号密文',
  `passenger_type` TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '1成人 2儿童 3学生',
  `verify_status`  TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '0未核验 1已核验 2核验失败',
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id_card` (`user_id`, `id_card_hash`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='常用乘客表';
```

- `uk_user_id_card`：保证**同一用户下同一证件号只出现一次**。注意唯一键是 `(user_id, id_card_hash)`
  而不是单独的 `id_card_hash`——同一个证件号理论上可以被多个账号添加（比如家人代买），
  业务上是否允许是产品问题，本设计选择"允许跨账号，不允许同账号重复"。
  **这个差异必须在面试时能讲清楚**：唯一索引的范围就是业务规则的范围，索引建多宽，业务就被限制得多严。
- `idx_user`：`(user_id, id_card_hash)` 已经能覆盖 `WHERE user_id = ?` 的前缀查询，
  按最左前缀原则，单独的 `idx_user` 是**冗余索引**。保留它在这里是为了演示一个常见错误：
  **冗余索引会拖慢写入且骗过 `EXPLAIN`**——实际上本表应当删掉 `idx_user`。
  本文档在此显式声明：`idx_user` 为教学对照保留，正式建表时应删除。

### 4.3 rail_biz.t_station（车站）

```sql
CREATE TABLE `rail_biz`.`t_station` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `station_code` VARCHAR(8)      NOT NULL                COMMENT '车站电报码，业务主键',
  `station_name` VARCHAR(32)     NOT NULL                COMMENT '车站名',
  `city_code`    VARCHAR(8)      NOT NULL                COMMENT '所属城市编码',
  `city_name`    VARCHAR(32)     NOT NULL                COMMENT '所属城市名',
  `pinyin`       VARCHAR(64)     NOT NULL                COMMENT '全拼，用于搜索',
  `pinyin_abbr`  VARCHAR(16)     NOT NULL                COMMENT '拼音首字母，用于搜索',
  `status`       TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '0停用 1启用',
  `create_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                 ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_code` (`station_code`),
  KEY `idx_name` (`station_name`),
  KEY `idx_pinyin_abbr` (`pinyin_abbr`),
  KEY `idx_city_code` (`city_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车站字典表';
```

- 车站是**字典表**，读极多写极少，可以整体加载进本地缓存，DB 查询只发生在缓存失效时。
  正因如此，这张表的索引可以"奢侈"一点，但每多一个索引就多一份写入成本，字典表写入极少，可以接受。
- `idx_name`：用户可能输入完整站名（"北京南"）而不是选列表，需要按名精确匹配。
- `idx_pinyin_abbr`：搜索框输入 `bj` 要能搜到北京相关车站。注意这**不是** `LIKE '%bj%'`——
  那是前缀模糊，用不上索引；这里的策略是**用户输入的首字母做等值匹配**，前缀模糊交给 ES（本项目未实现）。
- `idx_city_code`：查"某城市有哪些车站"（同城多站换乘）。
- `uk_station_code`：车次表的 `from_station_code` / `to_station_code` 引用它。
  不建物理外键（3.4 节），但唯一索引保证了站码不会重复，这是应用层拼装 SQL 时的信任基础。

### 4.4 rail_biz.t_train（车次）

```sql
CREATE TABLE `rail_biz`.`t_train` (
  `id`                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `train_no`          VARCHAR(16)      NOT NULL                COMMENT '车次号，如 G1024',
  `from_station_code` VARCHAR(8)       NOT NULL                COMMENT '始发站电报码',
  `to_station_code`   VARCHAR(8)       NOT NULL                COMMENT '终到站电报码',
  `depart_time`       TIME             NOT NULL                COMMENT '发车时刻(当日)',
  `arrive_time`       TIME             NOT NULL                COMMENT '到达时刻(当日或跨日)',
  `arrive_day_offset` TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '跨日天数 0当日到 1次日到 2第三日到',
  `duration_minutes`  SMALLINT UNSIGNED NOT NULL               COMMENT '全程历时(分钟)',
  `train_type`        TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '1高铁 2动车 3普速',
  `status`            TINYINT UNSIGNED NOT NULL DEFAULT 1      COMMENT '0停运 1正常 2调整',
  `create_time`       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                       ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_train_no` (`train_no`),
  KEY `idx_route` (`from_station_code`, `to_station_code`, `depart_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车次表';
```

| 字段 / 索引 | 为什么 |
| --- | --- |
| `train_no` | 车次号是业务标识（"G1024"）。`uk_train_no` 意味着**一个车次号只描述一条线路**，即"每天开行"被抽象成一行 |
| `from_station_code` / `to_station_code` | 用站码而不是站 id：站码是对外接口和运维都认的业务标识，且未来拆库时它比自增 id 更稳定 |
| `depart_time` / `arrive_time` | 用 `TIME` 而不是 `DATETIME`：车次的时刻是"每天的几点"，具体日期在 `t_train_stock.travel_date` 里。用 `DATETIME` 就必须为每一天插一行车次，车次表会被日期维度污染 |
| `arrive_day_offset` | 跨日车次（夕发朝至）必须有这个字段，否则 `arrive_time` 会给出"到达时间早于发车时间"的荒谬结果。**踩坑点**：`TIME` 类型本身不能表达"次日"，必须额外一列 |
| `duration_minutes` | 用分钟整数而不是计算 `arrive - depart`：跨日车次算差值要带 offset，每次查询都算一遍既费 CPU 又容易在 SQL 里写错。冗余但语义清晰 |
| `idx_route` | 首页查询的核心路径：`WHERE from_station_code=? AND to_station_code=? ORDER BY depart_time`。三列顺序不是随便排的——前两列是等值条件（区分度最高），第三列是有序的排序键，让 `ORDER BY` 也能吃到索引而不用 filesort |
| `train_type` | 界面要按"高铁/动车/普速"筛选。**低区分度列单独建索引没有意义**（见 6.3），它只能作为组合索引的一部分 |

**本表的已知局限（必须在文档里写明）**：它只支持**直达**车次，
不支持一列车经过多个站、也不支持中转查询（A→C 经由 B）。
真实铁路模型需要 `t_train_stop`（车次经停站，含区间余票）表。
本设计为了聚焦"秒杀"这个核心问题，把区间问题简化掉了，
代价是"北京→上海"只能查到始发终到为该区间的车次。
这是一个明确的取舍，不是遗漏。

**派生出的一个隐患**：`t_stock_flow.stock_id` 指向 `(train_id, travel_date, seat_type)` 这一行，
而 `t_train_stock` 的行是**按需生成**还是**预生成**？本设计选**预生成**（每天凌晨为次日车次铺库存行），
理由是抢票的瞬间不允许"库存行还不存在"这种分支——那一刻要做的是 `UPDATE` 一行已存在的记录，
而不是先 `INSERT` 再 `UPDATE`（后者在并发下会撞唯一索引、产生大量回滚）。
预生成的代价是"没卖出去的车次也占一行"，量级 = 车次数 × 席别数 × 预售天数，可接受（具体行数待估）。

### 4.5 rail_biz.t_train_stock（余票库存，热点表）

这是**整个系统写入压力最大的一张表**，每个下单请求最终都要更新它的一行。

```sql
CREATE TABLE `rail_biz`.`t_train_stock` (
  `id`            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `train_id`      BIGINT UNSIGNED  NOT NULL                COMMENT '车次 id',
  `travel_date`   DATE             NOT NULL                COMMENT '乘车日期',
  `seat_type`     TINYINT UNSIGNED NOT NULL                COMMENT '1商务座 2一等座 3二等座 4软卧 5硬卧 6硬座',
  `total_qty`     INT UNSIGNED     NOT NULL DEFAULT 0      COMMENT '总票额',
  `available_qty` INT UNSIGNED     NOT NULL DEFAULT 0      COMMENT '当前可售余票',
  `price`         DECIMAL(10,2)    NOT NULL                COMMENT '票价(元)',
  `create_time`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_train_date_seat` (`train_id`, `travel_date`, `seat_type`),
  KEY `idx_date_seat` (`travel_date`, `seat_type`),
  CONSTRAINT `chk_available_le_total` CHECK (`available_qty` <= `total_qty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='车次余票库存表';
```

**逐字段说明**

| 字段 | 为什么存在 / 为什么是这个类型 |
| --- | --- |
| `id` | 主键，同时也是**行锁的定位方式**。所有扣减 SQL 都写成 `WHERE id = ? AND available_qty >= ?`，走主键，锁的是一行 |
| `train_id` + `travel_date` + `seat_type` | 这三列共同定义了"一个可售卖的库存单位"。`travel_date` 是 `DATE` 不是 `DATETIME`（理由见 3.2） |
| `total_qty` | 总票额。不可变（放票后一般不调整），但必须有：它是"超卖检查"的基准线，没有它就无法判断 `available_qty` 是否合理 |
| `available_qty` | **唯一的可变字段**。扣减、回补都只动它。用 `INT UNSIGNED` 让"负数"在类型层面不可能出现——即便应用层逻辑写错，DB 也会用报错而不是静默写入负数来提醒 |
| `price` | 票价属于"某天某席别"这个维度，不属于车次（同一车次不同席别价格不同），也不属于活动（活动价在 `t_seckill_activity`）。**价格放这里 = 下单时不需要再查一次活动表** |
| 无 `sold_qty` | 已售 = `total_qty - available_qty`，能算出来的数不存（2.3 节） |

**索引理由**

- `uk_train_date_seat`：第一重作用是**唯一约束**——防止"同一车次同一天同一席别"被铺出两条库存行。
  如果真的出现两行，那就有两份票额，超卖几乎必然发生。第二重作用是给"查某车次某天的所有席别"提供索引。
- `idx_date_seat`：支撑"某天所有车次的某席别余票"这类运营查询，
  以及**对账任务按日期扫描库存行**（第 7 节的 Redis 对账需要一个遍历维度，按 `travel_date` 遍历比全表扫好）。
- `chk_available_le_total`：最后一道防线，条件是 `available_qty <= total_qty`。
  **重要提醒**：MySQL 8.0.16 之前的版本会解析 CHECK 约束但不强制执行，
  8.0.16 起才真正生效。**本机版本行为待验证**，验证前不要把正确性押在这个约束上，
  真正兜底的是第 7 节的校验 SQL。

**状态字段与状态机**

`t_train_stock` **没有 status 字段**，这是刻意的。库存只有"剩下多少"，没有"状态"。
活动状态（未开始 / 进行中 / 已结束）属于运营语义，放在 `t_seckill_activity.status`。
把两种语义混在一列里，会导致"扣库存时改了一次状态"这种谁都不敢删的代码。

### 4.6 rail_biz.t_seckill_activity（秒杀活动）

```sql
CREATE TABLE `rail_biz`.`t_seckill_activity` (
  `id`             BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stock_id`       BIGINT UNSIGNED  NOT NULL                COMMENT '关联 t_train_stock.id',
  `activity_name`  VARCHAR(64)      NOT NULL                COMMENT '活动名称',
  `seckill_price`  DECIMAL(10,2)    NOT NULL                COMMENT '秒杀价(元)',
  `start_time`     DATETIME(3)      NOT NULL                COMMENT '开售时刻',
  `end_time`       DATETIME(3)      NOT NULL                COMMENT '结束时刻',
  `per_user_limit` SMALLINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '每人限购张数',
  `status`         TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '0未开始 1进行中 2已结束 3已下线',
  `create_time`    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                    ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock` (`stock_id`),
  KEY `idx_status_start` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='秒杀活动表';
```

**为什么把活动单独拆一张表，而不是把 `seckill_price`、`start_time` 塞进 `t_train_stock`？**

| 维度 | 塞进 stock 表 | 独立活动表（本设计） |
| --- | --- | --- |
| 行锁 | 运营改价要 `UPDATE` 库存行，而库存行正在被成千上万个下单请求持有行锁 → **改价会长时间等锁，甚至超时失败** | 改价只锁活动行，与扣库存的行锁**完全不冲突** |
| 变更频率 | 库存行每秒变几十次；价格每次活动才变一次 | 冷热分离，各自按自己的频率更新 |
| 职责 | 一张表同时承担"交易扣减"和"运营配置"两种语义 | 各表各司其职 |
| 代价 | 少一次 JOIN | 下单路径多一次查询（可缓存，活动信息变化极少） |

结论：**热点表上不能有任何"运营会随时改的字段"**。这是本设计里最重要的一条性能纪律，
比任何索引技巧都重要。

**状态机**

```
0 未开始 --(到达 start_time，由定时任务或首次访问惰性触发)--> 1 进行中
1 进行中 --(到达 end_time / 手工下线)--> 2 已结束
1 进行中 --(运营下线)--> 3 已下线
0 未开始 --(运营下线)--> 3 已下线
```

注意 **1 → 0 不允许**、**2 / 3 是终态**。
`status` 在这张表里是"给运营看的当前阶段"，不是"下单的准入判据"——
下单准入必须用**时间比较**（`start_time <= now < end_time`）而不是 `status = 1`，
因为 `status` 是定时任务刷出来的，任务延迟就会让"其实已经开售"的场次被挡住，或者反之。
**用状态字段做准入判断，一定要问自己"这个状态是谁在什么时候改的、延迟了会怎样"。**

### 4.7 rail_order.t_order（订单）

```sql
CREATE TABLE `rail_order`.`t_order` (
  `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '订单主键',
  `order_no`         VARCHAR(32)      NOT NULL                COMMENT '业务订单号，对外唯一标识',
  `user_id`          BIGINT UNSIGNED  NOT NULL                COMMENT '下单用户',
  `train_id`         BIGINT UNSIGNED  NOT NULL                COMMENT '车次 id（分片键）',
  `train_no`         VARCHAR(16)      NOT NULL                COMMENT '车次号快照',
  `travel_date`      DATE             NOT NULL                COMMENT '乘车日期',
  `seat_type`        TINYINT UNSIGNED NOT NULL                COMMENT '席别，取值同 t_train_stock.seat_type',
  `stock_id`         BIGINT UNSIGNED  NOT NULL                COMMENT '库存行 id',
  `from_station_code` VARCHAR(8)      NOT NULL                COMMENT '出发站快照',
  `to_station_code`  VARCHAR(8)       NOT NULL                COMMENT '到达站快照',
  `depart_time`      DATETIME(3)      NOT NULL                COMMENT '发车时刻快照',
  `passenger_name`   VARCHAR(64)      NOT NULL                COMMENT '乘客姓名快照',
  `id_card_hash`     CHAR(64)         NOT NULL                COMMENT '乘客证件号 SHA-256',
  `id_card_cipher`   VARBINARY(256)   NOT NULL                COMMENT '乘客证件号密文',
  `amount`           DECIMAL(10,2)    NOT NULL                COMMENT '订单金额(元)',
  `status`           TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '0待支付 1已支付 2已出票 3已关闭 4已退款',
  `active_flag`      BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '0=有效；取消/退票后写入本行id，使唯一索引不再冲突',
  `pay_deadline`     DATETIME(3)      NOT NULL                COMMENT '支付截止时刻',
  `pay_time`         DATETIME(3)          NULL                COMMENT '支付成功时刻',
  `close_time`       DATETIME(3)          NULL                COMMENT '关闭时刻',
  `close_reason`     TINYINT UNSIGNED     NULL                COMMENT '1超时未支付 2用户取消 3出票失败',
  `refund_time`      DATETIME(3)          NULL                COMMENT '退款完成时刻',
  `trace_id`         VARCHAR(64)      NOT NULL                COMMENT '链路追踪 id，排查用',
  `create_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_user_train_seat` (`user_id`, `train_id`, `travel_date`, `seat_type`, `active_flag`),
  KEY `idx_user_create` (`user_id`, `create_time`),
  KEY `idx_close_scan` (`status`, `pay_deadline`),
  KEY `idx_stock_status` (`stock_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单主表';
```

**逐字段说明**

| 字段 | 为什么存在 / 为什么是这个类型 |
| --- | --- |
| `id` | 内部主键，用于 JOIN 和行锁定位。**不对外暴露**——自增 id 可被遍历，会泄露业务量 |
| `order_no` | 对外唯一标识。用 `VARCHAR(32)` 而不是靠 id 对外：订单号需要携带少量业务信息（如下单渠道、日期），且**不能被用户猜到下一个**。唯一索引保证生成算法出 bug 时也不会重复 |
| `train_id` | 分片键，同时是命中库存行的定位键。冗余 `stock_id` 是因为下单时已经算出了库存行，直接用 `stock_id` 定位比每次都用三列条件去查更省 |
| `train_no` / `from_station_code` / `to_station_code` / `depart_time` | **快照字段**。车次时刻表会调整，但"我买的是哪趟车"必须永远是我下单那一刻的样子。快照语义；同时让订单列表页不用 JOIN 车次表 |
| `passenger_name` / `id_card_hash` / `id_card_cipher` | 乘客信息同样做快照。常用乘客表里的信息可以被用户改，**已出票的订单不能被改** |
| `amount` | 下单那一刻算出来的应付金额。不从库存表实时读——活动改价不能影响已生成的订单 |
| `status` | 订单状态机，见下方 |
| `active_flag` | **本设计里最需要理解的一个字段**，见 5.2 节 |
| `pay_deadline` | 下单时就定死（下单时刻 + 支付窗口）。用它做超时关单扫描，比每次算 `create_time + 15min` 能用上索引 |
| `close_reason` | 同样是"已关闭"，超时关和用户主动取消的后续处理不同（是否要发通知、是否要计入风控）。有区分度的信息要单独存 |
| `trace_id` | 秒杀排查的核心工具。一个请求跨了网关、应用、MQ、DB，没有 traceId 就无法把日志串起来 |

**主键选择理由**

自增 `BIGINT UNSIGNED`：
- 订单是**只增不改**的追加型数据，自增意味着 B+ 树只在右侧增长，不会因随机插入导致页分裂。
- 对比雪花 ID：雪花能带来全局唯一、利于将来分片，但需要处理时钟回拨，且低位随机会让插入位置分散。
  本项目已经用 `order_no` 承担了"业务唯一标识"的职责，主键可以纯粹为存储效率服务。
- 对比 UUID：36 字节、完全随机、且**每个二级索引的叶子节点都要存主键值**，
  本表有 4 个二级索引，主键大小会被放大 4 倍。这是 UUID 在 InnoDB 里最贵的地方。

**索引理由**

| 索引 | 为什么存在 |
| --- | --- |
| `uk_order_no` | 订单号必须全局唯一。同时它是支付回调、查询详情的主入口——用户拿着订单号来查，走这个索引 |
| `uk_user_train_seat` | 挡住同一用户重复抢同一车次，见第 5 节 |
| `idx_user_create` | "我的订单"列表：`WHERE user_id = ? ORDER BY create_time DESC`。**注意列序（user_id, create_time）**——等值列在前、排序列在后，这样 `ORDER BY` 直接从索引取有序数据，不产生 filesort。如果只建 `(user_id)`，列表页每次都要把该用户所有订单捞出来排序 |
| `idx_close_scan` | 超时关单定时任务的扫描路径：`WHERE status = 0 AND pay_deadline < now()`。`status` 放前面是因为"待支付"是筛选条件，`pay_deadline` 是范围条件——**范围列必须放在等值列之后**，否则后面的列用不上索引 |
| `idx_stock_status` | 对账用：`WHERE stock_id = ? AND status IN (0,1,2)` 统计某库存行的有效订单数，与 `total - available` 比对。这是第 7 节核心校验 SQL 的执行基础 |

**状态机（订单）**

```
状态枚举：
  0 PENDING_PAY  待支付
  1 PAID         已支付（钱到了，票还没出）
  2 ISSUED       已出票
  3 CLOSED       已关闭（终态，含超时/取消/出票失败）
  4 REFUNDED     已退款（终态）

合法流转：
  0 --支付回调成功--> 1     触发：支付渠道异步通知      库存动作：无（下单时已预扣）
  0 --超时/用户取消--> 3    触发：定时任务 / 用户点击    库存动作：回补 +1，active_flag 置为 id
  1 --出票成功--> 2         触发：出票服务回调          库存动作：无
  1 --出票前退款--> 4       触发：用户申请 / 出票失败    库存动作：回补 +1
  2 --退票成功--> 4         触发：退票流程              库存动作：回补 +1

非法流转（必须在代码里显式拒绝）：
  3 → 1 / 3 → 2 / 4 → 1 / 4 → 2 / 2 → 3 / 1 → 0
```

**为什么 1（已支付）和 2（已出票）要分开？**
支付成功不等于出票成功，中间有第三方出票系统。如果把两者合成一个状态，
"钱收了但票没出"这个状态就无处表达，退款流程也无从触发。

**状态机的并发保护靠条件更新，不靠先查后改：**

```sql
-- 支付回调：只有"待支付"的订单能被推进到"已支付"
UPDATE t_order SET status = 1, pay_time = ?
 WHERE order_no = ? AND status = 0;
-- 影响行数 = 0 表示：订单不存在，或已经被处理过（重复回调）→ 直接返回成功，不报错
```

这一条 `WHERE status = 0` 就是**幂等**的实现：重复的回调第二次执行时影响 0 行，
不需要额外的锁，也不需要读一次再判断。**任何状态流转都必须带上"原状态"作为 WHERE 条件。**

### 4.8 rail_order.t_pay_record（支付流水）

```sql
CREATE TABLE `rail_order`.`t_pay_record` (
  `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pay_no`           VARCHAR(32)      NOT NULL                COMMENT '支付流水号(本系统生成)',
  `order_id`         BIGINT UNSIGNED  NOT NULL                COMMENT '订单 id',
  `order_no`         VARCHAR(32)      NOT NULL                COMMENT '订单号快照',
  `user_id`          BIGINT UNSIGNED  NOT NULL                COMMENT '支付用户',
  `channel`          TINYINT UNSIGNED NOT NULL                COMMENT '1支付宝 2微信 3模拟渠道',
  `channel_trade_no` VARCHAR(64)          NULL                COMMENT '渠道侧交易号，未回调前为 NULL',
  `amount`           DECIMAL(10,2)    NOT NULL                COMMENT '支付金额(元)',
  `status`           TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '0待支付 1支付成功 2支付失败 3已退款',
  `callback_digest`  VARCHAR(512)         NULL                COMMENT '回调报文的摘要，完整报文不入库',
  `request_time`     DATETIME(3)      NOT NULL                COMMENT '发起支付时刻',
  `finish_time`      DATETIME(3)          NULL                COMMENT '支付终态时刻',
  `create_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_no` (`pay_no`),
  UNIQUE KEY `uk_channel_trade_no` (`channel_trade_no`),
  KEY `idx_order` (`order_id`),
  KEY `idx_status_request` (`status`, `request_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付流水表';
```

**这张表最重要的设计点是 `uk_channel_trade_no`。**

- 支付渠道的回调**可能重复投递**（渠道重试、网络抖动、我们返回超时但对方认为失败）。
  重复回调会造成重复发货。`uk_channel_trade_no` 保证"渠道侧的一笔交易"在本系统里只能落一条记录。
- 关键细节：**该列允许为 NULL**，因为支付发起后、渠道回调前我们拿不到渠道交易号。
  MySQL 的唯一索引**允许多个 NULL 值共存**（NULL 不等于 NULL）。
  这一点正好契合需求：未回调的记录可以有多条，一旦写入具体交易号就必须唯一。
  **这是"唯一索引 + NULL 语义"的典型用法，也是最容易被忽略的一点**——
  如果误以为"唯一索引里 NULL 只能有一条"，就会设计出错误的分支逻辑。
- 一笔订单可能有**多条**支付记录（用户第一次支付失败、换渠道重试），
  所以 `order_id` 是普通索引 `idx_order` 而不是唯一索引。
  **把不该唯一的列建成唯一索引，是线上故障的经典来源**。
- `idx_status_request`：扫"长时间处于待支付/支付中"的支付单，用于和渠道对账。
- `idx_create_time`：按天做渠道对账。**若确认没有这个查询，应删除**（每个索引都是写入成本）。

### 4.9 rail_order.t_stock_flow（库存流水）

```sql
CREATE TABLE `rail_order`.`t_stock_flow` (
  `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stock_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '库存行 id',
  `train_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '车次 id（分片键）',
  `order_id`    BIGINT UNSIGNED  NOT NULL                COMMENT '关联订单 id',
  `user_id`     BIGINT UNSIGNED  NOT NULL                COMMENT '用户 id',
  `flow_type`   TINYINT UNSIGNED NOT NULL                COMMENT '1扣减 2回补 3人工修正',
  `change_qty`  INT              NOT NULL                COMMENT '变动量，扣减为负、回补为正',
  `before_qty`  INT UNSIGNED     NOT NULL                COMMENT '变动前余票',
  `after_qty`   INT UNSIGNED     NOT NULL                COMMENT '变动后余票',
  `biz_no`      VARCHAR(64)      NOT NULL                COMMENT '关联请求号/消息id，排查用',
  `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_type` (`order_id`, `flow_type`),
  KEY `idx_stock_time` (`stock_id`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水表';
```

- **为什么需要这张表？** 它是库存变化的**账本**。
  `t_train_stock.available_qty` 是"当前余额"，流水是"每笔交易"。
  没有流水，对账时只能看到余额不对，看不出是**哪一笔**出的问题，
  也无法回答"这个用户的票到底扣了没扣"。账本 + 余额是财务系统的标准结构，库存同理。
- `uk_order_type`：**同一个订单的同一类库存操作只能有一条**。
  这是 MQ 重复消费的兜底——消息重复投递时，第二次插入撞唯一索引，
  事务回滚，库存不会被重复扣减。**这是"用唯一索引实现幂等"的第二个实例。**
- `before_qty` / `after_qty`：让每条流水**自洽**。对账时可以逐条验证
  `before_qty + change_qty = after_qty`，不需要重放全部历史流水。
  代价是每行的空间变大（8 字节），换来的是排查效率。
- `change_qty` 用带符号的 `INT`：扣减为负、回补为正，
  这样 `SUM(change_qty) GROUP BY stock_id` 直接就是净变动量，不用区分类型做加减。
- **没有 `update_time`**：流水是只增不删不改的账本，任何 UPDATE 都意味着设计出了问题。

### 4.10 rail_common.t_local_message（本地消息表）

```sql
CREATE TABLE `rail_common`.`t_local_message` (
  `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type`        VARCHAR(32)      NOT NULL                COMMENT '业务类型，如 STOCK_DEDUCT / ORDER_CLOSE',
  `biz_id`          VARCHAR(64)      NOT NULL                COMMENT '业务唯一标识，如 orderNo',
  `payload`         TEXT             NOT NULL                COMMENT '消息体(JSON)',
  `status`          TINYINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '0待发送 1已发送 2已确认 3死信',
  `retry_count`     SMALLINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '已重试次数',
  `next_retry_time` DATETIME(3)      NOT NULL                COMMENT '下次可重试时刻',
  `last_error`      VARCHAR(512)         NULL                COMMENT '最近一次失败原因摘要',
  `create_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                     ON UPDATE CURRENT_TIMESTAMP(3)        COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`),
  KEY `idx_scan` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地消息表';
```

**为什么需要它？** 下单成功要发消息给下游（通知、出票），
但"写订单"和"发消息"是两个系统，不可能原子。本地消息表是最常用的解法：

```
本地事务 {
  UPDATE 库存
  INSERT 订单
  INSERT 库存流水
  INSERT 本地消息 (status = 0)   <-- 和上面三步同一个事务，必然一起成功
}
事务提交后：投递 MQ → 投递成功则 UPDATE status = 1
兜底：定时任务扫 status = 0 且 next_retry_time <= now() 的消息重新投递
```

对比过的替代方案：

| 方案 | 为什么没选 |
| --- | --- |
| 事务消息（RocketMQ 半消息） | 依赖特定 MQ 的事务消息能力，本项目为教学演示，引入后本地难验证；且它的本质也是"本地表 + 回查" |
| 直接发 MQ，发送失败就回滚本地事务 | MQ 发送不是事务资源，网络超时无法区分"没送到"和"送到了但响应丢了"，必然出现不一致 |
| 数据库 binlog 订阅（Canal） | 解耦最干净，但引入额外组件与运维成本，且消息延迟不可控，教学场景不划算 |
| 本地消息表（本设计） | 只用 MySQL 就能实现，逻辑全部可见可调试；代价是多一次 INSERT 与一个扫描任务 |

- `uk_biz`：保证**同一个业务动作只会产生一条消息**，重试时不会重复插入。
- `idx_scan`：定时任务的扫描路径，`status` 等值在前、`next_retry_time` 范围在后。
  **扫描任务必须能用上这个索引**，否则每次扫描都是全表扫，消息表越大越慢。
- `status = 3`（死信）：重试超过阈值后转人工处理。**不能无限重试**——
  一条永远失败的消息会让扫描任务的 `rows` 越来越大。

### 4.11 rail_common.t_idempotent_record（幂等记录）

```sql
CREATE TABLE `rail_common`.`t_idempotent_record` (
  `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type`    VARCHAR(32)      NOT NULL                COMMENT '业务类型',
  `idem_key`    VARCHAR(64)      NOT NULL                COMMENT '幂等键，通常是 requestId / 消息id',
  `result_code` VARCHAR(32)          NULL                COMMENT '首次执行的业务结果码',
  `expire_time` DATETIME(3)      NOT NULL                COMMENT '过期时刻，过期后可被清理',
  `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_key` (`biz_type`, `idem_key`),
  KEY `idx_expire` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='幂等记录表';
```

这张表用于**接口层的通用幂等**：客户端重试时带上同一个 `requestId`，
服务端先尝试插入 `(biz_type, idem_key)`，插入成功说明是首次请求，继续执行；
插入撞唯一索引说明是重复请求，直接返回首次的结果码。

三种幂等实现方式的对比（本设计三种都用，用在不同地方）：

| 方式 | 用在哪 | 优点 | 缺点 |
| --- | --- | --- | --- |
| 唯一索引（本表 `uk_biz_key`） | 对外接口、通用入口 | 实现简单，不依赖业务表结构，能记录返回结果 | 多一张表和一次插入；需要清理任务 |
| 业务唯一索引（`t_order.uk_user_train_seat`） | 下单 | 零额外成本，本来就要建这个索引 | 语义绑在业务上，不能泛化 |
| 状态机条件更新（`WHERE status = 0`） | 状态流转 | 不产生任何额外写入 | 只能用于有状态机的场景，且不能返回首次结果 |

**为什么三种都要有，不能只留一种？** 它们解决的问题粒度不同：
状态机管"同一条记录的重复流转"，业务唯一索引管"同一业务语义的重复创建"，
幂等记录表管"同一个请求的重复提交"。**在面试里能把这三种的适用边界说清楚，比背"用 Redis 做幂等"有价值得多。**

- `idx_expire`：清理任务 `DELETE FROM t_idempotent_record WHERE expire_time < now() LIMIT 1000` 的索引。
  **清理必须分批 + LIMIT**，一次删太多会长时间持有锁并产生大事务。
- 幂等键必须**客户端生成并携带**，服务端生成的键在重试时会变，等于没有幂等。

### 4.12 表之间的引用关系（逻辑外键，非物理）

```
t_user.id ──┬─→ t_passenger.user_id
            ├─→ t_order.user_id
            ├─→ t_stock_flow.user_id
            └─→ t_pay_record.user_id

t_station.station_code ──┬─→ t_train.from_station_code / to_station_code
                         └─→ t_order.from_station_code / to_station_code

t_train.id ──┬─→ t_train_stock.train_id
             ├─→ t_order.train_id
             └─→ t_stock_flow.train_id

t_train_stock.id ──┬─→ t_seckill_activity.stock_id   (1:1，唯一索引保证)
                   ├─→ t_order.stock_id
                   └─→ t_stock_flow.stock_id

t_order.id ──┬─→ t_stock_flow.order_id   (1:N，但 (order_id, flow_type) 唯一)
             └─→ t_pay_record.order_id   (1:N，允许支付重试产生多条)
```

这些关系**全部由应用代码维护**。不建物理外键的理由见 3.4 节。
代价是可能出现孤儿数据，所以第 7 节必须有不一致数据的巡检 SQL。

---

## 5. 唯一索引与幂等的关系

### 5.1 核心论点

> **锁是"减少冲突"的优化手段，唯一索引是"保证正确性"的约束。
> 前者可以失败、可以超时、可以因为主从切换而丢失；后者由数据库引擎在写入路径上强制。
> 所以并发正确性必须落在唯一索引上，分布式锁只能用来提升性能。**

这个区分是本设计里最重要的一条。下面用具体场景说明。

### 5.2 挡住"同一用户重复抢同一车次"的唯一索引

需求：一个用户，对同一个车次、同一个乘车日期、同一个席别，**只能有一张有效订单**（限购 1 张）。

**方案 A：只建 4 列唯一索引**

```sql
UNIQUE KEY uk_user_train_seat (`user_id`, `train_id`, `travel_date`, `seat_type`)
```

- 它挡住了什么：任何第二个 `(user_id, train_id, travel_date, seat_type)` 相同的插入都会失败。
- 它的问题：**用户取消订单后无法重新下单**。因为被取消的订单仍然占着这个键，
  用户想改签到同一天的另一趟车再改回来，或者只是手滑取消后想重买，都会被数据库挡住。
- 这在业务上是不可接受的，但它**至少是安全的**（不会超卖）。

**方案 B：把 `status` 加进唯一索引**

```sql
UNIQUE KEY uk_user_train_seat (`user_id`, `train_id`, `travel_date`, `seat_type`, `status`)
```

- 看起来很美：待支付的订单 `(...,0)` 互相冲突，取消的订单是 `(...,3)`，不阻碍新的 `(...,0)`。
- **致命缺陷**：用户下单后支付成功，订单状态变成 1，键变成 `(...,1)`。
  此时他**可以再下一单**，形成 `(...,0)`，支付后变成 `(...,1)`——也成功了！
  结果是**同一个用户同一车次有两张有效票**，限购规则被绕过，
  而且这个 bug 只在"用户支付之后"才出现，测试很容易漏掉。
- 根本原因：**把可变的字段放进唯一索引 = 索引的约束力随状态漂移**。

**方案 C：引入 `active_flag`（本设计采用）**

```sql
`active_flag` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=有效；取消/退票后写入本行id',
UNIQUE KEY uk_user_train_seat (`user_id`, `train_id`, `travel_date`, `seat_type`, `active_flag`)
```

- 订单有效时 `active_flag = 0`，所以同一用户同一车次的**有效订单最多一条**。
- 订单关闭/退款时，应用在同一个 `UPDATE` 里把 `active_flag` 置为**本行的 `id`**：

```sql
UPDATE t_order SET status = 3, active_flag = id, close_time = ?, close_reason = 1
 WHERE id = ? AND status = 0;
```

- 因为 `id` 全局唯一，取消后的订单不会和任何其他行的 `(..., active_flag)` 组合冲突。
- 用户可以重新下单（新的 `active_flag = 0`），而**有效的仍然只有一张**。
- `WHERE id = ? AND status = 0` 保证了这个操作本身也是幂等的（重复执行影响 0 行）。

**三个方案的对比**

| 方案 | 挡住重复下单 | 取消后可重买 | 支付后仍受约束 | 额外成本 |
| --- | --- | --- | --- | --- |
| A：4 列 | 是 | **否** | 是 | 无 |
| B：加 status | **否**（支付后可再买） | 是 | **否** | 无 |
| C：加 active_flag（本设计） | 是 | 是 | 是 | 一列 8 字节 + 取消时要多写一列 |

**为什么不用 `deleted_flag` / `is_deleted`？** 也可以用，但二值标记在"多条已删除记录"上会互相冲突
（同一个用户第二次取消时，`active_flag` 若为固定值 1 就会和第一次取消的行撞车）。
用 `id` 作为标记值，天然唯一，不需要额外思考。若改用 `NULL` 也可以——MySQL 唯一索引允许多个 NULL——
但 `active_flag IS NULL` 的条件查询用不上索引的等值匹配，且 NULL 的语义（"已失效"）不如"写入自身 id"直白。

**这个索引还顺带提供了什么？**
`(user_id, train_id, travel_date, seat_type, active_flag)` 的最左前缀可以支撑：
`WHERE user_id = ?`、`WHERE user_id = ? AND train_id = ?` 等查询。
但它**不能**支撑 `WHERE train_id = ? AND travel_date = ?`（最左前缀断裂），
对账按库存行统计订单必须靠 `idx_stock_status`。

### 5.3 其他唯一索引分别挡住了什么重复

| 唯一索引 | 挡住的重复 | 不建会怎样 | 触发场景 |
| --- | --- | --- | --- |
| `t_order.uk_order_no` | 同一个订单号被写入两次 | 重复订单号会污染支付回调（回调按订单号定位订单，可能更新到错误订单） | 订单号生成器 bug、分布式环境下多节点撞号 |
| `t_order.uk_user_train_seat` | 同一用户对同一车次日席别的重复有效订单 | 一个人抢到多张票，超卖，限购失效 | 用户狂点、脚本重放、MQ 重复消费 |
| `t_stock_flow.uk_order_type` | 同一订单的同类库存操作被执行两次 | **库存被重复扣减或重复回补**，超卖或余票虚高 | MQ 至少一次投递语义下的重复消费 |
| `t_train_stock.uk_train_date_seat` | 同一车次同一天同席别存在两行库存 | 两份票额被分别扣减，**必然超卖** | 铺库存任务重跑、并发铺数据 |
| `t_pay_record.uk_channel_trade_no` | 同一笔渠道交易写入两条支付记录 | 重复回调被当成两笔支付，触发两次出票 | 支付渠道重复通知（很常见） |
| `t_local_message.uk_biz` | 同一业务动作产生两条消息 | 下游收到两次，重复发货（若下游无幂等） | 本地事务重试 |
| `t_idempotent_record.uk_biz_key` | 同一个请求被处理两次 | 重复下单、重复扣款 | 客户端超时重试、网关重发 |
| `t_user.uk_phone` / `uk_username` | 并发注册出两个同手机号账号 | 账号体系混乱，登录可能命中错误账号 | 用户并发点击注册 |
| `t_station.uk_station_code` | 车站字典重复 | 车次查询条件匹配到两条站记录，余票计算错乱 | 字典导入重跑 |
| `t_seckill_activity.uk_stock` | 一个库存行有多个活动 | 价格取哪一条不确定，下单金额不可预期 | 运营重复建活动 |

**共同点**：这些约束全部是**业务不变式（invariant）**，不是性能优化。
写在 DDL 里的约束，是"任何绕过应用的写操作（运维手改、数据修复脚本、新同事写的另一条代码路径）都必须遵守"的规则。
写在应用代码里的 `if (exists) throw` 不是约束，是一次检查。

### 5.4 为什么不用分布式锁来挡

先明确分布式锁**能**做什么、**不能**做什么。

| 维度 | 分布式锁（Redis SETNX / Redisson） | 唯一索引（本设计） |
| --- | --- | --- |
| 语义 | "我大概率先拿到执行权" | "数据库保证不可能有两条" |
| 租约到期 | 业务执行超过 TTL 时锁自动释放，**第二个请求会进来** | 不适用，约束永远生效 |
| 主从切换 | Redis 主从异步复制，锁可能丢失，两个客户端同时持锁 | 不适用，InnoDB 提交即生效 |
| 服务不可用 | 要么拒绝全部请求（可用性下降），要么放行（正确性丢失），**没有第三个选择** | 数据库不可用则整个系统不可写，本来就是硬依赖 |
| 成本 | 每次请求至少一次网络 RTT + 锁竞争；Redisson 看门狗还要额外的续期请求 | 冲突检测在 B+ 树插入路径上顺带完成，成本 ≈ 一次索引插入 |
| 排查 | 锁泄漏、锁误删、续期失败等问题很难复现 | 冲突会直接抛出 `DuplicateKeyException`，日志里有唯一键名，一眼定位 |
| 跨语言/跨系统 | 需要所有写入方都遵守"先加锁"的约定 | 任何写入方（包括运维手写的 SQL）都逃不掉 |

**具体的失败场景（面试可以直接讲）**：

1. 用户 A 的请求拿到锁，正在执行；JVM 发生了一次长 Full GC（或数据库慢查询），
   执行时间超过锁 TTL，锁自动释放。用户 A 的重试请求拿到锁并成功下单；
   随后用户 A 的第一个请求恢复执行，也写入订单 → **两张订单**。
   有了 `uk_user_train_seat`，第二个请求会撞唯一索引并被拒绝。
2. Redis 主从切换，锁数据还没同步到从库，新的主节点上没有这把锁。
   两个请求同时认为自己持锁 → **两张订单**。
   唯一索引仍然会挡住第二个。
3. 有人写了数据修复脚本，直接连数据库批量补订单，**完全绕过了应用层的加锁逻辑**。
   有唯一索引时，脚本会立刻报错；没有时，脏数据会静静写进去，
   等到对账时才被发现，而那时已经无法判断哪条是对的。

**结论**：分布式锁可以保留，但要它做的是**削峰和减少无效请求**——
比如在网关层用"用户 id + 车次"做限流，避免同一用户的上百次点击都打到数据库。
即使锁全部失效，系统也不会产生一条重复订单，因为最终把关的是唯一索引。
**这个"锁失效了也不会错"的性质，才是把约束放在数据库里的意义。**

### 5.5 Redis 预扣与唯一索引的分工

秒杀路径里的两道防线必须说清楚各自负责什么：

| 防线 | 位置 | 负责 | 挂了会怎样 |
| --- | --- | --- | --- |
| 第一道：Redis 预扣库存 + 去重 | `rail_biz` 的库存 key + 去重集合，Lua 脚本保证原子 | **性能**：把绝大多数无效请求挡在 DB 之外 | 请求全部涌向 DB，DB 压力上升甚至被打挂（**性能事故，不是数据事故**） |
| 第二道：DB 条件更新 + 唯一索引 | `t_train_stock`、`t_order` | **正确性**：绝不多卖一张票、绝不多出一条订单 | 系统不可写（**这是硬依赖，无法绕过**） |

Lua 脚本的核心逻辑（写在 Redis 侧，保证"判断 + 扣减"原子）：

```lua
-- KEYS[1] = 库存 key,  KEYS[2] = 去重集合 key
-- ARGV[1] = userId,    ARGV[2] = 扣减数量
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return -2                            -- 该用户已抢过，直接拒绝
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock < tonumber(ARGV[2]) then
  return -1                            -- 库存不足
end
redis.call('DECRBY', KEYS[1], ARGV[2])
redis.call('SADD', KEYS[2], ARGV[1])
return stock - tonumber(ARGV[2])
```

- `SISMEMBER + SADD` 用来挡**同一用户的重复请求**，是性能优化，不是正确性保证。
  集合会随参与用户数增长，必须设置 TTL（活动结束后过期）；内存占用**待压测**。
- 去重集合的替代方案对比：

| 方案 | 为什么没选 / 为什么可用 |
| --- | --- |
| Redis Set（本设计的教学版） | 实现最简单、语义准确；缺点是内存随用户数线性增长 |
| Bitmap | 只有 `user_id` 连续自增时才可行，且需要预分配；本设计的主键是自增的，**可以作为后续优化方向** |
| 布隆过滤器 | 有误判：会把没抢过的用户判为"已抢过"，直接损害真实用户体验。**只能用于前置挡流量，不能用于最终判定** |
| 不在 Redis 去重，只靠 DB 唯一索引 | 正确性没问题，但同一用户的重复请求会全部打到 DB，白白消耗连接和行锁，浪费削峰层的价值 |

- **Redis 预扣的数值与 DB 的 `available_qty` 必然会有短暂不一致**：
  消息还在 MQ 里没落库。所以对账 SQL（7.4 节）必须先在途消息清零，
  否则会把"正在路上的扣减"误判为泄漏。**这是对账任务最容易写错的地方。**

---

## 6. 索引失效与慢查询预防

判定一条 SQL 是否走索引，用 `EXPLAIN`。关注四列：

| 列 | 期望 | 需要警惕 |
| --- | --- | --- |
| `type` | `const` / `eq_ref` / `ref` / `range` | `index`（全索引扫描）、`ALL`（全表扫描） |
| `key` | 命中了预期的索引名 | `NULL` |
| `rows` | 与实际匹配行数量级相当 | 远大于预期（统计信息过期或索引选错） |
| `Extra` | 尽量少 | `Using filesort`、`Using temporary`、大 rows 下的 `Using where` |

`Using index`（覆盖索引）是好的；`Using filesort` 在小结果集上无所谓，在大结果集上必须消除。
**不要凭感觉判断有没有走索引，一定要看 `EXPLAIN`。** 优化器会根据统计信息自己做选择，
"我建了索引它就一定用"是错误的心智模型。

### 6.1 错误写法 vs 正确写法

**（1）对列做函数运算 → 索引失效**

```sql
-- 错误：对 create_time 用 DATE()，索引失效，全表扫描
SELECT id, order_no FROM t_order WHERE DATE(create_time) = '2026-09-15';

-- 正确：改成范围查询，能用上 create_time 上的索引
SELECT id, order_no FROM t_order
 WHERE create_time >= '2026-09-15 00:00:00.000'
   AND create_time <  '2026-09-16 00:00:00.000';
```

原因：B+ 树索引里存的是 `create_time` 的原始值，不是 `DATE(create_time)` 的值。
一旦对列施加函数，索引中就没有任何可用来定位的键。
**推而广之**：`LEFT(order_no, 6)`、`UPPER(username)`、`amount + 1 > 100`、
`CONCAT(a, b) = 'xx'` 全部同理。
**例外**：`WHERE create_time >= ?` 这种"列在比较符左边、右边是常量表达式"是没问题的，
函数作用在常量上没有影响。

**（2）隐式类型转换 → 索引失效**

```sql
-- 错误：order_no 是 VARCHAR(32)，右边写成了数字字面量
SELECT id FROM t_order WHERE order_no = 2026091512345678;

-- 正确：字符串列必须用字符串字面量比较
SELECT id FROM t_order WHERE order_no = '2026091512345678';
```

原因：字符串列与数字比较时，MySQL 会把**列的值转成 DOUBLE 再比较**（而不是把数字转成字符串），
列被函数化了，索引无法定位。
**注意方向性**：`WHERE user_id = '123'`（数字列 vs 字符串常量）**不会**失效，
因为转换发生在常量那一侧。所以规矩是：**列是什么类型，字面量就写成什么类型**，
不要依赖"反正 MySQL 会转"。

**（3）前导模糊 LIKE → 索引失效**

```sql
-- 错误：以 % 开头，无法定位 B+ 树的起点，全表扫描
SELECT id FROM t_train WHERE train_no LIKE '%1024%';

-- 正确：前缀匹配，能用上 uk_train_no
SELECT id FROM t_train WHERE train_no LIKE 'G102%';
```

如果业务真的需要"包含"查询（如按车站名模糊搜），正确做法不是硬撑 SQL，
而是把搜索交给搜索引擎，或者为站名/拼音首字母单独建列做等值匹配（本设计在 `t_station` 里就是
用 `pinyin_abbr` 等值匹配代替 `LIKE '%bj%'`）。

**（4）联合索引最左前缀断裂 → 索引部分或完全失效**

```sql
-- 索引 uk_user_train_seat (user_id, train_id, travel_date, seat_type, active_flag)

-- 正确：从最左列开始，用上前 2 列
SELECT id FROM t_order WHERE user_id = ? AND train_id = ?;

-- 错误：跳过了 user_id，整个索引用不上（除非优化器选其他索引或走覆盖索引扫描）
SELECT id FROM t_order WHERE train_id = ? AND travel_date = ?;
```

**跨过最左列不是"用不上"，而是"无法快速定位"**——优化器可能选择扫描整个二级索引
（`type = index`，即全索引扫描）而不是全表扫描，看起来 `key` 列有值，但 `rows` 是整张表，
性能上与全表扫描同级。**只看 `key` 不为 NULL 就认为"索引生效了"，是最常见的误判。**

正确做法：为真实的查询模式单独建索引。本设计中"按库存行查订单"用的是
`idx_stock_status(stock_id, status)`，而不是去用 `uk_user_train_seat`。

**（5）不等号 / NOT IN / IS NOT NULL → 通常全表扫描**

```sql
-- 错误：!= 无法用 B+ 树做范围定位（即使有 idx_close_scan）
SELECT id FROM t_order WHERE status != 3;

-- 正确：改成等值枚举，把需要的少量状态列出来，用上 idx_close_scan 的最左列
SELECT id FROM t_order WHERE status IN (0, 1, 2);
```

补充说明：`status IN (0,1,2)` 且这三个值覆盖了表里绝大部分数据时，
优化器可能仍然选择全表扫描——**这不是索引失效，是优化器的正确选择**
（走索引再回表比直接扫表更贵）。判断标准是 `rows` 和实际执行时间，不是 `key` 是否为 NULL。

同理 `WHERE cancel_time IS NULL` 这种"大部分行都满足"的条件，即便建了索引也不会被优先选择。
`NULL` 值在二级索引里是存了的（索引会记录 NULL），所以 `IS NULL` 本身能用索引，
问题在于**区分度**。

**（6）低区分度列单独建索引 → 白花写入成本**

```sql
-- 错误：status 只有 5 个取值，单独建索引没有任何过滤价值
ALTER TABLE t_order ADD INDEX idx_status (status);

-- 正确：作为组合索引的等值前缀，后面跟有区分度的列
KEY `idx_close_scan` (`status`, `pay_deadline`)
```

一个只有 5 个取值的列，走索引定位到 20% 的行，再逐行回表，
代价通常高于直接全表扫描。**低区分度列只有作为组合索引的前缀才有意义。**

**（7）排序字段与索引顺序不一致 → filesort**

```sql
-- 索引 idx_user_create (user_id, create_time)

-- 正确：等值列 user_id + 排序列 create_time，索引天然有序，无 filesort
SELECT id, order_no FROM t_order WHERE user_id = ? ORDER BY create_time DESC LIMIT 20;

-- 错误：排序列与索引顺序相反（索引是 (user_id, create_time)，这里按 status 排序）
SELECT id, order_no FROM t_order WHERE user_id = ? ORDER BY status DESC LIMIT 20;
```

`ORDER BY` 的列顺序必须与索引中排序列的顺序一致（方向可以全反向，
MySQL 8.x 支持降序索引，但 `ORDER BY a ASC, b DESC` 这种混合方向在旧版本上无法用索引排序，
**本机版本表现待验证**）。

**（8）深分页 → 越翻越慢**

```sql
-- 错误：LIMIT 1000000, 20，MySQL 要扫描并丢弃前 100 万行
SELECT id, order_no FROM t_order WHERE user_id = ? ORDER BY create_time DESC LIMIT 1000000, 20;

-- 正确：游标分页，用上一页最后一条的 create_time（或 id）作为游标
SELECT id, order_no FROM t_order
 WHERE user_id = ? AND create_time < ?
 ORDER BY create_time DESC LIMIT 20;
```

订单列表只做"下一页"，不做"跳到第 N 页"（产品上也没有这种需求），
所以游标分页是完全可行的。**任何 `LIMIT` 偏移量随页码增长的 SQL，都是慢查询的定时炸弹。**

**（9）`SELECT *` → 破坏覆盖索引**

```sql
-- 错误：即使 idx_user_create 能提供 user_id 和 create_time，
-- 但 SELECT 需要其他列，必须回表
SELECT * FROM t_order WHERE user_id = ? ORDER BY create_time DESC LIMIT 20;

-- 正确（列表页只需要这几列，可以走覆盖索引，避免回表）
SELECT id, order_no, train_no, travel_date, amount, status
  FROM t_order WHERE user_id = ? ORDER BY create_time DESC LIMIT 20;
```

注意：InnoDB 的二级索引叶子节点存的是**主键值**，所以上面这条 SQL 仍然需要回表取 `order_no` 等列。
如果要做到完全不回表，需要把列表页所有列都放进索引——**索引会变得很大，写入变慢**。
这是一个明确的取舍，具体收益**待压测**，不要在文档里拍数字。

**（10）JOIN 字段类型/字符集不一致 → 被驱动表索引失效**

```sql
-- 错误：两列字符集不同（一个 utf8mb4、一个 utf8），JOIN 时发生隐式转换
SELECT o.id FROM t_order o JOIN t_user u ON o.user_id = u.id;

-- 正确：全库统一 utf8mb4，且被驱动表的关联列必须有索引
```

**本设计通过"全库统一 utf8mb4 / utf8mb4_0900_ai_ci"从一开始避免这个问题**，
这是 2.1 节里"统一字符集"最实际的收益。
另外要记住 JOIN 的执行顺序：**小表驱动大表，被驱动表的关联列必须建索引**，
否则每一行驱动数据都要对被驱动表做一次全表扫描。

### 6.2 上线前的慢查询纪律

| 措施 | 具体做法 | 为什么 |
| --- | --- | --- |
| 慢查询日志 | 开启 `slow_query_log`，阈值先设一个较低值，稳定后按实际情况调整（具体阈值**待定**） | 没有日志就没有优化对象 |
| DDL 评审 | 每个索引都要回答"哪条 SQL 用它"，回答不出来就不建 | 索引是写入成本，不是免费的 |
| 禁止无 WHERE 的 UPDATE/DELETE | 代码评审强制项 | 一次全表 UPDATE 会锁住整张表 |
| 批量操作必须 LIMIT | 清理任务、重试任务 | 大事务会长时间持锁并放大主从延迟 |
| 上线前对生产量级的数据跑 EXPLAIN | 用真实数据量验证，不用开发库的几十行 | 小表上所有计划都"看起来很好" |

### 6.3 本设计里几个索引的取舍说明

| 表 | 索引 | 保留理由 | 可能被删掉的情况 |
| --- | --- | --- | --- |
| `t_user` | `idx_create_time` | 运营统计注册量 | 确认无此查询即删 |
| `t_passenger` | `idx_user` | **冗余，应删除**（`uk_user_id_card` 已覆盖前缀查询） | 建表时就该去掉 |
| `t_station` | `idx_name` / `idx_pinyin_abbr` / `idx_city_code` | 字典表写入极少，读多，可接受 | 若全部走缓存，可只留 `uk_station_code` |
| `t_pay_record` | `idx_create_time` | 按天渠道对账 | 确认无此查询即删 |
| `t_stock_flow` | `idx_create_time` | 按时间清理历史流水 | 若按 `stock_id` 归档则不需要 |

**索引数量与写入性能是直接冲突的**：每一个二级索引在 INSERT / UPDATE / DELETE 时都要维护。
本设计的做法是**先把"确定要用"的索引建上，其余等真实查询出现再加**，
而不是"先把可能用到的都建上"。加索引比删索引容易，但两者都需要 DDL 变更。

---

## 7. 校验 SQL 清单

这些 SQL 是**离线/定时**执行的，不是写路径的一部分。
它们的作用是：把"我以为数据是对的"变成"我能证明数据是对的"。
所有 SQL 都是只读查询（对账 SQL 也是先查再决定是否修复），可以安全地在从库上跑。

### 7.1 超卖检查

**（1）余票为负或超过总票额**

```sql
SELECT id, train_id, travel_date, seat_type, total_qty, available_qty
  FROM t_train_stock
 WHERE available_qty > total_qty;
```

- 预期结果：**0 行**。
- 说明：`available_qty` 是 `INT UNSIGNED`，正常情况下不可能为负（扣到 0 再扣会报错或被
  `WHERE available_qty >= n` 挡住），所以真正要查的是"大于总票额"。
  如果 `chk_available_le_total` 约束真正生效（8.0.16+），这里理论上查不出东西，
  但**约束是否生效必须验证**，不能假设。
- 发现后怎么办：立即冻结该库存行的售卖（把活动下线），然后按 7.5 节的方式排查。

**（2）库存余额与有效订单数不一致（超卖/少卖的核心校验）**

```sql
SELECT s.id            AS stock_id,
       s.train_id,
       s.travel_date,
       s.seat_type,
       s.total_qty,
       s.available_qty,
       s.total_qty - s.available_qty AS sold_by_stock,
       COUNT(o.id)                   AS sold_by_order
  FROM t_train_stock s
  LEFT JOIN t_order o
         ON o.stock_id = s.id
        AND o.status IN (0, 1, 2)          -- 待支付/已支付/已出票都占用库存
 WHERE s.travel_date >= ?                   -- 用日期缩小扫描范围，避免全表
   AND s.travel_date <  ?
 GROUP BY s.id, s.train_id, s.travel_date, s.seat_type, s.total_qty, s.available_qty
HAVING sold_by_stock <> sold_by_order;
```

- 预期结果：**0 行**。
- 口径说明（非常关键，口径写错会得到满屏的假告警）：
  - `status IN (0,1,2)`：待支付订单也占着库存（下单时就预扣了），必须计入；
    已关闭（3）和已退款（4）的订单在关单时已经回补，**不能计入**。
  - 这个口径必须和代码里"什么时候扣、什么时候回补"完全一致。
    **对账 SQL 的价值取决于口径的正确性，而口径错误比不做对账更危险**（会产生大量噪音，最后没人看告警）。
- 局限：这条 SQL 是跨表 JOIN，**分库分表后无法执行**。
  届时的替代方案是：分别按分片统计 `sold_by_stock` 和 `sold_by_order`，
  在应用层汇总比对（本项目未实现，标注为扩展点）。

**（3）库存流水与库存余额是否自洽**

```sql
SELECT f.stock_id,
       SUM(f.change_qty)             AS net_change,
       MIN(f.before_qty)             AS min_before,
       MAX(f.after_qty)              AS max_after,
       COUNT(*)                      AS flow_count
  FROM t_stock_flow f
 WHERE f.create_time >= ?
   AND f.create_time <  ?
 GROUP BY f.stock_id
HAVING net_change <> 0;
```

- 用途：`t_stock_flow` 是账本，`SUM(change_qty)` 应该等于 `初始值 - 当前 available_qty`。
  这条 SQL 只检查"净变动是否为 0"这种明显异常；
  完整校验需要与库存表 JOIN（见下一条）。
- **每条流水的自洽性**（不依赖历史，可单独验证）：

```sql
SELECT id, stock_id, order_id, flow_type, before_qty, change_qty, after_qty
  FROM t_stock_flow
 WHERE before_qty + change_qty <> after_qty;
```

预期 0 行。这是最便宜也最有效的一条校验——它不需要任何上下文。

### 7.2 重复订单检查

**（1）同一用户同一车次日席别的重复有效订单**

```sql
SELECT user_id, train_id, travel_date, seat_type, COUNT(*) AS cnt,
       GROUP_CONCAT(order_no ORDER BY id) AS order_nos
  FROM t_order
 WHERE status IN (0, 1, 2)
   AND create_time >= ?
   AND create_time <  ?
 GROUP BY user_id, train_id, travel_date, seat_type
HAVING cnt > 1;
```

- 预期结果：**0 行**。
- 注意：这里用的是 `status IN (0,1,2)` 而**不是** `active_flag = 0`。
  两者应该等价（有效订单的 `active_flag` 为 0），
  **故意用不同口径写一遍，是为了交叉验证**：
  如果 `active_flag` 的维护逻辑写错了（比如关单时忘记更新），
  唯一索引就会失效，而这条 SQL 能查出来。
  这比"再跑一遍同样的条件"有价值。

**（2）订单号重复**

```sql
SELECT order_no, COUNT(*) AS cnt FROM t_order GROUP BY order_no HAVING cnt > 1;
```

预期 0 行（有 `uk_order_no` 时理论上不可能，**这条 SQL 的作用是验证索引真的存在**——
手工建表、误删索引、迁移时丢索引，都会在这里暴露）。

**（3）孤儿订单 / 孤儿流水**

```sql
-- 订单引用的库存行不存在
SELECT o.id, o.order_no, o.stock_id
  FROM t_order o
  LEFT JOIN t_train_stock s ON s.id = o.stock_id
 WHERE s.id IS NULL AND o.create_time >= ?;

-- 流水引用的订单不存在
SELECT f.id, f.order_id, f.stock_id
  FROM t_stock_flow f
  LEFT JOIN t_order o ON o.id = f.order_id
 WHERE o.id IS NULL AND f.create_time >= ?;
```

预期 0 行。因为不建物理外键（3.4 节），**这类校验是"不用外键"这个决策必须配套的代价**。

### 7.3 幂等与消息积压检查

```sql
-- 本地消息积压（待发送 + 长期未确认）
SELECT status, COUNT(*) AS cnt, MIN(create_time) AS oldest
  FROM t_local_message
 WHERE status IN (0, 1)
 GROUP BY status;

-- 死信（需要人工介入）
SELECT id, biz_type, biz_id, retry_count, last_error, update_time
  FROM t_local_message
 WHERE status = 3
 ORDER BY update_time DESC
 LIMIT 100;

-- 重试次数异常高的消息（可能在毒打下游）
SELECT id, biz_type, biz_id, retry_count, last_error
  FROM t_local_message
 WHERE retry_count >= ?                       -- 阈值按实际情况设，待定
   AND update_time >= ?
 LIMIT 100;
```

- 用途：**在跑库存对账之前必须先看这里**。如果"待发送"的消息很多，
  Redis 与 DB 的库存差异就是正常的在途差异，不是数据丢失。
- `oldest` 这一列很重要：一条卡了很久的待发送消息，比一万条刚产生的消息更可疑。

### 7.4 Redis 与 DB 库存对账

**对账的三个步骤，顺序不能反。**

**第一步：确认没有在途消息**

```sql
SELECT COUNT(*) AS in_flight
  FROM t_local_message
 WHERE biz_type = 'STOCK_DEDUCT'
   AND status IN (0, 1)
   AND create_time >= ?;
```

- 如果 `in_flight > 0`，**对账结果不可信**，直接等下一轮。
- 这就是"先看在途"的原因：Redis 预扣成功但消息还在 MQ 里时，
  Redis 值比 DB 小，看起来像"Redis 扣了但 DB 没扣"，实际上只是还没到。

**第二步：把 DB 侧的余票快照拉出来**

```sql
SELECT id AS stock_id, train_id, travel_date, seat_type, available_qty
  FROM t_train_stock
 WHERE travel_date >= ?          -- 只在售的日期范围，不查历史
   AND travel_date <  ?
 ORDER BY id;
```

DB 侧的值就是"真值"（理由见下）。应用侧拿到这份列表后，
按 `stock:{trainId}:{travelDate}:{seatType}` 逐个（或用 pipeline / MGET 批量）读 Redis，
逐条比对：

| Redis 值 | DB 值 | 判定 | 处理 |
| --- | --- | --- | --- |
| 不存在 | 任意 | key 未被预热或已过期 | 按 DB 值重新预热 |
| 与 DB 相同 | 同 | 一致 | 无 |
| 小于 DB | 更小 | Redis 少票 | 可能是**已扣未落库的在途请求**（回到第一步确认），确认无在途则为**扣减泄漏**，按 DB 回写并记录差异 |
| 大于 DB | 更大 | Redis 多票 | 可能是**回补泄漏**（关单回补只写了 Redis 没写 DB，或写反了），立即回写并**停止该库存行的秒杀入口**，人工核查是否已超卖 |
| 负数 | 任意 | Lua 逻辑或人为误操作把库存改成负 | 立即回写，并把该活动下线排查 |

**第三步：差异归因与修复**

```sql
-- 找出"扣了库存但没有对应订单"的流水（疑似泄漏点）
SELECT f.stock_id, f.order_id, f.flow_type, f.create_time
  FROM t_stock_flow f
  LEFT JOIN t_order o ON o.id = f.order_id
 WHERE f.flow_type = 1
   AND f.create_time >= ?
   AND o.id IS NULL;
```

**为什么以 DB 为准，而不是以 Redis 为准？**

1. DB 是**有约束、有事务、有持久化**的一侧。`available_qty` 上的 UPDATE 是原子的，
   加上唯一索引和 `WHERE available_qty >= n` 的条件更新，DB 的值有明确的正确性论证。
2. Redis 的预扣是**性能层**，它的设计目标就是"尽快给出一个大概率正确的答案"。
   Redis 宕机重启、主从切换、key 过期、Lua 脚本被误改，都可能让它偏离。
3. 以 Redis 为准会**放大**错误：如果 Redis 因为回补泄漏而偏大，
   以它为准回写 DB 就等于凭空造票，直接超卖。
4. 反过来，以 DB 为准回写 Redis 的最坏结果是"少卖了票"，可以通过人工核对后补偿；
   **少卖可以补救，超卖不能。**

**对账频率**：日切后跑全量 + 活动进行中按较短周期跑重点库存行。
具体周期**待压测**确定，取决于库存行数量和单次比对耗时。

### 7.5 发现异常后的处理顺序（Runbook）

1. **止血**：把相关活动 `status` 置为已下线，或在 Redis 侧把该库存 key 置 0，先停止新的售卖。
2. **定性**：跑 7.1(2) 判断是"真的超卖"还是"账对不上但没超卖"。
3. **归因**：查 7.3（在途消息）→ 7.2（重复订单）→ 7.4 第三步（孤儿流水），逐个排除。
4. **修复**：以 DB 为真值修正 Redis；若确认已超卖，进入人工介入流程（这里不写业务补偿细节，
   属于产品与客服流程）。
5. **复盘**：把本次差异的数据留档，作为下一次对账的基线。

**注意**：第 3 步一定要按顺序，**先排除在途消息**。
跳过这一步会得到大量的假阳性，最终导致所有人不再相信对账结果。

---

## 8. 明确留下的技术债与未决问题

写下这些不是自我批评，而是让接手的人知道"哪里可以踩，哪里不能踩"。

| 项 | 现状 | 风险 | 待办 |
| --- | --- | --- | --- |
| `id_card_hash` 使用无盐 SHA-256 | 身份证号空间可枚举，理论上可被反查 | 中 | 改为加盐哈希或 HMAC |
| `t_train` 只支持直达 | 无法表达经停站与区间余票 | 中（功能边界） | 需要时增加 `t_train_stop` 表 |
| 未实现分片 | 单表数据量增长后有上限 | 低（教学项目） | 表结构已按 `train_id` 预留 |
| `t_passenger.idx_user` 冗余 | 多余索引拖慢写入 | 低 | 建表时删除 |
| 部分索引（`idx_create_time` 等）理由薄弱 | 无查询则纯成本 | 低 | 按真实查询清单裁剪 |
| Redis 去重集合内存 | 随参与用户数增长 | 中 | 设 TTL；评估 Bitmap 方案 |
| `CHECK` 约束是否生效 | 依赖 MySQL 8.0.16+ | 中 | **待验证**本机版本行为 |
| 对账周期与阈值 | 未定 | 低 | **待压测** |
| 各索引的实际收益 | 未实测 | 中 | **待压测**（用生产量级数据） |
| 降序索引 / 混合方向 ORDER BY | 未验证 | 低 | **待验证** |

**本文档中没有出现任何性能数字，是有意为之。**
所有"快多少、省多少"的结论都必须来自压测，而压测需要真实的数据分布，
否则数字只是自我安慰。**在面试里，说"这里我做了取舍，具体收益需要压测验证"，
比编一个数字可信得多。**

---

## 9. 参考文档

- [架构设计规格（唯一事实来源）](../architecture/design-spec.json)
- 项目说明：[HELP.md](../../HELP.md)

以下文档为本文档的上下游，尚未编写，此处仅列出规划位置，不是有效链接：

- `docs/architecture/cache-design.md`：Redis key 设计、预热与过期策略
- `docs/architecture/seckill-flow.md`：抢票全链路时序与降级方案
- `docs/database/validation-runbook.md`：第 7 节校验 SQL 的调度配置与告警阈值
