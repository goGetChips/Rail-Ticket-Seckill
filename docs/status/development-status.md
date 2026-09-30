# 开发状态

**更新日期**：2026-09-30
**依据**：以当前代码、配置、SQL 为准，**不根据旧文档推测**。
**项目介绍与阅读入口**见 [../01-project-guide.md](../01-project-guide.md)；本文件只回答"做到哪了"。

> 🔴 **本文件的读法**：阶段 5 的**代码、测试、脚本、SQL 全部写完了，但一次都没跑过**
> （写代码期间本机 MySQL 起不来，详见下方"阻塞"）。所以阶段 5 的一切状态都是
> **🟡 待验证**，本文件里**没有任何阶段 5 的实测数字** —— 这是刻意的，
> 不是漏写。跑完之后要把数字回填到 [05-seckill.md §六](../05-seckill.md) 的「实测状态」表。

---

## 一、一句话

**阶段 0~4 已完成并实测通过；阶段 5 的代码已写完，等一次真实的运行来证明它。**

当前可运行的只有 `rail-train-service` 一个模块，对外提供 **6 个查询接口 + 2 个写入接口**：

| 类型 | 数量 | 明细 |
| --- | --- | --- |
| 查询 🟢 已实测 | 6 | 车站 2 / 车次 3 / 余票 1 |
| 写入 🟡 已实现待验证 | 2 | `POST /api/order/orders`（下单 + 扣库存）、`POST /api/order/orders/{orderNo}/pay`（模拟支付） |

`t_station`、`t_train`、`t_train_station`、`t_seat_inventory` 四张表已能通过 HTTP 读到真实数据；
阶段 5 新写通的 `t_order` / `t_order_item` / `t_stock_flow` **三张表在写代码前是 0 行**，
现在有了写入路径，但**库里目前是否已经有数据，取决于有没有人跑过测试**。

秒杀、Redis、MQ、微服务拆分、超时关单**全部尚未开始**。

**阻塞**：本机 MySQL 的数据目录被公司 DLP（亚信安全）透明加密，
`ibdata1` / `undo_001` / `undo_002` / `ib_buffer_pool` 四个**无扩展名**文件被套上
`%TSD-Header-###%` 头并填充成 8192 字节容器，实例起不来。
阶段 5 的全部验证（JUnit、JMeter、SQL 校验）都依赖数据库，因此**全部待跑**。

---

## 二、阶段进度

| 阶段 | 名称 | 状态 | 完成判据 / 证据 |
| --- | --- | --- | --- |
| 0 | 需求与架构设计 | ✅ **已完成** | 6 项决策全部拍板；依赖版本用真实 `mvn dependency:tree` 解析零冲突 |
| 1 | 环境搭建 | ✅ **已完成** | JDK 17 / Maven 3.9.16 / MySQL 8.4.8 / Redis 8.10.1 / Nacos 3.2.3 全部就绪，**每项都有实测验证证据** |
| 2 | 数据库设计 | ✅ **已完成** | 4 库 9 表建成；**约束行为用违规数据实测拒绝**（[06-database.md §6](../06-database.md)） |
| 3 | 项目初始化 | ✅ **已完成** | 多模块骨架 + `rail-train-service` 启动后 `curl` 到 `t_station` 的**真实数据**（14 行），404 分支也验证过 |
| 4 | 核心业务（单体） | ✅ **已完成** | 6 个查询接口用 curl + JMeter 走通，**每张表都读到真实数据**（见 §3） |
| **5** | **购票 + MySQL 库存（方案 A）** | 🟡 **代码与测试已写完，待运行验证** | 判据：**用条件 UPDATE 实现并发下不超卖，并用并发测试证明它不超卖**。代码、`OrderServiceIT`（10 用例）、`OrderConcurrencyTest`（4 用例）、JMeter 脚本、校验 SQL 均已就位；**⛔ 一条都还没跑过**（MySQL 被 DLP 加密，起不来） |
| 6 | 压测 v1 | ⏭️ **下一步** | 有真实瓶颈数据 |
| 7 | 引入 Redis（方案 B） | ⬜ 未开始 | 与阶段 6 对比有量化提升 |
| 8 | 微服务拆分（5 服务） | ⬜ 未开始 | 跨服务调用通，服务下线可感知 |
| 9 | 引入 MQ（方案 C） | ⬜ 未开始 | 先定 MQ 选型，再逐条实测异常路径 |
| 10 | 压测 v2 + 优化 | ⬜ 未开始 | 完整的优化前后对比 |
| 11 | 复盘 + 面试化 | ⬜ 未开始 | 能不看文档讲 30 分钟 |

