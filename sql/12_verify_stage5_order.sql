-- =============================================================================
-- 12_verify_stage5_order.sql   阶段 5 校验：条件 UPDATE 扣库存的"数字对不对"
-- =============================================================================
-- 用法：
--   mysql -h 127.0.0.1 -P 3306 -u rail -prail123456 --default-character-set=utf8mb4 \
--         < sql/12_verify_stage5_order.sql
--
-- ⚠️ --default-character-set=utf8mb4 不能省：
--    Windows 的 mysql 客户端默认用 GBK 解释连接，
--    中文注释和 remark 里的中文会变成乱码，
--    而"乱码"和"数据写错了"在输出里长得一样。
--
-- 与 99_verify.sql 的分工：
--    99_verify.sql  验证**建表结果**（阶段 2）：库在不在、约束在不在
--    本脚本          验证**运行时结果**（阶段 5）：并发跑完之后数字对不对
--
-- =============================================================================
--  ⭐ 本脚本的存在理由：阶段 5 起，"通过了但数字是错的"才成为可能的失败
-- =============================================================================
--   阶段 0~4 全部只读，接口返回 200 就意味着数据没问题。
--   阶段 5 起有三种失败**不会**让接口报错：
--
--     超卖   两个请求都卖出去了同一张票（sold_count > total_count 或订单数 > 票额）
--     少卖   扣了库存但订单没写成功（库存少了，票没卖出去，静默）
--     重复   同一用户同一趟车同一天同一席别有两张票
--
--   它们都只能靠**对账 SQL** 发现。下面的 ①~④ 就是这四种情况的可执行检查。
--   全部以"0 行 = 通过"的形式表达（除了打印信息的那几段）。
--
-- ⚠️ 本脚本**只读**，不修改任何数据。fixture 的创建和清理见文末注释。
-- =============================================================================

USE `rail_train`;

SELECT '========== 阶段 5 校验开始 ==========' AS `===`;

-- -----------------------------------------------------------------------------
-- ① 超卖：sold_count 永远不能超过 total_count
-- -----------------------------------------------------------------------------
-- 期望：0 行
--
-- ⚠️ 这一条理论上"永远"返回 0 行 —— 因为数据库上还有
--    CHECK (sold_count <= total_count) 这条约束兜着（sql/03_rail_inventory.sql）。
--    也就是说：**它红了说明 CHECK 约束也没了**，那是一个更严重的问题。
--
--    ⛔ 所以：**①通过 ≠ 应用层没写错。**
--      它只是"最后一道防线还在"。真正的证据是 ③（订单 ↔ 流水 ↔ 库存三方对账）。
SELECT '---------- ① 超卖检查（期望 0 行）----------' AS `===`;

SELECT id, train_id, travel_date, seat_type, sold_count, total_count,
       CONCAT('超卖 ', sold_count - total_count, ' 张') AS `问题`
FROM rail_inventory.t_seat_inventory
WHERE sold_count > total_count;

SELECT CASE WHEN COUNT(*) = 0 THEN 'PASS ① 无超卖'
            ELSE CONCAT('FAIL ① 发现 ', COUNT(*), ' 行超卖') END AS `① 超卖`
FROM rail_inventory.t_seat_inventory
WHERE sold_count > total_count;

-- -----------------------------------------------------------------------------
-- ② 重复购票：同一「用户 + 车次 + 日期 + 席别」只能有一张票
-- -----------------------------------------------------------------------------
-- 期望：0 行
--
-- ⚠️⚠️ 【这一条能通过，原因和你想的不一样】
--   它返回 0 行**不是因为业务代码写对了**，而是因为
--   uk_user_train_date_seat 这个唯一索引让 COUNT(*) > 1 **物理上不可能**。
--
--   所以这条校验的真实作用是：**证明那个唯一索引真的建了。**
--   （如果哪天有人把 UNIQUE KEY 改成 KEY，这条会立刻变红 —— 那正是它的价值。）
SELECT '---------- ② 重复购票检查（期望 0 行）----------' AS `===`;

SELECT user_id, train_id, travel_date, seat_type, COUNT(*) AS `张数`
FROM rail_order.t_order_item
GROUP BY user_id, train_id, travel_date, seat_type
HAVING COUNT(*) > 1;

SELECT CASE WHEN COUNT(*) = 0 THEN 'PASS ② 无重复购票'
            ELSE CONCAT('FAIL ② 发现 ', COUNT(*), ' 组重复') END AS `② 重复购票`
FROM (
    SELECT user_id, train_id, travel_date, seat_type
    FROM rail_order.t_order_item
    GROUP BY user_id, train_id, travel_date, seat_type
    HAVING COUNT(*) > 1
) AS dup;

