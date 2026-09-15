-- =============================================================================
-- 03_rail_inventory.sql   库存库     owner: rail-inventory-service
-- =============================================================================
-- ⚠️ 本脚本会先 DROP TABLE，重复执行会丢失该库的数据。仅用于开发环境。
--
-- 执行： mysql -h 127.0.0.1 -P 3306 -u root -p rail_inventory < sql/03_rail_inventory.sql
-- =============================================================================

USE `rail_inventory`;

-- -----------------------------------------------------------------------------
-- 席别库存（整个项目的核心表）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_seat_inventory`;

CREATE TABLE `t_seat_inventory` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `train_id`     BIGINT UNSIGNED NOT NULL                COMMENT '车次 ID',
    `travel_date`  DATE            NOT NULL                COMMENT '乘车日期',
    `seat_type`    TINYINT         NOT NULL                COMMENT '1=商务座 2=一等座 3=二等座',
    `price`        DECIMAL(10, 2)  NOT NULL                COMMENT '票价',
    `total_count`  INT             NOT NULL                COMMENT '总票额',
    `sold_count`   INT             NOT NULL DEFAULT 0      COMMENT '已售数量',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),

    -- （一）业务主键：一行 = 一个车次 + 一天 + 一种席别
    UNIQUE KEY `uk_train_date_seat` (`train_id`, `travel_date`, `seat_type`),

    -- （二）最后一道防线：数据库层面拒绝负数库存和超卖
    -- MySQL 8.0.16 起 CHECK 约束被真正强制执行（8.0.16 之前是解析但忽略）
    CONSTRAINT `ck_sold_non_negative` CHECK (`sold_count` >= 0),
    CONSTRAINT `ck_sold_not_exceed_total` CHECK (`sold_count` <= `total_count`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '席别库存';

-- -----------------------------------------------------------------------------
-- 库存流水（每一次库存变动都留痕）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_stock_flow`;

