# 技术栈与环境

**读者**：要装环境、升级依赖、或被"本机跑得好好的、换台机器就出问题"困扰的人。
**解决什么问题**：用了什么技术、什么版本、怎么装、以及**本机平台特有的坑**。
**不包含**：为什么这么设计（→ [02](02-architecture.md)）、业务流程（→ [03](03-business-flow.md)）。

---

## 一、技术栈现状

| 分类 | 组件 | 版本 | 状态 |
| --- | --- | --- | --- |
| 语言 | Java | 17.0.20.1 | 🟢 已实测（`D:\jdk-17.0.20.1`） |
| 构建 | Maven | `mvnw` wrapper（3.9.16） | 🟢 已实测，本机无独立 CLI |
| 框架 | Spring Boot | 3.5.9 | 🟢 已实测（现有骨架） |
| ORM | MyBatis-Plus | 3.5.17 | 🟢 已实测（已跑通查询） |
| 数据库 | MySQL | 8.4.8 | 🟢 已实测（含 JDBC 连通、时区配置） |
| 缓存 | Redis | 8.10.1（`zkteco-home/redis-windows` 第三方移植版） | 🟢 已实测 |
| 注册中心/配置 | Nacos Server | 3.2.3 standalone | 🟢 已实测启动，**端口已改，见 §3** |
| 微服务框架 | Spring Cloud / Spring Cloud Alibaba | 2025.0.x / 2025.0.0.0 | 🟡 依赖解析已实测，**尚未接入代码** |
| 限流熔断 | Sentinel | 1.8.9 | 🟡 由 SCA 固定引入，**尚未接入** |
| 消息队列 | RocketMQ | 5.5.1 | 🟢 已安装并**成功启动过**，**选型仍未拍板**（见 §7.3） |
| 压测 | JMeter | 5.6.3 | 🟢 已安装，JDK 17 下可运行（见 §4.4） |
| 容器化 | Docker | — | ❌ **公司电脑无法安装，已放弃此路线** |

> **"目标 vs 现状"必须分清**：上表里 🟡 和 🔴 的组件都**还没有出现在代码里**。根 POM 的 `<dependencyManagement>` 现在只有 MyBatis-Plus，Spring Cloud / SCA 的 BOM 等到阶段 8 才引入——现在加了，它们会出现在依赖树里但不做任何事。

---

## 二、版本矩阵（🟢 已用真实依赖解析验证）

> **验证方法**（2026-09-15）：在临时目录用 IntelliJ 内置 Maven 3 + JDK 17，构造一个引用全部核心依赖的 POM，执行 `mvn dependency:tree`，**EXIT=0，无版本冲突**。

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17.0.20.1** | 🟢 `D:\jdk-17.0.20.1` |
| Spring Boot | **3.5.16**（建议从现有 3.5.9 升级） | 🟢 解析成功；传递引入 Spring Framework 6.2.19 |
| Spring Cloud | **2025.0.3** | 🟢 gateway 4.3.5、openfeign 4.3.3 |
| Spring Cloud Alibaba | **2025.0.0.0** | 🟢 解析成功 |
| Nacos Client | **3.0.3** | 🟢 由 SCA 2025.0.0.0 固定引入 |
| Sentinel | **1.8.9** | 🟢 由 SCA 固定引入（含 `sentinel-spring-webflux-adapter`） |
| MyBatis-Plus | **3.5.17** | 🟢 `mybatis-plus-jsqlparser` 3.5.17 必须**显式**引入 |
| MySQL 驱动 | **9.7.0** | 🟢 由 Boot 3.5.16 管理版本（当前 3.5.9 对应 9.5.0） |
| Nacos Server | 3.x | 🟡 客户端锁 3.0.3，**服务端不要盲目上最新** |
| springdoc-openapi | 2.8.x | 🟡 待验证（Boot 3.x 必须用 2.8.x 线） |

### 2.1 这次验证解决了什么

**最初的判断是错的。** 项目开始时担心"Spring Cloud Alibaba 不支持 Spring Boot 3.5"，并准备了两条退路（降级 Boot 或弃用 SCA 组件）。

**实测结论：SCA 2025.0.0.0 与 Boot 3.5.16 完全兼容，依赖解析零冲突。** 退路不需要了。

**判断兼容性的方法**（比"版本号看起来像不像"可靠得多）：

