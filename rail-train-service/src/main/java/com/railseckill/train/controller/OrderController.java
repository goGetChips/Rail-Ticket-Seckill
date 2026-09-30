package com.railseckill.train.controller;

import com.railseckill.train.dto.CreateOrderRequest;
import com.railseckill.train.dto.OrderCreated;
import com.railseckill.train.dto.OrderStatusChanged;
import com.railseckill.train.entity.Train;
import com.railseckill.train.enums.SeatType;
import com.railseckill.train.service.OrderService;
import com.railseckill.train.service.TrainService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下单与支付接口。
 *
 * <pre>
 *   POST /api/order/orders                   下单（扣库存 + 建订单）
 *   POST /api/order/orders/{orderNo}/pay     模拟支付
 * </pre>
 *
 * <p><b>这是全项目前两个"写"接口。</b> 阶段 0~4 的 6 个接口全是查询，
 * 不存在"虽然返回了 200 但数据是错的"这种失败；从这里开始它存在了。
 *
 * <h2>状态码一览</h2>
 * <pre>
 *   POST /api/order/orders
 *     200 OrderCreated                          下单成功
 *     400 （details 非空）                       请求体不合法：缺字段 / 日期是过去 / seatType 不在 1~3
 *     404 空 body                               车次号不存在
 *     409 {"error":"Conflict", ...}             未放票 / 已售罄 / 重复购票
 *
 *   POST /api/order/orders/{orderNo}/pay
 *     200 OrderStatusChanged                    支付成功（幂等重复时为 idempotent=true）
 *     404 空 body                               订单号不存在
 *     409 订单已取消，不能支付
 * </pre>
 */
