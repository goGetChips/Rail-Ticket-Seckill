# Rail-Ticket-Seckill

> 一个用于**深入理解分布式高并发**的铁路购票/秒杀教学项目。
> 目标不是功能齐全，而是能对每一个设计决策回答"**为什么这么做**"和"**为什么不用 X**"。

---

## 当前状态（2026-09-17）

**阶段 0～3 已完成。仓库现在有可以真正跑起来、并真的读到了数据库的服务。**

| 内容 | 状态 |
| --- | --- |
| 阶段 0 架构设计 | ✅ → [docs/architecture/phase0-design.md](docs/architecture/phase0-design.md) |
| 阶段 1 环境搭建 | ✅ JDK 17 / Maven 3.9.16 / MySQL 8.4.8 / Redis 8.10.1 / Nacos 3.2.3 全部就绪 |
| 阶段 2 数据库设计 | ✅ → [docs/database/schema-design.md](docs/database/schema-design.md)，建表脚本在 [sql/](sql/) |
| 阶段 3 项目初始化 | ✅ 多模块骨架 + `rail-train-service` 可启动、可查库（详见下方"如何运行"） |
| 压测脚本、MQ | ❌ / ⏸️ 尚未编写（阶段 6 起 / 选型推迟到阶段 9） |

### 已完成的关键验证（实测，非推断）

| 验证 | 证据 |
| --- | --- |
| **服务能起来并读到真实数据**（阶段 3） | `curl http://127.0.0.1:8082/api/train/stations/VNP` → `200` + `{"stationCode":"VNP","stationName":"北京南",...}`；查不到的车站返回 `404`；列表接口返回 14 行，日志里能看到真实执行的 SQL |
| **JVM 的 `file.encoding` 真的是 UTF-8**（阶段 3） | `jcmd <pid> VM.system_properties` → `file.encoding=UTF-8`（不加参数时本机默认是 GBK） |
| Redis 第三方移植版的 **Lua 原子性** | [scripts/env/verify-redis.sh](scripts/env/verify-redis.sh) —— 100 张票 vs 1000 并发请求 → 余票恰好 0、恰好 100 人成功；同一用户 1000 并发 → 恰好扣 1 张 |
| MySQL **唯一索引 / CHECK 约束真的拦得住** | [sql/99_verify.sql](sql/99_verify.sql) —— 故意制造重复购票、负库存、超卖，数据库**全部拒绝** |
| 条件 UPDATE 的 **CAS 行为** | 同上 —— 有余票时受影响 1 行、已售罄时受影响 0 行 |

### ⚠️ 本机环境的一个已知陷阱（已踩到，已修复）

公司 DLP（亚信安全）会**透明加密「没有扩展名」的文件**：编辑器里看是明文，但 `git.exe` 读到的是磁盘原始密文，会把一坨二进制当成文件内容提交，**全程不报任何错**。

`.gitignore` 恰好是整个工程里唯一一个「必须没有扩展名」的文件——第一次提交时，忽略规则**一条都没生效**，`git add -A` 暂存了 **54** 个文件。

**后果不是「多提交了几个文件」**：`.gitignore` 里的 `**/application-local.yml` 一旦失效，**本机真实数据库密码会被正常提交**，而且它在上游仓库里是二进制乱码，review 时没人会点开看。

- 完整排查过程（含控制变量实验）：[docs/troubleshooting/README.md 案例 1](docs/troubleshooting/README.md)
- 提交前守卫：`bash scripts/env/check-dlp-encryption.sh`
- ⚠️ **无扩展名的文件禁止用编辑器保存**（保存动作本身就会触发加密），必须用 bash 重定向生成

> ⚠️ `docs/_drafts-unverified/` 目录下存放的是**一次性 AI 生成的未验证草稿**。
> 生成时设计规格为空，其中包含**三套互不兼容的服务拆分方案**和**互相矛盾的 MQ 选型结论**，**不得作为设计依据**。
> 保留它们仅为了在有需要时回收其中有价值的教学内容。

---

## 如何运行（阶段 3 现状）

### 1. 启动中间件

MySQL 必须先起来，否则服务能启动但**第一次调接口会 500**（HikariCP 懒加载，见 [案例 2](docs/troubleshooting/README.md)）。

```cmd
D:\dev_tools\start_all.bat
```

它会拉起 MySQL、Nacos、Redis 三个窗口。阶段 3 只需要 **MySQL**，另外两个现在用不到，可以先关掉。

### 2. 首次准备数据库（只需一次）

