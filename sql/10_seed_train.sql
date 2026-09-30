-- =============================================================================
-- 10_seed_train.sql   车次库的种子数据
-- =============================================================================
-- ⚠️ 本脚本会先清空 rail_train 的三张表，重复执行会覆盖已有数据。仅用于开发环境。
--
-- 执行（必须指定 utf8mb4，否则中文会乱码）：
--   mysql -h 127.0.0.1 -P 3306 -u root -p --default-character-set=utf8mb4 < sql/10_seed_train.sql
--
-- ⚠️ 【为什么命令行必须加 --default-character-set=utf8mb4】
--   本机是中文 Windows，mysql 客户端默认按 **GBK** 解释发送的字节。
--   而这个文件本身是 UTF-8 编码的（文件头有 "北京南" 这样的中文）。
--   不加这个参数，MySQL 会拿 GBK 去解 UTF-8 的字节流，
--   结果是**表里存进去一串乱码，而且不报任何错**——数据看起来"插入成功"了。
--   这类问题的排查成本很高，因为它不在代码里，在工具调用方式里。
--
-- =============================================================================
-- 数据性质说明（重要，不要在面试里吹成真实数据）
-- =============================================================================
--   车站名、车次号、经停顺序取自**京沪高铁的真实运行框架**，用于演示。
--   但 **station_code（电报码）与时刻不保证与铁路官方数据一致**，
--   是本项目为演示编写的示例值。
--
--   为什么仍然要用真实的车站名和车次号：
--     用 "站点A → 站点B" 那种假数据，会让"按城市查车站""同一车次不能重复经过同一站"
--     这些约束显得没有意义。真实的名字能让每个约束的动机一目了然。
--
--   为什么必须诚实标注：面试官很可能会问"这数据哪来的"。
--   说"京沪高铁的真实结构 + 我自己编的电报码"是可信的；
--   说"我爬了 12306"而实际没爬，一问细节就露馅。
-- =============================================================================

USE `rail_train`;

-- -----------------------------------------------------------------------------
-- 0. 清空（顺序是"先删引用方，再删被引用方"）
-- -----------------------------------------------------------------------------
-- 说明：本项目的表**没有建外键约束**（原因见 docs/06-database.md §3.6），
-- 所以这个顺序在数据库层面不是强制的。但保持它有两个好处：
--   · 万一将来加了外键，脚本不用改
--   · 它诚实地表达了数据之间的依赖关系，读的人一眼知道谁引用谁
DELETE FROM `t_train_station`;
DELETE FROM `t_train`;
DELETE FROM `t_station`;

-- 重置自增计数器，让每次重跑的 id 都从 1 开始。
-- 目的不是"好看"，而是**让每次执行的结果可复现**：
-- 如果 id 每次都在涨，两次执行的结果就没法逐字节对比，
-- "重跑一遍看看是不是同样结果"这个最有用的排查手段就失效了。
ALTER TABLE `t_station` AUTO_INCREMENT = 1;
ALTER TABLE `t_train` AUTO_INCREMENT = 1;
ALTER TABLE `t_train_station` AUTO_INCREMENT = 1;

-- -----------------------------------------------------------------------------
-- 1. 车站
-- -----------------------------------------------------------------------------
-- 【为什么不写 id】
--   让 AUTO_INCREMENT 生成，后面的车次和经停站用 **station_code 子查询**来引用。
--   如果这里手写 id（1,2,3...），后面的 SQL 里就会出现一堆魔法数字 id=7，
--   读代码的人必须来回翻才能知道 7 是哪一站。
--   用 "WHERE station_code='JNK'" 则自解释，而且**不依赖插入顺序**——
--   将来往列表中间插一个车站，后面的 SQL 一行都不用改。
INSERT INTO `t_station` (`station_code`, `station_name`, `city_name`) VALUES
('VNP', '北京南',  '北京'),
('LJP', '廊坊',    '廊坊'),
('TIP', '天津南',  '天津'),
('DIP', '德州东',  '德州'),
('JNK', '济南西',  '济南'),
('QFK', '曲阜东',  '曲阜'),
('ZAK', '枣庄',    '枣庄'),
('UUH', '徐州东',  '徐州'),
('BMH', '蚌埠南',  '蚌埠'),
('NJH', '南京南',  '南京'),
('CWH', '常州北',  '常州'),
('WGH', '无锡东',  '无锡'),
('SOH', '苏州北',  '苏州'),
('AOH', '上海虹桥', '上海');

-- -----------------------------------------------------------------------------
-- 2. 车次
-- -----------------------------------------------------------------------------
-- 【train_type】1=高铁 2=动车 3=普快。这里三趟都是 1。
--
-- 【为什么 start/end_station_id 要和 t_train_station 的首尾站一致】
--   这是一个**冗余字段**（可以从经停站推导出来），设计理由见 sql/02_rail_train.sql。
--   冗余的前提是"有唯一的写入者且在同一个事务里"。本脚本一次性灌入，
--   相当于满足了这个前提；如果你手工改过经停站却没同步改这里，
--   就会出现"车次表说从北京南出发，经停站表说第一站是天津南"的矛盾数据。
--   ⚠️ 真实系统里这种不一致很难发现，因为两个查询各自的返回都是"对"的。
INSERT INTO `t_train` (`train_no`, `train_type`, `start_station_id`, `end_station_id`,
                       `depart_time`, `arrive_time`, `status`)
