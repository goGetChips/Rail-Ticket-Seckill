-- =============================================================================
-- 99_verify.sql   验证阶段 2 的建表结果
-- =============================================================================
-- 用法（**必须加 --force**，否则第一处"故意制造的错误"就会中断整个脚本）：
--
--   mysql --force -h 127.0.0.1 -P 3306 -u rail -prail123456 < sql/99_verify.sql
--
-- 用 rail（应用账号）而不是 root 执行，本身就是一次检查：
--   如果应用账号的权限不够用，这里就跑不通。
--
-- 本脚本的测试数据全部使用 ID = 999999999 这个明显的哨兵值，
-- 不会碰任何真实业务数据，且结束时全部清理。
--
-- ⚠️ 脚本中间会出现若干行 "ERROR ..." —— **那不是失败，那正是成功的证据**。
--    每一处错误前面都有 "--- 期望出现 ERROR ---" 的标记。
--    判断标准是最后的「行为验证」汇总：三列都应该是 "被数据库拒绝"。
-- =============================================================================

-- =============================================================================
-- 第一部分：结构检查（期望全部输出 PASS）
-- =============================================================================

SELECT '---------- 第一部分：结构检查 ----------' AS `===`;

SELECT CASE WHEN COUNT(*) = 4 THEN 'PASS' ELSE CONCAT('FAIL 实际=', COUNT(*)) END AS `检查1 四个数据库`
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME IN ('rail_user','rail_train','rail_inventory','rail_order');

SELECT CASE WHEN COUNT(*) = 9 THEN 'PASS' ELSE CONCAT('FAIL 实际=', COUNT(*)) END AS `检查2 九张表`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('rail_user','rail_train','rail_inventory','rail_order')
  AND TABLE_NAME IN ('t_user','t_station','t_train','t_train_station',
                     't_seat_inventory','t_stock_flow','t_order','t_order_item','t_local_message');

-- MySQL 8.0.16 之前 CHECK 约束是"解析但不执行"的，必须确认它真的被登记了
SELECT CASE WHEN COUNT(*) = 2 THEN 'PASS' ELSE CONCAT('FAIL 实际=', COUNT(*)) END AS `检查3 库存表 CHECK 约束`
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = 'rail_inventory'
  AND TABLE_NAME = 't_seat_inventory'
  AND CONSTRAINT_TYPE = 'CHECK';

-- 表名/列名必须全小写：本机 lower_case_table_names=1 会掩盖大写问题，
-- 但部署到 Linux（默认 0）时，大写表名会直接报"表不存在"
SELECT CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE CONCAT('FAIL 有大写表名=', COUNT(*)) END AS `检查4 表名全小写`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA LIKE 'rail\_%'
  AND BINARY TABLE_NAME <> LOWER(TABLE_NAME);

SELECT CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE CONCAT('FAIL 有非 utf8mb4 表=', COUNT(*)) END AS `检查5 字符集统一 utf8mb4`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA LIKE 'rail\_%'
  AND TABLE_COLLATION NOT LIKE 'utf8mb4%';

-- 关键索引是否都存在
SELECT TABLE_SCHEMA AS `库`, TABLE_NAME AS `表`, INDEX_NAME AS `索引`
FROM information_schema.STATISTICS
WHERE (TABLE_SCHEMA='rail_order'     AND TABLE_NAME='t_order_item'      AND INDEX_NAME='uk_user_train_date_seat')
   OR (TABLE_SCHEMA='rail_inventory' AND TABLE_NAME='t_seat_inventory'  AND INDEX_NAME='uk_train_date_seat')
   OR (TABLE_SCHEMA='rail_inventory' AND TABLE_NAME='t_stock_flow'      AND INDEX_NAME='uk_biz_id_type')
   OR (TABLE_SCHEMA='rail_order'     AND TABLE_NAME='t_order'           AND INDEX_NAME='uk_order_no')
GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME
ORDER BY TABLE_SCHEMA;

-- =============================================================================
-- 第二部分：行为验证（故意制造违规，确认数据库真的拦住了）
-- =============================================================================

SELECT '' AS ''; SELECT '---------- 第二部分：行为验证 ----------' AS `===`;
SELECT '下面每处 ERROR 都是预期内的，是约束生效的证据' AS `说明`;

-- ---------------------------------------------------------------- 准备数据
DELETE FROM rail_inventory.t_seat_inventory WHERE train_id = 999999999;
DELETE FROM rail_order.t_order_item         WHERE user_id  = 999999999;

