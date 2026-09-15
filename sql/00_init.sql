-- =============================================================================
-- 00_init.sql  创建数据库与应用账号
-- =============================================================================
-- 执行方式（用 root，本文件只跑一次）：
--   mysql -h 127.0.0.1 -P 3306 -u root -p < sql/00_init.sql
--
-- ⚠️ 本脚本不删任何数据，可以安全地重复执行（全部是 IF NOT EXISTS）。
--    真正删数据的是后面 01~04 的建表脚本，它们的头部各自有警告。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 四个数据库，对应四个服务的数据所有权边界
-- -----------------------------------------------------------------------------
-- 【为什么按服务切库，而不是一个库放所有表】
--   一个库 = 一个数据所有权边界。拆库之后，「哪个服务能读写哪些表」这件事
--   在物理上就是明确的，而不是靠约定。
--   代价：跨库 JOIN 和跨库事务都做不了。这正是微服务的现实——
--   阶段 8 拆分后，"扣库存 + 写订单"必须靠最终一致性方案，而不是本地事务。
--
-- 【为什么现在（单体阶段）就拆库】
--   如果阶段 3~7 全部写在一个库、阶段 8 再拆，那么拆分带来的问题会被推迟到
--   "代码已经写完了"的时候才爆发，改起来代价大得多。
--   现在就拆，等于提前把边界画出来，让代码从一开始就遵守它。
--
-- 【可以质疑的地方】
--   如果你觉得四个库太啰嗦，合成一个库同样能跑完整个项目。
--   我选择现在就拆，是因为"跨库事务做不了"这个约束本身是重要的学习内容。

CREATE DATABASE IF NOT EXISTS `rail_user`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `rail_train`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `rail_inventory`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `rail_order`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 字符集说明：
--   utf8mb4 而不是 utf8 —— MySQL 的 "utf8" 是每字符最多 3 字节的历史遗留，
--   存不下 emoji 和部分生僻汉字。utf8mb4 才是真正的 UTF-8。
--   utf8mb4_0900_ai_ci：MySQL 8 默认排序规则，ai = accent-insensitive（忽略音调），
--   ci = case-insensitive（忽略大小写）。对用户名/车站名的检索正合适。

-- -----------------------------------------------------------------------------
-- 2. 应用专用账号
-- -----------------------------------------------------------------------------
-- 【为什么不用 root 连数据库】
--   root 拥有 DDL 权限，能 DROP DATABASE。
--   应用一旦被 SQL 注入攻破，用 root 连接等于把"删库"的能力交给了攻击者；
--   用只有 DML 权限的账号，攻击者最多改数据，删不掉表结构。
--   这是最小权限原则（Principle of Least Privilege）。
--
-- 【为什么建两个 host 条目】
--   MySQL 把 localhost 和 127.0.0.1 当作**不同的主机**：
--   localhost 可能走命名管道/共享内存，127.0.0.1 走 TCP。
--   只建一个，另一种连接方式会被拒绝，而报错信息是 "Access denied"，
--   排查起来很浪费时间。两个都建，省掉这个坑。
--
-- 【为什么只给 DML（SELECT/INSERT/UPDATE/DELETE）】
--   建表、改表用 root 执行 sql/ 目录下的脚本。
--   应用账号不需要 CREATE/ALTER/DROP——它只需要读写数据。
--   这也让"应用代码里出现了 DDL"变成一个立刻能发现的错误。

CREATE USER IF NOT EXISTS 'rail'@'localhost' IDENTIFIED BY 'rail123456';
CREATE USER IF NOT EXISTS 'rail'@'127.0.0.1' IDENTIFIED BY 'rail123456';

GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_user`.*      TO 'rail'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_train`.*     TO 'rail'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_inventory`.* TO 'rail'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_order`.*     TO 'rail'@'localhost';

GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_user`.*      TO 'rail'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_train`.*     TO 'rail'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_inventory`.* TO 'rail'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON `rail_order`.*     TO 'rail'@'127.0.0.1';

FLUSH PRIVILEGES;

-- 生产环境的补充做法（本项目不实现，但要知道）：
--   1. 密码不应硬编码在脚本里，应由密钥管理服务下发
--   2. 每个服务用**独立的**账号，只授予它自己那个库的权限。
--      现在四个服务共用一个 rail 账号只是为了本地开发方便
--   3. 授予权限时用 GRANT ... ON 具体库.具体表，而不是 库.*

-- -----------------------------------------------------------------------------
-- 3. 结果确认
-- -----------------------------------------------------------------------------
SELECT SCHEMA_NAME AS `已创建的数据库`,
       DEFAULT_CHARACTER_SET_NAME AS `字符集`,
       DEFAULT_COLLATION_NAME AS `排序规则`
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME LIKE 'rail\_%'
ORDER BY SCHEMA_NAME;

SELECT CONCAT(USER, '@', HOST) AS `已创建的账号` FROM mysql.user WHERE USER = 'rail';
