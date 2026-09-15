#!/usr/bin/env bash
# verify-redis.sh - 验证 Redis（第三方 Windows 移植版）能否支撑本项目的核心能力
#
# 用法（在 Git Bash 中，从项目根目录执行）：
#   bash scripts/env/verify-redis.sh
#
# 【为什么要做这套验证】
#   本机使用的是 zkteco-home/redis-windows，不是 Redis 官方发布版。
#   它的 INFO server 里 atomicvar_api = msvc-zkatomic（官方是 atomic-builtin /
#   c11-builtin），说明原子变量实现被替换过。而本项目「不超卖」的全部论证
#   都建立在 DECR / SISMEMBER 的原子性上，所以必须实测，不能假设官方行为成立。
#
# 退出码：0 = 全部通过；1 = 有失败项

set -u

REDIS_HOME="/d/dev_tools/redis-windows-8.10.1"
CLI="$REDIS_HOME/redis-cli.exe"
BENCH="$REDIS_HOME/redis-benchmark.exe"
HOST=127.0.0.1
PORT=6379

# redis-cli 作为原生 exe 不认 Git Bash 的 /d/... 路径，这里统一给 Windows 风格路径
LUA_DIR="D:/toDoList/Rail-Ticket-Seckill/scripts/env/lua"
STOCK_LUA="$LUA_DIR/stock_deduct.lua"
BENCH_LUA="$LUA_DIR/stock_deduct_bench.lua"

PASS=0
FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }

# assert_eq <期望> <实际> <说明>
assert_eq() {
    if [ "$1" = "$2" ]; then ok "$3 (期望=$1 实际=$2)"; else bad "$3 (期望=$1 实际=$2)"; fi
}

r() { "$CLI" -h "$HOST" -p "$PORT" "$@" 2>&1; }

echo "================================================================"
echo " Redis 验证  $(date '+%Y-%m-%d %H:%M:%S')"
echo "================================================================"

# ---------------------------------------------------------------- V4 连通性
echo
echo "--- V4a 连通性 ---"
assert_eq "PONG" "$(r PING | tr -d '\r')" "PING 有响应"

echo
echo "--- V4b 记录版本与构建信息（用于留档，判断是否为官方实现）---"
r INFO server | tr -d '\r' | grep -E "^(redis_version|redis_git_sha1|redis_git_dirty|redis_build_id|os|arch_bits|atomicvar_api|gcc_version|multiplexing_api|config_file):" | sed 's/^/  /'

# ---------------------------------------------------------------- V5 Lua 能力
echo
echo "--- V5a Lua 基础能力 ---"
assert_eq "1" "$(r EVAL 'return 1' 0 | tr -d '\r')" "EVAL 可用"
assert_eq "OK" "$(r EVAL "return redis.call('SET', KEYS[1], 'ok')" 1 lutest:k | tr -d '\r')" "redis.call 可嵌套调用"
assert_eq "ok" "$(r GET lutest:k | tr -d '\r')" "嵌套调用确实写入了数据"

