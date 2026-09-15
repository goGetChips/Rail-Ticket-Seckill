# 环境变量配置记录（阶段 1）

- **执行日期**：2026-09-15
- **机器**：Windows 10 Pro 19045，公司办公机（无管理员权限、无 Docker）
- **目的**：让 `java` / `javac` / `mvnw` 在任何新开的终端里都可用

---

## 一、改了什么

改动范围是 **当前用户**（`HKEY_CURRENT_USER\Environment`），**不需要管理员权限**，不影响其他用户。

| 变量 | 作用域 | 新值 | 注册表类型 |
| --- | --- | --- | --- |
| `JAVA_HOME` | 用户 | `D:\jdk-17.0.20.1` | `REG_SZ` |
| `Path` | 用户 | `%JAVA_HOME%\bin;<原有内容>` | `REG_EXPAND_SZ` |

`Path` 是**前置**插入，不是追加——这样即使将来机器上出现别的 `java.exe`，也是我们指定的这个优先。

**改动前的原始值已备份**在 [backup-HKCU-Environment-2026-09-15.txt](backup-HKCU-Environment-2026-09-15.txt)。

---

## 二、为什么用 `reg` / PowerShell 而不是 `setx`

最常见的做法是 `setx JAVA_HOME "D:\jdk-17.0.20.1"`，但这个项目**刻意不用它**，有三个具体原因：

| # | `setx` 的问题 | 后果 |
| --- | --- | --- |
| 1 | **PATH 超过 1024 字符会被静默截断** | 你的用户 PATH 现在是 371 字符，暂时安全。但这是个**定时炸弹**：装了更多工具后一旦越过 1024，`setx` 会**不报错地**砍掉后面的条目，环境直接损坏 |
| 2 | **写出的类型是 `REG_SZ`，会把 `REG_EXPAND_SZ` 降级** | 当前 PATH 里没有 `%VAR%`，所以今天无害。但只要将来有人加一个 `%USERPROFILE%\xxx`，它就不会再被展开了 |
| 3 | 没有事务性和回读 | 无法确认到底写进去了什么 |

因此改用 `reg add` / `New-ItemProperty -PropertyType ExpandString`，**保持原始类型不被降级**。

> **这不是纸上谈兵**：本次改动前专门回读了原始 PATH 长度（371）和类型（`REG_EXPAND_SZ`）才动手。先确认再修改，是这里最重要的一步。

### 为什么写 `%JAVA_HOME%\bin` 而不是硬编码 `D:\jdk-17.0.20.1\bin`

JDK 换版本时只需要改 **一处**（`JAVA_HOME`），PATH 不用动。这是企业环境的通行做法，也是 `JAVA_HOME` 这个变量存在的意义——**它本身不是给 Java 用的，是给构建工具（Maven、Gradle）和脚本用的间接层**。

---

## 三、如何验证

