# 开发状态

**更新日期**：2026-09-29
**依据**：以当前代码、配置、SQL 为准，**不根据旧文档推测**。
**项目介绍与阅读入口**见 [../01-project-guide.md](../01-project-guide.md)；本文件只回答"做到哪了"。

---

## 一、一句话

**阶段 0~3 已完成。仓库里有可以真正跑起来、并真的读到了数据库的服务。**

当前可运行的只有 `rail-train-service` 一个模块，对外提供 **2 个车站查询接口**。购票、库存、秒杀、微服务拆分、MQ 全部尚未开始。

---

## 二、阶段进度

| 阶段 | 名称 | 状态 | 完成判据 / 证据 |
| --- | --- | --- | --- |
| 0 | 需求与架构设计 | ✅ **已完成** | 6 项决策全部拍板；依赖版本用真实 `mvn dependency:tree` 解析零冲突 |
| 1 | 环境搭建 | ✅ **已完成** | JDK 17 / Maven 3.9.16 / MySQL 8.4.8 / Redis 8.10.1 / Nacos 3.2.3 全部就绪，**每项都有实测验证证据** |
| 2 | 数据库设计 | ✅ **已完成** | 4 库 9 表建成；**约束行为用违规数据实测拒绝**（[06-database.md §6](../06-database.md)） |
| 3 | 项目初始化 | ✅ **已完成** | 多模块骨架 + `rail-train-service` 启动后 `curl` 到 `t_station` 的**真实数据**（14 行），404 分支也验证过 |
| **4** | **核心业务（单体）** | ⏭️ **下一步** | 查询接口全部走通 |
| 5 | 购票 + MySQL 库存（方案 A） | ⬜ 未开始 | 并发测试证明不超卖 |
| 6 | 压测 v1 | ⬜ 未开始 | 有真实瓶颈数据 |
| 7 | 引入 Redis（方案 B） | ⬜ 未开始 | 与阶段 6 对比有量化提升 |
| 8 | 微服务拆分（5 服务） | ⬜ 未开始 | 跨服务调用通，服务下线可感知 |
| 9 | 引入 MQ（方案 C） | ⬜ 未开始 | 先定 MQ 选型，再逐条实测异常路径 |
| 10 | 压测 v2 + 优化 | ⬜ 未开始 | 完整的优化前后对比 |
| 11 | 复盘 + 面试化 | ⬜ 未开始 | 能不看文档讲 30 分钟 |

---

## 三、已完成的功能（可直接验证）

| 功能 | 位置 | 验证方式 |
| --- | --- | --- |
| 列出全部车站（按电报码升序） | `GET /api/train/stations` | 返回 14 条车站 |
| 按电报码查单个车站 | `GET /api/train/stations/{code}` | `VNP` → 200 + 北京南；`NOPE` → 404 |

**实测输出**（2026-09-17）：

```console
$ curl -sS http://127.0.0.1:8082/api/train/stations/VNP
{"id":1,"stationCode":"VNP","stationName":"北京南","cityName":"北京","createTime":"2026-09-17T12:12:57"}

$ curl -sS -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1:8082/api/train/stations/NOPE
HTTP 404
```

### 基础设施侧已完成（有实测证据，但尚未被业务代码使用）

| 项 | 证据 |
| --- | --- |
| 4 个 schema + 9 张表 + 应用账号 `rail` | [sql/00_init.sql](../../sql/00_init.sql) ~ [04](../../sql/04_rail_order.sql) 执行成功 |
| 唯一索引 / CHECK 约束 / 条件 UPDATE 的 CAS 行为 | [sql/99_verify.sql](../../sql/99_verify.sql)，违规数据**全部被数据库拒绝** |
| Redis 原子扣减能力（含 Lua 分支） | [scripts/env/verify-redis.sh](../../scripts/env/verify-redis.sh)：100 张票 vs 1000 并发 → 余票恰好 0、恰好 100 人成功 |
| Lua 扣减脚本设计稿 | [scripts/env/lua/stock_deduct.lua](../../scripts/env/lua/stock_deduct.lua) —— **尚未接入 Java 代码** |
| Nacos 3.2.3 standalone 可启动 | 旧端口 `8888` 的控制台曾返回 200；端口已改为 **主 8888 / 控制台 8889 / gRPC 9888,9889**（见 [04-technology §3.1](../04-technology.md)），**改后控制台未复验** |
| RocketMQ 5.5.1 可启动 | NameServer + Broker 曾成功运行（`~/logs/rocketmqlogs/broker.log` 有连续注册与心跳日志）。**选型仍未拍板** |
| JMeter 5.6.3 可运行 | `jmeter.bat --version` → 输出 5.6.3（JDK 17） |