---

## 三、已完成的功能

### 3.1 查询接口（🟢 已实测）

| 功能 | 位置 | 验证方式 |
| --- | --- | --- |
| 列出全部车站（按电报码升序） | `GET /api/train/stations` | 返回 14 条车站 |
| 按电报码查单个车站 | `GET /api/train/stations/{code}` | `VNP` → 200 + 北京南；`NOPE` → 404 |
| 车次分页列表（含始发/终到站名） | `GET /api/train/trains?page=1&size=10` | `total=3`、中文站名不乱码 |
| 某车次的经停站（按站序） | `GET /api/train/trains/{trainNo}/stations` | `G1` → 4 站 |
| 按出发/到达站查可乘区间（支持中途上车） | `GET /api/train/trains/search?from=&to=` | `VNP→AOH` → G1+G3；`JNK→NJH` → G1+G3 |
| 某车次某天的余票（按席别） | `GET /api/inventory/seats?trainNo=&date=` | `G1` + 明天 → 3 个席别 |

### 3.2 写入接口（🟡 已实现，**尚未运行过**）

| 功能 | 位置 | 预期行为（**待验证**） |
| --- | --- | --- |
| 下单 + 条件 UPDATE 扣库存 | `POST /api/order/orders` | 200 + 订单号；`t_stock_flow` 多一行 `change_type=2` |
| 同上，库存行不存在 | 同上 | 409「该席别尚未放票」 |
| 同上，`sold_count >= total_count` | 同上 | 409「该席别已售罄」 |
| 同上，同一用户同车同日同席别 | 同上 | 409「请勿重复购票」 |
| 同上，`trainNo` 不存在 | 同上 | 404（空 body） |
| 同上，请求体非法（如 `{}`）| 同上 | 400 + `ApiError`，**`details` 必须非空** |
| 模拟支付 | `POST /api/order/orders/{orderNo}/pay` | 200 `idempotent=false`；重复调用 → `idempotent=true` |
| 同上，订单已取消 | 同上 | **409**（不是 200 —— 见 [03-business-flow.md §二](../03-business-flow.md)） |
| 同上，订单号不存在 | 同上 | 404 |

> 🔴 **为什么这一节的"实测输出"是空的**：本仓库的硬规则是"**禁止编造数字**"
> （[01-project-guide.md §六](../01-project-guide.md)），阶段 5 的所有测试
> **一次都没跑过**（MySQL 被 DLP 加密）。所以这里**不放 console 输出** ——
> 放一段看起来像实测、实际上是编的 JSON，比不放更糟。
>
> **跑完之后要做的三件事**：
> 1. 把 `POST /api/order/orders` 的 200 与三个 409 的真实响应体贴到这里（照 §3.3 的格式）
> 2. 把并发测试的真实计数回填到 [05-seckill.md §六](../05-seckill.md) 的「实测状态」表
> 3. 更新本文件 §二 的阶段 5 状态为 ✅

