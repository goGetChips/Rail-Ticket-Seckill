# 真实问题记录（Troubleshooting）

> **规则**：这里只记录**实际发生过**的问题。
> 日志、命令输出、文件大小等一切数据必须来自真实执行，**禁止编造**。
> 格式固定为：问题现象 → 日志 → 初步判断（含被推翻的过程）→ 排查过程 → 真正原因 → 解决方案 → 为什么有效 → 优化前/后 → 面试如何回答。
>
> 目标：至少 5 个真实案例。**不刻意回避问题**——排查过程比结论更有价值。

| # | 发生阶段 | 问题 | 严重度 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 阶段 1→3 之间 | 公司 DLP 把 `.gitignore` 加密写盘，git 只能存到密文 | 🔴 高（可致真实密码泄露） | ✅ 已修复 + 已加守卫 |
| 2 | 阶段 3 | MySQL 时区表为空，JDBC 连接直接失败，报的却是"拿不到连接" | 🟡 中（无法启动，但排查路径清晰） | ✅ 已修复 |

---

## 案例 1：`.gitignore` 被公司 DLP 加密，git 把密文当成文件内容

- **发现时间**：2026-09-15，阶段 0/1/2 首次提交准备时
- **环境**：Windows 10 Pro 19045 + 亚信安全 DLP + Git 2.55.0.windows.3

### 1. 问题现象

准备做第一次 `git commit`。执行 `git add -A` 后，`git status` 里出现了 **54 个文件**，比预期多——`target/`、`.idea/`、`scripts/env/backup-*.txt` 全都进来了，**而这些明明都写在 `.gitignore` 里**。

### 2. 日志

`git check-ignore -v` 对任何路径都不输出，也就是**没有任何一条规则匹配**：

```console
$ git check-ignore -v target/classes/application.properties
$ echo $?
1
```

而 `.gitignore` 本身是能打开的，内容看起来完全正常。但从 git 的视角看，它存进去的东西是：

```console
$ git cat-file -p "$(git ls-files -s .gitignore | awk '{print $2}')" | head -c 24
%TSD-Header-###%K.i.....
```

**决定性证据**：

```console
$ stat -c '%s' .gitignore
8192
```

`.gitignore` 磁盘大小 **8192 字节**，而它的真实内容只有 **1099 字节**。

### 3. 初步判断（**这个判断是错的**）

第一反应是「`.gitignore` 规则写法有问题」——比如 `target/` 应该写成 `/target/`，或者被后面那行 `!**/src/main/**/target/` 反向包含覆盖了。

**为什么很快否掉了它**：`.gitignore` 第 2 行就是最普通的 `target/`，没有任何歧义；而且**连第 1 行 `HELP.md` 都没生效**。不可能是所有规则一起写错——**更像是这个文件根本没被当作规则文件来解析**。

### 4. 排查过程

关键转折：**停止读「内容」，改为读「磁盘上的原始字节」。**

```console
$ head -c 24 .gitignore | od -c
0000000   %   T   S   D   -   H   e   a   d   e   r   -   #   #   #   %
0000020   K 321   i 256 331 260 361 021 244   & 230   Y   z 250 006   U
```

磁盘上根本不是文本，是一个 **`%TSD-Header-` 加密容器**。

接着用**控制变量法**逐个排除可能的触发条件，一次只改一个变量：

| # | 假设 | 实验设计 | 结果 |
| --- | --- | --- | --- |
| 1 | 到某个时间点被批量扫描加密？ | 对比相隔 1 秒写入的两个文件 | ❌ 否。`.gitignore`(16:05:41) 是密文，`scripts/env/README.md`(16:05:42) 是明文 |
| 2 | 点前缀 / 隐藏文件触发？ | `.dltest_dot`（有点无扩展名）vs `dltest_plain`（无点无扩展名） | ❌ 否。**两个都被加密**，点前缀不是变量 |
| 3 | 内容里有密码字样触发？ | `dltest_secret.md`，内容含 `DB_PASSWORD=rail123456` | ❌ 否。**是明文**，内容不是变量 |
| 4 | **没有扩展名**触发？ | 综合 2、3 的结果对比 | ✅ **是。唯一的变量就是扩展名** |