| BOM | 其 parent `spring-cloud-dependencies-parent` 版本 | 结论 |
| --- | --- | --- |
| Spring Cloud 2025.0.3 | **4.3.4** | 2025.0.x 线 |
| Spring Cloud Alibaba 2025.0.0.0 | **4.3.0** | **同在 4.3.x 线 → 与 Spring Cloud 2025.0.x 对齐** ✅ |
| Spring Cloud 2025.1.3 | 5.0.3 | 2025.1.x 线 |
| Spring Cloud Alibaba 2025.1.0.0 | 5.0.0 | 同在 5.0.x 线 → 与 Spring Cloud 2025.1.x 对齐 |

> **判据**：看它们的 `spring-cloud-dependencies-parent` 父版本是否在同一条 minor 线上。SCA 的版本号（`2025.0.0.0`）和 Spring Cloud 的（`2025.0.3`）**命名规则不同，不能直接对比**。

**⚠️ 一条必须注意的**：SCA 的 BOM **不会帮你 import Spring Cloud BOM**。所以 `spring-cloud-dependencies` 必须在 `<dependencyManagement>` 里**手动显式引入**——只引 SCA 是不够的。

**⚠️ 支持周期**：Boot 3.5.x 与 Spring Cloud 2025.0.x 已于 2026-06-30 EOL。对教学项目不影响使用，但面试时可以主动提一句"我知道这条线已 EOL，新项目应该上 Boot 4 + Spring Cloud 2025.1.x"——**这反而是加分项**。

---

## 三、端口规划

| 端口 | 归属 |
| --- | --- |
| 8080 | `rail-gateway` |
| 8081–8084 | `rail-user / train / inventory / order-service` |
| **8888** | **Nacos 服务端主端口**（Spring 客户端 `server-addr` 填这个） |
| **8889** | **Nacos 控制台** |
| **9888 / 9889** | **Nacos gRPC**（SDK / 集群，= 主端口 +1000 / +1001，由 Nacos 自动推导，**无需配置**） |
| 3306 | MySQL |
| 6379 | Redis |
| 9876 | RocketMQ NameServer |
| 10911 / 10909 / 10912 | RocketMQ Broker（主 / VIP / HA） |
| 8858 | Sentinel Dashboard（阶段 8 引入时需再确认与 8888/8889 无冲突） |

### 3.1 ⚠️ Nacos 端口已从默认值改过（本机现状）

`D:\dev_tools\nacos\conf\application.properties` 中已显式改动：

```properties
nacos.server.main.port=8888     # 默认 8848
nacos.console.port=8889         # 默认 8888
```

**实测证据**（Nacos 启动日志原文）：

```
Nacos GrpcSdkServer Rpc server started at port 9888
Nacos GrpcClusterServer Rpc server started at port 9889
```

**三个必须记住的后果**：

1. **客户端连接串要写 8888**，不是 8848：`spring.cloud.nacos.server-addr=127.0.0.1:8888`。写 8848 会连不上。
2. **控制台在 8889**，不是 8888：`http://127.0.0.1:8889/`。8888 现在是服务端端口，浏览器打开它不是控制台。
3. **gRPC 端口跟着主端口走**（+1000/+1001），所以从 9848/9849 变成了 9888/9889。**如果 9888/9889 被别的程序占用，Nacos 客户端连不上但服务端日志看起来是正常的**——这是排查注册失败时容易漏掉的一环。

> **为什么当初要改**：Nacos 3.x 的控制台默认是 8080，和 `rail-gateway` 以及 Spring Boot 默认端口撞车。改完之后 8888/8889 与 8080–8084 不再冲突。

**⚠️ 端口冲突预警（已实测确认会踩）**：Nacos 3.x 控制台、Sentinel Dashboard、Spring Boot 应用**默认都是 8080**，三者必须错开。

**⚠️ 一个潜在（当前未生效的）冲突**：`application.properties` 里还有一行 `address.server.port=8081`。它只在 `address-server` 模式下生效，而 `address.server.domain` 是注释掉的，**所以现在是惰性配置、无影响**。但 8081 是 `rail-user-service` 的端口——将来若有人打开 address-server 模式，会撞上。

### 3.2 Nacos 鉴权已开启（阶段 8 接入时要记得）

同一份配置里，鉴权被打开了：