VALUES
('G1', 1,
 (SELECT id FROM `t_station` WHERE `station_code` = 'VNP'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'AOH'),
 '09:00:00', '13:28:00', 1),

('G3', 1,
 (SELECT id FROM `t_station` WHERE `station_code` = 'VNP'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'AOH'),
 '10:00:00', '14:35:00', 1),

('G2', 1,
 (SELECT id FROM `t_station` WHERE `station_code` = 'AOH'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'VNP'),
 '09:05:00', '13:36:00', 1);

-- -----------------------------------------------------------------------------
-- 3. 经停站
-- -----------------------------------------------------------------------------
-- 【station_order 从 1 开始且连续】
--   它决定了"某站到某站"的区间能不能被计算出来：
--   乘客买"济南西→南京南"的票，就是 station_order 5 → 10 这一段。
--   阶段 4 查"北京南→上海虹桥有哪几趟车"时，会用到这个顺序来判断
--   "这趟车到底停不停这两个站，且顺序对不对"。
--
-- 【arrive_time / depart_time 哪个为 NULL】
--   始发站没有到达时刻 → arrive_time 为 NULL
--   终到站没有发车时刻 → depart_time 为 NULL
--   中间的站两个都有，且中间站的发车时刻必然晚于到达时刻（停站时间）。
--   这个约束**没有**做成 CHECK 约束，因为要表达它需要跨行比较（和上一行比），
--   数据库的 CHECK 约束只能看当前行。这是数据库约束能力的一个真实边界。
--
-- 【G1 的经停站】
INSERT INTO `t_train_station` (`train_id`, `station_id`, `station_order`,
                               `arrive_time`, `depart_time`, `mileage`)
VALUES
((SELECT id FROM `t_train` WHERE `train_no` = 'G1'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'VNP'), 1, NULL,       '09:00:00', 0),
((SELECT id FROM `t_train` WHERE `train_no` = 'G1'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'JNK'), 2, '10:22:00', '10:24:00', 406),
((SELECT id FROM `t_train` WHERE `train_no` = 'G1'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'NJH'), 3, '12:18:00', '12:20:00', 1023),
((SELECT id FROM `t_train` WHERE `train_no` = 'G1'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'AOH'), 4, '13:28:00', NULL,       1318);

-- 【G3 的经停站：和 G1 停站不同，用来演示"同一区间不同车次经停不一样"】
INSERT INTO `t_train_station` (`train_id`, `station_id`, `station_order`,
                               `arrive_time`, `depart_time`, `mileage`)
VALUES
((SELECT id FROM `t_train` WHERE `train_no` = 'G3'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'VNP'), 1, NULL,       '10:00:00', 0),
((SELECT id FROM `t_train` WHERE `train_no` = 'G3'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'TIP'), 2, '10:31:00', '10:33:00', 120),
((SELECT id FROM `t_train` WHERE `train_no` = 'G3'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'JNK'), 3, '11:41:00', '11:44:00', 406),
((SELECT id FROM `t_train` WHERE `train_no` = 'G3'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'UUH'), 4, '12:57:00', '12:59:00', 692),
((SELECT id FROM `t_train` WHERE `train_no` = 'G3'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'NJH'), 5, '13:33:00', '13:36:00', 1023),
((SELECT id FROM `t_train` WHERE `train_no` = 'G3'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'AOH'), 6, '14:35:00', NULL,       1318);

-- 【G2 的经停站：反方向，station_order 依然从 1 开始】
-- 注意 station_order 是**相对这趟车自己的运行方向**的，
-- 不是全局的站序。所以上海虹桥在 G2 上是 1，在 G1 上是 4。
-- 这一点很容易搞错，导致"查上海→北京的车"查不到。
INSERT INTO `t_train_station` (`train_id`, `station_id`, `station_order`,
                               `arrive_time`, `depart_time`, `mileage`)
VALUES
((SELECT id FROM `t_train` WHERE `train_no` = 'G2'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'AOH'), 1, NULL,       '09:05:00', 0),
((SELECT id FROM `t_train` WHERE `train_no` = 'G2'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'NJH'), 2, '10:14:00', '10:17:00', 295),
((SELECT id FROM `t_train` WHERE `train_no` = 'G2'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'JNK'), 3, '12:11:00', '12:14:00', 912),
((SELECT id FROM `t_train` WHERE `train_no` = 'G2'),
 (SELECT id FROM `t_station` WHERE `station_code` = 'VNP'), 4, '13:36:00', NULL,       1318);

-- -----------------------------------------------------------------------------
-- 4. 执行结果确认（这条 SELECT 是给执行者看的，不是给程序用的）
-- -----------------------------------------------------------------------------
SELECT '车站' AS `表`, COUNT(*) AS `行数` FROM `t_station`
UNION ALL
SELECT '车次', COUNT(*) FROM `t_train`
UNION ALL
SELECT '经停站', COUNT(*) FROM `t_train_station`;

-- 预期：车站 14 / 车次 3 / 经停站 14