/*
 * =============================================================================
 *  ⭐ 【1. 为什么路径是 /api/order/** 而不是 /api/train/**（虽然代码住在 train-service）】
 * =============================================================================
 *   路径的第二段标识的是**服务归属**，不是资源归属 ——
 *   和 /api/train/trains、/api/inventory/seats 是同一套约定。
 *
 *   下单属于订单服务，所以从第一天起就用 /api/order，
 *   阶段 8 拆出 rail-order-service（8084）时只需加一条网关路由，
 *   **客户端一行都不用改**。
 *
 *   ⚠️ 反过来（现在叫 /api/train/orders，阶段 8 再改成 /api/order/orders）
 *      会是一次破坏性的 URL 变更，所有调用方都得跟着改。
 *      拆服务时"代码搬家"和"接口改地址"是两件事，
 *      **只让前者发生**是可以在第一天就决定的。
 *
 * =============================================================================
 *  ⭐⭐ 【2. 为什么"车次不存在 → 404"写在 Controller，不写在 Service】
 * =============================================================================
 *   判据：**它是 HTTP 语义判断，不是业务规则。**
 *
 *   "车次号拼错了"这件事，只有面向 HTTP 的这一层才知道该用 404 表达。
 *   如果把这个判断放进 OrderService，Service 就得开始关心 HTTP 状态码 ——
 *   而 Service 现在处理业务失败的方式是**抛异常**（409 那三个分支），
 *   404 却要变成一个"返回 null"的信号，两套失败表达混在一起。
 *
 *   ⚠️ 更要紧的是**事务边界**：这一句放在 Controller，
 *      意味着"车次不存在"这条路径**根本不会开启事务**。
 *      放进 Service 就必须先开事务、查一次、再抛异常回滚 ——
 *      为一个必然失败的请求白付一次事务开销。
 *
 *   ⭐ 对照记住这条分界：
 *      · "你指的东西不存在" → 404 → Controller 判断 → **不开事务**
 *      · "东西存在但规则不允许" → 409 → Service 抛异常 → 事务回滚
 *
 * =============================================================================
 *  ⭐⭐ 【3. 「还没放票」是 409 不是 404 —— 这个选择很容易做反】
 * =============================================================================
 *   一个自然的直觉是："t_seat_inventory 里查不到这一行，那不就是'没找到'吗？404。"
 *   **错。**
 *
 *       用户请求：G1 / 2026-12-01 / 商务座
 *       t_seat_inventory 里没有这一天 G1 的行
 *
 *   这句话的准确含义是「**G1 这趟车是对的，只是这一天还没放票**」——
 *   车次存在（否则上一步就 404 了），只是这个组合还没有库存记录。
 *
 *   判据是**用户下一步该做什么**：
 *     · 404 的心理暗示是"你指的车次不存在" → 用户会去改车次号
 *     · 409 的心理暗示是"这个操作现在不允许" → 用户会去换个日期
 *   而正确答案是**换个日期**（或者等放票）。用 404 会把人指向完全错误的动作。
 *
 *   ⚠️ 顺带一个必须写进文档的行为缺口：**查询接口支持"中途上车"
 *   （GET /api/train/trains/search 接受任意两个经停站），
 *   下单接口阶段 5 不支持** —— t_order_item 的两个站字段恒等于
 *   车次的始发/终到站，由服务端从 t_train 取，请求体里根本没有这两个字段。
 *   根因是库存粒度是"车次+日期+席别"的整段库存，**没有区间维度**。
 *   所以「济南西 → 南京南」这个区间查得到、买不了。
 *   这不是 bug，是库存粒度的必然结果 —— 但用户能直接感知到，必须写出来。
 *
 * =============================================================================
 *  ⭐ 【4. @Valid 是承重的 —— 漏写它是静默失效】
 * =============================================================================
 *   本类引入了全项目第一个 @Valid @RequestBody，因此：
 *
 *   ⚠️ **漏写 @Valid 不会报任何错。** 结果不是"校验宽松一点"，
 *      而是 CreateOrderRequest 上的所有约束**全部不执行**：
 *      `{}` 空请求体会一路走到 Service，`req.userId()` 是 null，
 *      拆箱成 `long` 时抛 NullPointerException → **500**。
 *      接口"能用"，只是所有非法输入都变成 500 而不是 400。
 *
 *   ★ 这就是为什么验证清单里必须有一条 `POST` 空 body 期望 400：
 *     **它是唯一能证明校验真的在跑的证据。**
 *     返回 500 或 200 都说明 @Valid 没生效或 validation 依赖缺失。
 *
 *   ⚠️ 同样地，本类**不加类级 @Validated**（理由同 TrainController §2）：
 *      加了之后校验改走 AOP，同一批约束会抛 ConstraintViolationException
 *      而不是 MethodArgumentNotValidException，错误体形状跟着变。
 *
 * =============================================================================
 *  ⭐ 【5. 为什么 pay 用 POST 而不是 PUT】
 * =============================================================================
 *   REST 的经典说法是"幂等操作该用 PUT"。本接口确实是幂等的
 *   （重复支付返回 200 + idempotent=true），但仍然用 POST，理由：
 *
 *     · PUT 的语义是「**用请求体替换目标资源**」，而这里**连请求体都没有**
 *     · "支付"是一个**动作**，不是一次资源替换。真实系统里它一定会带
 *       支付渠道、支付流水号、金额等请求体 —— 那时更不可能是 PUT
 *
 *   ⭐ 关键区分：**幂等是我们给这个操作赋予的性质，不是 HTTP 方法的定义。**
 *      用 POST 承载一个被实现成幂等的操作是完全允许的（HTTP 规范只要求
 *      POST 的语义"不保证幂等"，不禁止实现者让它幂等）。
 *      为了让方法名好看而选 PUT，反而会让将来的请求体无处安放。
 *
 * =============================================================================
 *  【6. 为什么返回 ResponseEntity 而不是直接返回 DTO】
 * =============================================================================
 *   因为这两个接口**都有多个成功状态之外的分支**（404 空 body、409 由
 *   异常接管）。直接返回 DTO 就只能表达 200 一种；
 *   ResponseEntity 让"这个方法会返回哪几种响应"在签名上可见。
 *
 *   阶段 4 的 TrainController 也是这么做的（stops / search 返回
 *   ResponseEntity<List<...>>），本类沿用同一风格。
 * =============================================================================
 */
@RestController
@RequestMapping("/api/order/orders")
public class OrderController {

    private final TrainService trainService;

    private final OrderService orderService;

    public OrderController(TrainService trainService, OrderService orderService) {
        this.trainService = trainService;
        this.orderService = orderService;
    }

