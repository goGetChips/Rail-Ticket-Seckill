package com.railseckill.train.exception;

import com.railseckill.train.enums.SeatType;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 票买不到：尚未放票，或者已售罄。
 *
 * <p>对应 HTTP <b>409 Conflict</b>。
 */
/*
 * =============================================================================
 *  ⭐⭐ 【为什么"没放票"和"售罄"必须分成两个原因，而不是合成一句"没票了"】
 * =============================================================================
 *   它们对用户的**下一步动作**指向完全不同：
 *
 *     NOT_ON_SALE（未放票）→ "这天的票还没开卖，过几天再来"
 *     SOLD_OUT   （已售罄）→ "这天的票卖光了，换车次或换日期"
 *
 *   合并成"无票"之后，用户不知道该等还是该换 —— 而这个区别
 *   数据库里本来就是可查的（库存表有没有那一行），
 *   是我们主动把它合并掉的。
 *
 *   docs/api/error-codes.md 已经把"还没放票 ≠ 已售罄"定为项目硬规则，
 *   这里是它在代码里的落点。
 *
 *   ⚠️ 但两者用**同一个状态码 409**，因为它们对调用方的处理方式是同一类：
 *   "这个请求现在做不了，之后也不会因为重发而成功"。区分只在 message 上。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【为什么是 409 而不是 404】
 * -----------------------------------------------------------------------------
 *   判据：**404 = "你指的东西不存在"；409 = "东西存在，但规则不允许"。**
 *
 *     · trainNo 拼错 → 车次不存在 → **404**（在 Controller 层判断）
 *     · 车次是对的，只是这一天没放票 → 库存行不存在 → **409**
 *
 *   如果把"未放票"也做成 404，前端会提示"车次不存在" ——
 *   而车次明明存在，用户会去改车次号，方向完全错了。
 *
 *   更根本的一点：**库存行不存在，不代表"车次资源不存在"这个 404 语义成立。**
 *   404 说的是 URL 指向的资源，而这里的 URL 是 /api/order/orders，
 *   它永远存在。
 *
 * -----------------------------------------------------------------------------
 *  ⭐ 【售罄与并发的关系：库存紧张时，重复提交者拿到的可能是"售罄"】
 * -----------------------------------------------------------------------------
 *   一个必须写进文档的产品语义：库存只剩 1 张时，同一个用户并发提交 50 次，
 *   第 1 次扣减成功、其余 49 次会在**扣库存这一步**就拿到 SOLD_OUT ——
 *   它们**根本没走到** t_order_item 那个"禁止重复购票"的唯一索引。
 *
 *   也就是说：**这 49 次失败的原因是"售罄"，不是"请勿重复购票"。**
 *   两个都对，取决于库存还剩多少。
 *
 *   ⚠️ 所以前端**不能依赖 message 文案**来判断用户到底犯了哪个错 ——
 *      message 不是契约（见 docs/api/error-codes.md），能依赖的只有状态码。
 *      这个结论也让"给 409 加一个细分错误码"有了真实的需求动机，
 *      但那要等阶段 8 一起做。
 * =============================================================================
 */
public class SeatNotAvailableException extends BusinessException {

    /**
     * 买不到票的两种原因。
     *
     * <p>用嵌套枚举而不是两个异常类：它们的**处理方式完全相同**
     * （都是 409、都不需要调用方做不同的事），差别只在文案和一个定位字段。
     * 拆成两个类会让 {@code catch (BusinessException)} 那一层多一个
     * 需要区分的东西，而调用方其实不需要区分。
     */
    public enum Reason {

        /** 这一天（这个席别）还没有放票 —— 库存表里没有对应的行。 */
        NOT_ON_SALE("该席别尚未放票"),

        /** 已经放票了，但票卖完了 —— 库存行在，只是 sold_count 已经等于 total_count。 */
        SOLD_OUT("该席别已售罄");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /** 面向调用方的一句话说明。 */
        public String message() {
            return message;
        }
    }

    private final Reason reason;

    public SeatNotAvailableException(Reason reason, String trainNo, LocalDate travelDate, int seatTypeCode) {
        super(HttpStatus.CONFLICT, reason.message(), List.of(
                "trainNo=" + trainNo,
                "travelDate=" + travelDate,
                "seatType=" + seatTypeCode + "（" + SeatType.of(seatTypeCode).label() + "）"));
        this.reason = reason;
    }

    /** 失败的具体原因。给日志和测试用；响应体里体现为 message 文案。 */
    public Reason getReason() {
        return reason;
    }
}