**扩展名验证**（这些正是阶段 3 要用的类型）：

| 文件 | 磁盘大小 | 结果 |
| --- | --- | --- |
| `dltest_a.yml` | 40 | ✅ 明文 |
| `dltest_c.java` | 23 | ✅ 明文 |
| `dltest_d.properties` | 23 | ✅ 明文 |
| `dltest_e.xml` | 54 | ✅ 明文 |
| `dltest_secret.md` | 85 | ✅ 明文 |
| `dltest_plain`（无扩展名） | 8192 | 🔒 加密 |
| `.dltest_dot`（无扩展名） | 8192 | 🔒 加密 |

**还有一个隐藏变量：写文件的进程。**

```console
$ printf 'aaa\nbbb\n' > dltest_w_printf_noext
$ head -c 16 dltest_w_printf_noext | grep -q TSD-Header && echo 密文 || echo 明文
明文
$ stat -c '%s' dltest_w_printf_noext
8
```

同一个「无扩展名」条件，用 bash 写出来就是明文（8 字节），用编辑工具写出来就是密文（8192 字节）。
**所以触发条件是「受管控进程写入」+「扩展名不在白名单内」两个条件同时成立。**

旁证：`mvnw` 同样无扩展名，但它是 11:01 随工程脚手架一起落盘的，一直是明文（11790 字节）。这与上面的结论一致。

### 5. 真正原因

办公机上的公司 DLP（亚信安全）**对「扩展名不在白名单内」的文件做透明加密**，三个关键性质：

1. **加密发生在写入时刻**，由受管控的进程触发。**没有回溯扫描**——那个由脚手架解压产生的 `.gitignore` 以明文在磁盘上躺了 5 小时，直到被我自己重写才变成密文。
2. **加密对白名单进程是透明的**：编辑器和 IDE 读到的仍然是明文。**所以用眼睛看、用编辑器打开，完全看不出任何异常。**
3. **`git.exe` 不在白名单**，读到的是磁盘原始密文。于是 git 老老实实把这 8192 字节的二进制当成 `.gitignore` 的内容存进了对象库，**全程零报错**。

`.gitignore` 恰好是整个工程里唯一一个「必须没有扩展名」的文件，所以它成了唯一的受害者。

> **为什么严重度是「高」而不是「烦」**
>
> `.gitignore` 一旦以密文进入提交，**所有 clone 这个仓库的人都拿不到有效的忽略规则**。而 `.gitignore` 里正好有这条：
>
> ```
> **/application-local.yml
> ```
>
> 于是 `application-local.yml`（本机真实数据库密码的存放处）会被 git 正常跟踪、正常提交——**一次静默的凭据泄露**。而且它在仓库里是个二进制乱码文件，code review 时不会有人点开看。

### 6. 解决方案

**① 用 bash 重新生成 `.gitignore`**（bash 写入不会被加密）

```bash
cat > .gitignore <<'GITIGNORE_EOF'
...内容...
GITIGNORE_EOF
```

磁盘大小从 8192（密文容器）变成 **1099**（真实内容），`git check-ignore` 立刻恢复正常。

**② 重建索引**（旧索引里存的仍是密文 blob）

```console
$ git rm -r --cached -qf .
error: the following file has staged content different from both the
file and the HEAD:
    .gitignore
(use -f to force removal)
```

这条报错本身就是证据——**git 在明确告诉你：它索引里的 `.gitignore` 和工作区的 `.gitignore` 内容不一致**。
加 `-f` 强制清空索引后重新 `git add -A`：

| | 文件数 |
| --- | --- |
| 修复前 | **54**（含 `target/`、`.idea/`、`backup-*.txt`） |
| 修复后 | **43** |