### 3.3 查询接口的实测输出

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
| `t_order` / `t_order_item` / `t_stock_flow` 三张表 | DDL 在阶段 2 就建好了 | **阶段 5 之前从来没有一行代码写过它们**（0 行）。阶段 5 补上了写入路径（`OrderService`），但**是否真的写进去过取决于有没有跑过测试** —— 未经验证 |
| MyBatis-Plus | 基础查询 + 分页 + XML Mapper（含自连接）全部跑通 | **mapper 层 SQL 日志还开在 debug**（技术债 #2，阶段 6 压测前必须关）。⚠️ 阶段 5 又给它加了两条 UPDATE 语句，**不关日志的代价比阶段 4 更大** |
| 环境验证清单 | V1~V13 完成，V7 / V10b / V11b 已关闭 | V14（RocketMQ 复验）、V15（RocketMQ 内存）、**V16~V18（阶段 5 的三项验证，见 [05-seckill.md §六](../05-seckill.md)）** |
| MQ 相关的表结构 | `t_local_message` 已建好 | **阶段 9 前不会有任何代码写它** |
| RocketMQ 5.5.1 | 已安装、官方脚本 JDK 17 兼容、历史上启动成功 | **选型未拍板**；默认 4 GB 堆对本机内存压力大（[04-technology §4.2](../04-technology.md)） |
| 错误处理 | 阶段 4：`ApiExceptionHandler` 统一了 400 / 405。**阶段 5 扩到 400 / 405 / 409 / 500**：新增 `BusinessException` 分支（409）与 `Exception` 兜底（500），`details()` 新增 `MethodArgumentNotValidException` 分支 | 🔴 **404 的 body 是空的、不经过 advice**（已知不一致，见 [error-codes.md §5](../api/error-codes.md)）；错误码枚举推迟到阶段 8 |

---

## 五、尚未实现

**功能**

- 用户注册 / 登录 / JWT 签发（`rail-user-service` 未建）
- **秒杀（项目的核心场景，尚未开始）**
- 库存预热、对账任务
- **超时关单 + 库存回补**（阶段 7）：占着库存不付款目前不会自动恢复
- 取消订单（主动取消）
- **库存的回补路径**：阶段 5 只会 `sold_count + 1`（扣），不会 `- 1`（回）。
  `SeatInventoryMapper` 里**故意没有 `restoreStock`**
- **库存的读缓存**：`GET /api/inventory/seats` 目前每次都直接查 MySQL

