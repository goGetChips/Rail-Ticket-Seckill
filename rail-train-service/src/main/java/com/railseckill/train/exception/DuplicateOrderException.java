package com.railseckill.train.exception;

import com.railseckill.train.enums.SeatType;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 重复购票：同一乘车人、同一车次、同一日期、同一席别已经有票了。
 *
 * <p>对应 HTTP <b>409 Conflict</b>。
 */
/*
 * =============================================================================
 *  ⭐⭐ 【这个异常是 t_order_item 上唯一索引撞车的产物，而它的抛出方式极为关键】
 * =============================================================================
 *
 *   【它从哪里来】
 *   代码不主动判断"这个人买过没有" —— 那样会多一次查询，
 *   而且"查完到写之间"有窗口（TOCTOU）。
 *   判断完全交给数据库：t_order_item 上有唯一索引
 *   `uk_user_train_date_seat (user_id, train_id, travel_date, seat_type)`，
 *   重复插入时 MySQL 抛 1062，Spring 翻译成 DuplicateKeyException，
 *   我们把它转成这个业务异常。
 *
 *   ⭐ **这就是"约束应该建在它真正约束的那个实体上"的兑现**：
 *   Service 里没有任何一行代码在判断重复，正确性由索引保证。
 *
 * -----------------------------------------------------------------------------
 *  🔴🔴 【最危险的坑：捕获 DuplicateKeyException 之后绝不能 return】
 * -----------------------------------------------------------------------------
 *   这是整个阶段 5 最容易写出、最难发现、后果最严重的错误。
 *
 *   错误写法（**看起来完全合理**）：
 *
 *       try { orderItemMapper.insert(item); }
 *       catch (DuplicateKeyException ex) {
 *           return new OrderCreated(...409...);     // ❌ 返回而不是抛
 *       }
 *
 *   为什么它是灾难：此刻事务里**已经执行过一次库存扣减**（② 步）。
 *   Spring 的事务拦截器判断"提交还是回滚"的依据是
 *   **方法是否抛出了异常**。方法正常 return 了 → 拦截器认为成功 → **COMMIT**。
 *   于是：
 *
 *       · rail_inventory 的 sold_count 被 +1（已提交，无法撤销）
 *       · t_order / t_order_item 没有这单
 *       · 接口老老实实返回 409，看起来"正确处理了重复下单"
 *
 *   结果就是**少卖**：库存少一张、票没卖出去、没有任何报错、没有任何日志、
 *   监控全绿。**只有对账时能看出来。** 这比超卖更难查，
 *   因为超卖至少还有个明显非法状态（sold > total），而它是完全合法的数字。
 *
 *   ⭐ 判据：**catch 块里只能 throw，不能 return。**
 *      写成 `catch (DuplicateKeyException ex) { throw new DuplicateOrderException(...); }`
 *      —— 抛异常让 Spring 回滚，② 步的扣减被一起撤销，数据回到一致状态。
 *
 *   这个坑和"返回值是 int 而不是 boolean"是同一个主题的两个面：
 *   **事务的边界由异常决定，所以异常的抛出与否是承重的语义，不是风格问题。**
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【为什么捕 DuplicateKeyException，不捕它的父类 DataIntegrityViolationException】
 * -----------------------------------------------------------------------------
 *   父类还会捕获 1364（NOT NULL 违规）等一堆**编码 bug**。
 *   那些必须返回 500，因为"某个字段忘了赋值"是必现的代码错误，
 *   把它伪装成 409「请勿重复购票」会让一个必现 bug 看起来像一次正常的业务拒绝。
 *
 *   一句话：**捕太宽 = 把系统故障伪装成业务失败。**
 *
 *   🔴 另外记一个实测事实，免得有人以为捕父类可以"顺手兜住 CHECK 约束"：
 *   **CHECK 违反（错误码 3819）根本不会被 Spring 翻译成 DataIntegrityViolationException**，
 *   它抛的是裸的 MyBatis PersistenceException。
 *   所以"捕父类能顺便拦住 CHECK 违反"这个念头是错的 ——
 *   它拦不住，而且会因为捕了别的 bug 造成更坏的结果。
 * =============================================================================
 */
public class DuplicateOrderException extends BusinessException {

    public DuplicateOrderException(long userId, String trainNo, LocalDate travelDate, int seatTypeCode) {
        super(HttpStatus.CONFLICT,
                "请勿重复购票：同一乘车人、同一车次、同一日期、同一席别只能购买一张",
                List.of(
                        "userId=" + userId,
                        "trainNo=" + trainNo,
                        "travelDate=" + travelDate,
                        "seatType=" + seatTypeCode + "（" + SeatType.of(seatTypeCode).label() + "）"));
    }
}