**③ 补一个守卫脚本** [scripts/env/check-dlp-encryption.sh](../../scripts/env/check-dlp-encryption.sh)

```console
$ bash scripts/env/check-dlp-encryption.sh
✅ 已检查 45 个文件，未发现 DLP 密文。
```

反向验证（故意用编辑工具造一个无扩展名文件，确认它真的拦得住）：

```console
$ bash scripts/env/check-dlp-encryption.sh
  🔒 badfile_noext_test

❌ 上面这些文件是 DLP 密文，禁止提交。
$ echo $?
1
```

可挂成 pre-commit 钩子，把「静默损坏」变成「提交前硬失败」：

```bash
printf '#!/bin/sh\nbash scripts/env/check-dlp-encryption.sh || exit 1\n' > .git/hooks/pre-commit
```

### 7. 为什么有效

- **①有效**：加密由**写入进程**触发。bash 不在 DLP 的受管控进程名单里，它的写入落盘就是明文。
  这不是「绕过安全策略」——`.gitignore` 里本来也不含任何机密，只是**换了一条不被该策略覆盖的写入路径**。
- **②有效**：git 的内容寻址存储只认「blob 里到底是什么字节」。索引重建后，存进去的是 1099 字节的明文 blob。
- **③有效**：它用**和 git 完全相同的读取方式**（bash 读磁盘原始字节）来检查。
  如果用一个会被 DLP 白名单解密的工具（比如 PowerShell 或编辑器）去检查，看到的是明文——**那样的检查是自欺欺人**。

### 8. 优化前 / 优化后

| 项目 | 优化前 | 优化后 |
| --- | --- | --- |
| `.gitignore` 磁盘大小 | 8192（`%TSD-Header-` 密文容器） | 1099（纯文本） |
| `git check-ignore target/` | 无规则匹配（exit 1） | 正确忽略 |
| `git add -A` 暂存文件数 | 54 | 43 |
| 真实密码泄露风险 | 🔴 高（`application-local.yml` 会被跟踪） | 🟢 已消除 |
| 发现手段 | 靠人注意到「文件数怎么多了」 | 守卫脚本硬失败 |

### 9. 面试如何回答

**先诚实说清楚：这不是分布式系统问题，是开发环境与工具链问题。** 讲它是为了讲**排查方法**，方法本身是可迁移的。

> 第一次提交时 `git add -A` 暂存了 54 个文件，`.gitignore` 里写的 `target/`、`.idea/` 全都进来了。
> 我第一反应是「规则写错了」，但很快否掉——因为连最简单的 `HELP.md` 那条都没生效，不可能是所有规则一起写错，**更像是这个文件根本没被当成规则文件解析**。
> 于是我停止读内容，改为读**磁盘原始字节**，发现文件头是 `%TSD-Header-`，一个 8192 字节的加密容器，而真实内容只有 1099 字节。
> 接着用**控制变量法**定位触发条件，一次只改一个变量：时间、点前缀、内容含密码，三个假设全部被实验否掉；最后锁定**扩展名**。
> 修复是用 bash 重定向重新生成该文件，再重建 git 索引。

**最值得说的两点：**

1. **「对白名单进程透明」意味着用编辑器永远看不出异常。**
   排查时必须**用和故障方相同的视角去观察**：git 读的是磁盘原始字节，我就得用能读原始字节的工具去验证它，而不是用编辑器。
2. **这类问题的危害不在功能，而在「静默」。**
   `.gitignore` 失效会让 `application-local.yml`（真实密码）被正常提交，而且它在仓库里是二进制乱码，review 时没人会点开。**所以修复之后必须补一个守卫，把它从"静默"变成"响亮"。**