> ✅ 已实现但**待验证**的写入：下单 + 扣库存、模拟支付 —— 见 §3.2。
> ⚠️ **阶段 5 之前**这一节的原文是"**库存扣减本身**——当前所有接口都是只读的，
> `t_seat_inventory` 还没有任何写入路径"。这句话现在是**事实错误**，
> 所以删掉。它同时是一个提醒：**"当前没有 X"这类现状描述，
> 有效期只到有人做了 X 为止** —— 而这句话在这里挂了整整一个阶段没人改。
>
> ✅ 已完成的查询：车次列表、经停站、按出发/到达站查车次、余票查询 —— 见 §3.1。

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
| 2 | **mapper 层 SQL 日志开在 debug** | `application.yml` 里 `com.railseckill.train.mapper: debug`。**压测前必须关掉**，否则控制台 IO 自己会变成瓶颈，测出的数字全是假的。⚠️ 阶段 5 让这条债变重了：现在每次下单会打 **5 条 SQL**（SELECT + UPDATE + 3 条 INSERT），阶段 4 的查询只有 1~2 条；100 线程并发时控制台 IO 会成为绝对瓶颈 | **阶段 6 压测前**（阶段 5 的并发测试已经临时在 `@SpringBootTest(properties=...)` 里把它调回 `info`，但**没动 `application.yml`** —— 见技术债 #18） |
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
| 14 | **🔴 train-service 跨库 ~~读~~ **读写** `rail_inventory.t_seat_inventory`** | 阶段 4 是跨库**读**（余票查询）；**阶段 5 起升级为跨库"读 + 事务性写"** —— `SeatInventoryMapper.deductStock` 会 `UPDATE` 它，而且是下单事务的一部分。严重度**升高了一档**：从"违反约定的只读"变成"别的服务的写路径住在 train-service 里"。注意 `rail` 账号对该库本来就有写权限（`GRANT SELECT, INSERT, UPDATE, DELETE`），数据库层面从没拦过 | **阶段 8 必须消除**：扣减代码迁往 **`rail-inventory-service`**（库存表的 owner），**不是** `rail-order-service`；余票查询也一起迁。接口路径已提前定成 `/api/inventory/**`，迁的时候只需加一条网关路由，**客户端无感** |
| 15 | **阶段 4 的分页 JOIN 没有做 COUNT 优化** | 现在 3 趟车，优化就是编数字。等阶段 6 压测有真实数据再说。⚠️ 注意：分页插件的 `optimizeJoin` 对本查询**本来就不生效**（它的实现是"遇到第一个非 LEFT JOIN 就放弃优化"），所以**不要为了"启用优化"而把 INNER JOIN 改成 LEFT JOIN**——那会让它开始剥离 JOIN，一旦被剥的 JOIN 会放大行数，`COUNT` 就静默变小 | 阶段 6 视压测数据 |
| 16 | **🔴 订单 / 订单明细 / 库存流水的写入路径住在 `rail-train-service`** | 和 #14 是同一个根因的另一半：`OrderService`（下单事务）写在 train-service 里，但**订单表属于 `rail-order-service`、流水表属于 `rail-inventory-service`**。所以这个事务横跨了三个未来服务的表。现阶段靠"同一个 MySQL 实例 + 全限定表名"是**合法且原子的**（见 [06-database.md §五](../06-database.md)），但代码的**归属**是错的 | **阶段 8 必须拆**：扣减 + 流水迁 `rail-inventory-service`，订单 + 明细迁 `rail-order-service`。**这一步会让"跨库事务"第一次真的变成分布式事务问题** —— 现在不做任何补偿设计，但转折点已经写在代码注释里 |
| 17 | **阶段 5 的测试数据靠手工清理** | 并发测试会真的往库里写订单 / 票 / 流水，而它们**不能靠事务回滚清理**（并发测试禁用 `@Transactional`，见测试类注释的四条理由）。目前靠测试类的 `@AfterEach` 显式 `DELETE` 兜着，JMeter 留下的数据只能按 [sql/12_verify_stage5_order.sql](../../sql/12_verify_stage5_order.sql) 文末的注释手工删 | **阶段 6 CI 化时**：把 fixture 的造与清都放进脚本，别让人记得住删哪些表 |
| 18 | **连接池大小在测试上下文里被临时改过** | `OrderConcurrencyTest` 用 `@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=32")` 把池从默认 10 改到 32 —— 因为**并发度上限 = min(线程数, 池大小)**，池是 10 的话 100 个线程里有 90 个在排队，"100 并发不超卖"是空话。**刻意只改测试上下文，不动 `application.yml`**：池大小本身是阶段 6 要压测的变量，现在改会把基准线弄脏 | **阶段 6 压测时**才在 `application.yml` 里定它的值，并且要有"改前/改后"的对照 |

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
| 6 | **404 是否也返回 `ApiError` 体** | 🔴 未定。当前 404 是空 body、400/405 是 `ApiError` JSON，形状不统一。倾向"保持现状 + 文档写清"，但**不现在拍**——等阶段 8 有了网关和统一的前端调用层，"客户端要多写几行"才有真实成本可衡量。⚠️ 阶段 5 让这条的**影响面扩大了**：下单接口的 404 是"车次不存在"，而真正常见的情况是 409（没放票 / 售罄 / 重复）——两个 4xx 的形状不一样，前端的处理分支要写两套 | 阶段 8 引入网关前 |
| 7 | **`change_type = 2`（确认扣减）的语义在阶段 7 引入预扣后要不要改** | 🔴 未定。阶段 5 的扣减是**一次性的终局**：没有预扣阶段，所以 `2` 就是"扣了，且这笔是真卖出去了"。阶段 7 会引入 `1`（预扣，Redis 侧）和 `3`（回补），那时 `2` 的含义要重新定义：是"预扣转确认"还是"没有预扣时的直接确认"？**这决定了阶段 5 已经写进库里的那些 `2` 会不会变成歧义数据。** 倾向：`2` 一律表示"库存已确认减少"，来源可以是预扣转来也可以是直接扣；但**不现在拍**——阶段 7 要先把"对账 SQL 怎么区分这两条路径"想清楚 | 阶段 7 设计 Redis 方案时（**必须先定，再动手写**） |
| 8 | **`message` 到底算不算对外契约** | 🟡 倾向"**不算**"：调用方只能依赖状态码，文案随时可改。但阶段 5 的三个 409 分支（没放票 / 售罄 / 重复购票）**现在只有文案能区分**——状态码是同一个 409。要么承认文案是契约，要么在阶段 8 给它们错的错误码 | 阶段 8（和 #6 一起定） |

