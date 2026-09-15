-- =============================================================================
-- 01_rail_user.sql   用户库     owner: rail-user-service
-- =============================================================================
-- ⚠️ 本脚本会先 DROP TABLE，重复执行会丢失该库的数据。仅用于开发环境。
--
-- 执行： mysql -h 127.0.0.1 -P 3306 -u root -p rail_user < sql/01_rail_user.sql
-- =============================================================================

USE `rail_user`;

DROP TABLE IF EXISTS `t_user`;

CREATE TABLE `t_user` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT           COMMENT '用户 ID',
    `username`    VARCHAR(50)     NOT NULL                          COMMENT '登录名',
    `password`    VARCHAR(100)    NOT NULL                          COMMENT '密码哈希，不存明文',
    `phone`       VARCHAR(20)     NOT NULL                          COMMENT '手机号',
    `status`      TINYINT         NOT NULL DEFAULT 1                COMMENT '1=正常 0=禁用',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                  ON UPDATE CURRENT_TIMESTAMP       COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone`    (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户';

-- -----------------------------------------------------------------------------
-- 设计说明
-- -----------------------------------------------------------------------------
--
-- 【1. 为什么 username 和 phone 都要唯一索引，而不是写代码查一下再插】
--
--   写代码判断是「先查后插」，两步之间有窗口：
--     请求A: SELECT ... WHERE username='tom'  -> 没有
--     请求B: SELECT ... WHERE username='tom'  -> 没有   （两个都通过了检查）
--     请求A: INSERT                            -> 成功
--     请求B: INSERT                            -> 也成功，出现两个 'tom'
--
--   唯一索引由存储引擎在**索引层面**保证不重复，不存在任何窗口。
--   应用层判断只能用来**提前给出友好提示**（"该用户名已被占用"），
--   不能用来保证正确性。
--   这个「应用层判断做体验、数据库约束做正确性」的分工，贯穿整个项目。
--
-- 【2. 为什么唯一索引是 uk_username 而不是 uk_username_status】
--
--   朴素的想法是「用户名 + 状态」唯一，这样被禁用的用户不占坑。
--   但这样会导致：同一个用户名可以存在一条"正常"和任意多条"禁用"的记录，
--   不但没解决问题，反而更难查。
--   正确的做法是让禁用只是一个状态位，用户名始终唯一。
--
-- 【3. password 存什么】
--
--   存 BCrypt 哈希，绝不明文，也绝不用 MD5/SHA1。
--   原因：MD5/SHA1 是**快**哈希，设计目标是计算速度快，所以适合用来暴力破解。
--   BCrypt 是**慢**哈希，且可以调 cost 参数控制慢的程度，
--   攻击者拿到库之后每秒只能试几千次而不是几十亿次。
--
--   为什么用 VARCHAR(100) 而不是 CHAR(60)：
--   BCrypt 输出固定 60 字符，但将来换算法（如 Argon2）会变长。
--   留余量比省几字节重要。（MySQL 的 VARCHAR 只按实际长度存，不浪费空间）
--
-- 【4. 为什么不用外键】
--
--   本项目的表之间**一律不加外键约束**，包括服务内部的表。理由有三：
--     a) 跨服务的外键在微服务架构里是禁止的（数据库都不在同一个实例上）
--     b) 外键会在写入时加额外的锁，秒杀场景下会放大锁竞争
--     c) 一旦分库分表，外键根本无法跨分片生效——现在依赖它，将来要全部拆掉
--   数据完整性改由「应用层逻辑 + 唯一索引 + CHECK 约束」保证。
--   这是一个可以争论的选择，取舍已经写在这里，面试时应当能说出两面。
--
-- 【5. 这个表在秒杀流量下的表现】
--
--   秒杀热路径**完全不碰这张表**。用户登录校验发生在秒杀之前，
--   秒杀请求只带 token，网关验签后直接放行。
--   如果每个秒杀请求都去查一次用户表，这张表会立刻成为瓶颈。
--   这是「秒杀链路」与「普通业务链路」必须分开设计的具体体现。