INSERT INTO rail_inventory.t_seat_inventory
    (train_id, travel_date, seat_type, price, total_count, sold_count)
VALUES (999999999, '2026-10-01', 3, 553.00, 100, 0);

-- ---------------------------------------------------------------- 测试 A：唯一索引挡住重复购票
SELECT '' AS ''; SELECT '--- 测试A 期望出现 ERROR: Duplicate entry ---' AS `===`;

INSERT INTO rail_order.t_order_item
    (order_id, user_id, train_id, travel_date, seat_type, from_station_id, to_station_id, price)
VALUES (999999999, 999999999, 999999999, '2026-10-01', 3, 1, 2, 553.00);

-- 这一条应当被 uk_user_train_date_seat 拒绝
INSERT INTO rail_order.t_order_item
    (order_id, user_id, train_id, travel_date, seat_type, from_station_id, to_station_id, price)
VALUES (999999998, 999999999, 999999999, '2026-10-01', 3, 1, 2, 553.00);

SELECT CASE WHEN COUNT(*) = 1 THEN '被数据库拒绝（只留下 1 条）'
            ELSE CONCAT('FAIL 重复数据进去了！实际=', COUNT(*)) END AS `测试A 结果`
FROM rail_order.t_order_item WHERE user_id = 999999999;

-- ---------------------------------------------------------------- 测试 B：CHECK 挡住负库存
SELECT '' AS ''; SELECT '--- 测试B 期望出现 ERROR: Check constraint ---' AS `===`;

UPDATE rail_inventory.t_seat_inventory
   SET sold_count = -1
 WHERE train_id = 999999999;

SELECT CASE WHEN sold_count = 0 THEN '被数据库拒绝（仍是 0）'
            ELSE CONCAT('FAIL 库存变成负的了！实际=', sold_count) END AS `测试B 结果`
FROM rail_inventory.t_seat_inventory WHERE train_id = 999999999;

-- ---------------------------------------------------------------- 测试 C：CHECK 挡住超卖
SELECT '' AS ''; SELECT '--- 测试C 期望出现 ERROR: Check constraint ---' AS `===`;

UPDATE rail_inventory.t_seat_inventory
   SET sold_count = 101
 WHERE train_id = 999999999;

SELECT CASE WHEN sold_count = 0 THEN '被数据库拒绝（仍是 0）'
            ELSE CONCAT('FAIL 超卖了！实际=', sold_count) END AS `测试C 结果`
FROM rail_inventory.t_seat_inventory WHERE train_id = 999999999;

-- ---------------------------------------------------------------- 测试 D：条件 UPDATE 的行为
-- 这是阶段 5 会真正使用的扣减手法，现在先验证它的行为符合预期
SELECT '' AS ''; SELECT '--- 测试D 条件 UPDATE（阶段5 的扣减手法）---' AS `===`;

-- D1：还有余票时扣减，期望受影响 1 行
UPDATE rail_inventory.t_seat_inventory
   SET sold_count = sold_count + 1
 WHERE train_id = 999999999 AND travel_date = '2026-10-01' AND seat_type = 3
   AND sold_count < total_count;
SELECT ROW_COUNT() AS `D1 有余票时扣减-受影响行数(期望1)`;

-- D2：把库存置为售罄（sold = total），再扣一次，期望受影响 0 行
UPDATE rail_inventory.t_seat_inventory
   SET sold_count = total_count
 WHERE train_id = 999999999;

UPDATE rail_inventory.t_seat_inventory
   SET sold_count = sold_count + 1
 WHERE train_id = 999999999 AND travel_date = '2026-10-01' AND seat_type = 3
   AND sold_count < total_count;
SELECT ROW_COUNT() AS `D2 已售罄时扣减-受影响行数(期望0)`;

-- ---------------------------------------------------------------- 清理
SELECT '' AS ''; SELECT '--- 清理测试数据 ---' AS `===`;
DELETE FROM rail_inventory.t_seat_inventory WHERE train_id = 999999999;
DELETE FROM rail_order.t_order_item         WHERE user_id  = 999999999;

SELECT (SELECT COUNT(*) FROM rail_inventory.t_seat_inventory WHERE train_id = 999999999) AS `残留-库存`,
       (SELECT COUNT(*) FROM rail_order.t_order_item         WHERE user_id  = 999999999) AS `残留-订单明细`;

SELECT '' AS ''; SELECT '=========== 验证结束 ===========' AS `===`;
