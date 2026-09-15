-- 库存原子预扣脚本（阶段 5 的核心，此处为参考实现，届时会移入 src/main/resources/lua/）
--
-- KEYS[1] = 库存 key          stock:train:{trainId}:{date}:{seatType}
-- KEYS[2] = 已购用户集合 key    buyers:train:{trainId}:{date}:{seatType}
-- ARGV[1] = 用户 id
--
-- 返回值：
--   >= 0  扣减成功，值为扣减后的余票
--   -1    库存已售罄
--   -2    该用户已购买过（重复请求）
--   -3    库存未预热（key 不存在）

-- 【做了什么】把「判断余票」和「扣减库存」合并成一次 Redis 调用。
--
-- 【为什么必须用 Lua】
--   如果分成两条命令（先 GET 判断、再 DECR），两个并发请求会穿插执行：
--     A: GET stock -> 1     B: GET stock -> 1     两条都以为还有票
--     A: DECR       -> 0    B: DECR       -> -1   卖出 2 张，超卖
--   Redis 执行 Lua 脚本时不会被其他命令打断，整段脚本是一个原子操作，
--   所以「读-判断-写」之间不存在任何窗口，从根上消除超卖。
--
-- 【和「WATCH + 事务」相比】
--   WATCH/MULTI 是乐观锁：冲突时要客户端重试，高并发下重试风暴会把
--   失败率放大。Lua 是服务端一次执行到底，没有重试，更适合秒杀这种
--   写冲突极度集中的场景。
--
-- 【和「分布式锁」相比】
--   这里不需要分布式锁。锁要解决的是「多个进程同时改一份数据」，
--   而 Redis 单线程执行命令，加上 Lua 的原子性，已经不存在这个问题了。
--   再加一层锁只会增加开销和一个新的故障点。

-- GET 一个不存在的 key 在 Lua 里返回 boolean false（不是 nil），
-- tonumber(false) 得到 nil，所以用 == nil 判断「未预热」。
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -3
end

-- 先判断再扣减，保证库存永远不为负。
-- 注意这里不能写成「先 DECR 再判断 <0 就回滚」：DECR 会把库存短暂改成负数，
-- 而对账任务或监控一旦在这个瞬间读到，就会误报。
if stock <= 0 then
    return -1
end

-- 同一用户重复提交的拦截放在扣库存之前：
-- 顺序反过来的话，重复请求会先把库存扣掉再被拒绝，需要额外回补，多一次写操作。
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

-- 返回扣减后的余票，便于调用方直接使用，省掉一次 GET。
return stock - 1