```properties
nacos.core.auth.enabled=true
nacos.core.auth.admin.enabled=true
nacos.core.auth.console.enabled=true
nacos.core.auth.server.identity.key=secret
nacos.core.auth.server.identity.value=secret
```

**对当前阶段（0~4）无影响**——还没有任何代码连 Nacos。

**阶段 8 接入时必须处理两件事**：

1. **客户端要带用户名密码**：`spring.cloud.nacos.username` / `password`（默认 `nacos`/`nacos`）。不加会注册失败，而错误信息不一定直指鉴权。
2. **`nacos.core.auth.server.identity.key/value` 都设成了 `secret`**，且 `token.secret.key` 用的是文档里的示例值。**本地教学无所谓，但这属于"默认凭据"**——面试被问到安全时应当主动说明这是本地开发配置，生产必须替换。

> 控制台现在需要登录，默认账号 `nacos` / `nacos`。

---

## 四、环境搭建

> 详细的安装记录、验证脚本与实测输出见 [scripts/env/README.md](../scripts/env/README.md)。

| 顺序 | 组件 | 方案 | 可行性 | 风险 |
| --- | --- | --- | --- | --- |
| 1 | **JDK 17** | 已有 `D:\jdk-17.0.20.1`，配置 `JAVA_HOME` + `PATH` | 已具备 | 无 |
| 2 | **Maven** | 用项目自带 `mvnw`，或 IDEA 内置 Maven | 高 | 无 |
| 3 | **MySQL 8.4** | 官方 MSI 安装包，自带 Configurator | 高 | 低 |
| 4 | **Redis** | `zkteco-home/redis-windows` 第三方移植版 | 中 | 中 |
| 5 | **Nacos 3.x** | 官方 zip，`startup.cmd -m standalone` | 高 | 需配鉴权环境变量、端口冲突 |
| 6 | **Sentinel Dashboard** | `java -jar sentinel-dashboard.jar`（改端口） | 高 | 低 |
| 7 | **RocketMQ 5.5.1** | 官方 zip，`D:\dev_tools\rocketmq-all-5.5.1-bin-release` | 已装并跑通 | **内存占用大，见 §4.2** |
| 8 | **JMeter 5.6.3** | 解压即用，`D:\dev_tools\apache-jmeter-5.6.3` | 已装并跑通 | **只认 PATH 上的 `java`，见 §4.4** |

### 4.1 Redis 的选型与它带来的责任

| 方案 | 状态 |
| --- | --- |
| **`zkteco-home/redis-windows`** | ✅ 已选定，第三方移植版 |
| Memurai Developer | 备选。商业产品免费开发版，正规签名安装包，合规性更好 |
| WSL2 + 原生 Linux Redis | 备选。最干净，但需要 IT 权限 |
| `tporadowski/redis` / `microsoftarchive/redis` | ❌ **已排除**：前者停在 2022 年的 Redis 5.0 分支，后者 2019 已归档。**用 5.0 学现代 Redis 是错的** |

**⚠️ 已知代价（如实记录）**：

- 这是社区移植版，**不是 Redis 官方发布**。出现行为差异时无法向官方求助。
- 版本号（8.x）来自项目自述，**不代表与官方 Redis 8.x 完全等价**。
- 实测 `atomicvar_api=msvc-zkatomic` 为非官方实现，因此**额外做了并发验证**（见第五节）。

**回退路径**：切换 Memurai Developer 或确认能否装 WSL2。

### 4.2 RocketMQ 5.5.1：能跑，但吃内存

**已安装**：`D:\dev_tools\rocketmq-all-5.5.1-bin-release`，启动脚本 `D:\dev_tools\rocketmq_start.bat`（NameServer 9876 + Broker）。历史上**已成功启动并运行过**（`~/logs/rocketmqlogs/broker.log` 有连续心跳与注册日志）。

**JDK 17 兼容性：没问题。** `runserver.cmd` / `runbroker.cmd` 里都有版本分支判断 `if %JAVA_MAJOR_VERSION% lss 17`，JDK 17 会走 `else` 分支，**自动注释掉 JDK 14 已移除的 CMS GC 参数**。这是 5.x 版本才有的适配，4.x 在 JDK 17 上会直接启动失败。

**真正的问题是内存**。脚本里写死的默认值是：

