-- =============================================================================
-- 04_rail_order.sql   订单库     owner: rail-order-service
-- =============================================================================
-- ⚠️ 本脚本会先 DROP TABLE，重复执行会丢失该库的数据。仅用于开发环境。
--
-- 执行： mysql -h 127.0.0.1 -P 3306 -u root -p rail_order < sql/04_rail_order.sql
-- =============================================================================

USE `rail_order`;

-- -----------------------------------------------------------------------------
-- 订单头（一次交易）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_order`;

CREATE TABLE `t_order` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `order_no`     VARCHAR(32)     NOT NULL                COMMENT '业务订单号，对外暴露',
    `user_id`      BIGINT UNSIGNED NOT NULL                COMMENT '下单人',
    `total_amount` DECIMAL(10, 2)  NOT NULL                COMMENT '订单总额',
    `status`       TINYINT         NOT NULL DEFAULT 0      COMMENT '0=待支付 1=已支付 2=已取消',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_create` (`user_id`, `create_time`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '订单';

-- -----------------------------------------------------------------------------
-- 订单明细（每一张票）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_order_item`;

CREATE TABLE `t_order_item` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `order_id`         BIGINT UNSIGNED NOT NULL            COMMENT '所属订单 ID',
    `user_id`          BIGINT UNSIGNED NOT NULL            COMMENT '乘车人（票的归属人）',
    `train_id`         BIGINT UNSIGNED NOT NULL,
    `travel_date`      DATE            NOT NULL,
    `seat_type`        TINYINT         NOT NULL            COMMENT '1=商务座 2=一等座 3=二等座',
    `from_station_id`  BIGINT UNSIGNED NOT NULL,
    `to_station_id`    BIGINT UNSIGNED NOT NULL,
    `price`            DECIMAL(10, 2)  NOT NULL            COMMENT '本张票的票价',
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),

    -- ⭐ 整个项目「不超卖 / 不重复购票」的最后一道防线
    UNIQUE KEY `uk_user_train_date_seat` (`user_id`, `train_id`, `travel_date`, `seat_type`),

    KEY `idx_order_id` (`order_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '订单明细（票）';

-- -----------------------------------------------------------------------------
-- 本地消息表 —— ⏸️ 阶段 1~8 不会写入这张表，阶段 9 引入 MQ 后才启用
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_local_message`;

CREATE TABLE `t_local_message` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `biz_id`         VARCHAR(64)     NOT NULL              COMMENT '业务单号（订单号）',
    `msg_type`       VARCHAR(32)     NOT NULL              COMMENT '消息类型，如 ORDER_CREATED',
    `payload`        TEXT            NOT NULL              COMMENT '消息体 JSON',
    `status`         TINYINT         NOT NULL DEFAULT 0    COMMENT '0=待发送 1=已发送 2=已确认',
    `retry_count`    INT             NOT NULL DEFAULT 0    COMMENT '已重试次数',
    `next_retry_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_id_type` (`biz_id`, `msg_type`),
    KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '本地消息表（阶段 9 启用）';

-- =============================================================================
-- 设计说明
-- =============================================================================
--
-- 【1. 为什么拆成 t_order + t_order_item，而不是一张表】
--
--   这不是"为了看起来规范"，而是因为**它们是两个不同的实体**：
--
--     t_order.user_id      = 下单的人
--     t_order_item.user_id = 票归谁（乘车人）
--
--   在秒杀场景里两者永远相同（自己给自己抢票），所以看起来是冗余的。
--   但一旦支持"帮别人买票"，它们就分离了——一个订单可以是 A 下的单，
--   包含 B 和 C 两个人的票。
--
--   ⚠️ **这是本设计中最值得你质疑的一处。**
--      如果确定永远只做"一人一单一张票"，把两张表合成一张是更简单、
--      写入更少的正确选择（少一次 INSERT，阶段 6 压测时差别看得见）。
--      我选择拆开，是因为它把"下单人"和"乘车人"这两个概念分开了，
--      而这两个概念的分离，正是**限购约束必须落在明细表上**的原因。
--
-- 【2. ⭐ 为什么限购唯一索引建在 t_order_item 而不是 t_order】
--
--   一句话：**约束应该建在它真正约束的那个实体上。**
--
--   我们要约束的是"一个乘车人，同一趟车同一天同席别，只能有一张票"。
--   被约束的对象是**票**，票在 t_order_item 里。所以索引建在这里。
--
--   如果建在 t_order 上，语义就变成了"一个下单人，同一趟车同一天同席别
--   只能下一单"——当支持代人购票后，这条约束就会**错误地阻止**
--   A 一次给 B 和 C 各买一张票。
--
--   代价：user_id 需要在两张表各存一份。这是**由约束驱动的冗余**，
--   理由是充分的（不冗余就无法表达这个约束）。
--   请和"凭感觉冗余字段"区分开：**冗余必须有明确的服务对象，
--   且必须有唯一的写入者。**
--
-- 【3. 这道唯一索引到底挡住了什么（面试必答）】
--
--   它是三层幂等防线里的最后一道：
--     第一层：Redis Lua 里的 SISMEMBER —— 挡掉 99% 的重复请求，**性能优化**
--     第二层：t_local_message 的唯一键 —— 保证消息不重复投递，**阶段 9**
--     第三层：这里的唯一索引 —— 前两层都失效时，正确性的**最终保证**
--
--   为什么必须有第三层：Redis 会被清空、会重启、会主从切换丢数据；
--   MQ 的"至少一次投递"语义意味着重复是**必然**而非意外。
--   只有数据库的唯一索引是"无论上游怎么乱来，都一定成立"的。
--
--   ✅ **怎么证明它有效**：跑 sql/99_verify.sql，它会故意插入重复数据，
--      然后确认 MySQL 真的拒绝了。不实测，就只是"应该没问题"。
--
-- 【4. 订单号为什么不用自增主键】
--
--   自增 ID 会**泄露业务量**：今天下单成功返回 1024，明天 2048，
--   竞对连续下两单就能算出你的日订单量。
--   所以对外只暴露 order_no，它是独立生成的（时间戳 + 序列 + 随机数）。
--   自增 id 只做主键，不出现在任何对外接口里。
--
--   生成方式本项目先用简单的「时间戳+随机数」，阶段 8 跨服务后再评估
--   是否需要雪花算法。**不提前引入分布式 ID 组件**——现在的量级不需要。
--
-- 【5. ⭐ 状态机为什么用条件 UPDATE，而不是分布式锁】
--
--   阶段 7 支付时要做状态流转（待支付 → 已支付），必须防重复支付：
--
--       UPDATE t_order SET status = 1
--        WHERE order_no = ? AND status = 0;    -- ← 只有当前是待支付才更新
--
--   受影响行数为 1 → 本次支付生效
--   受影响行数为 0 → 订单已被处理过（重复支付 / 已取消），直接返回成功即可
--
--   这和库存扣减用的是**同一个手法**：把「判断 + 写入」压成一条原子的 SQL。
--   用分布式锁也能实现，但要付出一次网络往返 + 一把锁的生命周期管理，
--   而这里一行 SQL 就够了。
--
--   【为什么受影响行数为 0 时应该"返回成功"而不是报错】
--     支付回调重复到达是常态（网络重试）。此时用户的诉求是"我付过的钱算数"，
--     而钱确实已经记上了。报错会让用户以为支付失败，可能重复付款。
--     这叫**幂等**：同样的请求执行多次，结果和执行一次相同。
--
-- 【6. t_local_message 为什么现在就建（它阶段 9 才用）】
--
--   诚实说：**现在建它是因为阶段 2 在建 schema，改表结构比建表麻烦。**
--   阶段 1~8 不会有任何代码写它。
--
--   它是「本地消息表」模式的载体，解决的是：
--     「Redis 扣库存成功 → 发 MQ 消息 → 但消息发出前服务挂了」
--   把"写业务数据"和"写待发消息"放进**同一个本地事务**，
--   再由后台任务扫描 status=0 的记录补发，就能保证消息最终一定发出去。
--
--   idx_status_retry 这个索引就是给扫描任务用的：
--     扫描"还没发送、且到了重试时间"的消息。
--   没有它，补偿任务会全表扫描——**这是补偿任务最容易踩的性能坑**。
--
-- 【7. 各状态字段的取值约定（全项目统一）】
--
--   订单 status：0=待支付  1=已支付  2=已取消
--   本地消息 status：0=待发送  1=已发送  2=已确认
--   席别 seat_type：1=商务座  2=一等座  3=二等座
--   库存流水 change_type：1=预扣  2=确认扣减  3=回补
--
--   用 TINYINT 而不是字符串枚举，是为了省空间和保证索引效率；
--   但代价是**可读性差**，必须在代码里定义对应的枚举类，禁止裸写数字。
