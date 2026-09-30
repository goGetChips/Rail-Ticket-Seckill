package com.railseckill.train.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.railseckill.train.entity.SeatInventory;

/**
 * 库存查询。空接口 —— 只用 {@code BaseMapper} 提供的单表查询就够。
 *
 * <p><b>⚠️ 这是一个跨库 Mapper，本服务只读它，永不写它。</b>
 * 表 {@code rail_inventory.t_seat_inventory} 的 owner 终态是
 * {@code rail-inventory-service}（阶段 8）。
 *
 * @see SeatInventory 实体的类注释里有完整说明
 */
/*
 * =============================================================================
 *  为什么是空接口（连一个方法都不用声明）
 * =============================================================================
 *
 *   余票查询是纯粹的单表条件查询：
 *     SELECT * FROM t_seat_inventory
 *      WHERE train_id = ? AND travel_date = ?
 *      ORDER BY seat_type
 *
 *   没有 JOIN、没有聚合、没有需要手写的 SQL，
 *   所以直接用 BaseMapper 的 selectList + LambdaQueryWrapper 就行，
 *   不需要 XML，也不需要在这里声明方法。
 *
 *   【这正好和 TrainMapper 形成对照】
 *   TrainMapper 需要 XML，是因为它的查询都要 JOIN；
 *   这个 Mapper 不需要，是因为它只查一张表。
 *   **判据是这个，不是"重要不重要"。**
 *   阶段 3 的 StationMapper 同理是空的。
 *
 * =============================================================================
 *  🔴 【为什么特意写明"永不写它"】
 * =============================================================================
 *   技术上，BaseMapper 白送了 insert / updateById / deleteById，
 *   而这个 Mapper 继承它，所以这些方法**现在就能调**，
 *   而且权限上也没有任何阻拦（账号 rail 对 rail_inventory 有完整 DML 权限）。
 *
 *   也就是说：**代码里没有任何机制阻止 train-service 去扣减库存。**
 *   唯一阻止它的是"没人这么写"。
 *
 *   这必须写清楚，因为阶段 5 要做的正是"扣库存"这件事 ——
 *   到那时很容易顺手在余票查询所在的这个服务里把 UPDATE 也写了，
 *   于是"库存的唯一写入者是 inventory/order 侧"这条设计约束
 *   在没人注意的情况下被破坏，而**任何测试都不会失败**。
 *
 *   刻意不通过继承更窄的接口来强制（比如不继承 BaseMapper，
 *   只声明 selectList 一个方法）。理由：那会在阶段 8 迁移时
 *   多一层无谓的改动，而现在这个阶段**没有任何并发写入**，
 *   用注释约束比用类型约束更划算。
 *   如果哪天这个服务真的开始写库存，那是设计出了问题，
 *   应该停下来讨论，而不是靠接口收窄来兜住。
 * =============================================================================
 */
public interface SeatInventoryMapper extends BaseMapper<SeatInventory> {
}