| 进程 | 参数 | 问题 |
| --- | --- | --- |
| NameServer | `-Xms2g -Xmx2g -Xmn1g` | 固定占 2 GB 堆 |
| Broker | `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` | 固定 2 GB，且 `AlwaysPreTouch` 在启动时**就把 2 GB 全部真实提交**，不是按需增长 |
| Broker | `-XX:MaxDirectMemorySize=15g` | 堆外上限 15 GB（这只是上限，不等于实际占用） |

**本机内存实测**：物理内存 16.9 GB，当时**空闲仅 ~1.5 GB**；页面文件 26 GB。所以 RocketMQ 这 4 GB 里有相当一部分会落到页面文件上。

**结论：能跑通，但机器会明显变卡。** 启动顺序建议先验完 MySQL/Redis 再上 RocketMQ；如果只是跑阶段 4~5 的查询接口，**不需要开它**。

**要减压就改这两个文件**（属于环境调优，不影响正确性）：

```
# runserver.cmd  →  -Xms512m -Xmx512m -Xmn256m
# runbroker.cmd  →  -Xms1g   -Xmx1g    并删掉 -XX:+AlwaysPreTouch
```

**⚠️ 一个必须知道的脚本坑**：`rocketmq_start.bat` 第 15 行是

```bat
taskkill /f /im java.exe
```

它会**杀掉机器上所有 `java.exe` 进程**，不只是 RocketMQ 的。所以它会一并干掉：

- **Nacos 服务端**（就是 java.exe，`start_all.bat` 刚起的那个会静默消失）
- **正在运行的 Spring Boot 应用**（`mvn spring-boot:run` fork 出来的子 JVM）
- **JMeter**（如果正在压测——**压测跑到一半被它杀掉，数据就废了**）

`idea64.exe` 是独立进程名，IntelliJ 本身**不会**被杀掉。

**改法**：把 `taskkill /f /im java.exe` 换成按端口精确杀，或干脆删掉这行手工确认。

### 4.3 `~/store` 里有个遗留的 `abort` 文件

`%USERPROFILE%\store\abort` 存在（内容是一个 PID）。RocketMQ 的规则是：**启动时创建 `abort`，正常关闭时删除**。它还留着，说明上一次 Broker **没有正常退出**（与上面那个 `taskkill /f` 的行为吻合）。

**影响**：下次启动时 RocketMQ 会对 commitlog 做一次恢复扫描。当前队列里没有真实业务数据，**后果可忽略**；但如果哪天出现了"消费到一半的消息不见了"，这是第一个该看的地方。

### 4.4 JMeter 5.6.3：只认 PATH 上的 `java`，不认 `JAVA_HOME`

**已实测可运行**（JDK 17）：

```
$ jmeter.bat --version
Apache JMeter 5.6.3
Copyright (c) 1999-2024 The Apache Software Foundation
```

**⚠️ 它的 Java 探测方式和别的工具不一样**。`jmeter.bat` 里那行是：

```bat
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do ...
```

它调的是**裸 `java`**，**完全不读 `JAVA_HOME`**。所以 `JAVA_HOME` 配得再对，只要 `java` 不在 `PATH` 上，就会报：

```
Not able to find Java executable or version. Please check your Java installation.
```

**排查提示**：看到这个报错时，**先 `where java` 看 PATH 里有没有**，不要去改 `JAVA_HOME`。本机 `JAVA_HOME` + `PATH` 在阶段 1 都已配好（[scripts/env/README.md](../scripts/env/README.md)），**新开的终端直接就能用**；只有在环境变量改过之前就已经开着的终端里才需要重开。

---

## 五、环境验证清单

**阶段 1 不是"把软件装上"，而是"证明这套环境能支撑本项目的核心能力"。** 每一项都必须有可复现的验证输出。