echo
echo "--- V5b SCRIPT LOAD + EVALSHA（Spring Data Redis 实际走的路径）---"
SHA=$(r SCRIPT LOAD "return redis.call('GET', KEYS[1])" | tr -d '\r')
if [ ${#SHA} -eq 40 ]; then ok "SCRIPT LOAD 返回 40 位 SHA ($SHA)"; else bad "SCRIPT LOAD 返回异常: [$SHA]"; fi
assert_eq "ok" "$(r EVALSHA "$SHA" 1 lutest:k | tr -d '\r')" "EVALSHA 可正常执行"

echo
echo "--- V5c 扣减脚本四个分支 ---"
SK="verify:stock"; BK="verify:buyers"

# 分支 -3：库存未预热
r DEL "$SK" > /dev/null; r DEL "$BK" > /dev/null
assert_eq "-3" "$(r --eval "$STOCK_LUA" "$SK" "$BK" , alice | tr -d '\r')" "未预热库存返回 -3"

# 分支 成功：余票 1 -> 0
r SET "$SK" 1 > /dev/null
assert_eq "0" "$(r --eval "$STOCK_LUA" "$SK" "$BK" , alice | tr -d '\r')" "扣减成功返回扣减后余票 0"
assert_eq "0" "$(r GET "$SK" | tr -d '\r')" "库存确实减到 0"

# 分支 -1：售罄
assert_eq "-1" "$(r --eval "$STOCK_LUA" "$SK" "$BK" , bob | tr -d '\r')" "售罄返回 -1"
assert_eq "0" "$(r GET "$SK" | tr -d '\r')" "售罄时库存未被扣成负数"

# 分支 -2：重复用户（需要库存 > 0 才能走到这一步）
r SET "$SK" 5 > /dev/null
assert_eq "-2" "$(r --eval "$STOCK_LUA" "$SK" "$BK" , alice | tr -d '\r')" "重复用户返回 -2"
assert_eq "5" "$(r GET "$SK" | tr -d '\r')" "重复请求没有扣减库存"

# ---------------------------------------------------------------- V6 并发
echo
echo "--- V6 并发扣减（redis-benchmark，1000 请求 / 100 并发连接）---"

# 用 -x 从 stdin 读脚本内容，避免把多行 Lua 当命令行参数传给 Windows 原生 exe
bench_sha=$(cat "$LUA_DIR/stock_deduct_bench.lua" | r -x SCRIPT LOAD | tr -d '\r')
stock_sha=$(cat "$LUA_DIR/stock_deduct.lua"       | r -x SCRIPT LOAD | tr -d '\r')
echo "  压测变体脚本 SHA = $bench_sha"
echo "  扣减脚本原版 SHA = $stock_sha"

# C1：1000 个不同用户抢 100 张票（用压测变体，每次生成唯一用户 id）
SK1="verify:c1:stock"; BK1="verify:c1:buyers"; CK1="verify:c1:counter"
r SET "$SK1" 100 > /dev/null; r DEL "$BK1" > /dev/null; r DEL "$CK1" > /dev/null
"$BENCH" -h "$HOST" -p "$PORT" -n 1000 -c 100 -q EVALSHA "$bench_sha" 3 "$SK1" "$BK1" "$CK1" u > /dev/null 2>&1
s1=$(r GET "$SK1" | tr -d '\r'); b1=$(r SCARD "$BK1" | tr -d '\r')
echo "  C1 结果: 余票=$s1  成功购票人数=$b1"
assert_eq "0"   "$s1" "C1 100 张票全部售出且未超卖（余票恰好为 0）"
assert_eq "100" "$b1" "C1 恰好 100 人购票成功，无丢失更新"

# C2：1000 个并发请求全部来自同一个用户
#
# 【为什么这个测试比 C1 更重要】
#   C1 里每个用户都是新的，SISMEMBER 永远返回 0，压根没检验到去重逻辑。
#   C2 才有真正的竞态：如果「查 SISMEMBER」和「SADD」之间存在窗口，
#   多个并发请求会同时查到 0、同时扣减——最终会出现 buyers 里有重复项、
#   或者库存被扣了不止 1 次。
#   期望结果：库存 100 -> 99（只扣 1 次），集合里有且只有 1 个元素。
SK2="verify:c2:stock"; BK2="verify:c2:buyers"
r SET "$SK2" 100 > /dev/null; r DEL "$BK2" > /dev/null
"$BENCH" -h "$HOST" -p "$PORT" -n 1000 -c 100 -q EVALSHA "$stock_sha" 2 "$SK2" "$BK2" sameuser > /dev/null 2>&1
s2=$(r GET "$SK2" | tr -d '\r'); b2=$(r SCARD "$BK2" | tr -d '\r')
echo "  C2 结果: 余票=$s2  成功购票人数=$b2"
assert_eq "99" "$s2" "C2 同一用户 1000 次并发请求只扣了 1 张票"
assert_eq "1"  "$b2" "C2 同一用户只被记录 1 次（去重判断无竞态窗口）"

# ---------------------------------------------------------------- 清理
echo
echo "--- 清理测试数据 ---"
for k in lutest:k verify:stock verify:buyers verify:c1:stock verify:c1:buyers verify:c1:counter verify:c2:stock verify:c2:buyers verify:c2:counter; do
    r DEL "$k" > /dev/null
done
echo "  已清理"

echo
echo "================================================================"
echo " 结果: PASS=$PASS  FAIL=$FAIL"
echo "================================================================"
[ "$FAIL" -eq 0 ] || exit 1
