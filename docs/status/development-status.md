# 开发状态

**更新日期**：2026-09-29
**依据**：以当前代码、配置、SQL 为准，**不根据旧文档推测**。
**项目介绍与阅读入口**见 [../01-project-guide.md](../01-project-guide.md)；本文件只回答"做到哪了"。

---

## 一、一句话

**阶段 0~4 已完成。仓库里有可以真正跑起来、并真的读到了数据库的服务。**

当前可运行的只有 `rail-train-service` 一个模块，对外提供 **6 个查询接口**（车站 2 / 车次 3 / 余票 1），`t_station`、`t_train`、`t_train_station`、`t_seat_inventory` 四张表都能通过 HTTP 读到真实数据。购票、库存扣减、秒杀、微服务拆分、MQ 全部尚未开始。

---

## 二、阶段进度

| 阶段 | 名称 | 状态 | 完成判据 / 证据 |
| --- | --- | --- | --- |
| 0 | 需求与架构设计 | ✅ **已完成** | 6 项决策全部拍板；依赖版本用真实 `mvn dependency:tree` 解析零冲突 |
| 1 | 环境搭建 | ✅ **已完成** | JDK 17 / Maven 3.9.16 / MySQL 8.4.8 / Redis 8.10.1 / Nacos 3.2.3 全部就绪，**每项都有实测验证证据** |
| 2 | 数据库设计 | ✅ **已完成** | 4 库 9 表建成；**约束行为用违规数据实测拒绝**（[06-database.md §6](../06-database.md)） |
| 3 | 项目初始化 | ✅ **已完成** | 多模块骨架 + `rail-train-service` 启动后 `curl` 到 `t_station` 的**真实数据**（14 行），404 分支也验证过 |
| 4 | 核心业务（单体） | ✅ **已完成** | 6 个查询接口用 curl + JMeter 走通，**每张表都读到真实数据**（见 §3） |
| **5** | **购票 + MySQL 库存（方案 A）** | ⏭️ **下一步** | 并发测试证明不超卖 |
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
| 车次分页列表（含始发/终到站名） | `GET /api/train/trains?page=1&size=10` | `total=3`、中文站名不乱码 |
| 某车次的经停站（按站序） | `GET /api/train/trains/{trainNo}/stations` | `G1` → 4 站 |
| 按出发/到达站查可乘区间（支持中途上车） | `GET /api/train/trains/search?from=&to=` | `VNP→AOH` → G1+G3；`JNK→NJH` → G1+G3 |
| 某车次某天的余票（按席别） | `GET /api/inventory/seats?trainNo=&date=` | `G1` + 明天 → 3 个席别 |

**实测输出**（2026-09-17，阶段 3）：

```console
$ curl -sS http://127.0.0.1:8082/api/train/stations/VNP
{"id":1,"stationCode":"VNP","stationName":"北京南","cityName":"北京","createTime":"2026-09-17T12:12:57"}

$ curl -sS -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1:8082/api/train/stations/NOPE
HTTP 404
```

**实测输出**（2026-09-29，阶段 4）：

```console
$ curl -sS "http://127.0.0.1:8082/api/train/trains?page=1&size=2"
{"records":[{"trainNo":"G1","trainType":1,"startStationCode":"VNP","startStationName":"北京南",
 "endStationCode":"AOH","endStationName":"上海虹桥","departTime":"09:00:00",
 "arriveTime":"13:28:00","status":1}, ...],"total":3,"page":1,"size":2,"pages":2}

$ curl -sS "http://127.0.0.1:8082/api/train/trains/G1/stations"
[{"stationOrder":1,"stationCode":"VNP","stationName":"北京南","cityName":"北京",
  "arriveTime":null,"departTime":"09:00:00","mileage":0},
 {"stationOrder":4,"stationCode":"AOH","stationName":"上海虹桥","cityName":"上海",
  "arriveTime":"13:28:00","departTime":null,"mileage":1318}]
  # ↑ 首站 arriveTime 为 null、末站 departTime 为 null，是正常建模不是缺数据

$ curl -sS "http://127.0.0.1:8082/api/train/trains/search?from=JNK&to=NJH"
[{"trainNo":"G1","fromStationName":"济南西","toStationName":"南京南",
  "segmentDepartTime":"10:24:00","segmentArriveTime":"12:18:00","segmentMileage":617}, ...]
  # ↑ 中途上车：不要求 from 是始发站、to 是终到站

$ curl -sS "http://127.0.0.1:8082/api/inventory/seats?trainNo=G1&date=2026-09-30"
[{"seatType":1,"price":1748.00,"totalCount":20,"soldCount":5,"remaining":15},
 {"seatType":2,"price":933.00, "totalCount":100,"soldCount":30,"remaining":70},
 {"seatType":3,"price":553.00, "totalCount":500,"soldCount":200,"remaining":300}]
```