| # | 验证项 | 方式 | 状态 |
| --- | --- | --- | --- |
| V1 | JDK 环境变量 | `java -version`、`echo %JAVA_HOME%` | ✅ 输出 17.0.20.1 |
| V2 | Maven 可用 | `mvnw -v` | ✅ Maven 3.9.16，runtime 指向 `D:\jdk-17.0.20.1` |
| V3 | MySQL 可连 | 建 4 库 + 9 表 | ✅ 2026-09-15 |
| V4 | Redis 基础命令 | `SET/GET/DECR/SADD/SISMEMBER` | ✅ 全部返回预期值 |
| V5 | **Redis Lua 执行** ⭐ | `EVAL` / `SCRIPT LOAD` / `EVALSHA`；扣减脚本四个分支（未预热 -3 / 售罄 -1 / 重复 -2 / 成功） | ✅ **逐个实测符合预期** |
| V6 | Redis 版本 | `INFO server` | ✅ 8.10.1 ⚠️ 非官方移植版 |
| V7 | Nacos 启动 | 控制台 `http://127.0.0.1:8889/` | 🟡 旧端口（8888）曾返回 200；**改端口后控制台未复验**，下次启动时确认 |
| V8 | Nacos 端口规划 | 主 8888 / 控制台 8889 / gRPC 9888,9889 | ✅ 配置 + 启动日志双重确认，与 8080–8084 无冲突 |
| V9 | SCA 版本兼容 ⭐ | `mvn dependency:tree` | ✅ **已于阶段 0 提前完成** |
| V10 | MyBatis-Plus 依赖 | 引入两个 artifact | ✅ 已于阶段 0 提前完成 |
| V10b | MyBatis-Plus **运行时**可用 | 写一个分页查询实际执行 | ⏳ 待阶段 4 |
| V11 | JMeter 可用 | `jmeter.bat --version` | ✅ 5.6.3，JDK 17 下实测输出正常 |
| V11b | JMeter 发出一个请求 | 对 `/api/train/stations` 发一次 HTTP 请求 | ⏳ 待阶段 6 |
| V14 | RocketMQ 启动 | NameServer 9876 + Broker 注册成功 | 🟡 历史上启动成功（有连续日志）；**改 Nacos 端口后未复验**，见 §4.2 |
| V15 | RocketMQ 抗压性 | 内存占用观测 | 🔴 **未评估**。默认 4 GB 堆 + `AlwaysPreTouch`，本机空闲内存曾低至 1.5 GB |
| V12 | **Redis 并发扣减** ⭐ | [scripts/env/verify-redis.sh](../scripts/env/verify-redis.sh)：100 张票 vs 1000 并发 → 余票恰好 0、恰好 100 人成功；同一用户 1000 并发 → 恰好扣 1 张 | ✅ **已实测** |
| V13 | **MySQL 约束行为** ⭐ | [sql/99_verify.sql](../sql/99_verify.sql)：唯一索引、2 个 CHECK 约束均实测拒绝违规写入；条件 UPDATE 的 CAS 行为（1 行 / 0 行）符合预期 | ✅ **已实测** |

> **V5 为什么要单独强调**：本项目**不超卖的全部保证都压在 Lua 脚本上**。如果这个第三方移植版在 Lua 或 `DECR` 上有任何行为差异，整个正确性论证就塌了。所以 V5 和 V12 是把"假设"变成"事实"的关键两步。

---

## 六、⚠️ 本机平台特有的三个坑

这三个坑的共同点是：**看起来成功，结果不对，而且不报错**。

### 6.1 JVM 默认编码是 GBK（不是 UTF-8）

`./mvnw -v` 输出里有 `Default locale: zh_CN, platform encoding: GBK`。Java 17 尚未默认 UTF-8（那是 JDK 18 的 JEP 400）。

| 场景 | 是否受影响 |
| --- | --- |
| Maven 编译 Java 源码 | 🟢 不受影响（`spring-boot-starter-parent` 已设 UTF-8） |
| Spring Boot 读 `application.yml` | 🟢 不受影响（Boot 自己按 UTF-8 读） |
| **自己写 `FileReader` / `Files.readString` 不指定字符集** | 🔴 **会乱码** |
| 控制台中文日志 | 🟡 取决于终端编码（Git Bash 是 UTF-8，CMD 默认 GBK） |

**处理方案**（已落地）：

- 根 POM 显式声明 `project.build.sourceEncoding` / `project.reporting.outputEncoding`
- `spring-boot-maven-plugin` 配 `<jvmArguments>-Dfile.encoding=UTF-8</jvmArguments>`
- **代码规范：任何字符流读写一律显式写 `StandardCharsets.UTF_8`，禁止依赖平台默认值**

> ⚠️ **IntelliJ 里直接 Run 不读根 POM 的 `jvmArguments`**（走的是 IDEA 自己的启动器），必须在 Run Configuration 的 VM options 里手动加 `-Dfile.encoding=UTF-8`。

### 6.2 mysql 客户端默认按 GBK 解释字节

Windows 中文环境下 mysql 客户端默认字符集是 **GBK**。直接导入 UTF-8 的建表脚本，**中文表注释会变成乱码，且不报错**。

