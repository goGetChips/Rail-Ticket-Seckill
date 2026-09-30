package com.railseckill.train.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 下单成功的响应体。
 *
 * <p>用于 {@code POST /api/order/orders} 的 200 响应。
 *
 * @param orderNo    业务订单号，后续支付用它
 * @param userId     下单人 ID
 * @param trainNo    车次号
 * @param travelDate 乘车日期
 * @param seatType   席别：1=商务座 2=一等座 3=二等座
 * @param totalAmount 订单总额
 * @param status     订单状态：0=待支付 1=已支付 2=已取消
 * @param statusLabel 状态的中文名，仅用于让响应可读
 */
/*
 * =============================================================================
 *  ⭐ 【为什么返回 200 而不是 201 Created】
 * =============================================================================
 *   201 的语义是"资源已创建"，并且**约定俗成要带一个 Location 头**
 *   指向这个新资源，让调用方知道去哪儿读它。
 *
 *   而阶段 5 **没有实现** `GET /api/order/orders/{orderNo}` ——
 *   查订单的接口属于后面的阶段。如果这里返回 201 + Location，
 *   那个 Location 指的 URL 会返回 404：**响应承诺了一个不存在的资源。**
 *
 *   ⚠️ 一个指向 404 的 Location 头比没有 Location 更糟：
 *      调用方会照着它去请求，然后拿到 404，于是开始怀疑
 *      "订单是不是没建成"—— 而订单其实建成了。
 *
 *   等有了查订单接口，这条应该改成 201 + Location，
 *   并且**那是一次响应契约的变更**，要写进文档。现在返回 200 是诚实的选择。
 *
 * =============================================================================
 *  【为什么没有 createTime】
 * =============================================================================
 *   t_order.create_time 由数据库的 DEFAULT CURRENT_TIMESTAMP 生成，
 *   而 MyBatis-Plus 的 insert **只回填自增主键**，不回填那些由数据库
 *   填充的普通列。所以插入完之后 Java 手里的 order.createTime 是 null。
 *
 *   要拿到真实值就得再 SELECT 一次 —— 为了一个展示字段多一次查询不划算；
 *   而返回一个 null 字段更糟（调用方会以为"创建时间没记上"）。
 *   **所以这个字段干脆不出现。** 需要它的接口（订单详情）在后面的阶段
 *   自己去查那一行，那时读取本来就是它的职责。
 *
 * =============================================================================
 *  【为什么 statusLabel 要有】
 * =============================================================================
 *   状态是数字（0/1/2），需要对着文档才能看懂。多一个中文字段，
 *   让"用 curl 看一眼就知道对不对"成为可能 —— 本阶段验证要大量用 curl。
 *
 *   ⚠️ 它是**给人看的**，不是契约：前端应该根据 `status` 数字自己决定展示，
 *      不要 `if (statusLabel.equals("已支付"))`。文案会变，数字不会。
 * =============================================================================
 */
public record OrderCreated(
        String orderNo,
        Long userId,
        String trainNo,
        LocalDate travelDate,
        Integer seatType,
        BigDecimal totalAmount,
        Integer status,
        String statusLabel) {
}