-- -----------------------------------------------------------------------------
-- ③ 三方对账：订单数 ↔ 扣减流水 ↔ 库存增量
-- -----------------------------------------------------------------------------
-- 期望：0 行
--
-- 🔴🔴 【这里替换掉了 docs/05-seckill.md §六 里的第 ③ 条 —— 那条永远不可能成立】
--
--   原文是：
--     SELECT SUM(sold_count) FROM rail_inventory.t_seat_inventory  ← A
--     SELECT COUNT(*) FROM rail_order.t_order WHERE status <> 2    ← B
--     然后要求 A = B
--
--   ⛔ **A 永远大于 B，因为 A 把种子数据里本来就卖掉的几百张全算进来了**，
--      而 t_order 是从 0 行开始的。两个数从第一次运行起就不可能相等，
--      它不是一个"偶尔会失败的检查"，而是一个"必然失败的检查"——
--      必然失败的检查等于没有检查（没人会去看一个总是红的灯）。
--
--   ⭐ 【正确的问法】不要问"全库总共卖了多少张"，
--      要问"**同一趟车同一天同一席别**，卖出的票数和扣减的流水数是否一致"。
--      这个问题与种子数据无关，因为种子数据没有订单，也就不会参与分组。
--
--   ⭐ 为什么拿"流水"当基准，而不是拿 sold_count 当基准：
--      sold_count 里含着**本应用存在之前**就有的销量（种子脚本直接写的数），
--      而 t_stock_flow 只记录本应用产生的每一次扣减。
--      两者之差就是"种子基线"，是个未知数，不能用来判定。
--      流水是**我们自己的账本**，可以和订单逐笔对上。
SELECT '---------- ③ 订单 ↔ 扣减流水 对账（期望 0 行）----------' AS `===`;

-- 两边各自聚合成"同一趟车同一天同一席别"的一组，再比大小。
-- 用两个派生表（而不是相关子查询）是因为这样**两个方向都能查到**：
--   订单数 > 流水数 → 有订单没扣减
--   订单数 < 流水数 → 有扣减没订单（← 这就是少卖，而且是最难发现的那种）
SELECT g.train_id,
       g.travel_date,
       g.seat_type,
       g.order_cnt AS `有效订单数`,
       COALESCE(f.flow_sum, 0) AS `扣减流水之和`,
       CONCAT('差 ', g.order_cnt - COALESCE(f.flow_sum, 0), ' 张') AS `问题`
FROM (
    SELECT o.train_id, o.travel_date, o.seat_type, COUNT(*) AS order_cnt
    FROM rail_order.t_order_item o
    JOIN rail_order.t_order t ON t.id = o.order_id AND t.status <> 2
    GROUP BY o.train_id, o.travel_date, o.seat_type
) g
LEFT JOIN (
    SELECT train_id, travel_date, seat_type, SUM(change_count) AS flow_sum
    FROM rail_inventory.t_stock_flow
    WHERE change_type = 2
    GROUP BY train_id, travel_date, seat_type
) f ON f.train_id = g.train_id
   AND f.travel_date = g.travel_date
   AND f.seat_type = g.seat_type
WHERE g.order_cnt <> COALESCE(f.flow_sum, 0);

SELECT CASE WHEN COUNT(*) = 0 THEN 'PASS ③ 订单与流水一一对应'
            ELSE CONCAT('FAIL ③ 有 ', COUNT(*), ' 组对不上') END AS `③ 三方对账`
FROM (
    SELECT g.train_id, g.travel_date, g.seat_type
    FROM (
        SELECT o.train_id, o.travel_date, o.seat_type, COUNT(*) AS order_cnt
        FROM rail_order.t_order_item o
        JOIN rail_order.t_order t ON t.id = o.order_id AND t.status <> 2
        GROUP BY o.train_id, o.travel_date, o.seat_type
    ) g
    LEFT JOIN (
        SELECT train_id, travel_date, seat_type, SUM(change_count) AS flow_sum
        FROM rail_inventory.t_stock_flow
        WHERE change_type = 2
        GROUP BY train_id, travel_date, seat_type
    ) f ON f.train_id = g.train_id
       AND f.travel_date = g.travel_date
       AND f.seat_type = g.seat_type
    WHERE g.order_cnt <> COALESCE(f.flow_sum, 0)
) AS mismatch;

-- ③b 信息（**不判定**，只打印）：把库存行上的 sold_count 一起列出来
--      `sold_count - 流水之和` = 本应用存在之前的销量（种子基线）。
--      ⚠️ 它是正数**不代表有问题** —— 种子脚本直接写了 sold_count，
--         却没有为那些销量生成流水行（那时还没有这张流水表要写）。
--         这正是为什么 ③ 不能拿 sold_count 当基准。
SELECT '---------- ③b 信息：库存 vs 流水（基线=种子数据，不判定）----------' AS `===`;

