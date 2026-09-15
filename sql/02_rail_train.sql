-- =============================================================================
-- 02_rail_train.sql   车次库     owner: rail-train-service
-- =============================================================================
-- ⚠️ 本脚本会先 DROP TABLE，重复执行会丢失该库的数据。仅用于开发环境。
--
-- 执行： mysql -h 127.0.0.1 -P 3306 -u root -p rail_train < sql/02_rail_train.sql
-- =============================================================================

USE `rail_train`;

-- -----------------------------------------------------------------------------
-- 车站
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_station`;

CREATE TABLE `t_station` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `station_code` VARCHAR(10)     NOT NULL                COMMENT '车站电报码，如 BJP',
    `station_name` VARCHAR(50)     NOT NULL                COMMENT '车站名，如 北京南',
    `city_name`    VARCHAR(50)     NOT NULL                COMMENT '所在城市，如 北京',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_station_code` (`station_code`),
    KEY `idx_city_name` (`city_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '车站';

-- -----------------------------------------------------------------------------
-- 车次（时刻表，不是某一天的具体车次）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_train`;

CREATE TABLE `t_train` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `train_no`         VARCHAR(20)     NOT NULL                COMMENT '车次号，如 G1234',
    `train_type`       TINYINT         NOT NULL                COMMENT '1=高铁 2=动车 3=普快',
    `start_station_id` BIGINT UNSIGNED NOT NULL                COMMENT '始发站',
    `end_station_id`   BIGINT UNSIGNED NOT NULL                COMMENT '终到站',
    `depart_time`      TIME            NOT NULL                COMMENT '始发时刻',
    `arrive_time`      TIME            NOT NULL                COMMENT '终到时刻',
    `status`           TINYINT         NOT NULL DEFAULT 1      COMMENT '1=正常 0=停运',
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                       ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_no` (`train_no`),
    KEY `idx_start_end` (`start_station_id`, `end_station_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '车次时刻表';

-- -----------------------------------------------------------------------------
-- 车次经停站（一趟车的完整运行路径）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_train_station`;

CREATE TABLE `t_train_station` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `train_id`      BIGINT UNSIGNED NOT NULL             COMMENT '车次 ID',
    `station_id`    BIGINT UNSIGNED NOT NULL             COMMENT '车站 ID',
    `station_order` INT             NOT NULL             COMMENT '第几站，从 1 开始递增',
    `arrive_time`   TIME            DEFAULT NULL         COMMENT '到达时刻，始发站为 NULL',
    `depart_time`   TIME            DEFAULT NULL         COMMENT '发车时刻，终到站为 NULL',
    `mileage`       INT             DEFAULT NULL         COMMENT '自始发站起的累计里程(km)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_order` (`train_id`, `station_order`),
    UNIQUE KEY `uk_train_station` (`train_id`, `station_id`),
    KEY `idx_station` (`station_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '车次经停站';

-- -----------------------------------------------------------------------------
-- 设计说明
-- -----------------------------------------------------------------------------
--
-- 【1. 「车次」和「某天的车次」是两件事——本设计刻意没有第三张表】
--
--   真实铁路系统里，G1234 是一个**运行图**（每天都有），
--   而 2026-10-01 的 G1234 是一个**具体列车**，可能有自己的编组、临时调整。
--   所以真实系统会再拆一张 t_train_schedule（车次日历表）。
--
--   本项目**不拆**，因为：
--     a) 秒杀关注的是「某个车次在某一天还有多少票」，这正好由
--        rail_inventory.t_seat_inventory 的 (train_id, travel_date) 承载
--     b) 拆出日历表后，查余票要先查日历再查库存，多一次 JOIN，
--        换不来任何本项目需要用到的能力
--   代价：无法表达"某天停运"。本项目的 status 是车次级的，不是日期级的。
--   **这是一个明确的简化，面试被问到时应主动说明。**
--
-- 【2. 为什么 t_train 要冗余存 start_station_id / end_station_id】
--
--   这两个字段完全可以从 t_train_station 推导出来（station_order 最小/最大）。
--   冗余的理由是**查询模式**：
--     "查北京→上海的车次" 是最核心的查询，如果每次都去 JOIN 经停站表，
--     一趟车有 10~20 个经停站，JOIN 的代价远大于读两个字段。
--
--   冗余的前提是**它有唯一的写入者**：只有车次录入这一个场景会写它，
--   且写入时与 t_train_station 在同一个事务里。**没有这个前提就不该冗余**。
--
-- 【3. idx_start_end 这个索引为什么这么建】
--
--   索引的列顺序应当匹配查询条件。最典型的查询是：
--     SELECT ... FROM t_train WHERE start_station_id = ? AND end_station_id = ?
--   两列等值查询，顺序无所谓，联合索引一次定位。
--
--   ⚠️ 但要注意：这个索引对
--     WHERE end_station_id = ?
--   这样的**单列查询无效**——联合索引只能从最左列开始匹配（最左前缀原则）。
--   如果将来出现"按到达站查车次"的需求，需要单独建索引。
--
-- 【4. uk_train_station 为什么要有】
--
--   一趟车不可能两次经过同一个车站（环线除外，本书不考虑）。
--   这个唯一索引能在**录入数据时**就挡住"同一车次重复添加同一车站"的错误，
--   否则会算出错误的区间和重复的班次。
--
-- 【5. 这张表在秒杀流量下的表现】
--
--   秒杀热路径同样**完全不碰**。车次信息在阶段 4 查一次、缓存进 Redis 就够。
--   秒杀请求只带 train_id，直接去 Redis 扣库存。
