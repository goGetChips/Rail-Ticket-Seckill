package com.railseckill.train.exception;

import com.railseckill.train.enums.OrderStatus;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 订单当前的状态不允许做这个操作（目前只有一种情况：支付一个已取消的订单）。
 *
 * <p>对应 HTTP <b>409 Conflict</b>。
 */
/*
 * =============================================================================
 *  ⭐⭐ 【这个异常存在的全部意义：否定 DDL 里写错的一句话】
 * =============================================================================
 *
 *   `sql/04_rail_order.sql` 和 `docs/03-business-flow.md` 都写着：
 *
 *     「受影响行数为 0 → 订单已被处理过（重复支付 / 已取消），
 *        **直接返回成功即可**」
 *
 *   这句话对"**重复支付**"是**对的**，对"**已取消**"是**错的**。
 *
 *   【为什么"重复支付返回成功"是对的】
 *   因为那是真幂等：用户第一次支付已经把订单变成已支付了，
 *   这次请求没有改变任何状态，返回"成功"陈述的是**订单当前的真实状态**。
 *   用户看到的是"支付成功了"—— 而订单确实已支付。没有说谎。
 *
 *   【为什么"已取消返回成功"是错的】
 *   订单已取消意味着**那张票已经回补给库存、卖给别人了**。
 *   这时返回"支付成功"，用户会以为他有一张票 ——
 *   而钱对应的票根本不存在。这不是幂等，这是**撒谎**。
 *   正确响应是 409：状态不允许，别再重试。
 *
 *   ⭐ 一句话总结这个区分的判据：
 *       **"已经是我想要的状态"可以报成功；"永远不可能变成我想要的状态"必须报错。**
 *       前者是幂等，后者是拒绝。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【阶段 5 这个分支走不到，但代码必须写 —— 验证时要手工构造】
 * -----------------------------------------------------------------------------
 *   阶段 5 的范围是"下单 + 模拟支付"，**没有取消接口**，
 *   所以没有任何代码路径能把订单变成 status=2。
 *   这个异常在阶段 5 是**不可达**的。
 *
 *   但仍然写它，因为：
 *     · 支付接口必须对**数据库里已经存在的任何状态**给出正确答案，
 *       而不是只对"本阶段能产生的状态"正确
 *     · 手工 `UPDATE rail_order.t_order SET status=2 WHERE order_no=...`
 *       一次就能验证它 —— **验证时必须真的构造一次**，
 *       否则它是一段从没被执行过的代码，等同于不存在
 *
 *   配套的测试（OrderServiceIT 里）就是靠手工 SQL 到达这个分支的。
 *   不写这个测试的后果：阶段 7 真的实现取消功能时，
 *   才第一次发现这条路径是错的 —— 而那时它已经在生产路径上了。
 * =============================================================================
 */
public class InvalidOrderStateException extends BusinessException {

    private final OrderStatus currentStatus;

    public InvalidOrderStateException(String orderNo, OrderStatus currentStatus, String attemptedAction) {
        super(HttpStatus.CONFLICT,
                "订单当前状态为「" + currentStatus.label() + "」，不能" + attemptedAction,
                List.of(
                        "orderNo=" + orderNo,
                        "currentStatus=" + currentStatus.code() + "（" + currentStatus.label() + "）"));
        this.currentStatus = currentStatus;
    }

    /** 订单被读到的那个（导致本次请求被拒的）状态。 */
    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