SELECT i.train_id,
       i.travel_date,
       i.seat_type,
       i.total_count AS `票额`,
       i.sold_count  AS `已售`,
       COALESCE(f.flow_sum, 0) AS `本应用扣减`,
       i.sold_count - COALESCE(f.flow_sum, 0) AS `种子基线`
FROM rail_inventory.t_seat_inventory i
LEFT JOIN (
    SELECT train_id, travel_date, seat_type, SUM(change_count) AS flow_sum
    FROM rail_inventory.t_stock_flow
    WHERE change_type = 2
    GROUP BY train_id, travel_date, seat_type
) f ON f.train_id = i.train_id AND f.travel_date = i.travel_date AND f.seat_type = i.seat_type
WHERE f.flow_sum IS NOT NULL
ORDER BY i.travel_date, i.train_id, i.seat_type;

-- -----------------------------------------------------------------------------
-- ④ 少卖：每一张有效订单都必须有一条 change_type=2 且数量为 1 的扣减流水
-- -----------------------------------------------------------------------------
-- 期望：0 行
--
-- ⭐⭐ 【这是本阶段**新增**的检查，也是 t_stock_flow 这张表存在的全部理由】
--
--   CHECK 约束 ck_sold_not_exceed_total 只能证明"没卖多"（超卖），
--   它**看不见"卖少了"**：扣了库存但订单没写成功时，
--   sold_count 增加、订单数不变，两条 CHECK 约束都是满足的。
--
--   ⭐ 这一条不受种子数据干扰，因为 t_order 从阶段 5 起才有数据 ——
--     也就是**所有订单都是本应用产生的**，可以全库检查，不需要哨兵区间。
--
--   ⚠️ 它红了的最可能原因：
--      OrderService 里那个 "catch (DuplicateKeyException) → return 409"
--      的写法。catch 里 return 会让 Spring 提交事务，于是
--      **库存扣减被提交、订单被回滚** —— 就是典型的少卖，
--      而且接口返回的 409 看起来完全正常。
--      判据：**catch 块里只能 throw，不能 return。**
SELECT '---------- ④ 少卖检查（期望 0 行）----------' AS `===`;

SELECT o.order_no,
       o.user_id,
       o.status,
       f.id        AS `流水ID`,
       f.change_count AS `流水数量`,
       CASE WHEN f.id IS NULL THEN '有订单但完全没有扣减流水'
            ELSE CONCAT('流水数量是 ', f.change_count, '，应为 1') END AS `问题`
FROM rail_order.t_order o
LEFT JOIN rail_inventory.t_stock_flow f
       ON f.biz_id = o.order_no AND f.change_type = 2
WHERE o.status <> 2
  AND (f.id IS NULL OR f.change_count <> 1);

SELECT CASE WHEN COUNT(*) = 0 THEN 'PASS ④ 无少卖'
            ELSE CONCAT('FAIL ④ 发现 ', COUNT(*), ' 笔少卖') END AS `④ 少卖`
FROM rail_order.t_order o
LEFT JOIN rail_inventory.t_stock_flow f
       ON f.biz_id = o.order_no AND f.change_type = 2
WHERE o.status <> 2
  AND (f.id IS NULL OR f.change_count <> 1);

-- -----------------------------------------------------------------------------
-- ⑤ 库存不能为负
-- -----------------------------------------------------------------------------
-- 期望：0 行。同 ①，由 CHECK ck_sold_non_negative 兜底 ——
-- 红了说明约束也没了。
SELECT '---------- ⑤ 库存非负检查（期望 0 行）----------' AS `===`;

SELECT id, train_id, travel_date, seat_type, sold_count
FROM rail_inventory.t_seat_inventory
WHERE sold_count < 0;

-- -----------------------------------------------------------------------------
-- ⑥ 信息汇总（**不判定**，用来把真实数字回填进文档）
-- -----------------------------------------------------------------------------
-- ⭐ 存在的理由：docs/01-project-guide.md 的硬规则是"禁止编造数字"。
--   文档里要写的实测数字，从这一段里抄 —— 而不是凭印象写。
--   ⚠️ 抄之前先确认数据没被清理过。
SELECT '---------- ⑥ 信息汇总（不判定，供文档回填）----------' AS `===`;

