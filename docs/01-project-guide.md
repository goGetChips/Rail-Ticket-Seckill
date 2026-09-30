# 项目阅读指南

**读者**：第一次接触这个仓库的人。
**目标**：读完这一篇，你能说清这个项目在做什么、架构长什么样、代码在哪、以及接下来该读哪一篇。
**前提**：不需要读过其它文档。**当前开发进度不在这篇里** —— 见 [status/development-status.md](status/development-status.md)。

---

## 一、这个项目是什么

一个用于**深入理解分布式高并发**的铁路购票 / 秒杀教学项目。

它要回答的不是"怎么实现一个售票系统"，而是：

> **在有限库存 + 瞬时高并发 + 分布式多服务的条件下，如何保证"不超卖、不丢单、不重复下单"，并且能说清每一处取舍的理由。**

功能是载体，**并发正确性**才是目标。这决定了优先级：

| 优先级 | 目标 | 说明 |
| --- | --- | --- |
| P0 | 正确性 | 不超卖、不重复下单。**不能被性能妥协** |
| P1 | 可解释性 | 每处设计都能回答"为什么"和"为什么不用 X" |
| P2 | 性能 | 抗突发流量、降低 DB 压力 |
| P3 | 功能完整度 | 够用即可 |

**为什么正确性优先于性能**：秒杀场景下超卖是资损且不可逆（票已经卖给两个人了）；性能不足只是体验问题，可以用限流把超额流量挡在门外。**用限流把系统保护成"慢但对"，好过"快但错"。**

---

## 二、核心矛盾：一句话

> **「判断」与「扣减」必须是原子的。**

```
线程 A: 读库存 = 1 ──┐
线程 B: 读库存 = 1 ──┤  两个线程都认为"还有票"
线程 A: 扣减 → 0    ──┤
线程 B: 扣减 → -1   ──┘  超卖
```

这个问题在单线程下不存在，在**任何**并发场景下都存在。整个项目就是它在三种环境下的三种形态：

| 环境 | 原子性由谁提供 | 对应阶段 |
| --- | --- | --- |
| 单机 MySQL | InnoDB 行锁 + 条件 UPDATE（CAS） | 阶段 5 |
| Redis | Lua 脚本的服务端单线程执行 | 阶段 7 |
| 分布式（多服务） | 上述两者 + 幂等 + 对账修复 | 阶段 8~9 |

**这是这个项目的教学主线。** 其他所有内容（微服务拆分、缓存、MQ）都是围绕它展开的。

---

## 三、整体架构

### 3.1 目标形态（阶段 8 拆分后 · 方案 C）

```
Client
  │
  ▼
rail-gateway :8080          JWT 校验 · 限流 · 路由
  │
  ▼
rail-order-service :8084 ──▶ Redis（Lua 原子预扣 + 用户去重）
  │                              │
  │ 立即返回「排队中」             │ 扣减成功
  │                              ▼
  │                            MQ（异步削峰）
  │                              │
  │              ┌───────────────┴───────────────┐
  │              ▼                               ▼
  │      order 消费者                       inventory 消费者
  │      写 t_order                  写 t_seat_inventory
  │              │                               │
  └──────▶ rail-train-service :8082 ◀────────────┘
                  ▲                （余票权威数据）
                  │
           rail-user-service :8081
```

> ⚠️ **这是目标形态，不是现状。** 当前仓库只有 `rail-train-service` 一个模块，MQ 与微服务拆分分别在阶段 9 / 阶段 8。现状见 [开发状态](status/development-status.md)。

### 3.2 关键设计：Redis 是共享预扣层，MySQL 才有 owner

| 角色 | 由谁承担 | 为什么 |
| --- | --- | --- |
| **Redis 的写入者**（秒杀扣减） | `order-service` **直连 Redis** | 热路径，绝不能包一层 HTTP |
| **Redis 的预热者 / 对账者** | `inventory-service` | 它是 `t_seat_inventory` 的 owner |
| **权威数据（source of truth）** | MySQL 的 `t_seat_inventory` | Redis 只是前置闸门 + 加速层，可丢可重建 |