```bash
mysql --default-character-set=utf8mb4 ... < sql/01_rail_user.sql
```

这与 6.1 是**同一类问题的两个面**：**中文 Windows 上，字符集必须处处显式指定，不能依赖默认值。**

### 6.3 公司 DLP 会加密「没有扩展名」的文件

这台办公机装了亚信安全 DLP。实测行为：**「扩展名不在白名单内」+「由受管控进程写入」两个条件同时成立时，文件被透明加密**——文件头变成 `%TSD-Header-###`，体积被撑到 8192 字节的容器。

最坑的地方是**它对编辑器完全透明**：编辑器里看是正常明文，但 `git.exe` 读到的是磁盘原始密文，会把一坨二进制当成文件内容提交，**全程不报任何错**。

`.gitignore` 恰好是整个工程里唯一一个「必须没有扩展名」的文件，第一次提交时就被它坑了——`git add -A` 暂存了 **54** 个文件，忽略规则**一条都没生效**。

| 类型 | 处理方式 |
| --- | --- |
| 带扩展名的文件（已实测安全：`.md` `.sql` `.sh` `.ps1` `.txt` `.lua` `.json` `.xml` `.yml` `.java` `.properties`） | ✅ 明文，正常写 |
| **无扩展名的文件**（`.gitignore`、将来的 `Dockerfile` / `LICENSE`…） | 🔒 **禁止用编辑器保存**——保存动作本身就会触发加密，必须用 bash 生成 |

**提交前守卫**（可挂 pre-commit）：

```bash
bash scripts/env/check-dlp-encryption.sh
# 退出码 0 = 干净，1 = 发现密文文件
```

完整排查过程（含控制变量实验设计）见 [troubleshooting/README.md 案例 1](troubleshooting/README.md)。

---

## 七、计划中的技术选型

以下内容**尚未接入代码**，列出是为了说明"将来会用什么、为什么"。

### 7.1 Spring Cloud 组件清单

> 每个组件必须能回答：**为什么需要它？没有它会发生什么？**

| 组件 | 为什么需要 | 没有它会发生什么 |
| --- | --- | --- |
| **Spring Cloud Gateway** | 统一入口、集中鉴权、集中限流、隐藏内部拓扑 | ① 每个服务都要写一遍 JWT 校验，密钥升级漏改一个服务 → **部分接口鉴权静默失效**（安全漏洞，没人会发现）② 没有统一限流入口，恶意流量直达业务服务 ③ 前端要知道所有服务地址，扩容/改端口要改前端 |
| **Nacos 注册中心** | 服务实例动态上下线，调用方自动感知 | 硬编码 IP → 扩容要改配置重启；实例宕机后调用方仍往死实例发请求，**表现为大量超时** |
| **Nacos 配置中心** | 配置集中管理、动态刷新 | 5 份 `application.yml` 各存一份 Redis 地址，改配置漏改一份 → **"随机出现的数据不一致"**，极难排查 |
| **OpenFeign** | 声明式 HTTP 调用 | 手写 `RestTemplate` + 拼 URL + 手动反序列化，代码量大且易错 |
| **Spring Cloud LoadBalancer** | 客户端负载均衡 | 要么只用单实例，要么自己实现轮询/随机策略 |
| **Sentinel** | 保护系统不被突发流量打垮；下游故障时快速失败 | 下游变慢 → 调用方线程池被占满 → **级联雪崩** |

**必须能回答的"五连问"**（以 Gateway 为例）：

1. **为什么需要它？** —— 见上表
2. **没有它会发生什么？** —— 见上表（要用具体故障场景描述，不是"不方便"）
3. **它怎么工作的？** —— 基于 Spring WebFlux + Netty，请求经过一串 `GatewayFilter` 链（鉴权、限流、改写），再由路由断言匹配目标服务，通过 `LoadBalancer` 找到实例转发。**它是非阻塞的**，少量线程就能扛住大量并发连接
4. **一个服务挂掉之后会发生什么？** —— Nacos 有心跳机制，实例下线后调用方拉取到新列表（有**秒级延迟**）。所以必须配**重试 + 熔断**兜住这个窗口
5. **什么时候需要降级？** —— 见 [02-architecture.md §5](02-architecture.md) 的降级判据

### 7.2 认证放在网关（🔴 已拍板，阶段 8 落地）