---

## 八、下一步：先把阶段 5 跑通，再进阶段 6

### 8.1 欠的第一件事：跑阶段 5 的验证（阻塞在 MySQL）

代码已经全部就位，**但一次都没跑过**。顺序如下（三条都是"跑不出数字就是没完成"）：

| # | 做什么 | 命令 / 位置 | 期望 |
| --- | --- | --- | --- |
| V16 | 单线程语义 | `./mvnw -pl rail-train-service test -Dtest=OrderServiceIT` | 10 个用例全绿 |
| V17 | **并发证明**（阶段判据） | `./mvnw -pl rail-train-service test -Dtest=OrderConcurrencyTest` | 100 线程抢 20 张 → 恰好 20 成功 / 80 售罄，库里数字一致 |
| V18 | 真实 HTTP 交叉验证 | [scripts/perf/stage5-order.jmx](../../scripts/perf/stage5-order.jmx) | `Err: 0 (0.00%)`（= 没有 200/409 之外的响应） |
| V18b | SQL 对账 | [sql/12_verify_stage5_order.sql](../../sql/12_verify_stage5_order.sql) | ①~⑤ 全部 0 行 |

跑完把真实数字回填到 [05-seckill.md §六](../05-seckill.md) 的「实测状态」表，
并把本文件 §二 的阶段 5 状态改成 ✅。

> ⚠️ **不要跳过 V18b**。阶段 5 之前，"接口返回 200"就等于"数据没问题"；
> 阶段 5 起**有三种失败不会让接口报错**（超卖 / 少卖 / 重复），
> 它们只能靠对账 SQL 发现。**"测试全绿"和"账对得上"是两件事。**

### 8.2 然后是阶段 6：压测 v1

**目标**：拿到**真实的瓶颈数据** —— 不是"优化"，先量。

**为什么必须在阶段 6 之前先跑完阶段 5**：阶段 6 压的是"方案 A（纯 MySQL 扣减）"，
它就是阶段 7 加 Redis 之后的**对照基线**。基线本身没被测准，后面所有的"提升了多少"都是空中楼阁。

**方向**（具体待规划）

1. **先关掉 mapper debug 日志**（技术债 #2）—— 不关就是量控制台 IO，不是量数据库
2. `maximum-pool-size` 从 10 开始调，**每次只动一个变量**，记录"改前/改后"（技术债 #18）
3. 压测方案 A 的下单接口，量出：QPS、P95/P99、错误率、以及**瓶颈在哪**（是连接池？是行锁竞争？是 IO？）
4. 压测脚本沿用 [scripts/perf/stage5-order.jmx](../../scripts/perf/stage5-order.jmx) 的骨架，但**要记录时延数字**（阶段 5 刻意不记）
5. 结果写进一份压测报告：机器配置 + commit hash + 每个变量的取值

**这一阶段最容易犯的错**：把"测不出瓶颈"当成"没有瓶颈"。
3 趟车、单机、没有真实流量 —— 测不出瓶颈是正常的，
**如实写"在当前数据量与并发度下未观测到瓶颈"**，而不是编一个数字出来。

---

## 九、阶段 5 的复盘

🔴 **状态：不完整。** 阶段 5 的代码写完了但**一次都没跑过**，
所以本节的"实测才暴露的坑"一栏**是空的** —— 不是因为没有坑，
是因为还没跑到会踩它的那一步。跑完之后必须回来把它填上。

### 9.1 写代码阶段识别出的坑（**待实测确认，不是实测结论**）

下面这些是**读代码 + 推演**就能判断的，价值是"提前知道往哪看"，
**不能当成已验证的结论**：