**由此引出的硬性约束**：秒杀热路径上，`order-service` **禁止**通过 OpenFeign 同步调用 `rail-inventory-service` 来扣库存。多一跳 HTTP RPC 就要付出连接池占用、序列化、超时、线程阻塞的全部代价，会把异步化省下的时间原样还回去。**秒杀链路里唯一允许的同步远程调用是 Redis。**

理由与备选对比见 [02-architecture.md](02-architecture.md) 与 [decisions/ADR-003](decisions/ADR-003-service-granularity.md)。

---

## 四、各服务职责

| 服务 | 端口 | 职责 | 数据库 | 拥有的表 |
| --- | --- | --- | --- | --- |
| `rail-gateway` | 8080 | 统一入口、路由、JWT 校验、限流 | 无 | 无 |
| `rail-user-service` | 8081 | 注册、登录、签发 JWT、用户查询 | `rail_user` | `t_user` |
| `rail-train-service` | 8082 | 车次、站点、经停站、票价 | `rail_train` | `t_train`、`t_station`、`t_train_station` |
| `rail-inventory-service` | 8083 | 库存权威数据、预热、对账、余票查询 | `rail_inventory` | `t_seat_inventory`、`t_stock_flow` |
| `rail-order-service` | 8084 | 下单、秒杀、订单状态机、模拟支付 | `rail_order` | `t_order`、`t_order_item`、`t_local_message` |

**端口分配**是终态：8080 留给网关，8081~8084 按服务顺序分配。现在虽然只有 train 一个服务在跑，端口也按终态配置，**这样阶段 8 拆分时不需要回来改配置**。

