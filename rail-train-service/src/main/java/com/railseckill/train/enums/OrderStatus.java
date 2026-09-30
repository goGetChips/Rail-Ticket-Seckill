package com.railseckill.train.enums;

/**
 * 订单状态，对应 {@code t_order.status}。
 *
 * <p>取值约定见 {@code sql/04_rail_order.sql}：0=待支付 1=已支付 2=已取消。
 */
/*
 * =============================================================================
 *  【⚠️ 这个状态值数据库层面不强制，全靠条件 UPDATE 的 AND status = ? 兜住】
 * =============================================================================
 *
 *   t_seat_inventory 有两个 CHECK 约束（sold_count >= 0、sold_count <= total_count）
 *   当最后一道防线。**t_order 上没有任何 CHECK 约束** ——
 *   `UPDATE t_order SET status = 99` 数据库会照单全收。
 *
 *   所以状态机的正确性**完全依赖**每次迁移都写成条件 UPDATE：
 *
 *       UPDATE t_order SET status = 1 WHERE order_no = ? AND status = 0
 *
 *   那句 `AND status = 0` 就是没有 CHECK 约束时的替代品：
 *   它把"当前状态是不是我期待的那个"压进了同一条原子 SQL 里，
 *   受影响行数 0 就说明状态已经不是我以为的样子了。
 *
 *   【为什么不在数据库上加 CHECK (status IN (0,1,2))】
 *   那是一道更强的防线，本项目没做，属于已知缺口。理由是：
 *   CHECK 能拦住"写入一个非法值"，但拦不住"从 1 跳回 0"这种
 *   **合法取值之间的非法迁移**——那只能靠条件 UPDATE。既然迁移的
 *   正确性本来就必须在 SQL 里保证，再补一个 CHECK 只增加了
 *   一道能拦的东西更少的防线。（这条判断可以在阶段 8 复核。）
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 阶段 5 只用到 PENDING 和 PAID 两个值的**迁移**，CANCELLED 不会被写入
 * -----------------------------------------------------------------------------
 *   阶段 5 的范围是「下单 + 模拟支付」，**不含取消 / 超时关单 / 回补库存**
 *   （那是阶段 7/9）。所以：
 *     · 代码不会把订单改成 2
 *     · 但**必须能正确识别** 2 —— payOrder 读到 2 时要返回 409
 *       "订单已取消，不能支付"，而不是像 DDL 注释里写的那样
 *       "受影响 0 行就直接返回成功"（那会告诉用户一个不存在的支付成功）
 *
 *   CANCELLED 出现在这里，是为了让那条判断有名字可用，
 *   而不是为了让阶段 5 去写它。
 * =============================================================================
 */
public enum OrderStatus {

    /** 待支付。订单刚创建时的状态。 */
    PENDING(0, "待支付"),

    /** 已支付。阶段 5 只做模拟支付，不对接真实支付渠道。 */
    PAID(1, "已支付"),

    /** 已取消。阶段 5 不会写入这个值，只会在 payOrder 里识别它。 */
    CANCELLED(2, "已取消");

    private final int code;

    private final String label;

    OrderStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 数据库里存的值。 */
    public int code() {
        return code;
    }

    /** 中文名，用于拼错误信息和日志。 */
    public String label() {
        return label;
    }

    /**
     * 由数据库值反查枚举。
     *
     * <p>无法识别时抛异常，理由同 {@link SeatType#of(int)}。
     */
    public static OrderStatus of(int code) {
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的订单状态：" + code + "（约定只有 0/1/2）");
    }
}
