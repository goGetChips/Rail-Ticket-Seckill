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

> 🔴 **⚠️ 「拥有的表」这一列是终态，不是现状。** 阶段 4 为了把查票链路走通，
> `rail-train-service` **跨库读了 `rail_inventory.t_seat_inventory`**（余票查询），
> 破坏了"一表一主"的边界。这是**单体的过渡期耦合**，阶段 8 会把这段代码整体迁往
> `rail-inventory-service`。
>
> 接口路径已经提前定成 `/api/inventory/seats`，所以迁移时**只需加一条网关路由，客户端无感**；
> 但代码在 train-service 里这件事，读代码的人是看不出来的——详见
> [开发状态 §6 技术债 #14](status/development-status.md)。

**为什么是 5 个而不是 4 个或 6 个**：分界线画在「**数据所有权不同**」和「**扩展需求不同**」的地方。`rail-inventory-service` 独立出来的核心理由是它可以和查票服务施加**完全相反的降级策略**——查票能降级（返回缓存余票 + 提示延迟），库存绝对不能降级（返回假成功 = 超卖）。判断标准是「**假的响应会不会造成资损**」。如果两者在同一个服务里，这个判断就无法落地。

再往下拆（比如拆出独立的余票查询服务）**不拆**：余票查询与库存扣减操作同一份数据，拆开后立刻产生读写模型同步延迟，导致"显示有票但下单失败"——这个不一致对用户是纯粹的困扰，换不来收益。

---

## 五、目录结构（当前真实结构）

```
Rail-Ticket-Seckill/
├── pom.xml                      # 父 POM + 聚合 POM（packaging=pom）
├── mvnw / mvnw.cmd              # Maven Wrapper，本机没有独立 Maven CLI
├── README.md                    # 第一入口 + 当前开发状态
├── docs/                        # 全部文档（见第六节）
├── rail-train-service/          # 车次服务（当前唯一有代码的模块）
│   └── src/main/
│       ├── java/com/railseckill/train/
│       │   ├── RailTrainApplication.java   # 启动类 + @MapperScan
│       │   ├── controller/StationController.java
│       │   ├── service/StationService.java
│       │   ├── mapper/StationMapper.java
│       │   └── entity/Station.java
│       └── resources/application.yml
├── sql/                         # 建库 / 建表 / 种子数据 / 验证脚本
└── scripts/env/                 # 环境变量、Redis 与 MySQL 验证脚本、Lua 脚本
```

> **根 POM 的 `<modules>` 只登记已经真的有代码的模块。** 一个没有代码的模块不是模块，是占位符——它不证明任何事，却会带来真实的成本。"以后再加一个模块"的成本是在 `<modules>` 里加 3 行。

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
-- 受影响 1 行 = 本次生效；0 行 = 已被处理过，直接返回成功（幂等）
```

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
| 压测 | JMeter 5.6.3 | 🟢 已实测发请求（阶段 4 冒烟脚本 6 请求 / 0 错误） |
| 容器化 | Docker | ❌ **公司电脑无法安装，已放弃此路线** |

版本矩阵、环境搭建方式、端口规划、以及**本机的字符集 / 时区 / DLP 陷阱**见 [04-technology.md](04-technology.md)。

---

## 九、文档地图

```
想了解……                          读这篇
─────────────────────────────────────────────────────────────
新人快速理解整个项目               01-project-guide.md（本篇）
项目现在做到哪了                    status/development-status.md
系统由哪些服务组成、为什么这么拆    02-architecture.md
一次购票/秒杀怎么走                 03-business-flow.md
用了什么技术、版本、怎么装          04-technology.md
秒杀为什么快、为什么不会超卖        05-seckill.md
表怎么设计、索引为什么这么建        06-database.md
高并发下哪些地方会出问题            07-risks.md
某个决策为什么这么做                decisions/ADR-003-service-granularity.md
踩过哪些真实的坑（含原始日志）      troubleshooting/README.md
环境变量与验证脚本怎么用            ../scripts/env/README.md
```

---

## 十、文档约定

**1. 证据等级标记（全文使用，请严格区分）**

| 标记 | 含义 |
| --- | --- |
| 🟢 **已实测** | 在本机实际执行命令得到的结论，可直接依赖 |
| 🟡 **调研结论** | 检索得到、附有出处，但**尚未在本机验证**，落地前必须实证 |
| 🔵 **架构判断** | 设计意见，含取舍理由，可以质疑 |
| 🔴 **待拍板** | 必须开发者本人决定的事项 |

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
