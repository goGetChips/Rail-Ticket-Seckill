-- stock_deduct.lua 的并发压测变体，仅用于 scripts/env/verify-redis.sh
--
-- KEYS[1] = 库存 key
-- KEYS[2] = 已购用户集合 key
-- KEYS[3] = 用户 id 计数器 key（仅压测用）
-- ARGV[1] = 用户 id 前缀
--
-- 【为什么需要这个变体】
--   redis-benchmark 会把同一条命令原样重复 N 次，ARGV 不能逐次变化。
--   如果 1000 次请求都用同一个用户 id，只有第 1 次能扣减成功，
--   最终测到的是「重复购买拦截」而不是「并发扣减」——两个完全不同的问题。
--   所以这里用 INCR 计数器为每次请求生成唯一用户 id。
--
--   INCR 自身也是原子命令，不会给这个测试引入新的竞态。
--
-- 【这个变体证明了什么、没证明什么】
--   证明了：这个第三方 Windows 移植版在高并发下，Lua 脚本内的
--           GET / DECR / SADD 组合没有出现丢失更新或状态损坏。
--   没证明：业务上的不超卖。Redis 单线程执行命令，Lua 脚本天然原子，
--           所以这个测试本质上不可能失败——它是「体检」而非「证明」。
--           本项目真正的不超卖风险不在这里，而在：
--             ① 阶段 5 的 MySQL 兜底扣减（没有 Lua 保护）
--             ② 阶段 7「Redis 扣成功但订单创建失败」的补偿窗口

local uid = ARGV[1] .. ':' .. redis.call('INCR', KEYS[3])

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -3
end
if stock <= 0 then
    return -1
end
if redis.call('SISMEMBER', KEYS[2], uid) == 1 then
    return -2
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], uid)

return stock - 1