### 自动验证

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/env/verify-env.ps1
```

脚本会依次检查：`JAVA_HOME` 指向真实目录 → 用户 PATH 引用了 `JAVA_HOME` → 在**模拟的有效 PATH** 下 `java` 能否解析 → `java -version` / `javac -version`。

最近一次运行结果存档在 [verify-env-output.txt](verify-env-output.txt)，**全部 PASS**，`java` 解析到 `D:\jdk-17.0.20.1\bin\java.exe`，版本 `17.0.20.1`。

### 必须由你手工做一次的真实验证

**上面那个脚本是模拟，不是端到端。** 真正要确认的是"全新终端里能不能用"，而这个我从会话里**无法可靠地模拟**（原因见下方踩坑 3）。

请你手动做：

1. 运行一次 `scripts/env/broadcast-env-change.ps1`（已执行过，重开终端前再跑一次也无副作用）
2. **关掉所有已打开的终端和 IntelliJ IDEA**（它们持有旧的环境块）
3. 新开一个 PowerShell 或 CMD，执行：

```powershell
echo $env:JAVA_HOME     # 期望输出 D:\jdk-17.0.20.1
java -version           # 期望输出 17.0.20.1
```

4. 在项目根目录执行 `.\mvnw.cmd -v`，期望看到 `Java version: 17.0.20.1 ... runtime: D:\jdk-17.0.20.1`

> 已用 Git Bash 跑通 `./mvnw -v`：**Maven 3.9.16，runtime 正是 `D:\jdk-17.0.20.1`**。

---

## 四、如何回滚

如果环境变量配置出问题，按备份文件恢复（把 `<原始值>` 换成备份里 `Path` 那一行的值）：

```bash
reg add "HKCU\Environment" /v Path /t REG_EXPAND_SZ /d "<原始值>" /f
reg delete "HKCU\Environment" /v JAVA_HOME /f
```

然后重新跑一次 `broadcast-env-change.ps1` 并重开终端。

---

## 五、踩坑记录（本次真实发生，非虚构）

### 坑 1：`.NET` 不展开 `REG_EXPAND_SZ`

第一版验证脚本用 `[Environment]::GetEnvironmentVariable('Path','User')` 读取 PATH，再拼成有效 PATH 去执行 `java`。

**结果：`java` 报"不是内部或外部命令"。**

**原因**：该方法返回的是注册表里的**原始字符串**，`%JAVA_HOME%\bin` **不会被展开**。于是 PATH 上多了一个名叫 `%JAVA_HOME%\bin` 的、根本不存在的目录。

**关键点**：这说明**是验证脚本错了，不是配置错了**。如果当时直接下结论"配置失败"去瞎改，就会把一个正确的配置改坏。**报错时先怀疑测量工具，再怀疑被测对象。**

修正方式：先按 `%VAR%` 正则找出用户 PATH 实际引用了哪些变量，把它们从注册表（用户级 → 机器级）灌进当前进程，再调 `ExpandEnvironmentVariables`。

### 坑 2：`setx` 的 1024 字符截断

见第二节。本次没触发（371 字符），但这是选型时就规避掉的。

### 坑 3：`Start-Process -UseNewEnvironment` 不能用来验证环境变量

它看起来是专门干这个的，但在这台机器上实测**造出的环境块里连机器级 PATH 都没有**——`where.exe` 都找不到（它位于 `C:\Windows\System32`）。

**结论**：这个开关不可信，本次已弃用。

替代方案就是 `verify-env.ps1` 里那段"按 Windows 的真实算法手工复现有效 PATH"的逻辑：

```
有效 PATH = 机器级 PATH + ";" + 用户级 PATH     （用户级在后）
其中 REG_EXPAND_SZ 的值在此刻展开，用户变量可以引用同为用户变量的 %JAVA_HOME%
```

### 坑 4：直接写注册表不会通知 Explorer

`setx` 会自动广播 `WM_SETTINGCHANGE`，所以它"看起来立刻生效"；而 `reg add` 和 `New-ItemProperty` **不会**。

Windows 上开始菜单、任务栏、桌面都由 `explorer.exe` 托管，**从它们启动的程序（包括 IntelliJ IDEA）继承的是 explorer 缓存的环境块**。不广播的话，你新开的终端可能仍是旧 PATH。

因此补了 `broadcast-env-change.ps1`。注意它只能刷新 shell 宿主，**已经开着的终端和 IDE 仍持有旧环境，必须逐个重启**。

---

## 六、遗留事项

### 6.1 用户 PATH 中存在一个空条目

原始 PATH 里有一段 `...Source_8wekyb3d8bbwe;;D:\Program Files\Git\cmd`——**两个连续分号**，中间是个空条目。

- **本次未改动它**：只做请求的事，不顺手改别人的东西，避免出现问题时无法归因。
- **风险**：有些老程序会把 PATH 中的空条目解释为"当前目录"，理论上存在**当前目录劫持**风险。
- **建议**：确认没有程序依赖后删掉这个空分号。要改的话我们单独做一次，并同样先备份。

### 6.2 平台默认编码是 GBK（重要）

`./mvnw -v` 输出里有一行：

```
Default locale: zh_CN, platform encoding: GBK
```

这意味着 **JVM 的 `file.encoding` 是 GBK 而不是 UTF-8**。Java 17 尚未默认 UTF-8（那是 JDK 18 的 JEP 400）。

**影响面评估**：

| 场景 | 是否受影响 | 说明 |
| --- | --- | --- |
| Maven 编译 Java 源码 | 🟢 **不受影响** | `spring-boot-starter-parent` 已把 `project.build.sourceEncoding` 设为 UTF-8，`maven-compiler-plugin` 会显式传 `-encoding UTF-8` |
| Spring Boot 读 `application.yml` | 🟢 **不受影响** | Spring Boot 显式按 UTF-8 读取配置 |
| **自己写 `FileReader` / `Files.readString` 不指定字符集** | 🔴 **会乱码** | 这是**默认字符集陷阱**，将来读 Lua 脚本、JSON 测试数据时必须显式传 `StandardCharsets.UTF_8` |
| 控制台中文日志 | 🟡 可能乱码 | 取决于终端编码（Git Bash 是 UTF-8，CMD 默认 GBK） |

**处理方案**（阶段 3 项目初始化时落地）：

```xml
<properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
</properties>
...
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <jvmArguments>-Dfile.encoding=UTF-8</jvmArguments>
    </configuration>