    /**
     * 下单。
     *
     * <pre>
     *   POST /api/order/orders
     *   {"userId":1,"trainNo":"G1","travelDate":"2026-11-29","seatType":1}
     *   → {"orderNo":"202609301530123456780001","userId":1,"trainNo":"G1",
     *      "travelDate":"2026-11-29","seatType":1,"totalAmount":1748.00,
     *      "status":0,"statusLabel":"待支付"}
     * </pre>
     *
     * <p><b>⚠️ travelDate 必须先由 SQL 造出库存行，否则一定是 409「尚未放票」。</b>
     * 阶段 5 <b>不新增种子数据</b> —— 种子脚本（sql/11_seed_inventory.sql）
     * 放的是"执行它那天"的 +1/+2/+3 天，过几天就过期了。
     * 测试用的库存行由测试自己造（见 OrderServiceIT / OrderConcurrencyTest）。
     *
     * @param request 下单请求，字段约束见 {@link CreateOrderRequest}
     */
    @PostMapping
    public ResponseEntity<OrderCreated> create(@Valid @RequestBody CreateOrderRequest request) {

        /*
         * 【为什么用 getByTrainNo 而不是 getById】
         * 客户端手里只有车次号（G1），没有数据库 id。
         * 让客户端先查一次列表拿 id 再下单，是把一次查询的负担
         * 转嫁给调用方，还会引入"id 过期/错配"的可能。
         *
         * ⚠️ 这一次查询是在事务外的。所以理论上存在一个极小窗口：
         *    这里查到车次存在，紧接着有人删了它，然后扣库存时库存行也没了
         *    → 走到 Service 里报 409「尚未放票」。
         *    这不是 bug，是"没有外键约束"的必然结果（跨库外键在 MySQL 里
         *    也做不到），而且结果仍然是诚实的失败，不是脏数据。
         */
        Train train = trainService.getByTrainNo(request.trainNo());
        if (train == null) {
            return ResponseEntity.notFound().build();
        }

        /*
         * ⚠️ SeatType.of 遇到 1~3 之外的值会抛 IllegalArgumentException → 500。
         * 走到这里能抛出来，说明 @Min(1) @Max(3) 没拦住 ——
         * 那正是"校验没生效"这个故障的显形方式（400 变成了 500）。
         * CreateOrderRequest 的类注释里有完整说明。
         */
        return ResponseEntity.ok(orderService.createOrder(
                train,
                request.userId(),
                request.travelDate(),
                SeatType.of(request.seatType())));
    }

    /**
     * 模拟支付：把订单从"待支付"改成"已支付"。
     *
     * <pre>
     *   POST /api/order/orders/202609301530123456780001/pay
     *   → {"orderNo":"...","status":1,"statusLabel":"已支付","idempotent":false}
     * </pre>
     *
     * <p><b>重复调用返回 200 且 {@code idempotent=true}</b> ——
     * 这是幂等，不是错误。字段含义见 {@link OrderStatusChanged}。
     *
     * <p>⚠️ <b>订单已取消（status=2）返回 409，不是"成功"。</b>
     * 这一点和 sql/04_rail_order.sql 与 docs/03-business-flow.md 里的措辞
     * <b>不一致</b>，那两处写的是"受影响 0 行 = 已被处理过，直接返回成功"。
     * 那句话对"重复支付"成立，对"已取消"**是错的** ——
     * 给一个已取消的订单回"支付成功"，是在告诉用户他有一张不存在的票。
     * 完整论证见 {@link com.railseckill.train.exception.InvalidOrderStateException}
     * 与 OrderService#payOrder 的注释，文档同步时按后者修正。
     */
    @PostMapping("/{orderNo}/pay")
    public ResponseEntity<OrderStatusChanged> pay(@PathVariable String orderNo) {
        /*
         * 【为什么这里没有 @Valid / @NotBlank(orderNo)】
         * 订单号的合法性由**查不到 → 404** 这一条路径覆盖，
         * 和 /api/train/trains/{trainNo}/stations 完全一致。
         *
         * 加一条格式校验（比如"必须 24 位数字"）看起来更严谨，
         * 但它会把这个格式变成**对外契约** —— 将来订单号规则一改
         * （换号段、加前缀、迁到号池服务），所有调用方都受影响。
         * 而"查不到就是 404"永远不会因为规则变化而失效。
         *
         * ★ 判据：**能被"存在性检查"覆盖的格式校验，就不要额外加。**
         *   它带来的是更严格的契约，换来的确定性是零。
         */
        return ResponseEntity.ok(orderService.payOrder(orderNo));
    }
}