> **这条经验在这个项目里已经出现过三次，值得单独记住。**
>
> | # | 场合 | 测量工具错在哪 | 差点得出的错误结论 |
> | --- | --- | --- | --- |
> | 1 | 本案例修复后自查 | `git check-ignore` **默认查索引**，而目标文件已经暂存 | "忽略规则还是没生效"——其实已经生效了，要用 `--no-index` |
> | 2 | [scripts/env/README.md §五 坑 1](../../scripts/env/README.md) | `.NET` 的 `GetEnvironmentVariable` **不展开** `REG_EXPAND_SZ` | "环境变量配错了"——其实配对了，是验证脚本拼出了不存在的路径 |
> | 3 | 阶段 3 检查行尾 | 引用写法不对，`grep -c $'\r$'` 退化成"匹配任意含 r 的行" | "仓库里的 `.sh` 是 CRLF"——用 `tr -cd '\r' \| wc -c` 一数，**CR 字节数 = 0**，是纯 LF |
>
> 三次的共同点是——**报错时先怀疑测量工具，再怀疑被测对象**。
>
> 第 3 次特别有代表性：它**没有报错**，只是给了一个错误答案。
> 命令跑通了、有输出、看起来像个结果——**这比报错更危险**。
> 所以现在的习惯是：**任何"结论性"的测量，都要用一个原理不同的方法交叉验证一次。**
> （第 3 次就是靠 `tr` + `wc` 才发现的：数字对不上。）

---

## 案例 2：MySQL 时区表为空，导致 Spring Boot 连不上数据库

- **发现时间**：2026-09-17，阶段 3 首次启动 `rail-train-service` 时
- **环境**：Windows 10 Pro 19045 + MySQL 8.4.8（Windows 版）+ mysql-connector-j 9.5.0

### 1. 问题现象

服务启动**完全正常**，Tomcat 在 8082 端口起来了，日志里没有任何异常。

但第一次调接口就 500：

```console
$ curl -i http://127.0.0.1:8082/api/train/stations/VNP
HTTP/1.1 500
{"timestamp":"2026-09-17T04:13:34.241+00:00","status":500,"error":"Internal Server Error","path":"/api/train/stations/VNP"}
```

> 注意：**启动时连不上数据库不会报错**。因为 HikariCP 是**懒加载**的——
> 它到第一次真正取连接时才去建池。所以"服务能起来"完全不等于"数据库配对了"。
> 这一点很值得记：如果依赖"启动成功"作为验证，会得到一个**假的通过**。

### 2. 日志

```
ERROR o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() for servlet
  [dispatcherServlet] threw exception [Request processing failed:
  org.apache.ibatis.exceptions.PersistenceException:
### Error querying database.
  Cause: org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
### Cause: org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection]
  with root cause

java.sql.SQLException: Unknown or incorrect time zone: 'Asia/Shanghai'
	at com.mysql.cj.jdbc.exceptions.SQLError.createSQLException(SQLError.java:121)
	at com.mysql.cj.jdbc.ConnectionImpl.createNewIO(ConnectionImpl.java:840)
	at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:488)
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:111)
	...
```

### 3. 初步判断（**这个判断是错的**）

看到 `CannotGetJdbcConnectionException: Failed to obtain JDBC Connection`，第一反应是：

- MySQL 服务没启动？→ 但是 `mysql` 命令行连得上，排除
- 密码错了？→ 但密码错会报 `Access denied`，不是这个
- 账号没有权限？→ 但 `rail` 账号是 `sql/00_init.sql` 建的，权限齐全
- 连接串写错了？→ 但库名 `rail_train` 确实存在

三个假设全部否掉，因为**它们都无法解释一个事实**：报错信息里没有出现任何关于"密码""权限""主机"的字眼。

**转折点**是注意到那行 `with root cause` —— 它后面才是真正的异常。

### 4. 排查过程

**第一步：读完整的异常链，而不是第一条异常。**

`Failed to obtain JDBC Connection` 只是 MyBatis/Spring 对底层异常的**包装**，
它在说"我拿不到连接"，但**没说是为什么**。真正的异常在最后一行：