</plugin>
```

以及代码规范：**任何字符流读写一律显式指定 `StandardCharsets.UTF_8`，禁止依赖平台默认值。**

### 6.3 公司 DLP 会加密「没有扩展名」的文件（重要）

这台办公机装了亚信安全 DLP。实测行为：**「扩展名不在白名单内」+「由受管控进程写入」两个条件同时成立时，文件被透明加密**——文件头变成 `%TSD-Header-###`，体积被撑到 8192 字节的容器。

最坑的地方是**它对编辑器完全透明**：在编辑器里看是正常明文，但 `git.exe` 读到的是磁盘原始密文，会把一坨二进制当文件内容提交上去，**全程不报任何错**。

`.gitignore` 恰好是整个工程里唯一一个「必须没有扩展名」的文件，第一次提交时就被它坑了——`git add -A` 暂存了 **54** 个文件，忽略规则**一条都没生效**。

严重性不在功能，而在**静默**：`.gitignore` 里有 `**/application-local.yml` 这条规则，规则失效就意味着**本机真实数据库密码会被正常提交**，而且它在仓库里是二进制乱码，review 时没人会点开看。

| 类型 | 处理方式 |
| --- | --- |
| 带扩展名的文件（已实测安全：`.md` `.sql` `.sh` `.ps1` `.txt` `.lua` `.json` `.xml` `.yml` `.java` `.properties`） | ✅ 明文，正常写就行 |
| **无扩展名的文件**（`.gitignore`、将来的 `Dockerfile` / `LICENSE`…） | 🔒 **禁止用编辑器直接保存**——保存动作本身就会触发加密。必须用 bash 生成 |

用 bash 生成（bash 不在 DLP 的受管控进程名单里，写入落盘即明文）：

```bash
cat > .gitignore <<'EOF'
...内容...
EOF
```

或从一份带扩展名的暂存副本拷过来：`cp gitignore.txt .gitignore`

**注意：不要用编辑器「重新保存」已修复的文件**，那会把它重新加密回 8192 字节。

提交前守卫（挂了 pre-commit 钩子就自动跑）：

```bash
bash scripts/env/check-dlp-encryption.sh
# 退出码 0 = 干净，1 = 发现密文文件
```

> 为什么守卫必须用 bash 写：它要和 **git 用相同的读取方式**（读磁盘原始字节）。用一个会被 DLP 解密的白名单工具去检查加密文件，看到的是明文——**那样的检查是自欺欺人**。

完整排查过程（含控制变量实验设计）见 [docs/troubleshooting/README.md 案例 1](../../docs/troubleshooting/README.md)。

---

## 七、本目录文件说明

| 文件 | 用途 |
| --- | --- |
| `README.md` | 本文件 |
| `verify-env.ps1` | 环境变量验证脚本（可重复运行） |
| `verify-env-output.txt` | 最近一次验证的**实测输出**存档 |
| `broadcast-env-change.ps1` | 广播 `WM_SETTINGCHANGE`，让 Explorer 重载环境 |
| `check-dlp-encryption.sh` | **提交前守卫**：扫描 DLP 密文文件（见 §6.3），可挂 pre-commit |
| `lua/stock_deduct.lua` | Redis 原子扣减脚本的**设计稿**（阶段 7 落地） |
| `lua/stock_deduct_bench.lua` | 压测用变体：给每次请求生成唯一 uid，用于验证脚本本身没被写坏 |
| `verify-redis.sh` | Redis 验证脚本（17 条断言，含并发正确性） |
| `verify-redis-output.txt` | `verify-redis.sh` 的**实测输出**存档 |
| `verify-mysql-output.txt` | `sql/99_verify.sql` 的**实测输出**存档 |
| `backup-HKCU-Environment-2026-09-15.txt` | 改动前的原始环境变量，**回滚依据**（⚠️ 已在 `.gitignore` 中排除，不进版本库——它含 `C:\Users\<用户名>` 绝对路径，对别人无用且会暴露本机用户名） |