**分页插件的运行时证据**（V10b，这是"插件真的在跑"而非"接口没报错"的证据）——
`application.yml` 里 mapper 日志开在 debug，可以直接看到插件改写的 SQL：

```console
selectTrainPage_mpCount : Preparing: SELECT COUNT(*) AS total FROM t_train t
                          JOIN t_station s1 ON s1.id = t.start_station_id
                          JOIN t_station s2 ON s2.id = t.end_station_id
TrainMapper.selectTrainPage : Preparing: ... ORDER BY t.train_no LIMIT ?
TrainMapper.selectTrainPage : Parameters: 2(Long)
```

两个要点：`COUNT` 是插件自动生成的（没手写），且 **JOIN 没有被剥离**；
`LIMIT` 只拼了**一次**（若把 `PaginationInnerInterceptor` 单独注册成 bean，
MP 3.5.17 的自动配置会导致拼两次）。

**JMeter 冒烟**（V11b）：`scripts/perf/stage4-smoke.jmx`，1 线程 6 请求，
`Err: 0 (0.00%)`。其中 2 个请求**故意期望 4xx**（400 / 404），
已用 JSR223 断言把它们显式标记成成功，**所以判读规则是"错误率 0% 才算通过"**。
该断言做过负向对照（把期望的 404 改成 500 后确实报错），确认不是空转。

### 基础设施侧已完成（有实测证据，但尚未被业务代码使用）

| 项 | 证据 |
| --- | --- |
| 4 个 schema + 9 张表 + 应用账号 `rail` | [sql/00_init.sql](../../sql/00_init.sql) ~ [04](../../sql/04_rail_order.sql) 执行成功 |
| 唯一索引 / CHECK 约束 / 条件 UPDATE 的 CAS 行为 | [sql/99_verify.sql](../../sql/99_verify.sql)，违规数据**全部被数据库拒绝** |
| Redis 原子扣减能力（含 Lua 分支） | [scripts/env/verify-redis.sh](../../scripts/env/verify-redis.sh)：100 张票 vs 1000 并发 → 余票恰好 0、恰好 100 人成功 |
| Lua 扣减脚本设计稿 | [scripts/env/lua/stock_deduct.lua](../../scripts/env/lua/stock_deduct.lua) —— **尚未接入 Java 代码** |
| Nacos 3.2.3 standalone 可启动 | **改端口后已于 2026-09-29 复验（V7）**：`http://127.0.0.1:8889/` → 302 跳 `/next/` → 200（页面标题 `Nacos Console`）；`8888` → 404（它是服务端端口，不是控制台）。8888/8889/9888/9889 四端口均在监听 |
| RocketMQ 5.5.1 可启动 | NameServer + Broker 曾成功运行（`~/logs/rocketmqlogs/broker.log` 有连续注册与心跳日志）。**选型仍未拍板** |
| JMeter 5.6.3 可运行 | `jmeter.bat --version` → 输出 5.6.3（JDK 17） |
| JMeter 真的能发请求（V11b） | `scripts/perf/stage4-smoke.jmx` 已对 8082 实发 6 个请求，`Err: 0 (0.00%)` |

> ⚠️ **上面这些能力"验证过"不等于"用上了"**。Redis 和 Nacos 现在都没有出现在任何 Java 代码或 POM 依赖里。

---

## 四、部分完成