```bash
MYSQL="D:/dev_tools/mysql-8.4.8-winx64/bin/mysql.exe"
"$MYSQL" -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 < sql/00_init.sql
"$MYSQL" -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 < sql/01_rail_user.sql
"$MYSQL" -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 < sql/02_rail_train.sql
"$MYSQL" -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 < sql/03_rail_inventory.sql
"$MYSQL" -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 < sql/04_rail_order.sql
"$MYSQL" -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 < sql/10_seed_train.sql
```

> ⚠️ **`--default-character-set=utf8mb4` 不能省。** 本机 mysql 客户端默认按 GBK 解释字节，
> 而 `.sql` 文件是 UTF-8 的。不加这个参数，中文会以**乱码形式存进数据库，且不报任何错**——
> 属于"看起来成功、结果不对"那类问题，排查成本很高。

### 3. 启动服务

```bash
# 在项目根目录
./mvnw -pl rail-train-service spring-boot:run
```

> ⚠️ **`-pl rail-train-service` 不能省。** 根 POM 是 `packaging=pom` 的聚合模块，
> 直接 `./mvnw spring-boot:run` 会在根模块上执行，报"找不到主类"。
> 将来模块间有了依赖，还要再加 `-am`（连带构建被依赖的模块）。

**在 IntelliJ 里运行**：直接 Run `RailTrainApplication` 即可，但要在
Run Configuration 的 **VM options** 里手动加上：

```
-Dfile.encoding=UTF-8
```

根 POM 里配的 `jvmArguments` 只对 `mvn spring-boot:run` 生效，**IntelliJ 走的是它自己的启动器，不会读它**。
不加的后果是本机 JVM 默认 GBK，将来读文件时中文乱码。

### 4. 验证（**别跳过这一步**）

```bash
curl -i http://127.0.0.1:8082/api/train/stations/VNP
# 期望 200 + {"id":1,"stationCode":"VNP","stationName":"北京南","cityName":"北京",...}

curl -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8082/api/train/stations/NOPE
# 期望 404

curl http://127.0.0.1:8082/api/train/stations
# 期望 14 条车站
```

> ⚠️ **停止服务不要只关掉 Maven。** `mvn spring-boot:run` 会 fork 出一个子 JVM，
> 杀掉 Maven 进程后**子 JVM 仍占着 8082 端口**，下次启动会报
> `Port 8082 was already in use`。要一并结束那个 `java.exe`：
>
> ```bash
> netstat -ano | grep ":8082.*LISTENING"     # 拿到 PID
> taskkill //F //PID <PID>
> ```

---

## 先读这个

**[docs/architecture/phase0-design.md](docs/architecture/phase0-design.md)** —— 阶段 0 的唯一权威设计文档。

文档中所有结论都用证据等级标注，**请严格区分**：

| 标记 | 含义 |
| --- | --- |
| 🟢 已实测 | 在你本机实际执行命令得到的结论，可直接依赖 |
| 🟡 调研结论 | 联网检索得到、附有出处，但**尚未在本机验证**，落地前必须实证 |
| 🔵 架构判断 | 设计意见，含取舍理由，可以质疑 |
| 🔴 待你拍板 | 必须你本人决定的事项 |

**当前有 6 项 🔴 决策等待拍板**，见文档 §15。

---

## 技术栈（目标，非现状）

| 分类 | 组件 | 版本 | 状态 |
| --- | --- | --- | --- |
| 语言 | Java | 17.0.20.1 | 🟢 已实测（`D:\jdk-17.0.20.1`） |
| 框架 | Spring Boot | 3.5.9 | 🟢 已实测（现有骨架） |
| 框架 | Spring Cloud / Spring Cloud Alibaba | 2025.0.x / 2025.0.0.0 | 🟡 待实证 |
| 注册中心/配置 | Nacos Server | 3.x | 🟡 待实证 |
| 数据库 | MySQL | 8.4.8 | 🟢 已实测（含 JDBC 连通、时区配置，见 [案例 2](docs/troubleshooting/README.md)） |
| 缓存 | Redis | 方案待定 | 🔴 见 §15 决策 1 |
| 消息队列 | RocketMQ（推荐）/ RabbitMQ | 待定 | 🔴 见 §15 决策 2 |
| ORM | MyBatis-Plus | 3.5.17 | 🟢 已实测（联网核实为 Central 最新版，且已跑通查询） |
| 限流熔断 | Sentinel | 1.8.x | 🔴 见 §15 决策 6 |
| 构建 | Maven | `mvnw` wrapper | 🟢 已实测（无独立 CLI） |
| 压测 | JMeter | — | ❌ 待安装 |
| 容器化 | Docker | — | ❌ **公司电脑无法安装，已放弃此路线** |

---