> ⚠️ **上面这些能力"验证过"不等于"用上了"**。Redis 和 Nacos 现在都没有出现在任何 Java 代码或 POM 依赖里。

---

## 四、部分完成

| 项 | 已完成的部分 | 还缺什么 |
| --- | --- | --- |
| `t_train` / `t_train_station` 的数据 | 种子数据已灌入（1 个车次 + 经停站） | **没有任何接口读它们** |
| MyBatis-Plus | 基础查询已跑通 | **分页插件未验证**（`mybatis-plus-jsqlparser` 已引入，但还没有分页查询调用它） |
| 环境验证清单 | V1~V9、V11、V12、V13 完成 | V7（Nacos 控制台改端口后复验）、V10b（分页运行时）、V11b（JMeter 实发请求）、V14（RocketMQ 复验）、V15（RocketMQ 内存） |
| MQ 相关的表结构 | `t_local_message` 已建好 | **阶段 9 前不会有任何代码写它** |
| RocketMQ 5.5.1 | 已安装、官方脚本 JDK 17 兼容、历史上启动成功 | **选型未拍板**；默认 4 GB 堆对本机内存压力大（[04-technology §4.2](../04-technology.md)） |
| JMeter 5.6.3 | 已安装，`--version` 实测通过 | 还没有用它发过任何请求 |

---

## 五、尚未实现

**功能**

- 用户注册 / 登录 / JWT 签发（`rail-user-service` 未建）
- 车次查询、经停站查询、按出发/到达站查车次
- 余票查询
- 购票下单、订单状态机、模拟支付
- **秒杀（项目的核心场景，尚未开始）**
- 库存预热、对账任务、超时关单回补

**模块**

`rail-gateway`、`rail-user-service`、`rail-inventory-service`、`rail-order-service` 四个模块**都还不存在**。根 POM 的 `<modules>` 只登记了 `rail-train-service`。

**基础设施**

Sentinel Dashboard（未安装）、链路追踪（无）。

JMeter 5.6.3 与 RocketMQ 5.5.1 **已安装**，但**都还没有被用起来**——JMeter 没发过请求，RocketMQ 没有一行代码连它，选型也还没拍板。

---

## 六、已知问题与技术债

| # | 项 | 说明 | 何时处理 |
| --- | --- | --- | --- |
| 1 | **没有统一的响应体包装** | 接口直接返回原始数据 + HTTP 状态码，**这是刻意不引入的**（秒杀场景下成功/失败必须和状态码对上；没有错误码规范时提前引入只会长出一堆随手写的 code） | 阶段 4 出现多种错误场景时 |
| 2 | **mapper 层 SQL 日志开在 debug** | `application.yml` 里 `com.railseckill.train.mapper: debug`。**压测前必须关掉**，否则控制台 IO 自己会变成瓶颈，测出的数字全是假的 | **阶段 6 压测前** |
| 3 | **依赖刻意未加** | `validation`（阶段 4）、`actuator`（阶段 8）、`data-redis`（阶段 7）、Nacos/OpenFeign（阶段 8）、Lombok（暂不加） | 按阶段 |
| 4 | **配置里有本地密码默认值** | `application.yml` 里 `${DB_PASSWORD:rail123456}`。真实项目应完全由密钥管理下发 | 生产环境 |
| 5 | **Spring Boot 版本落后于已验证的版本** | 当前 POM 是 3.5.9，阶段 0 实测建议 3.5.16 | 阶段 4 视情况升级 |
| 6 | **Boot 3.5.x / Spring Cloud 2025.0.x 已 EOL** | 2026-06-30 EOL。教学项目不影响使用，但面试时应主动提及 | 不处理 |
| 7 | **无链路追踪** | 跨服务问题定位靠日志 grep | 阶段 10 可选 |
| 8 | **`X-User-Id` 透传存在伪造风险** | 阶段 8 引入网关时必须同时处理（剥离客户端传入的值 + 内网隔离） | **阶段 8 必须做** |
| 9 | **本机 DLP 会加密无扩展名文件** | 已加 pre-commit 守卫脚本；无扩展名文件必须用 bash 生成 | 持续 |
| 10 | **已删除未验证的 AI 草稿** | `docs/_drafts-unverified/`（约 11k 行）已于本次文档整理中删除。其内容为一次性生成、内部矛盾、且与真实代码/DDL 不符（详见下方"处置说明"） | 已处理 |
| 11 | **`rocketmq_start.bat` 会杀掉所有 java 进程** | 脚本里是 `taskkill /f /im java.exe`，会连带干掉 **Nacos 服务端**和**正在运行的 Spring Boot 应用**；压测中执行会直接毁掉压测数据 | **下次用 RocketMQ 前改成按端口精确杀** |
| 12 | **RocketMQ 默认 4 GB 堆与本机内存不匹配** | NameServer 2 GB + Broker 2 GB（`AlwaysPreTouch` 启动即真实提交），而本机空闲内存曾低至 1.5 GB | 阶段 9 前调小 |
| 13 | **无扩展名文件之外，`jmeter.bat` 只认 PATH 上的 `java`** | 不读 `JAVA_HOME`。报 "Not able to find Java executable" 时应先 `where java` 而不是改 `JAVA_HOME` | 已记录，无需处理 |