CREATE TABLE `t_stock_flow` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `biz_id`       VARCHAR(64)     NOT NULL                COMMENT '业务单号（订单号）',
    `change_type`  TINYINT         NOT NULL                COMMENT '1=预扣 2=确认扣减 3=回补',
    `train_id`     BIGINT UNSIGNED NOT NULL,
    `travel_date`  DATE            NOT NULL,
    `seat_type`    TINYINT         NOT NULL,
    `change_count` INT             NOT NULL                COMMENT '变动数量，回补时为负',
    `remark`       VARCHAR(255)    DEFAULT NULL            COMMENT '备注，如失败原因',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),

    -- 同一个业务单号的同一种变动，只能出现一次
    UNIQUE KEY `uk_biz_id_type` (`biz_id`, `change_type`),

    KEY `idx_train_date` (`train_id`, `travel_date`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '库存变动流水';

-- =============================================================================
-- 设计说明
-- =============================================================================
--
-- 【1. 粒度为什么是「车次 + 日期 + 席别」，没有出发站/到达站】
--
--   真实铁路是**区间票**：北京南→上海虹桥 的一等座，
--   会占用「北京南→济南西」和「济南西→上海虹桥」两段运力。
--   所以真实系统的库存是按「每个区间的余票」来算的，
--   查"北京→上海有没有票"要把沿途每一段都算一遍取最小值。
--
--   本项目**刻意不做区间**，理由：
--     a) 项目目标是验证高并发下的不超卖，不是复刻铁路业务规则
--     b) 引入区间后，Lua 脚本从「扣一个 key」变成「扣一段区间内所有 key」，
--        原子性论证从"一次 DECR"退化成"区间扣减 + 部分失败如何回滚"——
--        那是另一道题，会把注意力从主线上拽走
--
--   ⚠️ **代价（必须知道，否则面试会露馅）**：
--      北京南→济南西 和 北京南→上海虹桥 会抢同一份库存。
--      现实中这是错的：一个只坐一站的乘客，不该占用全程的运力。
--
--   🔵 **如果要做区间，正确的做法是**：
--      库存按 seat_inventory(train_id, travel_date, seat_type, from_order, to_order)
--      存**每一段**的余票；下单时对 [from_order, to_order) 区间内的每一段都要扣减。
--      这在 Redis 里要用 Lua 遍历多个 key，且需要设计"扣到一半失败"的补偿。
--
-- 【2. 为什么存 total_count + sold_count，而不是一个 available_count】
--
--   available_count = total_count - sold_count 是**冗余字段**，
--   而冗余字段是数据漂移的来源：两个字段都可能被写错，且不一致时无法判断谁对。
--
--   存 total 和 sold 则天然自洽：total 几乎不变（改票额是极低频操作），
--   sold 只增不减（或通过明确的操作减少），两者各自有清晰的语义。
--
--   最关键的：**「不超卖」这个不变式可以直接写成 CHECK 约束**
--       sold_count <= total_count
--   如果只有一个 available_count，"不为负"这个约束的表达力就弱得多。
--
-- 【3. ⭐ 阶段 5 的核心：用条件 UPDATE 做原子扣减（CAS）】
--
--   在不引入 Redis 的阶段 5，扣库存靠这一条 SQL：
--
--       UPDATE t_seat_inventory
--          SET sold_count = sold_count + 1
--        WHERE train_id = ? AND travel_date = ? AND seat_type = ?
--          AND sold_count < total_count;        -- ← 关键在这一句
--
--   然后判断返回的**受影响行数**：
--       1 行 → 扣减成功
--       0 行 → 票已售罄（WHERE 条件不成立）
--
--   【为什么这样就是安全的】
--     InnoDB 执行 UPDATE 时会对匹配的行加**排他锁**，且加锁和判断是同一步。
--     两个并发事务不可能同时通过 `sold_count < total_count` 的判断：
--     后到的那个必须等前一个提交，提交后重新读到的 sold_count 已经变了，
--     于是 WHERE 不再成立，受影响行数为 0。
--
--   【这比「先 SELECT 判断，再 UPDATE」好在哪】
--       朴素写法： SELECT sold, total FROM ...   -- 两边都读到 99/100
--                 if (sold < total) UPDATE ...    -- 两边都执行，卖成 101
--     这是最经典的超卖写法。把判断和写入**合并成一条 SQL**，窗口就消失了。
--     本质上是把「应用层两步骤」压成「数据库层一步」——
--     和在 Redis 里用 Lua 合并判断与扣减，是**同一个思路**。
--
--   【为什么不需要分布式锁】
--     这里不存在"多个进程同时改一份数据"的问题吗？存在的。
--     但 InnoDB 的行锁已经解决了它，粒度更细、代价更低。
--     再加一层 Redis 分布式锁，等于给已经上锁的门再加一把锁——
--     增加了一次网络往返、一个故障点，却没有任何正确性收益。
--
--   【什么时候才真的需要分布式锁】
--     当「判断」和「写入」无法压进同一条 SQL，且没有原子操作可用时。
--     本项目里只有两处：库存预热（多实例同时灌数据）和对账任务。
--     详见 docs/architecture/phase0-design.md §6.4。
--
-- 【4. 两种失败的对比（这是面试官最爱追问的地方）】
--
--   同一个 `WHERE sold_count < total_count`，执行结果有三种，必须区分：
--
--     (a) 受影响行数 = 1        → 扣减成功
--     (b) 受影响行数 = 0        → 票卖完了，这是**正常的业务失败**
--     (c) 抛出异常 / 超时       → 这是**系统失败**，结果未知
--
--   把 (b) 和 (c) 混为一谈是常见 bug：
--     · 把 (b) 当异常处理 → 售罄时会大量报警，掩盖真正的故障
--     · 把 (c) 当售罄处理 → 系统故障时告诉用户"没票了"，实际库存被白白浪费
--   处理方式完全不同：(b) 直接返回"已售罄"，(c) 必须回滚/补偿。
--
-- 【5. 为什么需要 t_stock_flow 这张流水表】
--
--   因为「Redis 说扣了 1，MySQL 说扣了 0」这件事**必须有个地方能查**。
--   没有流水表，对账任务只能发现"不一致"，却无法回答"哪一笔不一致、为什么"。
--
--   同时它也是「扣了库存但订单创建失败」时的补偿依据：
--   扫描"有预扣流水但没有对应订单"的记录，就能找出所有需要回补的库存。
--
--   ⭐ 而 uk_biz_id_type 这个唯一索引，让**重复消费 MQ 消息天然幂等**：
--      同一条消息被投递两次，第二次插入流水会撞唯一键而失败，
--      于是整个事务回滚，库存不会被重复扣减。
--      这是阶段 9「MQ 重复消费怎么办」的第一道答案。
--
-- 【6. 为什么这里用 DECIMAL(10,2) 而不是 DOUBLE】
--
--   金额永远不能用浮点数。0.1 + 0.2 在二进制浮点里不等于 0.3，
--   累积误差会导致对账时"差了一分钱"这种极难排查的问题。
--   DECIMAL 是精确的定点小数。这是钱相关字段的铁律。