SELECT '订单总数' AS `项目`, COUNT(*) AS `数量` FROM rail_order.t_order
UNION ALL SELECT '其中待支付', COUNT(*) FROM rail_order.t_order WHERE status = 0
UNION ALL SELECT '其中已支付', COUNT(*) FROM rail_order.t_order WHERE status = 1
UNION ALL SELECT '其中已取消', COUNT(*) FROM rail_order.t_order WHERE status = 2
UNION ALL SELECT '票（订单明细）总数', COUNT(*) FROM rail_order.t_order_item
UNION ALL SELECT '库存流水总数', COUNT(*) FROM rail_inventory.t_stock_flow
UNION ALL SELECT '其中 确认扣减(2)', COUNT(*) FROM rail_inventory.t_stock_flow WHERE change_type = 2
UNION ALL SELECT '其中 预扣(1) —— 阶段 7 才有', COUNT(*) FROM rail_inventory.t_stock_flow WHERE change_type = 1
UNION ALL SELECT '其中 回补(3) —— 阶段 7 才有', COUNT(*) FROM rail_inventory.t_stock_flow WHERE change_type = 3;

-- 本次跑出来的订单（按订单号倒序，最近 20 笔）
-- ⚠️ order_no 前 17 位是 yyyyMMddHHmmssSSS，所以按它倒序 ≈ 按创建时间倒序。
SELECT '---------- 最近 20 笔订单 ----------' AS `===`;

SELECT o.order_no, o.user_id, o.total_amount, o.status, o.create_time,
       i.train_id, i.travel_date, i.seat_type
FROM rail_order.t_order o
LEFT JOIN rail_order.t_order_item i ON i.order_id = o.id
ORDER BY o.order_no DESC
LIMIT 20;

SELECT '========== 阶段 5 校验结束 ==========' AS `===`;

-- =============================================================================
-- fixture 的创建与清理（**本脚本不执行**，需要时手工复制出来跑）
-- =============================================================================
--
-- 【为什么要自己造 fixture，而不是用种子数据】
--   sql/11_seed_inventory.sql 放的是"**执行它那天**"的 +1/+2/+3 天。
--   过了几天那些日期就变成过去的日期了，下单接口的 @FutureOrPresent
--   会直接拒绝 —— 于是你会以为是接口坏了。
--   ⭐ 测试用的库存行必须**当场造**，日期当场算。
--
-- ---- 造 fixture（把 @date / @train 改成你要用的值）----
--
-- SET @train = (SELECT id FROM rail_train.t_train WHERE train_no = 'G1');
-- SET @date  = CURDATE() + INTERVAL 60 DAY;
-- SET @seat  = 1;
--
-- DELETE FROM rail_inventory.t_seat_inventory
--  WHERE train_id = @train AND travel_date = @date AND seat_type = @seat;
--
-- INSERT INTO rail_inventory.t_seat_inventory
--        (train_id, travel_date, seat_type, price, total_count, sold_count)
-- VALUES (@train, @date, @seat, 1748.00, 20, 0);
-- --                                              ↑  ↑
-- --                        票额（并发测试的抢票数）  已售数**必须是 0**
-- --
-- -- ⚠️ 为什么 sold_count 必须从 0 开始：
-- --    这样"库存增量"就等于"本应用卖出的票数"，③ 和 ③b 才有意义。
-- --    从 50 开始的话，你就分不清那 50 张是谁卖的。
--
-- ---- 清理（顺序：流水 → 票 → 订单 → 库存）----
-- -- 库里**没有外键约束**（跨库外键在 MySQL 里做不到），所以顺序不影响执行，
-- -- 但按依赖方向删更不容易在将来加约束时出错。
--
-- SET @user_min = 9100000;   -- JUnit 哨兵区间
-- SET @user_max = 9199999;
-- SET @jm_min   = 9300000;   -- JMeter 哨兵区间
-- SET @jm_max   = 9399999;
--
-- DELETE FROM rail_inventory.t_stock_flow
--  WHERE train_id = @train AND travel_date = @date AND seat_type = @seat;
-- DELETE FROM rail_order.t_order_item
--  WHERE train_id = @train AND travel_date = @date AND seat_type = @seat;
-- DELETE FROM rail_order.t_order
--  WHERE user_id BETWEEN @user_min AND @user_max
--     OR user_id BETWEEN @jm_min   AND @jm_max;
-- DELETE FROM rail_inventory.t_seat_inventory
--  WHERE train_id = @train AND travel_date = @date AND seat_type = @seat;
--
-- =============================================================================
-- 关于"为什么不清理也可以"（以及为什么不建议留脏数据）
-- =============================================================================
--   阶段 5 之前的接口全只读，库里没有测试残留。
--   阶段 5 起测试会真的写数据，所以"测试留下的数据"第一次成为问题：
--     · 它会让 ② 之外的检查在下次运行时看到历史数据（虽然 ⑤ 里那几条不受影响）
--     · 它会让 ⑥ 的信息汇总里混进上一次的数字，回填文档时抄错
--     · 最要紧的是，它让"库里现在到底有几张票在卖"变得需要解释
--
--   所以 CI 化的下一步（阶段 6+）应该把 fixture 的造与清都放进自动化脚本里。
--   现在靠 JUnit 的 @AfterEach 和本文件末尾的注释手工兜着。
-- =============================================================================
