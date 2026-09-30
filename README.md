# Rail-Ticket-Seckill

> 一个用于**深入理解分布式高并发**的铁路购票 / 秒杀教学项目。
> 目标不是功能齐全，而是能对每一个设计决策回答「**为什么这么做**」和「**为什么不用 X**」。

**一句话说清它要解决什么**：把「判断库存够不够」和「扣减库存」压成**一步原子操作**——用 MySQL 的行锁 + 条件 UPDATE，用 Redis 的 Lua 脚本，用消息队列的异步落库，三种形态讲同一个道理。

**第一次读这个项目，从这里开始** → [项目阅读指南](docs/01-project-guide.md)

---

## 当前开发状态

**阶段 0~4 已完成并实测通过。阶段 5（下单 + 条件 UPDATE 扣库存）的代码已写完，等一次真实的运行来证明它。**

当前可运行的只有 `rail-train-service` 一个模块，对外提供 **6 个查询接口 + 2 个写入接口**：

- 🟢 **查询**（只读，已实测）：车站 2 个、车次 3 个、余票 1 个
- 🟡 **写入**（已实现，**尚未运行过**）：`POST /api/order/orders`（下单 + 扣库存）、`POST /api/order/orders/{orderNo}/pay`（模拟支付）

> 🔴 **为什么阶段 5 是"待验证"而不是"已完成"**：写代码期间本机 MySQL 的数据目录
> 被公司 DLP 透明加密（`ibdata1` 等 4 个无扩展名文件被套上 `%TSD-Header-###%` 头），
> 实例起不来。**阶段 5 的全部验证都依赖数据库，因此一条都还没跑过。**
> 本仓库的硬规则是「禁止编造数字」，所以**阶段 5 不提供任何性能或计数数字**。

| 阶段 | 状态 |
| --- | --- |
| 0 需求与架构设计 / 1 环境搭建 / 2 数据库设计 / 3 项目初始化 | ✅ 已完成 |
| 4 核心业务（单体）—— 查询接口全部走通 | ✅ 已完成 |
| **5 购票 + MySQL 库存（方案 A）** | 🟡 **代码与测试已写完，待运行验证** |
| 6 压测 v1 | ⏭️ 下一步 |
| 7~11（Redis、微服务拆分、MQ、复盘） | ⬜ 未开始 |

**完整状态（含已完成功能、部分完成、已知技术债、待拍板事项）见 → [开发状态](docs/status/development-status.md)**

---

## 启动方式

### 1. 启动中间件

```cmd
D:\dev_tools\start_all.bat
```

MySQL 必须先起来，否则服务能启动但**第一次调接口会 500**（HikariCP 懒加载）。当前只有 MySQL 是必需的。

### 2. 首次准备数据库（只需一次）

```bash
MYSQL="D:/dev_tools/mysql-8.4.8-winx64/bin/mysql.exe"
for f in 00_init 01_rail_user 02_rail_train 03_rail_inventory 04_rail_order \
         10_seed_train 11_seed_inventory; do
  "$MYSQL" -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 < sql/$f.sql
done
```

> ⚠️ **`10_seed_train` 和 `11_seed_inventory` 的顺序不能反，而且必须成对重跑。**
> `10` 会重置 `t_train` 的自增 id，而 `11` 写库存时用 `train_id` 引用车次
> ——只重跑 `10` 不重跑 `11`，库存行就会挂到别的车次上，**接口不报错，只是数据是错的**。
>
> `11` 的日期用 `CURDATE() + 1/2/3` 生成（不写死），所以种子数据不会过期。

> ⚠️ `--default-character-set=utf8mb4` 不能省。本机 mysql 客户端默认按 GBK 解释字节，而 `.sql` 文件是 UTF-8 的——不加这个参数，中文会**以乱码形式存进数据库且不报任何错**。

### 3. 启动服务

```bash
./mvnw -pl rail-train-service spring-boot:run
```

> ⚠️ `-pl rail-train-service` 不能省。根 POM 是 `packaging=pom` 的聚合模块，直接在根模块执行 `spring-boot:run` 会报「找不到主类」。

IntelliJ 里直接 Run `RailTrainApplication` 即可，但要在 Run Configuration 的 **VM options** 里手动加 `-Dfile.encoding=UTF-8`——根 POM 里配的 `jvmArguments` 只对 `mvn spring-boot:run` 生效。

### 4. 验证（别跳过）