| 项 | 已完成的部分 | 还缺什么 |
| --- | --- | --- |
| `t_train` / `t_train_station` / `t_seat_inventory` 的数据 | 种子数据已灌入（3 车次、14 经停站、27 行库存），**三个接口都能读到** | 无 |
| MyBatis-Plus | 基础查询 + 分页 + XML Mapper（含自连接）全部跑通 | **mapper 层 SQL 日志还开在 debug**（技术债 #2，阶段 6 压测前必须关） |
| 环境验证清单 | V1~V13 完成，V7 / V10b / V11b 已关闭 | V14（RocketMQ 复验）、V15（RocketMQ 内存） |
| MQ 相关的表结构 | `t_local_message` 已建好 | **阶段 9 前不会有任何代码写它** |
| RocketMQ 5.5.1 | 已安装、官方脚本 JDK 17 兼容、历史上启动成功 | **选型未拍板**；默认 4 GB 堆对本机内存压力大（[04-technology §4.2](../04-technology.md)） |
| 错误处理 | `ApiExceptionHandler` 统一了 400 / 405 的错误体；观测到的状态码已记录 | 🔴 **404 的 body 是空的、不经过 advice**（已知不一致，见 [error-codes.md §5](../api/error-codes.md)）；错误码枚举推迟到阶段 8 |

---

## 五、尚未实现

**功能**

- 用户注册 / 登录 / JWT 签发（`rail-user-service` 未建）
- 购票下单、订单状态机、模拟支付
- **秒杀（项目的核心场景，尚未开始）**
- 库存预热、对账任务、超时关单回补
- **库存扣减本身**——当前所有接口都是只读的，`t_seat_inventory` 还没有任何写入路径

> ✅ 已完成的查询：车次列表、经停站、按出发/到达站查车次、余票查询 —— 见 §3。

**模块**

`rail-gateway`、`rail-user-service`、`rail-inventory-service`、`rail-order-service` 四个模块**都还不存在**。根 POM 的 `<modules>` 只登记了 `rail-train-service`。

**基础设施**

Sentinel Dashboard（未安装）、链路追踪（无）。

RocketMQ 5.5.1 **已安装但还没有被用起来**——没有一行代码连它，选型也还没拍板。（JMeter 在阶段 4 已经用起来了，见 §3。）

---

## 六、已知问题与技术债