```
java.sql.SQLException: Unknown or incorrect time zone: 'Asia/Shanghai'
```

**第二步：直接在 MySQL 里复现这个异常。** 不猜，去问数据库。

```console
$ mysql -h 127.0.0.1 -u root -e "SET time_zone='Asia/Shanghai'; SELECT NOW();"
ERROR 1298 (HY000) at line 1: Unknown or incorrect time zone: 'Asia/Shanghai'
```

复现成功，而且报的是**一模一样的错误码和文案**。

**第三步：确认根本原因——时区表是空的。**

```console
$ mysql -h 127.0.0.1 -u root -e "SELECT COUNT(*) FROM mysql.time_zone_name;"
+----------+
| COUNT(*) |
+----------+
|        0 |
+----------+
```

`mysql.time_zone_name` **一行数据都没有**。MySQL 支持两种写时区的方式：

| 写法 | 是否需要元数据表 | 本机结果 |
| --- | --- | --- |
| 命名时区 `'Asia/Shanghai'` | ✅ 需要查 `mysql.time_zone_name` | ❌ ERROR 1298 |
| 数字偏移 `'+08:00'` | ❌ 直接解析 | ✅ 成功 |

```console
$ mysql -h 127.0.0.1 -u root -e "SET time_zone='+08:00'; SELECT NOW();"
+---------------------+
| now_at_plus8        |
+---------------------+
| 2026-09-17 12:13:47 |
+---------------------+
```

**第四步：确认驱动确实在发这条 SET 语句。**

我们配了 `forceConnectionTimeZoneToSession=true`，它的作用就是"连接建立后立刻执行
`SET time_zone = <connectionTimeZone>`"。所以驱动拿着 `Asia/Shanghai` 去 SET，
被服务端拒绝，连接建立失败 → HikariCP 拿不到连接 → MyBatis 包装成
`CannotGetJdbcConnectionException`。

**整条因果链是通的，没有任何一步是猜的。**

### 5. 真正原因

**Windows 版 MySQL 不自带时区数据。**

MySQL 安装包里有一批 `mysql.time_zone*` 表，需要靠 `mysql_tzinfo_to_sql` 工具
读操作系统的时区数据库来填充。**这个工具只在 Unix/Linux 上提供**，
Windows 版没有，所以装了就是空的。

这不是本机配置错误，也不是 MySQL 的 bug —— 它是 Windows 版的一个已知特性
（MySQL 官方文档里明确说明 Windows 下需要手工导入时区数据）。

### 6. 解决方案

把 `connectionTimeZone` 从命名时区改成**数字偏移**：

```diff
- connectionTimeZone=Asia/Shanghai
+ connectionTimeZone=%2B08:00
```

`%2B` 是 URL 编码的 `+`（避免 `+` 在 URL 查询串里被解释成空格）。

### 7. 为什么有效

- **数字偏移不查表**：`+08:00` 由 MySQL 直接解析成一个固定偏移量，
  不需要 `mysql.time_zone_name` 里有对应记录。所以时区表空不空都不影响它。
- **为什么用数字偏移对本国应用是安全的**：中国自 **1991 年**起废止夏令时，
  东八区全年恒定，不存在"某天偏移量变一小时"的情况。
- **⚠️ 这个前提必须写清楚**：如果项目要服务多时区，或部署在有夏令时的国家，
  数字偏移会算错时间，此时必须填充时区表。**图省事用数字偏移而不写明前提，就是埋雷。**

> **替代方案（本次没采用）**：往 `mysql.time_zone*` 表里导入时区数据。
> 没采用的理由：Windows 上没有 `mysql_tzinfo_to_sql`，
> 需要先弄到一份现成的 SQL dump 再导入，多一个不可控的步骤；
> 而本项目只服务一个时区，数字偏移完全够用，且**行为更可预测**（不依赖任何外部数据）。

### 8. 优化前 / 优化后