## 架构流向（目标形态，方案 C）

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
  │      order 消费者                       train 消费者
  │      写 t_order                  写 t_seat_inventory
  │              │                               │
  └──────▶ rail-train-service :8082 ◀────────────┘
                  ▲                （余票权威数据）
                  │
           rail-user-service :8081
```

**关键设计**：Redis 库存是**无主的共享预扣层**，MySQL 的 `t_seat_inventory` 才是权威数据。
秒杀热路径上**不做同步的跨服务 HTTP 调用**——唯一的同步远程调用是 Redis。

详见 [阶段 0 设计文档](docs/architecture/phase0-design.md)。

---

## 开发路线图

| 阶段 | 名称 | 完成判据 |
| --- | --- | --- |
| 0 | 需求与架构设计 | ✅ 已完成 → [设计文档](docs/architecture/phase0-design.md) |
| 1 | 环境搭建 | ✅ 已完成，**每个中间件都有实测验证证据**（见上方表格） |
| 2 | 数据库设计 | ✅ 已完成：9 张表建好，**约束行为已用违规数据实测** |
| 3 | 项目初始化 | ✅ 已完成：`rail-train-service` 启动后 `curl` 到 `t_station` 的**真实数据**（14 行），404 分支也验证过 |
| 4 | 核心业务（单体） | 查询接口全部走通 |
| 5 | 购票 + MySQL 库存（方案 A） | **并发测试证明不超卖** |
| 6 | 压测 v1 | 有真实瓶颈数据 |
| 7 | 引入 Redis（方案 B） | 与阶段 6 对比有量化提升 |
| 8 | 微服务拆分 | 跨服务调用通，服务下线可感知 |
| 9 | 引入 MQ（方案 C） | 9 条异常路径逐条实测 |
| 10 | 压测 v2 + 优化 | 完整的优化前后对比 |
| 11 | 复盘 + 面试化 | 能不看文档讲 30 分钟 |

> **为什么方案 A → B → C 要分阶段走，而不是直接写最终形态**：
> 如果直接写方案 C，你永远无法回答面试官那句最致命的追问——"**如果不加 Redis 会怎么样？**"
> 只有亲手在阶段 6 压出 `Lock wait timeout` 的真实数字，那个答案才是你自己的。

---

## AI-Assisted Development

这个项目刻意采用 AI 辅助开发流程，并把它作为能力的一部分。

| 环节 | AI 的角色 | 人的角色 |
| --- | --- | --- |
| 需求分析 | 拆解需求、发现规格缺口（如"有出发/到达站需求但没有经停站表"） | 确认范围边界 |
| 架构方案讨论 | 并行产出多套候选方案、联网核实版本兼容性、**对抗性验证**（专门尝试推翻自己的设计） | **做最终决策** |
| 代码生成 | 按设计生成实现与教学注释 | 逐段理解、拒绝不理解的代码 |
| Debug | 辅助定位与解释根因 | 复现问题、验证修复 |
| 测试设计 | 设计并发测试与异常路径用例 | 执行并确认结果 |
| Code Review | 审查一致性、并发正确性 | 决定是否采纳 |
| 性能分析 | 分析压测数据的可能瓶颈 | **执行压测、确认数据真实性** |
| 文档整理 | 结构化文档、沉淀 ADR | 审核准确性 |

> **明确声明**：**关键技术决策、代码验证、性能测试和最终结果由开发者本人确认。**
> 本仓库不包含任何未经实测的性能数字。所有 🟡 标记的结论都必须在本机验证后才能转为 🟢。

### 一次真实的 AI 协作失败（已记录）

阶段 0 的首次多代理工作流**部分失败**：综合与修正环节被安全分类器拦截，导致最终设计规格为空，
而后续 8 个文档代理在**没有规格**的情况下各自生成内容，产出了 **3 套互不兼容的服务拆分**和**互相矛盾的 MQ 选型**。

处理方式：全部隔离至 `docs/_drafts-unverified/`，由人工重新编写权威文档。
**教训**：AI 生成的架构文档必须做**跨文档一致性检查**，且不能想当然地把"生成成功"当作"内容正确"。

---

## 文档目录约定

| 目录 | 放什么 |
| --- | --- |
| `docs/architecture/` | 系统架构、服务拆分、高并发风险清单 |
| `docs/database/` | 表设计、索引理由、校验 SQL |
| `docs/api/` | 接口定义、错误码规范 |
| `docs/decisions/` | **ADR（架构决策记录）**，格式：背景 / 决策 / 备选对比 / 理由 / 代价 / 什么条件下失效 |
| `docs/troubleshooting/` | **真实问题记录**（当前 2 例），必须含真实日志，禁止编造 |
| `docs/performance/` | 压测计划与**实测报告**，禁止编造数据 |
| `docs/interview/` | 面试问题库与追问链 |