| # | 项 | 说明 | 何时处理 |
| --- | --- | --- | --- |
| 1 | **没有统一的响应体包装** | 接口直接返回原始数据 + HTTP 状态码，**这是刻意不引入的**（秒杀场景下成功/失败必须和状态码对上）。✅ **阶段 4 已按预定顺序处理**：先让多种错误场景真实出现，再把它们记进 [error-codes.md](../api/error-codes.md)；错误码**枚举**仍推迟到阶段 8（届时才有业务错误可枚举） | 已完成（枚举部分阶段 8） |
| 1b | **404 的响应体是空的，与 400/405 的 `ApiError` 形状不一致** | 404 由 Controller 主动 `return ResponseEntity.notFound()`，不抛异常，因此不经过 `ApiExceptionHandler`。调用方无条件 `JSON.parse` 会失败。实测 `Content-Length: 0`、无 `Content-Type` | **阶段 8 引入网关前必须定**（见 [error-codes.md §5](../api/error-codes.md) 的两个选项） |
| 2 | **mapper 层 SQL 日志开在 debug** | `application.yml` 里 `com.railseckill.train.mapper: debug`。**压测前必须关掉**，否则控制台 IO 自己会变成瓶颈，测出的数字全是假的 | **阶段 6 压测前** |
| 3 | **依赖刻意未加** | ✅ `validation` 已于阶段 4 加入。仍未加：`actuator`（阶段 8）、`data-redis`（阶段 7）、Nacos/OpenFeign（阶段 8）、Lombok（暂不加） | 按阶段 |
| 4 | **配置里有本地密码默认值** | `application.yml` 里 `${DB_PASSWORD:rail123456}`。真实项目应完全由密钥管理下发 | 生产环境 |
| 5 | **Spring Boot 版本落后于已验证的版本** | 当前 POM 是 3.5.9，阶段 0 实测建议 3.5.16。✅ **阶段 4 决定不升级**：没有强制升级的技术理由，不该把「改框架版本」和「加新功能」两个失败来源混在同一步 | 已决策，不处理 |
| 6 | **Boot 3.5.x / Spring Cloud 2025.0.x 已 EOL** | 2026-06-30 EOL。教学项目不影响使用，但面试时应主动提及 | 不处理 |
| 7 | **无链路追踪** | 跨服务问题定位靠日志 grep | 阶段 10 可选 |
| 8 | **`X-User-Id` 透传存在伪造风险** | 阶段 8 引入网关时必须同时处理（剥离客户端传入的值 + 内网隔离） | **阶段 8 必须做** |
| 9 | **本机 DLP 会加密无扩展名文件** | 已加 pre-commit 守卫脚本；无扩展名文件必须用 bash 生成 | 持续 |
| 10 | **已删除未验证的 AI 草稿** | `docs/_drafts-unverified/`（约 11k 行）已于本次文档整理中删除。其内容为一次性生成、内部矛盾、且与真实代码/DDL 不符（详见下方"处置说明"） | 已处理 |
| 11 | **`rocketmq_start.bat` 会杀掉所有 java 进程** | 脚本里是 `taskkill /f /im java.exe`，会连带干掉 **Nacos 服务端**和**正在运行的 Spring Boot 应用**；压测中执行会直接毁掉压测数据 | **下次用 RocketMQ 前改成按端口精确杀** |
| 12 | **RocketMQ 默认 4 GB 堆与本机内存不匹配** | NameServer 2 GB + Broker 2 GB（`AlwaysPreTouch` 启动即真实提交），而本机空闲内存曾低至 1.5 GB | 阶段 9 前调小 |
| 13 | **`jmeter.bat` 只认 PATH 上的 `java`，不读 `JAVA_HOME`** | 报 "Not able to find Java executable" 时应先 `where java`，而不是改 `JAVA_HOME`。⚠️ **本机 PATH 配置是正确的**（注册表 `HKCU\Environment` 有 `JAVA_HOME=D:\jdk-17.0.20.1`，用户 PATH 有 `%JAVA_HOME%\bin`），**新开的终端直接就能用**；阶段 4 之所以要临时 `export PATH` 是因为 **AI 工具（Claude Code）的 shell 继承的是改环境变量之前的旧进程环境**，`echo $PATH` 里没有 jdk。判据是 `reg query "HKCU\Environment" //v Path` 而不是当前 shell 的 `$PATH` | 已记录，无需处理 |
| 14 | **🔴 train-service 会跨库读 `rail_inventory.t_seat_inventory`** | 余票查询（阶段 4）住在 train-service 里，但读的是 `rail_inventory` 的表——**破坏了"一表一主"的边界**。当前靠约定只读，没有任何技术手段阻止写入。**注意 `rail` 账号对该库有写权限**（`GRANT SELECT, INSERT, UPDATE, DELETE`） | **阶段 8 必须消除**：代码整体迁往 `rail-inventory-service`。接口路径已经提前定成 `/api/inventory/**`，迁的时候只需加一条网关路由，**客户端无感** |
| 15 | **阶段 4 的分页 JOIN 没有做 COUNT 优化** | 现在 3 趟车，优化就是编数字。等阶段 6 压测有真实数据再说。⚠️ 注意：分页插件的 `optimizeJoin` 对本查询**本来就不生效**（它的实现是"遇到第一个非 LEFT JOIN 就放弃优化"），所以**不要为了"启用优化"而把 INNER JOIN 改成 LEFT JOIN**——那会让它开始剥离 JOIN，一旦被剥的 JOIN 会放大行数，`COUNT` 就静默变小 | 阶段 6 视压测数据 |

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
| 3 | Spring Boot 是否升级 3.5.9 → 3.5.16 | ✅ **已拍板：不升级，保持 3.5.9**（无强制技术理由；不在加新功能的同时改框架版本） | 已关闭 |
| 4 | Redis 第三方移植版的剩余风险 | 🟡 基础命令与 Lua 已实测通过；`atomicvar_api=msvc-zkatomic` 非官方实现，并发行为已单独验证 | 持续 |
| 5 | `rail-inventory-service` 是否站得住 | 🔴 **阶段 8 拆分时必须回头验证它的 5 条职责是否真的成立**；站不住就合并回 train-service 并记录修正 | 阶段 8 |
| 6 | **404 是否也返回 `ApiError` 体** | 🔴 未定。当前 404 是空 body、400/405 是 `ApiError` JSON，形状不统一。倾向"保持现状 + 文档写清"，但**不现在拍**——等阶段 8 有了网关和统一的前端调用层，"客户端要多写几行"才有真实成本可衡量 | 阶段 8 引入网关前 |