**决定**：网关统一校验 JWT，解析出 `userId` 后通过请求头（如 `X-User-Id`）透传给下游服务。

**引入的新问题（面试时能主动讲出这个才是真懂了）**：`X-User-Id` 是**可伪造的**——如果攻击者能直连 `rail-order-service:8084` 并自己塞一个 `X-User-Id`，鉴权就被绕过了。生产做法：

1. 服务不对外暴露端口，只允许网关访问（内网隔离）
2. 网关在转发时**先剥离客户端传入的 `X-User-Id`**，再写入自己解析的值

### 7.3 MQ 选型（🔴 推迟到阶段 9）

**决定**：阶段 1~8 **完全不引入 MQ**。这个决定是明智的——MQ 选型取决于"哪个能在这台机器上跑起来"，而那要到阶段 9 才有答案，现在选是凭调研猜。

**倾向 RocketMQ —— 前置条件已验证通过（2026-09-29）**：

| 维度 | RocketMQ 5.x | RabbitMQ 4.x |
| --- | --- | --- |
| 部署方式 | **纯 Java**，官方 zip 含 `.cmd` 脚本，解压 + 设 `JAVA_HOME`/`ROCKETMQ_HOME` 即可 | 需**先安装 Erlang/OTP**（官方包不捆绑） |
| **管理员权限** | **不需要** | Erlang 必须用管理员账号安装，全机只能存在一个版本 |
| 延时消息（订单超时关单） | ✅ 原生支持 | ❌ 无原生支持，只能 TTL + 死信队列硬拼 |
| 事务消息（扣减成功但发送失败） | ✅ 原生支持（半消息 + 回查） | ❌ 无对应物，需手写本地消息表 |
| 已知坑 | 官方 `runserver.cmd` 在 JDK 17 下会注入已移除的 `-XX:+UseConcMarkSweepGC`，需手工改脚本 | 延时插件已停止维护，其依赖的 Mnesia 在新版本被移除 |

**决定性因素是「零安装、不需要管理员权限」**——在一台装了 DLP 的公司办公机上，这个优势压倒其他一切。

### ✅ 时间盒验证：已通过（2026-09-29）

原定的 45 分钟启动验证**已经完成**，RocketMQ 5.5.1 已装好并在本机成功运行过（详见 §4.2）。

**两处比预想中顺利的地方**：

1. **`runserver.cmd` 不需要手工修补**。文档早先预测"JDK 17 下会注入已移除的 `-XX:+UseConcMarkSweepGC`，需手工改脚本"——**5.5.1 的脚本已经自带版本分支**（`if %JAVA_MAJOR_VERSION% lss 17`），JDK 17 自动走无 CMS 的分支。**这条预测已经过时，保留在此是为了记下"预测和现实不一致"这件事**。
2. **`ROCKETMQ_HOME` 只需在启动脚本里设**，`JAVA_HOME` 复用阶段 1 已配好的即可。

**⚠️ 但反转条件没有消失，只是换了形式**：

| 新发现的问题 | 说明 |
| --- | --- |
| **内存** | 默认 4 GB 堆 + Broker 的 `AlwaysPreTouch`，而本机空闲内存曾低至 1.5 GB。**能跑，但机器会卡**。这是当前最现实的风险 |
| **启动脚本会误杀** | `rocketmq_start.bat` 里的 `taskkill /f /im java.exe` 会连带杀掉 Nacos 和 Spring Boot 应用 |

**反转条件（改成选 RabbitMQ）**：如果实测发现内存压力导致 Broker 频繁 GC 或压测数据失真；或所在环境安装 Erlang 不需要管理员权限。

**为什么不用 Kafka**：优势是分区并行和海量吞吐，本项目消息量小、不需要回溯重放，而它**没有原生延时消息**——"订单超时关单"要自研时间轮。**它的长处一个用不上，短处一个躲不掉。场景不匹配，不是它不好。**

---

## 八、相关文档

- [scripts/env/README.md](../scripts/env/README.md) —— 环境变量配置、验证脚本、实测输出存档
- [troubleshooting/README.md](troubleshooting/README.md) —— 真实踩坑记录（含原始日志）
- [06-database.md](06-database.md) —— 数据库相关的环境差异（时区表、CHECK 约束版本、`lower_case_table_names`）
- [07-risks.md](07-risks.md) —— 风险清单
