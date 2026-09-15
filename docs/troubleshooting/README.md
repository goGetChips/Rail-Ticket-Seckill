# 真实问题记录（Troubleshooting）

> **规则**：这里只记录**实际发生过**的问题。
> 日志、命令输出、文件大小等一切数据必须来自真实执行，**禁止编造**。
> 格式固定为：问题现象 → 日志 → 初步判断（含被推翻的过程）→ 排查过程 → 真正原因 → 解决方案 → 为什么有效 → 优化前/后 → 面试如何回答。
>
> 目标：至少 5 个真实案例。**不刻意回避问题**——排查过程比结论更有价值。

| # | 发生阶段 | 问题 | 严重度 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 阶段 1→3 之间 | 公司 DLP 把 `.gitignore` 加密写盘，git 只能存到密文 | 🔴 高（可致真实密码泄露） | ✅ 已修复 + 已加守卫 |

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

> **这条经验在这个项目里已经出现过两次。**
> 另一次见 [scripts/env/README.md §五 坑 1](../../scripts/env/README.md)：当时 `.NET` 的 `GetEnvironmentVariable` 不展开 `REG_EXPAND_SZ`，让验证脚本把一个**正确**的环境变量配置误判成错误。
> 两次的共同点是——**报错时先怀疑测量工具，再怀疑被测对象**。
