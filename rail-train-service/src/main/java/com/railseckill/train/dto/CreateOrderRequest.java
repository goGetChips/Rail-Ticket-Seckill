package com.railseckill.train.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 下单请求体。
 *
 * <p>用于 {@code POST /api/order/orders}。
 *
 * @param userId     下单人 ID
 * @param trainNo    车次号，如 {@code G1}
 * @param travelDate 乘车日期
 * @param seatType   席别：1=商务座 2=一等座 3=二等座
 */
/*
 * =============================================================================
 *  🔴 【userId 由客户端传入 —— 这是阶段 5 最大的安全缺口，必须显著标注】
 * =============================================================================
 *   现在任何人 POST 一个 `{"userId": 1, ...}` 就能**以用户 1 的身份下单**。
 *   这不是疏忽，是阶段顺序决定的临时形态：
 *     · 没有 user-service，没有 JWT，t_user 表里一行数据都没有（阶段 8 才有）
 *     · 本阶段要证明的是"并发下不超卖"，这个命题和身份认证正交
 *
 *   ⚠️ **但没有记录下来的缺口才是危险的那个**，所以这里写清楚：
 *
 *     终态（阶段 8）：网关校验 JWT → 把用户身份放进请求头或
 *     SecurityContext → Controller 从**这里**取 userId，
 *     请求体里的 userId 字段**直接删掉**（不是"服务端覆盖它"，
 *     而是这个字段根本不该存在 —— 客户端不该有机会表达"我是谁"）。
 *
 *   为什么不是"服务端覆盖"：只要字段还收，就总有人写
 *   `if (req.userId() == null) userId = fromToken;` 这种"灵活"的代码，
 *   而那个 if 就是漏洞本身。**去掉字段比覆盖字段更难写错。**
 *
 *   顺带一个具体的攻击场景（面试常问）：如果这个字段在真实系统里存在，
 *   攻击者可以拿别人的 userId 疯狂下单占库存 —— 库存被吃光，
 *   而所有"重复购票"的唯一索引约束都是按**受害者**的 userId 生效的，
 *   受害者自己反而买不了票。
 *
 * =============================================================================
 *  ⭐ 【为什么刻意不收 fromStationId / toStationId】
 * =============================================================================
 *   查询接口（`GET /api/train/trains/search`）是支持"济南西 → 南京南"
 *   这种中途上车的，但**下单不支持** —— 这两个站字段在阶段 5
 *   恒等于该车次的始发站和终到站，由服务端从 t_train 取。
 *
 *   让客户端传会引入三条内部一致性校验（两个站必须在这趟车上、
 *   顺序必须正确、必须和库存粒度对得上），却**没有对应的业务能力** ——
 *   因为库存是按"车次+日期+席别"存的整段库存，**没有区间维度**。
 *   详见 entity/OrderItem.java 的类注释。
 *
 *   ⚠️ 由此产生一个必须写进文档的行为不一致：**查得到、买不了。**
 *      它不是 bug，是库存粒度的必然结果，但用户能直接感知到。
 *
 * =============================================================================
 *  【为什么用 @NotNull + @Positive 而不是 @Min(1)】
 * =============================================================================
 *   两个注解各自负责一件事，报告的错误才准确：
 *     @NotNull  → "下单人不能为空"      （没传这个字段）
 *     @Positive → "下单人 ID 必须是正整数" （传了 0 或 -1）
 *   只用 @Min(1) 的话，null 会通过校验，然后在 Service 里拆箱成 long 时
 *   抛 NullPointerException → **500**。一个本该是 400 的调用错误变成 500，
 *   在监控上就是一次假的"服务故障"。
 *
 *   ⚠️ 这个坑在**包装类型 + 校验**的组合里非常常见：
 *      @Min 只在值非 null 时才被评估，null 是"跳过校验"而不是"校验失败"。
 *      **只要字段是包装类型，就必须自己补 @NotNull。**
 *
 * =============================================================================
 *  【为什么 seatType 用 @Min(1) @Max(3)，而不是 @Pattern 或字符串】
 * =============================================================================
 *   对外契约是数字 1/2/3（阶段 4 的 SeatAvailability 已经这么发布了），
 *   改它会是一次破坏性 API 变更。
 *
 *   ⚠️ 这两个注解不只是"友好提示"，它们是**承重的**：
 *      Service 里会调用 `SeatType.of(req.seatType())`，而它遇到
 *      未知值**抛 IllegalArgumentException**（刻意不返回 null）。
 *      没有 @Min/@Max 拦住的话，`{"seatType": 9}` 会变成
 *      IllegalArgumentException → 被兜底 handler 变成 **500**。
 *      有了它们，同一个输入得到 **400 + "seatType: 席别只有 1/2/3"**。
 *      —— **参数校验的落点是"让错误变成 400 而不是 500"**，
 *      这一点比"提示友好"重要得多。
 *
 * =============================================================================
 *  【@FutureOrPresent：不只拦"昨天的票"】
 * =============================================================================
 *   它同时表达了"不能买已经过去的日期"这条业务规则。
 *
 *   ⚠️ 它按 **JVM 的默认时区**判断"今天"，而库存是按数据库的 CURDATE()
 *      放的。本机两者都是 +08:00，一致；**部署到别的时区时要重新核对这一条**。
 *      （application.yml 里 connectionTimeZone 固定成 +08:00 也基于同一个假设。）
 *
 *   为什么要有它：没有它，"买昨天的票"会一路走到库存查询，
 *   查不到那一行 → 报 **409「该席别尚未放票」**。
 *   而真实原因是"日期是非法的"，用 400 表达才准确 ——
 *   它和"这一天还没开卖"对用户是两件完全不同的事。
 *
 * =============================================================================
 *  【每条 message 都写中文，理由同 TrainController】
 * =============================================================================
 *   Jakarta 的默认 message 是英文模板（"must be greater than or equal to 1"），
 *   会原样出现在 details 里。这是一个中文文档的项目，接口报错却是英文，
 *   读起来割裂。显式写中文只多几个字符。
 * =============================================================================
 */
public record CreateOrderRequest(

        @NotNull(message = "下单人不能为空")
        @Positive(message = "下单人 ID 必须是正整数")
        Long userId,

        @NotBlank(message = "车次号不能为空")
        @Size(max = 16, message = "车次号长度不能超过 16")
        String trainNo,

        @NotNull(message = "乘车日期不能为空")
        @FutureOrPresent(message = "乘车日期不能早于今天")
        LocalDate travelDate,

        @NotNull(message = "席别不能为空")
        @Min(value = 1, message = "席别只有 1/2/3")
        @Max(value = 3, message = "席别只有 1/2/3")
        Integer seatType) {
}