```bash
# --- 阶段 3 的接口 ---
curl http://127.0.0.1:8082/api/train/stations                # 期望 14 条车站
curl -i http://127.0.0.1:8082/api/train/stations/VNP          # 期望 200 + 北京南

# --- 阶段 4 的接口（都要看到真实数据，不是"没报错"）---
curl "http://127.0.0.1:8082/api/train/trains?page=1&size=2"   # 期望 total=3、中文站名
curl "http://127.0.0.1:8082/api/train/trains/G1/stations"     # 期望 4 站
curl "http://127.0.0.1:8082/api/train/trains/search?from=VNP&to=AOH"   # 期望 G1 + G3
curl "http://127.0.0.1:8082/api/train/trains/search?from=JNK&to=NJH"   # 期望 G1 + G3（中途上车）
D=$(date -d "+1 day" +%F)
curl "http://127.0.0.1:8082/api/inventory/seats?trainNo=G1&date=$D"    # 期望 3 个席别
```

**两条必须看状态码的检查**（`?size=0` 是唯一能证明参数校验在生效的方式，
依赖缺失是**静默失效**的）：

```bash
curl -sS -o /dev/null -w "%{http_code}\n" \
     "http://127.0.0.1:8082/api/train/trains?size=0"         # 期望 400，不是 200
curl -sS -o /dev/null -w "%{http_code}\n" \
     "http://127.0.0.1:8082/api/train/trains/NOPE/stations"  # 期望 404
```

### 5. 阶段 5 的写入接口怎么验（🔴 **待验证，一条都没跑过**）

⚠️ **阶段 5 不需要任何 DDL 变更，也不新增种子数据。**
（所以**不要去找 `12_seed_order.sql` 这种文件 —— 它不存在**。
`sql/12_verify_stage5_order.sql` 是**只读的校验脚本**，不是种子脚本。）

**但这些验证需要一个 fixture：当天那一行 `t_seat_inventory` 必须存在**，
否则下单会全部返回 409「尚未放票」—— 那不是 bug，是没造数据。
造数据与清理的完整 SQL 抄 [sql/12_verify_stage5_order.sql](sql/12_verify_stage5_order.sql) 文末那一段。

```bash
# ① 下单：200 + 订单号（前提是 fixture 已造好）
curl -sS -X POST http://127.0.0.1:8082/api/order/orders \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":1,\"trainNo\":\"G1\",\"travelDate\":\"$(date -d '+60 day' +%F)\",\"seatType\":1}"

# ② 三个 409 都要看到，且 message 不同：尚未放票 / 已售罄 / 请勿重复购票
#    再来一次同样的请求 → 期望 409「请勿重复购票」

# ③ 支付：两次，第二次 idempotent=true
curl -sS -X POST http://127.0.0.1:8082/api/order/orders/<orderNo>/pay
```

**三条必须看状态码的检查**（阶段 5 新增的都带 🔴）：

```bash
# @Valid 真的生效（漏写是静默失效：返回 500 说明注解没了，返回 200 说明依赖没了）
curl -sS -w "\n%{http_code}\n" -X POST http://127.0.0.1:8082/api/order/orders \
     -H 'Content-Type: application/json' -d '{}'          # 期望 400，且 details 非空 🔴

# 车次不存在是 404，不是 409（判据：东西不存在 vs 存在但规则不允许）🔴
curl -sS -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:8082/api/order/orders \
     -H 'Content-Type: application/json' \
     -d "{\"userId\":1,\"trainNo\":\"NOPE\",\"travelDate\":\"$(date -d '+1 day' +%F)\",\"seatType\":1}"
```

**并发证明（阶段 5 的完成判据）：**

```bash
./mvnw -pl rail-train-service test -Dtest=OrderServiceIT          # 单线程语义 10 个用例
./mvnw -pl rail-train-service test -Dtest=OrderConcurrencyTest    # 100 线程抢 20 张票

# 跑完必须对账 —— 这三种失败**不会让接口报错**，只能靠 SQL 发现
"D:/dev_tools/mysql-8.4.8-winx64/bin/mysql.exe" -h 127.0.0.1 -u rail -prail123456 \
  --default-character-set=utf8mb4 < sql/12_verify_stage5_order.sql   # ①~⑤ 全部 0 行
```

⭐ **"测试全绿"和"账对得上"是两件事。** 超卖、**少卖**（扣了库存但订单没写成功）、
重复购票，这三种失败**都不会让接口返回错误码** —— 它们只能靠对账 SQL 发现。
这是阶段 5 相对前四个阶段最重要的变化：