> 🔴 **⚠️ 「拥有的表」这一列是终态，不是现状。** 阶段 4~5 为了把购票链路走通，
> `rail-train-service` **跨库读写了 `rail_inventory.t_seat_inventory`**：
>
> | 阶段 | 越界的方式 |
> | --- | --- |
> | 4 | **跨库读**（余票查询） |
> | **5** | **跨库"读 + 事务性写"**：`SeatInventoryMapper.deductStock` 会 `UPDATE` 它，而且是下单事务的一部分 |
>
> 阶段 5 还多越了一层：下单事务同时写 `rail_inventory`（扣减 + 流水）和
> `rail_order`（订单 + 明细），**而这两张表分别属于 `rail-inventory-service` 和
> `rail-order-service`**。所以现在这个"单体过渡期"里，
> train-service 实际在代管**三个服务**的表。
>
> 🟢 **但它在数据库层面是原子且合法的** —— 三个 schema 在**同一个 MySQL 实例**上，
> 一个本地事务能覆盖它们（InnoDB 事务是服务器级的，不是库级的，
> 见 [06-database.md §5.5](06-database.md)）。**"代码归属错了"和"事务会失效"
> 是两件事，别混**（详见 [技术债 #14 / #16](status/development-status.md)）。
>
> 阶段 8 的迁移：扣减 + 流水 → `rail-inventory-service`；订单 + 明细 → `rail-order-service`。
> 接口路径已经提前定成 `/api/inventory/**` 和 `/api/order/**`，所以迁移时
> **只需加网关路由，客户端无感**；但"代码在 train-service 里"这件事，
> 读 URL 的人是看不出来的。

**为什么是 5 个而不是 4 个或 6 个**：分界线画在「**数据所有权不同**」和「**扩展需求不同**」的地方。`rail-inventory-service` 独立出来的核心理由是它可以和查票服务施加**完全相反的降级策略**——查票能降级（返回缓存余票 + 提示延迟），库存绝对不能降级（返回假成功 = 超卖）。判断标准是「**假的响应会不会造成资损**」。如果两者在同一个服务里，这个判断就无法落地。

再往下拆（比如拆出独立的余票查询服务）**不拆**：余票查询与库存扣减操作同一份数据，拆开后立刻产生读写模型同步延迟，导致"显示有票但下单失败"——这个不一致对用户是纯粹的困扰，换不来收益。

---

## 五、目录结构（当前真实结构）

```
Rail-Ticket-Seckill/
├── pom.xml                      # 父 POM + 聚合 POM（packaging=pom）
├── mvnw / mvnw.cmd              # Maven Wrapper，本机没有独立 Maven CLI
├── README.md                    # 第一入口 + 当前开发状态
├── docs/                        # 全部文档（见第九节）
├── rail-train-service/          # 车次服务（当前唯一有代码的模块）
│   └── src/
│       ├── main/java/com/railseckill/train/
│       │   ├── RailTrainApplication.java   # 启动类 + @MapperScan
│       │   ├── config/MybatisPlusConfig.java
│       │   ├── controller/                 # 4 个：Station / Train / Inventory / Order
│       │   ├── service/                    # 4 个：Station / Train / Inventory / Order
│       │   ├── mapper/                     # 6 个：Station / Train / SeatInventory /
│       │   │                               #        Order / OrderItem / StockFlow
│       │   ├── entity/                     # 6 个：Station / Train / SeatInventory /
│       │   │                               #        Order / OrderItem / StockFlow
│       │   ├── enums/                      # 3 个：SeatType / OrderStatus / StockChangeType
│       │   ├── dto/                        # 出参 6 个 + 入参 1 个 + ApiError
│       │   └── exception/                  # ApiExceptionHandler + BusinessException
│       │                                   #   + 4 个业务异常子类
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── mapper/                     # 3 个 XML：Train / Order / SeatInventory
│       └── test/java/com/railseckill/train/order/
│           ├── OrderServiceIT.java          # 单线程语义（HTTP 层）
│           └── OrderConcurrencyTest.java    # 并发证明（直接调 Service）
├── sql/                         # 建库 / 建表 / 种子数据 / 验证脚本
└── scripts/
    ├── env/                     # 环境变量、Redis 与 MySQL 验证脚本、Lua 脚本
    └── perf/                    # JMeter：stage4-smoke.jmx / stage5-order.jmx
```

**读代码的顺序**（每个包先看 `Order*`，它是最新的、也是唯一有写入的）：

1. `enums/` —— 三张对照表，**禁止裸写数字**这条规则的落点
2. `entity/` + `mapper/` —— 注意 `@TableName` 上的**库名前缀**（跨库的依据）
3. `OrderService` —— **本项目唯一带 `@Transactional` 的类**，注释最长，值得逐段读
4. `exception/` —— 五个异常类 + 一个 handler，409 的来源
5. `test/` —— 两类的分工：`OrderServiceIT` 验语义，`OrderConcurrencyTest` 验并发

> **根 POM 的 `<modules>` 只登记已经真的有代码的模块。** 一个没有代码的模块不是模块，是占位符——它不证明任何事，却会带来真实的成本。"以后再加一个模块"的成本是在 `<modules>` 里加 3 行。

> 🔴 **`test/` 目录是阶段 5 才出现的，而且这两个测试类都不许加 `@Transactional`。**
> 理由写在 `OrderConcurrencyTest` 的类注释里（四条，任一条都足以让测试失效）。
> **这是"读代码看不出来、抄模板会踩"的一类约束** —— 单线程测试的常规写法在并发测试里是错的。

---

## 六、核心业务流程

一句话链路：**查票 → 抢票 → 排队 → 落单 → 支付 → 超时关单回补库存**。

```
① 用户查余票        →  train-service（带缓存）
② 提交购票请求      →  order-service
③ Redis Lua 原子预扣 →  判断库存 + 扣减 + 用户去重，一次完成
④ 写订单            →  同步写（方案 B）或经 MQ 异步写（方案 C）
⑤ 支付 / 超时       →  状态机 CAS 流转，超时触发库存回补
```

**订单状态机**（当前 DDL 的取值）：`0=待支付 → 1=已支付`；`0=待支付 → 2=已取消`（触发库存回补）。

**状态流转一律用条件 UPDATE 做 CAS**，不用分布式锁：

```sql
UPDATE t_order SET status = 1 WHERE order_no = ? AND status = 0;
-- 受影响 1 行 = 本次生效                        → 200，idempotent=false
-- 受影响 0 行 = 订单当前状态不是"待支付"         → **必须再读一次状态才能决定回什么**
```

🔴 **"0 行就直接返回成功"是错的**（这句话在本仓库的早期文档和 DDL 注释里都出现过，已修正）：

| 订单当前状态 | 0 行的含义 | 正确响应 |
| --- | --- | --- |
| `1` 已支付 | **已经是我想要的状态** | **200**（真幂等，`idempotent=true`） |
| `2` 已取消 | **永远不可能变成我想要的状态** | **409** —— 票已回补，可能已卖给别人 |

⭐ **判据**：「已经是我想要的状态」可以报成功；「永远不可能变成我想要的状态」必须报错。
完整论证见 [03-business-flow.md §二](03-business-flow.md)。

完整流程、时序与验收标准见 [03-business-flow.md](03-business-flow.md)。

---

## 七、秒杀核心流程

秒杀链路的设计目标是：**请求线程只做「Redis 一次往返 + 一次消息投递」，与数据库完全解耦。**

```
1. Gateway    校验 JWT → 解析 userId → 令牌桶限流
2. order-service 执行 Redis Lua 原子扣减
     >= 0 扣减成功 / -1 售罄 / -2 用户重复 / -3 未预热（系统故障）
3. 扣减成功 → 写本地消息表 PENDING → 发 MQ → 立即返回「排队中」
4. 消费者幂等落库 → 写订单 + 落库存账
5. 客户端轮询秒杀结果（存在 Redis，不查 DB）
```

**为什么返回"排队中"而不是"成功"**：请求线程没有写 DB，它**没有资格承诺订单已创建**。返回"排队中"是诚实的；返回"成功"而订单后来创建失败，是欺骗用户。**这不是技术细节，是正确性边界。**

秒杀的完整设计（Redis key、Lua 脚本、缓存三件套、幂等三层、九条异常路径）见 [05-seckill.md](05-seckill.md)。

---

## 八、使用到的主要技术

| 分类 | 组件 | 状态 |
| --- | --- | --- |
| 语言 / 构建 | Java 17、Maven Wrapper | 🟢 已实测 |
| 框架 | Spring Boot 3.5.9 | 🟢 已实测（现有骨架） |
| ORM | MyBatis-Plus 3.5.17 | 🟢 已实测（已跑通查询） |
| 数据库 | MySQL 8.4.8 | 🟢 已实测 |
| 缓存 | Redis 8.10.1（`zkteco-home/redis-windows` 第三方移植版） | 🟢 已实测 |
| 注册中心 / 配置 | Nacos 3.2.3 standalone | 🟢 已实测启动（端口 8888 / 控制台 8889） |
| 微服务 | Spring Cloud 2025.0.x + Spring Cloud Alibaba 2025.0.0.0 | 🟡 依赖解析已实测，尚未接入 |
| 限流熔断 | Sentinel 1.8.9 | 🟡 由 SCA 固定引入，尚未接入 |
| 消息队列 | RocketMQ 5.5.1 | 🟢 已装并跑通，🔴 选型未拍板，推迟到阶段 9 |
| 测试 | JUnit 5 + AssertJ（`spring-boot-starter-test`） | 🟡 **阶段 5 才开始用**：`src/test` 目录与 2 个测试类已写好，**尚未运行** |
| 压测 | JMeter 5.6.3 | 🟢 阶段 4 冒烟脚本 **6 请求 / Err 0** 已实测；🟡 该脚本阶段 5 扩到 8 个请求、以及新增的并发脚本，**都尚未运行** |
| 容器化 | Docker | ❌ **公司电脑无法安装，已放弃此路线** |

版本矩阵、环境搭建方式、端口规划、以及**本机的字符集 / 时区 / DLP 陷阱**见 [04-technology.md](04-technology.md)。

---

## 九、文档地图

```
想了解……                          读这篇
─────────────────────────────────────────────────────────────
新人快速理解整个项目               01-project-guide.md（本篇）
项目现在做到哪了                  status/development-status.md
系统由哪些服务组成、为什么这么拆     02-architecture.md
一次购票/秒杀怎么走                03-business-flow.md
用了什么技术、版本、怎么装          04-technology.md
秒杀为什么快、为什么不会超卖        05-seckill.md
表怎么设计、索引为什么这么建        06-database.md
高并发下哪些地方会出问题            07-risks.md
某个决策为什么这么做                decisions/ADR-003-service-granularity.md（服务拆分）
                                   decisions/ADR-004-stock-deduction-mysql-cas.md（库存扣减）
接口会返回哪些状态码与错误体        api/error-codes.md
踩过哪些真实的坑（含原始日志）      troubleshooting/README.md
环境变量与验证脚本怎么用            ../scripts/env/README.md
并发跑完之后怎么对账                ../sql/12_verify_stage5_order.sql
```

⭐ **`api/error-codes.md` 和 `sql/12_verify_stage5_order.sql` 是阶段 5 的产物，
它们回答的是阶段 0~4 完全没有的问题**：

- 阶段 0~4 全只读，"接口返回 200"就等于"数据没问题"，**不需要对账**。
- 阶段 5 起有**三种失败不会让接口报错**：超卖、**少卖**、重复。
  它们只能靠对账 SQL 发现 —— 所以"测试全绿"和"账对得上"是**两件事**。

---

## 十、文档约定

**1. 证据等级标记（全文使用，请严格区分）**

| 标记 | 含义 |
| --- | --- |
| 🟢 **已实测** | 在本机实际执行命令得到的结论，可直接依赖 |
| 🟡 **已就位，未验证** | 两种具体情况：① 检索得到、附有出处但**尚未在本机验证**的结论（"调研结论"）；② **代码/脚本/测试已写好，但从未运行过**。两者共同的判据是"**不能当成事实依赖**" |
| 🔵 **架构判断** | 设计意见，含取舍理由，可以质疑 |
| 🔴 **待拍板 / 待处理** | 必须开发者本人决定或必须修的事项 |
| ⏳ **计划中** | 还没开始做，或明确排在后面的阶段 |

⭐ **🟡 的第一种用法在阶段 5 被扩展过，理由写在下面**（这不是"标记变得含混"，
恰恰相反，是承认了同一件事的两种来源）：

> 阶段 0~4 里 🟡 只表示"**别人这么说，我还没试**"。
> 阶段 5 出现了第三种状态：**"我自己写完了，但我还没试"**。
> 后者比前者更接近事实，但**依然不是证据** ——
> 而且它更容易被误读成"已经能用了"。
>
> **所以判据统一成一句话：🟡 的东西不能当成事实依赖。**
> 阶段 5 的代码、测试、JMeter 脚本、SQL 校验**全部是 🟡**，
> 因为写它们的时候本机 MySQL 起不来，**一次都没跑过**。
>
> 📌 这与第 4 条（"状态必须与实际代码一致"）配合使用：
> 🟡 要求把**"已实现"和"已验证"分开写**，而不是笼统写"已完成"。

**2. 禁止编造数字。** 任何性能数字、压测结果、版本号、API 细节，没实测过就写「**待压测**」或「**待验证**」，并写清验证方法。这条没有例外。本仓库**不包含任何未经实测的性能数字**。

**3. 用工程语言，不用营销词。** 不写「高性能」，写「扣减在 Redis 单命令内完成，不产生数据库行锁竞争」。

**4. 状态必须与实际代码一致。** 文档描述的功能必须能对应到当前代码、配置或 SQL。尚未实现的内容要明确标注为「计划中」，不得写成已完成。

**5. 决策用 ADR 格式**（背景 / 决策 / 理由 / 备选对比 / 代价 / 什么条件下失效），放在 [decisions/](decisions/)。
**6. 故障复盘必须有真实日志**，放在 [troubleshooting/](troubleshooting/)；没有日志支撑的"故障"不允许写进去。

---

相关文档：

- [开发状态](status/development-status.md)
- [系统架构与拆分](02-architecture.md)
- [核心业务流程](03-business-flow.md)
- [技术栈与环境](04-technology.md)
- [秒杀与库存](05-seckill.md)
- [数据库设计](06-database.md)
- [高并发风险清单](07-risks.md)