| # | 坑 | 为什么危险 |
| --- | --- | --- |
| 1 | **`catch` 块里 `return` 会让事务提交** | 重复下单时 ④ 撞唯一索引抛异常；若 catch 住然后 `return` 一个 409，Spring 认为方法成功 → **COMMIT** → ②的库存扣减被提交、订单没写。**接口返回的 409 完全正常，只有对账才能发现**。判据：`OrderService` 里**没有任何 catch 块以 return 结束** |
| 2 | **捕错异常类型会掩盖代码 bug** | 必须捕 `DuplicateKeyException`，**不能捕父类 `DataIntegrityViolationException`** —— 父类会连带吞掉 `1364`（NOT NULL 违规），把"必现的编码错误"伪装成"正常的业务拒绝"。而 CHECK 违反（`3819`）**Spring 根本不翻译**，所以也没有"顺手兜住 CHECK"这条退路 |
| 3 | **`@TableName` 漏库名前缀** | 不写 `rail_order.` 就报 `Table 'rail_train.t_order' doesn't exist`。**应用启动正常、其它接口全正常，只有第一次 POST 才炸** |
| 4 | **测试类的假阳性** | `ok + soldOut == THREADS` 这个断言**在发生死锁/超时时照样成立**（那些线程被算进别的桶）。所以必须**单独收集"预期外的异常"并断言为空**，且 `done.await(...)` 的返回值必须断言 —— 不等齐就断言，可能在"线程还没跑完"时恰好凑出 `ok == STOCK` 的**假通过** |
| 5 | **并发测试绝不能加 `@Transactional`** | 四条理由（fixture 对别的连接不可见 / 工作线程的提交不受回滚管 / 断言读自己的未提交改动 / 锁互等导致挂起 50 秒），任一条都足以让测试失效。**代价是清理必须显式 DELETE**（技术债 #17） |
| 6 | **JMeter 客户端计数不是证据** | 收到 200 ≠ 服务端提交了。**判据必须落在数据库里** —— 这是阶段 5 的最基本原则 |

### 9.2 需要实测才能回答的问题（跑完必须回来记）

| # | 问题 | 答案记在哪 |
| --- | --- | --- |
| 1 | 到底有没有观测到死锁（`DeadlockLoserDataAccessException`）？次数是多少？ | 本节；**0 就写"未观测到"，不写"不会有"** |
| 2 | 100 线程的测试里，实际同时执行的数据库事务有多少？（连接池 32，但受锁竞争影响） | 本节 |
| 3 | 同一用户 50 并发时，失败的那 49 个拿到的到底是「售罄」还是「请勿重复购票」？ | 本节 + 产品语义（[05-seckill.md §六](../05-seckill.md)） |
| 4 | 跨库写 + ROLLBACK 真的两处同时回滚了吗？（不能引用设计阶段的结论，要亲手跑一遍） | 本节 |
| 5 | 三条对账 SQL 是不是真的 0 行？ | [05-seckill.md §六](../05-seckill.md) |

---

## 十、阶段 4 的复盘（做对了什么、什么差点踩坑）

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

## 十一、相关文档

- [../01-project-guide.md](../01-project-guide.md) —— 项目阅读指南
- [../03-business-flow.md](../03-business-flow.md) —— 验收标准 A1~A6、方案 A/B/C
- [../05-seckill.md](../05-seckill.md) —— 不超卖的 5 道防线与**实测状态表（待回填）**
- [../api/error-codes.md](../api/error-codes.md) —— **实测观测到**的状态码与错误体形状
- [../07-risks.md](../07-risks.md) —— 风险与已知缺陷
- [../decisions/ADR-004-stock-deduction-mysql-cas.md](../decisions/ADR-004-stock-deduction-mysql-cas.md) —— 为什么用条件 UPDATE
- [sql/12_verify_stage5_order.sql](../../sql/12_verify_stage5_order.sql) —— 阶段 5 的对账 SQL
- [../troubleshooting/README.md](../troubleshooting/README.md) —— 真实踩坑记录（**阶段 5 尚未观测到任何故障，所以没有新增**）