> **阶段 0~4 全只读，"接口返回 200"就等于"数据没问题"。**
> **阶段 5 起，"通过了但数字是错的"第一次成为可能的失败。**

### 6. JMeter：冒烟 + 并发（两个脚本，别只跑一个）

```bash
export JAVA_HOME="D:/jdk-17.0.20.1"; export PATH="/d/jdk-17.0.20.1/bin:$PATH"

# 冒烟（阶段 3~5 的接口各打一遍，顺序、1 线程）
# 🟡 阶段 5 把请求数从 6 加到 8，所以「Err: 0」这个判据是**这次改完之后**的期望值，尚未实测
/d/dev_tools/apache-jmeter-5.6.3/bin/jmeter.bat -n \
  -t scripts/perf/stage4-smoke.jmx -l /tmp/stage4.jtl      # 期望 Err: 0 (0.00%)

# 并发（50 线程抢 20 张票）—— 判据以数据库为准，不看 JMeter 的计数
# 🔴 需要先造 fixture（日期是 今天+70 天），见脚本头部
/d/dev_tools/apache-jmeter-5.6.3/bin/jmeter.bat -n \
  -t scripts/perf/stage5-order.jmx -l /tmp/stage5.jtl      # 期望 Err: 0 (0.00%)
```

> ⚠️ `jmeter.bat` 只认 PATH 上的 `java`，不读 `JAVA_HOME`。
> **新开的终端本来就有 `java`**（本机 `JAVA_HOME` 与 PATH 都配好了）；
> 上面那句 `export PATH` 是给 **AI 工具的 shell** 准备的——它继承的是改环境变量之前的旧进程环境。
>
> ⚠️ **两个脚本都含"故意期望 4xx"的采样器**，它们已用 JSR223 断言显式标记成成功，
> 所以**判读规则是「错误率 0% 才算通过」**，而不是"错误率越低越好"。
> 冒烟脚本里是 400/404；并发脚本里**连 409 也是成功**（没抢到票是设计内的结果）。
> 原因见各自脚本头部注释。

> ⚠️ **并发脚本不记录任何时延数字** —— 当前 mapper 层 SQL 日志还开在 debug，
> 此时测出来的响应时间没有意义。时延基准线是阶段 6 的事。

错误响应的形状与实测观测到的状态码 → [接口错误约定](docs/api/error-codes.md)。

> ⚠️ 停止服务不要只关掉 Maven。`spring-boot:run` 会 fork 一个子 JVM，杀掉 Maven 后它**仍占着 8082 端口**：`netstat -ano | grep ":8082.*LISTENING"` 拿到 PID，再 `taskkill //F //PID <PID>`。

---

## 微服务列表（目标形态，方案 C）

当前仓库只有 `rail-train-service` 一个模块；下表的另外四个**都还不存在**，属于阶段 8 的拆分目标。

| 服务 | 端口 | 职责 |
| --- | --- | --- |
| `rail-gateway` | 8080 | 统一入口、JWT 校验、限流 |
| `rail-user-service` | 8081 | 注册、登录、用户查询 |
| `rail-train-service` | 8082 | 车次、站点、经停站、票价 ← **当前唯一已实现的模块** |
| `rail-inventory-service` | 8083 | 库存权威数据、预热、对账、回补落库、余票查询 |
| `rail-order-service` | 8084 | 下单、秒杀、订单状态机、模拟支付 |

**两条硬约束**（详见 [系统架构 §2](docs/02-architecture.md)）：

- 🔒 Redis 是**无主的共享预扣层**，`t_seat_inventory`（MySQL）才是权威数据。
- ⛔ 秒杀热路径上**禁止 order-service 同步 Feign 调用 inventory-service**——order-service 直连 Redis。多一跳 RPC，会把异步化省下的时间原样还回去。

---

## 技术栈

**「已实测」= 在本机实际跑通过；「未接入」= 选型已定但还没进代码。**

