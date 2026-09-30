package com.railseckill.train.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务失败的基类。
 *
 * <p>继承它意味着：<b>请求本身是合法的，是业务规则不允许这么做。</b>
 * 由 {@code ApiExceptionHandler} 统一转成对应的 HTTP 状态码。
 *
 * <p>子类见 {@link SeatNotAvailableException}（未放票 / 售罄）、
 * {@link DuplicateOrderException}（重复购票）、
 * {@link InvalidOrderStateException}（订单状态不允许）、
 * {@link OrderNotFoundException}（订单不存在）。
 */
/*
 * =============================================================================
 *  ⭐ 【为什么业务失败用异常表达，而不是让 Service 返回 null / 枚举 / ResponseEntity】
 * =============================================================================
 *   三种写法都有人用，本项目选异常的判据是**"调用方是否可能忘记处理它"**：
 *
 *     返回 null            → 调用方忘记判空 → NPE，或者更糟：把 null 当成成功
 *     返回结果枚举         → 忘记 switch 的 default → 静默当成成功
 *     抛异常              → **编译器/运行时都不给你忘记的机会**，
 *                            不处理就一路往上抛，最差也是个 500
 *
 *   业务失败一旦被当成成功，后果是"库存扣了、订单没建"这类只有在
 *   对账时才能发现的问题。**让错误无法被忽略**，比让代码短一点重要。
 *
 * -----------------------------------------------------------------------------
 *  ⭐ 【为什么带 HttpStatus，而不是让 ApiExceptionHandler 去 instanceof 判断类】
 * -----------------------------------------------------------------------------
 *   如果状态码写在 handler 里，那么"加一个新的业务异常"要改**两个地方**
 *   （新异常类 + handler 的 if/switch）。漏改一处，新异常就会掉进
 *   兜底的 500 —— 而 500 是"服务坏了"的语义，会让监控误报。
 *
 *   状态码放在异常自己身上之后，"这个业务失败该回什么状态码"
 *   和"什么情况下算这种失败"写在同一个文件里，改的时候一目了然。
 *
 *   ⚠️ 这里也顺带解释了一个容易被质疑的设计：**如果所有子类都是 409，
 *      那 status 这个字段就是伪装成字段的常量。**
 *      所以本项目刻意保留了 {@link OrderNotFoundException}（404）——
 *      "404 与 409 的分界是「你指的东西不存在」vs「东西存在但规则不允许」"，
 *      两种语义真实存在，status 字段因此是有用的。
 *
 * -----------------------------------------------------------------------------
 *  【为什么是抽象类，不是接口】
 * -----------------------------------------------------------------------------
 *   因为要继承 RuntimeException —— Java 单继承，所以只能是抽象类。
 *   （接口 + 让每个实现自己 extends RuntimeException 会导致
 *    "不是所有实现都是 Throwable"，异常处理那一层就没法统一 catch 了。）
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【一个刻意不做的优化，附上做它的条件】
 * -----------------------------------------------------------------------------
 *   业务异常在秒杀场景下是**高频且正常**的（100 个人抢 20 张票，
 *   80 个人会得到"售罄"）。每抛一次都要 fillInStackTrace，
 *   在高并发下是一笔真实的、纯浪费的开销。
 *
 *   可以做的是 `super(message, null, false, false)`（关掉栈的写入），
 *   但**现在不做**：阶段 5 还没有任何实测数据说明这笔开销值得优化，
 *   而关掉栈会让"万一真需要看这个异常从哪来"变得不可能。
 *   什么时候做：阶段 6 压测数据显示异常构造在火焰图里可辨认时。
 *   —— 这是本项目对"优化"的一贯立场：先有数字，再有优化。
 * =============================================================================
 */
public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;

    private final List<String> details;

    /**
     * @param status  该业务失败对应的 HTTP 状态码
     * @param message 面向调用方的一句话说明。会**原样出现在响应体里**，
     *                所以要写成用户能看懂的话，不要写内部术语、不要拼 SQL。
     * @param details 定位信息（见下）。传 {@code null} 会被当成空列表。
     */
    protected BusinessException(HttpStatus status, String message, List<String> details) {
        super(message);
        this.status = status;
        /*
         * List.copyOf 一次做两件事：拒绝 null 元素，并且做一份不可变副本。
         *
         * 用 List.copyOf 而不是直接赋值，是为了守住 ApiError 那条
         * "details 不能为 null"的约定 —— 构造点在这里收口，
         * 就不需要让 ApiExceptionHandler 再去判一次空。
         * （null 会抛 NPE，这是**故意的**：传 null 是编码错误，应该立刻暴露。）
         */
        this.details = List.copyOf(details);
    }

    /** 期望返回给调用方的 HTTP 状态码。 */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * 定位信息，会原样进入响应体的 {@code details} 字段。
     *
     * <p>⚠️ <b>409 的 details 是「定位信息」，不是「逐字段的校验原因」。</b>
     * 这个区别值得说清：400 的 details 回答的是"你哪个字段填错了"
     * （"seatType: 席别只有 1/2/3"），而 409 的 details 回答的是
     * <b>"你这次失败针对的是哪一趟车的哪一天"</b>
     * （"trainNo=G1"、"travelDate=2026-11-29"、"seatType=1（商务座）"）。
     *
     * <p>为什么 409 需要这个：售罄和未放票都不带字段级错误 —— 请求完全合法。
     * 但调用方在**并发重试或批量下单**时，需要一个东西来对应"我这一批里
     * 哪个请求失败了"，否则回执里只有一句"已售罄"，对不上号。
     */
    public List<String> getDetails() {
        return details;
    }
}