### 处置说明：`docs/_drafts-unverified/`

该目录下 18 份文档**全部删除**，原因是它们描述的是一个**不存在的系统**，与真实代码无对应关系：

| 草稿 | 与事实的偏差 |
| --- | --- |
| `database/schema-design.md` | 描述了 `t_train_stock`、`t_passenger`、`t_pay_record`、`t_seckill_activity`、`sold_qty`/`total_qty` 等**真实 DDL 中不存在**的表和字段 |
| `api/api-spec.md` | 接口路径为 `/api/v1/...`，与真实的 `/api/train/stations` **不一致** |
| `adr/ADR-001-mq-selection.md` | 状态标为"已接受"、结论选 **RabbitMQ**；真实决策是**推迟到阶段 9、倾向 RocketMQ** |
| `decisions/ADR-003`、`service-split.md` 等 | 存在**多套互不兼容的服务拆分方案** |
| `troubleshooting/README.md` | 827 行的"T-01~T-10 预判清单"，**全部是未发生的问题**，与本项目"故障复盘必须有真实日志"的规则冲突 |

**回收的内容**（已并入正式文档）：R4 少卖 / R5 回滚误伤已支付 / R8 支付回调伪造 / R15 开售尖峰与预热 / R16 脚本刷票 → [../07-risks.md](../07-risks.md)。

**如需找回原始文件**（它们已在 git 历史中）：

```bash
git show 0bb786c:docs/_drafts-unverified/database/schema-design.md
git checkout 0bb786c -- docs/_drafts-unverified/      # 恢复整个目录
```

---

## 七、待拍板 / 待验证事项

| # | 事项 | 当前状态 | 何时必须定 |
| --- | --- | --- | --- |
| 1 | MQ 选型（RocketMQ vs RabbitMQ） | 🔴 **仍未拍板**，但倾向 RocketMQ 的**前置条件已验证通过**（5.5.1 已装、JDK 17 兼容、历史上启动成功）。剩余变量是内存压力 | 阶段 9 |
| 2 | 是否启用 Sentinel | 🔴 未定（依赖已由 SCA 固定引入，但未接入） | 阶段 8 |
| 3 | Spring Boot 是否升级 3.5.9 → 3.5.16 | 🔴 未定 | 阶段 4 |
| 4 | Redis 第三方移植版的剩余风险 | 🟡 基础命令与 Lua 已实测通过；`atomicvar_api=msvc-zkatomic` 非官方实现，并发行为已单独验证 | 持续 |
| 5 | `rail-inventory-service` 是否站得住 | 🔴 **阶段 8 拆分时必须回头验证它的 5 条职责是否真的成立**；站不住就合并回 train-service 并记录修正 | 阶段 8 |

---

## 八、下一步：阶段 4

**目标**：核心业务（单体）—— 查询接口全部走通。

**待做**

1. 车次列表查询（引入分页 → 同时验证 V10b）
2. 车次经停站查询
3. 按出发站 / 到达站查可售车次（需要 JOIN `t_train_station`，是第一次写 XML Mapper）
4. 余票查询（读 `t_seat_inventory`）
5. 引入 `spring-boot-starter-validation` 做查询参数校验
6. **出现多种错误场景后再定错误码规范**，并考虑引入统一响应体

**完成判据**：用 curl / JMeter 走通所有查询接口，看到真实数据（**不是"没报错"**）。

---

## 九、相关文档

- [../01-project-guide.md](../01-project-guide.md) —— 项目阅读指南
- [../03-business-flow.md](../03-business-flow.md) —— 验收标准 A1~A6
- [../07-risks.md](../07-risks.md) —— 风险与已知缺陷
- [../troubleshooting/README.md](../troubleshooting/README.md) —— 真实踩坑记录
