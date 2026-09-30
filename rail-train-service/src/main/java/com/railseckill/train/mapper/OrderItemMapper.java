package com.railseckill.train.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.railseckill.train.entity.OrderItem;

/**
 * 订单明细（票）Mapper。
 *
 * <p><b>只插不改</b> —— 所以没有手写 SQL，也没有 XML。
 */
/*
 * =============================================================================
 *  【为什么这个 Mapper 一个方法都不需要，OrderMapper 却需要一个】
 * =============================================================================
 *   判据不是"重要不重要"，而是**这张表有没有"条件更新"的需求**：
 *
 *     · t_order       状态会流转（0 → 1），且流转必须防并发 → 需要条件 UPDATE
 *     · t_order_item  一行 = 一张卖出去的票，**写进去就永不修改**
 *                     （退票是另一张流水/另一个状态，不是改这一行）
 *                     → 只有 insert，BaseMapper 白送
 *
 *   `create_time` 之外这张表连 `update_time` 都没有 —— 那不是遗漏，
 *   是刻意的：一张只会被插入的表不该有"最后修改时间"这个字段。
 *
 * -----------------------------------------------------------------------------
 *  ⭐⭐ 【插入这张表是整个下单流程的"决断点"】
 * -----------------------------------------------------------------------------
 *   它上面有 `uk_user_train_date_seat (user_id, train_id, travel_date, seat_type)`，
 *   所以这一次 insert 是**三层幂等防线里唯一真正兜底的那一层**
 *   （另两层是 Redis 和 t_local_message，分别属于阶段 7 和阶段 9）。
 *
 *   撞车时得到 `DuplicateKeyException`。⚠️ 处理它的方式**不能是
 *   catch 住然后正常返回 409** —— 那时 rail_inventory 上的库存已经扣过了，
 *   正常返回会让 Spring 提交事务，造成"库存少一张、订单没增加"的**少卖**。
 *
 *   完整的危险说明写在 OrderService 里（那里是决策发生的地方），
 *   这里指出来是为了让读这个 Mapper 的人知道：**它的 insert 会抛异常，
 *   而那个异常不是错误，是设计的一部分。**
 * =============================================================================
 */
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