| 分类 | 组件 | 版本 | 状态 |
| --- | --- | --- | --- |
| 语言 | Java | 17.0.20.1 | 🟢 已实测 |
| 框架 | Spring Boot | 3.5.9（实测建议 3.5.16） | 🟢 已实测 |
| ORM | MyBatis-Plus | 3.5.17 | 🟢 已实测 |
| 数据库 | MySQL | 8.4.8 | 🟢 已实测（建表 + 约束验证） |
| 缓存 | Redis | 8.10.1（第三方 Windows 移植版） | 🟢 基础命令与 Lua 已实测，**代码未接入** |
| 注册中心 | Nacos Server | 3.2.3 | 🟢 可启动，**代码未接入**；端口已改为 8888/8889 |
| 微服务框架 | Spring Cloud / Alibaba | 2025.0.x / 2025.0.0.0 | 🟡 版本已解析零冲突，未接入 |
| 限流熔断 | Sentinel | 1.8.9 | 🔴 是否启用未定 |
| 消息队列 | RocketMQ | 5.5.1 | 🟢 已装并跑通，🔴 **选型未拍板**，推迟到阶段 9 |
| 构建 | Maven | 3.9.16（`mvnw` wrapper） | 🟢 已实测 |
| 测试 | JUnit 5 + AssertJ | 随 Boot 3.5.9 | 🟡 **阶段 5 才开始用**，2 个测试类已写好，**尚未运行** |
| 压测 | JMeter | 5.6.3 | 🟢 阶段 4 冒烟脚本（**6 请求 / Err 0**）已实测；🟡 该脚本扩到 8 请求、以及新增的并发脚本，**都尚未运行** |
| 容器化 | Docker | — | ❌ 公司电脑无法安装，**已放弃此路线** |

> Docker 不可用是本项目的一等约束：MySQL、Redis、Nacos 全部以 Windows 原生方式安装，由此带来若干平台特有的坑（见 [技术栈 §6](docs/04-technology.md)）。

---

## 核心业务流程

**一次购票（🟡 方案 A：阶段 5 已实现，是我们现在的真实路径）**

```
用户下单 → POST /api/order/orders
        → 开事务，在同一个 MySQL 实例的三个库上依次做五件事：
             ① 读库存行（取 price，并区分"未放票"与"售罄"）
             ② UPDATE 条件扣减  ← 判断与扣减在这一条 SQL 里原子完成
             ③ 写订单头   ④ 写票（撞唯一索引 → 409）
             ⑤ 写库存流水（change_type=2）
        → 提交 → 同步返回订单号
        → 支付 → 条件 UPDATE 订单状态 0→1
        → 超时未付 → ⏳ 未实现（阶段 7）
```

⭐ **① 不是"先查后改"**：它的结果**从不作为扣减的充分条件** ——
即使 ① 读到有票，也照样执行 ②，由 ② 的受影响行数说了算。
① 只用来取价格、并给"0 行"分类（是"没放票"还是"售罄"）。
完整论证见 [ADR-004](docs/decisions/ADR-004-stock-deduction-mysql-cas.md)。

**一次购票（⏳ 目标形态：阶段 7/9 的方案 B / C）**

```
用户下单 → 校验（车次/余票/限购）→ Redis Lua 原子预扣库存
        → 订单落库（待支付）→ 立即返回 → 异步写 MySQL 库存
        → 支付 → 订单状态 0→1
        → 超时未付 → 状态 0→2 并回补库存
```

**订单状态机（以真实 DDL 为准）**

```
0 待支付 ──支付──▶ 1 已支付
   │
   └──超时/取消──▶ 2 已取消
```

状态迁移**一律用条件 UPDATE（CAS）**，不用分布式锁。注意：**没有 FAILED 状态**——早期设计稿里那个状态在真实建表脚本中不存在。

⚠️ **"受影响 0 行就直接返回成功"是错的**（本仓库早期文档和 DDL 注释都这么写过，已修正）：

- 0 行 + 订单**已支付** → 200（真幂等）
- 0 行 + 订单**已取消** → **409**（票已回补，可能已卖给别人；回"成功"等于告诉用户他有一张不存在的票）

判据：「**已经是我想要的状态**」可以报成功；「**永远不可能变成我想要的状态**」必须报错。
详见 [业务流 §二](docs/03-business-flow.md)。

**秒杀链路的关键取舍**：异步化省下的时间不能被 RPC 吃掉，所以热路径只有「Redis 一次往返 + 一次消息投递」。

详细流程、方案 A/B/C 的对比与验收标准 → [业务流程](docs/03-business-flow.md)、[秒杀与库存](docs/05-seckill.md)

---

## 文档入口

**项目介绍与阅读入口**见 [docs/01-project-guide.md](docs/01-project-guide.md)（**不含开发状态**）。