---

## 八、下一阶段：阶段 5（购票 + MySQL 库存方案 A）

**目标**：用「条件 UPDATE」实现并发下不超卖，并用并发测试**证明**它不超卖。

**方向**（具体待规划）

1. 下单接口：生成订单 + 扣减库存，**判断与扣减必须是原子的**
2. 库存扣减用 `UPDATE ... WHERE sold_count < total_count`（CAS），**不用 `SELECT` 再 `UPDATE`**
3. 订单状态机（0 待支付 / 1 已支付 / 2 已取消），状态迁移一律条件 UPDATE
4. 并发测试：多线程抢同一批票，**断言"成功数 == 库存数"且"余票恰好为 0"**
5. 这是"🔴 库存还没有任何写入路径"这条现状的第一次改变 —— 接口从此不再全是只读

**为什么阶段 5 是分水岭**：前 4 个阶段所有接口都是查询，不存在并发正确性问题。
从阶段 5 开始，**"虽然通过了但结果是错的"** 才成为可能的失败模式
（超卖、少卖、重复扣），所以测试的重点从"接口通不通"变成"数字对不对"。

---

## 九、阶段 4 的复盘（做对了什么、什么差点踩坑）

保留这一节是因为**下面这些都不是靠读代码能想到的**，都是实测才暴露的：

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | **record 的构造器映射是个静默陷阱** | MyBatis 的 `argNameBasedConstructorAutoMapping` **默认 false**，此时 record 按**列的物理顺序**绑定构造参数、**完全不比名字**。类型相容时（如 `departTime` 和 `arriveTime` 都是 `LocalTime`）会静默串值。本项目一律用显式 `<constructor>` + `<arg column=...>` 规避 |
| 2 | **`?size=0` 只能靠 `@Min(1)` 拦** | 分页插件的 `maxLimit` 钳制条件是 `size > limit \|\| size < 0`，**接不住 0**；0 会生成 `LIMIT 0` 并**照样白跑一次 COUNT**。而 `spring-boot-starter-validation` 缺失是**静默失效**，所以验证清单里必须有这条期望 400 |
| 3 | **类上加 `@Validated` 会让错误信息变差** | 它会**关闭** Spring MVC 内建的方法校验，改走 AOP，异常类型变成 `ConstraintViolationException`，`propertyPath` 带**方法名前缀**（`pageTrains.size` 而非 `size`），参数名退化成 `arg0`。本项目刻意不加 |
| 4 | **`optimizeJoin` 对 INNER JOIN 是空转** | 不要为了"启用优化"改成 LEFT JOIN —— 理由见技术债 #15 |
| 5 | **"缺必填参数"的 `details` 曾经是空的** | 最初只处理了校验失败和类型转换两种异常，实测 `?to=AOH`（不带 `from`）才发现调用方看不出缺的是哪个参数。**读代码想不到，跑一次就看见了** |
| 6 | **JMeter 里"通过"的 ResponseAssertion 不会翻回成功** | 它设的是 `AssertionResult` 的成功标志，不是 `SampleResult` 的，所以期望 4xx 的采样器仍然算 failed，整体错误率永远是 1/3，读的人分不清是设计如此还是接口坏了。改用 JSR223 断言显式 `prev.setSuccessful(true)`，**并做了负向对照确认断言不是空转** |

---

## 十、相关文档

- [../01-project-guide.md](../01-project-guide.md) —— 项目阅读指南
- [../03-business-flow.md](../03-business-flow.md) —— 验收标准 A1~A6
- [../api/error-codes.md](../api/error-codes.md) —— **实测观测到**的状态码与错误体形状
- [../07-risks.md](../07-risks.md) —— 风险与已知缺陷
- [../troubleshooting/README.md](../troubleshooting/README.md) —— 真实踩坑记录
