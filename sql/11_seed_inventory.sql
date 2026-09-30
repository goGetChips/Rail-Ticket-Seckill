-- =============================================================================
-- 11_seed_inventory.sql   库存库的种子数据
-- =============================================================================
-- ⚠️ 本脚本会先清空 rail_inventory.t_seat_inventory，重复执行会覆盖已有数据。仅用于开发环境。
--
-- 执行（必须指定 utf8mb4）：
--   mysql -h 127.0.0.1 -P 3306 -u root -p --default-character-set=utf8mb4 < sql/11_seed_inventory.sql
--
-- 【为什么必须给命令行加 --default-character-set=utf8mb4】
--   本机是中文 Windows，mysql 客户端默认按 GBK 解释发送的字节。
--   本文件虽然是纯 ASCII 的（没有中文数据），但表注释和库里的车站名是中文，
--   为了和 10_seed_train.sql 保持一致的用法，这里也要求带上这个参数。
--
-- =============================================================================
-- 【为什么库存要单独一个种子脚本，而不是并进 10_seed_train.sql】
-- =============================================================================
--   这两张种子数据分属两个 schema：
--     · 10_seed_train.sql   →  rail_train.t_train / t_train_station / t_station
--     · 11_seed_inventory.sql →  rail_inventory.t_seat_inventory
--   按数据所有权拆开，将来 rail_inventory 迁到独立服务时，
--   这个脚本跟着它走，不会和车次库的种子数据缠在一起。
--
--   ⚠️ **代价：两个脚本必须成对执行，且顺序固定。**
--      10 会 `ALTER TABLE t_train AUTO_INCREMENT = 1`，把车次 id 重置回 1、2、3。
--      而 11 是**通过 train_no 子查询**去引用车次 id 的。
--      如果只重跑 10 不重跑 11：
--        · 10 重置了 t_train 的自增计数器，但**它不碰 rail_inventory**
--        · 于是库存表里那些 train_id 仍然指向"上一次" id 的行
--        · 极端情况下会变成"G1 的库存挂到了 G3 名下"，而两个查询各自看起来都是对的
--      **所以：重跑 10 之后必须接着重跑 11。**
--      这也是为什么 11 开头要 DELETE —— 不清空的话 uk_train_date_seat 会撞唯一键，
--      或者"看起来插入成功了，但 total_count / price 还是上一版的旧值"。
--
-- =============================================================================
-- 数据性质说明（和 10_seed_train.sql 一样，不要在面试里吹成真实数据）
-- =============================================================================
--   **票价 total_count / sold_count 全部是为演示编写的示例值。**
--   票价量级参考了真实京沪高铁（约 1318 km），但**不是官方数据**；
--   total_count 是按"16 节编组"的常见容量随手定的，也没有查证。
--   面试被问到就直说"为了演示编的，量级参考真实数据"——
--   这与 docs/01-project-guide.md 的「禁止编造数字」规则一致：
--   不实测就不能写成事实，但可以写成**演示用示例值**并标注清楚。
--
--   travel_date 用 CURDATE() 相对计算而不是写死日期，原因见下方注释。
-- =============================================================================

USE `rail_inventory`;

-- -----------------------------------------------------------------------------
-- 0. 清空（必须先清，理由见上方「两个脚本必须成对执行」）
-- -----------------------------------------------------------------------------
DELETE FROM `rail_inventory`.`t_seat_inventory`;

-- 【为什么不在这里 ALTER TABLE ... AUTO_INCREMENT = 1（和 10_seed_train.sql 不一样）】
--   两个理由，都是刻意的：
--     1) **权限**。ALTER 属于 DDL，需要 root；而本文件的其余语句只需要 DML。
--        应用账号 `rail` 对 rail_inventory 有 SELECT/INSERT/UPDATE/DELETE
--        （见 sql/00_init.sql），但**没有 ALTER**。
--        mysql 客户端在批处理模式下遇到错误会中止执行，
--        所以留着这行会让整个脚本连 INSERT 都跑不完 —— 而 INSERT 才是本文件的目的。
--        去掉它之后，本文件可以由 `rail` 账号独立执行，不依赖 root。
--     2) **它本来就达不到目的**。重置自增计数器的意义是"让每次重跑结果可复现"，
--        但本文件的 travel_date 用的是 CURDATE() 相对日期（理由见下），
--        每次执行的数据本来就不同。为一件已经放弃的事去要求 root 权限，不划算。
--   代价：重复执行时 t_seat_inventory.id 会一直增长。这不影响任何查询
--   （业务主键是 uk_train_date_seat，不是 id）。