| 文档 | 回答什么问题 |
| --- | --- |
| [01 项目阅读指南](docs/01-project-guide.md) | 这是什么项目、整体架构、目录结构、核心流程、技术全景 |
| [02 系统架构](docs/02-architecture.md) | 三套候选架构怎么选、为什么拆 5 个服务、服务挂了会怎样 |
| [03 业务流程](docs/03-business-flow.md) | 功能边界、订单状态机、验收标准 |
| [04 技术栈与选型](docs/04-technology.md) | 版本矩阵、端口规划、环境搭建、本机特有的三个坑 |
| [05 秒杀与库存](docs/05-seckill.md) | 不超卖的防线（阶段 5 已落地两条）、Lua 脚本、幂等、MQ 异常路径矩阵、**实测状态表（待回填）** |
| [06 数据库设计](docs/06-database.md) | 表设计决策、不超卖的数据库侧防线、跨 schema 写为什么能用本地事务 |
| [接口错误约定](docs/api/error-codes.md) | **实测观测到**的状态码与错误体形状、409 的四个分支、为什么还没有错误码枚举 |
| [07 风险与已知缺陷](docs/07-risks.md) | 会在哪里出问题、本设计已知没解决的地方 |
| [开发状态](docs/status/development-status.md) | **做到哪了**、技术债、下一步、阶段 5 的验证清单 |
| [ADR-003 服务拆分粒度](docs/decisions/ADR-003-service-granularity.md) | 为什么拆 5 个服务、拆完的代价 |
| [ADR-004 库存扣减方案](docs/decisions/ADR-004-stock-deduction-mysql-cas.md) | 为什么用条件 UPDATE、为什么不用分布式锁、**什么时候才该换成 Redis** |
| [sql/12 阶段 5 对账脚本](sql/12_verify_stage5_order.sql) | 并发跑完之后，怎么证明**没有超卖、没有少卖、没有重复** |
| [真实踩坑记录](docs/troubleshooting/README.md) | **实际发生过**的故障，每篇都有原始日志 |

### 标记约定

文档中的结论一律用证据等级标注，**请严格区分**：

| 标记 | 含义 |
| --- | --- |
| 🟢 已实测 | 在本机实际执行命令得到的结论，可直接依赖 |
| 🟡 **已就位，未验证** | ① 检索得到、附有出处但**尚未在本机验证**；② **代码/脚本/测试已写好，但从未运行过**。共同判据：**不能当成事实依赖** |
| 🔵 架构判断 | 设计意见，含取舍理由，可以质疑 |
| 🔴 待拍板 / 待处理 | 必须由开发者本人决定或必须修的事项 |
| ⏳ 计划中 | 还没开始做，或明确排在后面的阶段 |

**本项目不包含任何未经实测的性能数字。** 所有 🟡 都必须在本机验证后才能转为 🟢。

> ⚠️ **阶段 5 让 🟡 有了第二种用法**（"我自己写完了，但还没试"）。
> 它比"别人这么说"更接近事实，但**依然不是证据**，而且更容易被误读成"已经能用了"。
> 阶段 5 的代码 / 测试 / JMeter 脚本 / SQL 校验**全部是 🟡** ——
> 写它们的时候本机 MySQL 起不来，**一次都没跑过**。
> 详见 [阅读指南 §十 文档约定](docs/01-project-guide.md)。

---

## AI 辅助开发

这个项目刻意采用 AI 辅助开发流程，并把它作为能力的一部分。

| 环节 | AI 的角色 | 人的角色 |
| --- | --- | --- |
| 需求分析 | 拆解需求、发现规格缺口 | 确认范围边界 |
| 架构方案讨论 | 并行产出候选方案、核实版本兼容性、**对抗性验证** | **做最终决策** |
| 代码 / 测试 | 按设计生成实现，设计并发与异常用例 | 逐段理解、拒绝不理解的代码，执行并确认真实结果 |
| Debug / 性能 | 辅助定位根因、分析压测数据的可能瓶颈 | 复现问题、**执行压测、确认数据真实性** |

> **明确声明**：关键技术决策、代码验证、性能测试和最终结果由开发者本人确认。

**一次真实的协作失败（已记录）**：阶段 0 的首次多代理工作流中，综合与修正环节被安全分类器拦截，导致设计规格为空，而后续 8 个文档代理在**没有规格**的情况下各自生成内容，产出 **3 套互不兼容的服务拆分方案**和**互相矛盾的 MQ 选型结论**。这些草稿已于 2026-09-29 的文档整理中全部删除（原始文件仍在 git 历史里，恢复方式与内容比对见 [开发状态 §6](docs/status/development-status.md)）。

**教训**：AI 生成的架构文档必须做**跨文档一致性检查**，不能把「生成成功」当作「内容正确」。
