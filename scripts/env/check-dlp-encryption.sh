#!/usr/bin/env bash
#
# 提交前守卫：检查工作区里有没有被公司 DLP 透明加密的文件。
#
# ── 为什么需要这个脚本 ────────────────────────────────────────────────
# 这台办公机的亚信安全 DLP 会把「没有扩展名」的文件加密写盘，
# 文件头会变成 %TSD-Header-### 加一坨二进制。
#
# 加密对白名单进程是「透明」的——编辑器/IDE 读到的仍是明文，所以肉眼看不出异常；
# 但 git.exe 读到的是磁盘原始密文，于是 git 会把一坨二进制垃圾当成文件内容存进提交里，
# 而且全程不报任何错。
#
# 最要命的是 .gitignore 恰好就是这种「必须没有扩展名」的文件。
# 一旦它以密文进入提交，所有 clone 这个仓库的人拿到的都是一个解析不了的 .gitignore，
# 后果是 application-local.yml（真实密码所在）会被 git 正常跟踪并提交上去。
#
# ── 为什么用 bash 而不是 PowerShell ───────────────────────────────────
# 本机实测：bash（Git Bash 的 head/od）读到的是磁盘原始字节，
# 而编辑器类进程可能被 DLP 白名单解密，看到的是明文。
# 用可能被解密的工具去检查加密，等于自欺欺人。所以这个检查必须走 bash。
#
# ── 用法 ──────────────────────────────────────────────────────────────
#   bash scripts/env/check-dlp-encryption.sh
#   # 退出码 0 = 干净，1 = 发现密文文件（不要提交）
#
# 建议挂成 pre-commit 钩子（一次配置，之后每次 commit 自动检查）：
#   printf '#!/bin/sh\nbash scripts/env/check-dlp-encryption.sh || exit 1\n' > .git/hooks/pre-commit
#   chmod +x .git/hooks/pre-commit

set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2

# 密文容器的魔数，出现在文件最开头
MAGIC='%TSD-Header-'

checked=0
found=0

# git ls-files -c -o -z --exclude-standard
#   -c 已跟踪的 / -o 未跟踪的 / --exclude-standard 应用 .gitignore 规则
# 取到的正好是「下一次 git add -A 会提交的文件集合」，不重不漏。
while IFS= read -r -d '' f; do
    [ -f "$f" ] || continue          # 跳过符号链接等非普通文件
    checked=$((checked + 1))

    # 密文头一定在文件开头，只读前 16 字节就够，不用把大文件整个读一遍
    if head -c 16 -- "$f" 2>/dev/null | LC_ALL=C grep -qa "$MAGIC"; then
        printf '  🔒 %s\n' "$f"
        found=$((found + 1))
    fi
done < <(git ls-files -z -c -o --exclude-standard)

if [ "$found" -gt 0 ]; then
    cat <<'HINT'

❌ 上面这些文件是 DLP 密文，禁止提交。

   不要用编辑器「重新保存」它们——保存动作本身就会再次触发加密。

   正确做法：用 bash 重定向生成（bash 写入不会被加密）。

     · 该文件已经提交过：
         git show HEAD:<路径> > <路径>

     · 还没提交过：
         cat > <路径> <<'EOF'
         ...内容...
         EOF

   或者从一份带扩展名的暂存副本拷过来（.txt 是安全的）：
         cp <暂存副本>.txt <路径>

   原因与完整排查过程见 docs/troubleshooting/README.md 第一例。
HINT
    exit 1
fi

printf '✅ 已检查 %d 个文件，未发现 DLP 密文。\n' "$checked"
exit 0