-- -----------------------------------------------------------------------------
-- 1. 库存
-- -----------------------------------------------------------------------------
-- 【为什么 travel_date 用 DATE_ADD(CURDATE(), ...) 而不是写死 '2026-10-01'】
--   写死日期的种子数据会**过期**：过上几天，所有库存都变成了"昨天的票"，
--   余票接口再查就永远返回空数组，而接口本身没有任何错误。
--   于是"接口是不是坏了"变成一个需要人工判断的问题，而不是看一眼就知道。
--   用相对日期，这份数据永远指向"明天 / 后天 / 大后天"，随时可测。
--
--   代价是**每次执行结果不可逐字节复现**（日期在变）。
--   这是刻意的取舍：数据可测性 > 执行结果可对比。
--   真正需要固定日期的场景是回归测试，那时应该用测试框架里的事务回滚，而不是种子脚本。
--
-- 【席别编码】1=商务座 2=一等座 3=二等座（见 sql/03_rail_inventory.sql 的列注释）
--
-- 【为什么车次用 train_no 子查询而不是写 train_id = 1,2,3】
--   和 10_seed_train.sql 同样的理由：id 是自增的、不可自解释的魔法数字。
--   写 'G1' 则一眼知道这行属于哪趟车，而且**不依赖插入顺序**。
--
-- 【sold_count 为什么不全填 0】
--   全填 0 的话，余票接口返回的 remaining 恰好等于 total_count，
--   此时"remaining 算错了"和"remaining 算对了"看起来一模一样（都是满票）。
--   让 sold_count 取各种值 —— 包括一行**恰好售罄**（G3 明天商务座 20/20）——
--   才能让 total - sold 这个减法真的被验证到。
--   注意 sold_count <= total_count 是表上的 CHECK 约束，写超了会被数据库直接拒绝。

INSERT INTO `rail_inventory`.`t_seat_inventory`
    (`train_id`, `travel_date`, `seat_type`, `price`, `total_count`, `sold_count`)
-- ---------------------------------------------------------------- G1 北京南 → 上海虹桥
SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1, 1748.00,  20,   5
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 2,  933.00, 100,  30
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 3,  553.00, 500, 200
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 1, 1748.00,  20,   2
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 2,  933.00, 100,  12
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 3,  553.00, 500,  60
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 1, 1748.00,  20,   0
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 2,  933.00, 100,   0
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G1'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 3,  553.00, 500,   0

-- ---------------------------------------------------------------- G3 北京南 → 上海虹桥
-- 明天商务座恰好 20/20：**已售罄**，remaining 为 0。用来验证"0 余票"不是异常。
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1, 1748.00,  20,  20
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 2,  933.00, 100,  45
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 3,  553.00, 500, 120
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 1, 1748.00,  20,   1
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 2,  933.00, 100,   8
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 3,  553.00, 500,  33
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 1, 1748.00,  20,   0
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 2,  933.00, 100,   0
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G3'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 3,  553.00, 500,   0

-- ---------------------------------------------------------------- G2 上海虹桥 → 北京南（反方向）
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1, 1748.00,  20,   3
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 2,  933.00, 100,  21
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 1 DAY), 3,  553.00, 500,  88
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 1, 1748.00,  20,   1
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 2,  933.00, 100,   6
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 2 DAY), 3,  553.00, 500,  25
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 1, 1748.00,  20,   0
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 2,  933.00, 100,   0
UNION ALL SELECT (SELECT `id` FROM `rail_train`.`t_train` WHERE `train_no` = 'G2'),
       DATE_ADD(CURDATE(), INTERVAL 3 DAY), 3,  553.00, 500,   0;

-- -----------------------------------------------------------------------------
-- 2. 执行结果确认（这条 SELECT 是给执行者看的，不是给程序用的）
-- -----------------------------------------------------------------------------
-- 【这个自连接在验证什么】
--   光看行数 27 只能证明"插了 27 行"，不能证明"每一行都挂对了车次"。
--   所以这里把 train_no 拉出来按车次分组计数：
--   如果 G1/G3/G2 各 9 行，说明 train_id 的子查询真的解析到了三趟不同的车。
--   如果三个数字不是各 9，或者出现了 NULL 车次，就是上面「两个脚本必须成对执行」
--   那个坑 —— 重跑 10_seed_train.sql 即可。
SELECT t.`train_no` AS `车次`, COUNT(*) AS `库存行数`
FROM `rail_inventory`.`t_seat_inventory` i
         JOIN `rail_train`.`t_train` t ON t.`id` = i.`train_id`
GROUP BY t.`train_no`
ORDER BY t.`train_no`;

-- 再看一眼"余票"这个减法本身，确认 sold_count 取到了各种值：
-- 预期能看到 G3 明天商务座 remaining = 0（售罄），以及一批 remaining = total 的新开票行。
SELECT t.`train_no` AS `车次`, i.`travel_date` AS `乘车日期`, i.`seat_type` AS `席别`,
       i.`total_count`, i.`sold_count`,
       i.`total_count` - i.`sold_count` AS `remaining`
FROM `rail_inventory`.`t_seat_inventory` i
         JOIN `rail_train`.`t_train` t ON t.`id` = i.`train_id`
ORDER BY i.`travel_date`, t.`train_no`, i.`seat_type`;

-- 预期：总行数 27，且 G1 / G2 / G3 各 9 行