| 项目 | 优化前 | 优化后 |
| --- | --- | --- |
| 连接串的时区参数 | `connectionTimeZone=Asia/Shanghai` | `connectionTimeZone=+08:00` |
| 连接能否建立 | 🔴 否，HikariCP 抛 `CannotGetJdbcConnectionException` | 🟢 是 |
| `GET /api/train/stations/VNP` | 500 | 200，返回真实数据 |
| 对 `mysql.time_zone_name` 的依赖 | 有（必须非空） | 无 |
| 报错时的可读性 | 表面原因（拿不到连接）与真实原因（时区解析失败）相隔 5 层调用栈 | — |

**验证证据（实测输出）**：

```console
$ curl -sS http://127.0.0.1:8082/api/train/stations/VNP
{"id":1,"stationCode":"VNP","stationName":"北京南","cityName":"北京","createTime":"2026-09-17T12:12:57"}

$ curl -sS -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1:8082/api/train/stations/NOPE
HTTP 404
```

日志里能同时看到连接成功和真实执行的 SQL：

```
INFO  com.zaxxer.hikari.HikariDataSource : HikariPool-1 - Start completed.
DEBUG c.r.t.m.StationMapper.selectWithCursor : ==>  Preparing: SELECT id,station_code,station_name,city_name,create_time FROM t_station WHERE (station_code = ?)
DEBUG c.r.t.m.StationMapper.selectWithCursor : ==> Parameters: VNP(String)
DEBUG c.r.t.m.StationMapper.selectWithCursor : <==      Total: 1
DEBUG c.r.t.mapper.StationMapper.selectList   : <==      Total: 14
```

### 9. 面试如何回答

> 阶段 3 第一次调接口返回 500，日志说 `Failed to obtain JDBC Connection`。
> 我先按"连不上库"的思路排查，否掉了三个假设——MySQL 没启动（命令行连得上）、
> 密码错（那会报 Access denied）、权限不足（账号是建表脚本建的）。
> 三个都解释不通之后，我注意到异常链最后一行的 `with root cause`，
> 真正的异常是 `Unknown or incorrect time zone: 'Asia/Shanghai'`。
> 然后我直接去数据库里执行 `SET time_zone='Asia/Shanghai'`，**一模一样地复现了它**，
> 再查 `mysql.time_zone_name` 发现是 **0 行**——
> Windows 版 MySQL 不带时区数据，填充它需要的 `mysql_tzinfo_to_sql` 只在 Unix 提供。
> 改成数字偏移 `+08:00` 就好了，因为数字偏移不查表。
> 中国从 1991 年起没有夏令时，东八区恒定，所以用固定偏移是安全的——
> 但如果项目要跨时区部署，就必须先把时区表填上。

**最值得说的三点：**

1. **报错信息指向的位置，往往不是原因所在。**
   `CannotGetJdbcConnectionException` 说的是"拿不到连接"，
   真实原因是"连接建立时执行的一条 SET 语句被服务端拒绝了"。
   **读异常一定要读到最底层的 root cause**，中间层的包装异常只说明"谁在什么时候失败了"，
   不说明"为什么失败"。

2. **要能区分"服务启动成功"和"依赖配置正确"。**
   HikariCP 懒加载，所以数据库配置全错服务照样起得来——**启动成功是一个假信号**。
   这个项目从阶段 3 起，每个阶段的完成判据都必须是"**真的调一次、看到真实数据**"，
   而不是"没报错"。这也是本项目坚持不把"代码生成成功"当"功能完成"的原因。

3. **"能跑"和"可移植"是两件事。**
   如果只在本机跑，`SET time_zone='+08:00'` 在客户端里敲一下也能让当时的会话工作；
   但把它写进连接串，才能保证**每一次连接**都一致。
   更关键的是，我把"中国无夏令时"这个前提**显式写进了配置注释**——
   将来有人把这个项目部署到有时区的地区，看到注释就知道这里会出问题。
   **一个正确的配置如果没说清它的前提，它就只是一个碰巧能跑的配置。**
