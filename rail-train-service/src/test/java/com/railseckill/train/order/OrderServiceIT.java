package com.railseckill.train.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railseckill.train.entity.Train;
import com.railseckill.train.service.TrainService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下单 / 支付的**单线程语义**测试 —— 阶段 5 的第一个测试类。
 *
 * <p>它验证的是"每条业务分支是否走了正确的路径"：成功、未放票、售罄、
 * 重复购票、支付幂等、支付已取消、车次不存在、参数校验失败。
 *
 * <p><b>它证明不了并发正确性</b> —— 那是 {@link OrderConcurrencyTest} 的事。
 * 两个类都绿，阶段判据才成立。
 *
 * <h2>为什么走真实 HTTP 而不是直接调 OrderService</h2>
 * 有三个分支**只在 HTTP 层存在**：
 * <ul>
 *   <li>{@code @Valid} 失败 → 400 + 非空 details。这条路径要
 *       Jackson 反序列化 + Validator + ApiExceptionHandler 全部参与，
 *       直接调 Service 根本走不到。</li>
 *   <li>车次不存在 → 404 空 body。这个判断写在 {@code OrderController} 里。</li>
 *   <li>409 的响应体形状（message 里是不是"已售罄"）。这是 ApiExceptionHandler 的产物。</li>
 * </ul>
 * 走 HTTP 还能顺带证明"Controller 有没有漏写 {@code @Valid}"这种静默失效。
 */
/*
 * =============================================================================
 *  🔴🔴 【本类绝不能加 @Transactional —— 连单线程用例都不行】
 * =============================================================================
 *   直觉是"单线程测试加事务回滚最干净"。**错，而且失败方式很隐蔽。**
 *
 *   本类用的是真实 HTTP（RANDOM_PORT），所以请求是**Tomcat 的另一个线程**
 *   处理的，它从连接池里拿的是**另一条连接**。于是：
 *
 *     测试方法开事务 → @BeforeEach 插入 fixture（未提交）→
 *     发 HTTP 请求 → 处理线程在**另一条连接**上看不到那条未提交的 fixture
 *     → REPEATABLE READ 默认隔离级别下读不到 → 返回 409「尚未放票」
 *     → 测试失败，而你以为是业务代码错了
 *
 *   ⚠️ 这个坑和"并发测试不能用事务回滚"是**同一个根因**（连接隔离），
 *      只是它在单线程下也会出现，因为 HTTP 引入了第二个线程。
 *
 *   所以清理靠 @AfterEach 里的**显式 DELETE**，不靠事务回滚。
 *
 *   ⚠️ ⚠️ 这与 sql/11_seed_inventory.sql 里那句"回归测试用测试框架里的事务回滚
 *      做清理"有张力。那句话在"单个连接内做完所有事"的测试里是对的，
 *      在**任何走 HTTP 或开多线程**的测试里都不成立。
 *      ★ 这是需要回头修正的文档点（见阶段 5 文档同步清单）。
 * =============================================================================
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServiceIT {

    /*
     * =========================================================================
     *  【测试常量：每一个都换来一条确定性，不要"随手写一个"】
     * =========================================================================
     */

    /** 用 G1（北京南 → 上海虹桥），种子脚本里一定有它。 */
    private static final String TRAIN_NO = "G1";

    /** 1=商务座。三个席别里取一个固定值，让"扣错席别"能立刻暴露。 */
    private static final int SEAT_TYPE = 1;

    private static final BigDecimal PRICE = new BigDecimal("1748.00");

    /**
     * 测试日期 = 今天 + 60 天。
     *
     * <p>⛔ <b>绝不用 LocalDate.now().plusDays(1) 去找种子里的库存行</b>：
     * sql/11_seed_inventory.sql 放的是"**执行它那天**"的 +1/+2/+3 天，
     * 过几天就查不到了 —— 测试会报「尚未放票」，而那不是 bug，是测试写错了。
     *
     * <p>✔ fixture 由本类自己造，日期自己定。这样测试**任何时候跑都成立**。
     *
     * <p>⚠️ 选 +60 而不是 +1，还有一个理由：JMeter 脚本用的是 +70
     * （{@code ${__timeShift(yyyy-MM-dd,,P70D,,)}}）。两者错开，
     * 同时跑的时候不会互相踩 fixture。
     */
    private static final LocalDate TEST_DATE = LocalDate.now().plusDays(60);

    /**
     * 哨兵 userId 的基数 9_100_000。
     *
     * <p>为什么需要哨兵区间：清理只能按 user_id 定位（t_order 表里
     * **没有 train_id / travel_date 列**，票的定位信息在 t_order_item 上）。
     * 用一个远高于任何真实用户的区间，才能保证 DELETE 不会误删别人的数据。
     *
     * <p>约定（和 sql/99_verify.sql 用 999999999 当哨兵是同一条思路）：
     * <pre>
     *   9_100_000 ~ 9_199_999   JUnit（本类 + 并发测试）
     *   9_300_000 ~ 9_399_999   JMeter（stage5-order.jmx 用 __threadNum + 9300000）
     * </pre>
     * 两个区间错开，JMeter 和 JUnit 可以同时跑而不互相清理。
     */
    private static final long SENTINEL_USER_BASE = 9_100_000L;

    private static final long SENTINEL_USER_MIN = 9_100_000L;

    private static final long SENTINEL_USER_MAX = 9_199_999L;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TrainService trainService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 车次 id 从库里查，**不写 trainId = 1**（理由见 setUp）。 */
    private Long trainId;

    @BeforeEach
    void setUp() throws SQLException {
        /*
         * 【为什么 trainId 要查出来，而不是写死 1】
         * 两个理由，任一都足以致命：
         *   1. 重跑 sql/10_seed_train.sql 会 DROP + INSERT，自增值不会回到 1
         *   2. 库里的 id 是**不连续**的（G1 是 1，但 id=2 是 G3 不是 G2）——
         *      凭"G1 应该是第 1 条"这种直觉写死 id，会扣到别的车次的库存上，
         *      而测试照样能过（因为 fixture 也是按同一个错 id 造的）
         */
        Train train = trainService.getByTrainNo(TRAIN_NO);
        assertThat(train)
                .as("库里没有车次 %s —— 先执行 sql/10_seed_train.sql", TRAIN_NO)
                .isNotNull();
        trainId = train.getId();

        assertConnectedToExpectedSchema();

        // 先清理再建，保证重复执行是幂等的（上一次跑挂在中途也会被收拾干净）
        cleanFixtures();
        insertInventory(50);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // =========================================================================
    //  用例
    // =========================================================================

    @Test
    @DisplayName("正常下单：200 + 四张表都写对（库存 +1、订单 1、票 1、流水 1）")
    void createOrder_success() {
        long userId = user(1);

        ResponseEntity<String> response = postOrder(body(userId, TRAIN_NO, TEST_DATE, SEAT_TYPE));

        assertThat(response.getStatusCode())
                .as("期望 200，实际响应体：%s", response.getBody())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> body = json(response);
        String orderNo = (String) body.get("orderNo");

        assertThat(orderNo).as("订单号不能为空").isNotBlank();
        assertThat(orderNo).as("订单号是 24 位（17 时间戳 + 3 序列 + 4 随机）").hasSize(24);
        assertThat(body.get("status")).as("新订单是待支付").isEqualTo(0);
        assertThat(body.get("statusLabel")).isEqualTo("待支付");
        assertThat(body.get("trainNo")).isEqualTo(TRAIN_NO);
        assertThat(body.get("seatType")).isEqualTo(SEAT_TYPE);
        assertThat(body.get("travelDate")).isEqualTo(TEST_DATE.toString());

        /*
         * ⚠️ 金额比较必须用 compareTo，不能用 equals。
         * BigDecimal.equals **对 scale 敏感**：1748.00 和 1748.0 的 equals 是 false
         * （虽然数值相等）。JSON 里出来的是 1748.00，但这条路一旦经过
         * 任何一次算术（比如以后要乘以张数），scale 就会变，
         * 于是"金额没变但断言挂了"。
         * AssertJ 的 isEqualByComparingTo 用的是 compareTo，是金额比较的正确做法。
         */
        assertThat(new BigDecimal(String.valueOf(body.get("totalAmount"))))
                .as("订单总额应等于库存行里的票价")
                .isEqualByComparingTo(PRICE);

        // ---- 四张表逐一核对。断言全部读**数据库里的真实值**，不信响应体 ----

        assertThat(soldCount()).as("库存应恰好 +1").isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM rail_order.t_order WHERE user_id = ?", userId))
                .as("t_order 应有 1 行").isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM rail_order.t_order_item WHERE user_id = ?", userId))
                .as("t_order_item 应有 1 行").isEqualTo(1);

        /*
         * 流水行是"少卖"检查的依据，所以每个字段都要核对 ——
         * biz_id 拼错、change_type 写成正扣或回补，都会让对账 SQL 失效，
         * 而接口本身照样返回 200。
         */
        Map<String, Object> flow = jdbc.queryForMap(
                "SELECT biz_id, change_type, change_count, train_id, travel_date, seat_type "
                        + "FROM rail_inventory.t_stock_flow WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);
        assertThat(flow.get("biz_id")).as("流水的 biz_id 必须是订单号").isEqualTo(orderNo);
        assertThat(((Number) flow.get("change_type")).intValue())
                .as("change_type=2（确认扣减）—— 方案 A 里扣减即终态").isEqualTo(2);
        assertThat(((Number) flow.get("change_count")).intValue())
                .as("扣减为正").isEqualTo(1);
    }

    @Test
    @DisplayName("车次不存在：404 空 body（不是 409，也不是 400）")
    void createOrder_trainNotFound() {
        ResponseEntity<String> response = postOrder(body(user(2), "NOPE", TEST_DATE, SEAT_TYPE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        /*
         * ⚠️ 空 body 是阶段 4 就定下的约定（见 docs/api/error-codes.md §五），
         * 这里把它作为**契约**断言下来 —— 因为阶段 5 之后，
         * "404 也有结构化错误体"这个诱惑会越来越大（眼看 409 就有）。
         * 用测试把现状钉住，将来要改它必须显式改这里。
         */
        assertThat(response.getBody())
                .as("404 是空 body，不是 ApiError（这是已知缺口，见 error-codes.md）")
                .satisfiesAnyOf(
                        b -> assertThat(b).isNull(),
                        b -> assertThat(b).isBlank());
    }

    @Test
    @DisplayName("这一天还没放票：409「尚未放票」（不是 404）")
    void createOrder_notOnSale() {
        // TEST_DATE + 1 天没有 fixture，所以库存行不存在
        LocalDate noStockDate = TEST_DATE.plusDays(1);

        ResponseEntity<String> response = postOrder(body(user(3), TRAIN_NO, noStockDate, SEAT_TYPE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(message(response))
                .as("「没放票」和「售罄」必须是两条不同的信息 —— 用户下一步动作不同")
                .contains("尚未放票");
        assertThat(soldCount()).as("失败不该动库存").isZero();
    }

    @Test
    @DisplayName("已售罄：409「已售罄」（0 行受影响 = 正常业务失败，不是 500）")
    void createOrder_soldOut() {
        // 把票额改成 1，先卖掉，再让另一个人买
        jdbc.update("UPDATE rail_inventory.t_seat_inventory SET total_count = 1 "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);

        String orderNo = createOrderOk(user(4));
        assertThat(orderNo).isNotBlank();
        assertThat(soldCount()).isEqualTo(1);

        ResponseEntity<String> response = postOrder(body(user(5), TRAIN_NO, TEST_DATE, SEAT_TYPE));

        /*
         * ⭐ 这个用例是全阶段最核心的一条断言：
         *   受影响行数 = 0 时必须是 **409 正常业务失败**，
         *   而不是异常、更不是 500。sql/03_rail_inventory.sql §4 把
         *   "0 行 = 正常业务失败，抛异常 = 系统失败、结果未知"
         *   定成了规格，这里就是那条规格的可执行版本。
         */
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(message(response)).contains("已售罄");

        assertThat(soldCount()).as("售罄后库存不该再涨").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM rail_order.t_order WHERE user_id = ?", user(5L)))
                .as("失败的用户不该有订单").isZero();
    }

    @Test
    @DisplayName("重复购票：409「重复购票」，且已扣的那张库存被回滚（不吞异常）")
    void createOrder_duplicate() {
        long userId = user(6);

        String firstOrderNo = createOrderOk(userId);
        assertThat(firstOrderNo).isNotBlank();
        assertThat(soldCount()).isEqualTo(1);

        ResponseEntity<String> response = postOrder(body(userId, TRAIN_NO, TEST_DATE, SEAT_TYPE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(message(response)).contains("重复购票");

        /*
         * 🔴🔴 【这个断言是"catch 块不能 return"的证明】
         *
         * 第二次下单的执行顺序是：
         *   ① 读库存行  → 有
         *   ② 扣减      → **成功**（sold_count 变成 2）
         *   ③ 写订单    → 成功
         *   ④ 写票      → 撞唯一索引 uk_user_train_date_seat，抛 DuplicateKeyException
         *
         * 如果 OrderService 在 ④ 的 catch 里 **return** 一个 409，
         * Spring 会认为方法正常结束 → **COMMIT** → ② 的扣减被提交。
         * 结果：库存少了一张可卖的票、票没卖出去、接口规规矩矩返回 409、
         * **没有任何日志和告警**。这就是"少卖"。
         *
         * 正确实现是 catch 里 throw → 事务回滚 → ② 的扣减被撤销 →
         * sold_count 回到 1。下面这条断言就是在测这个。
         */
        assertThat(soldCount())
                .as("第二次失败的下单，它的库存扣减必须被回滚 —— 否则是静默「少卖」")
                .isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM rail_order.t_order WHERE user_id = ?", userId))
                .as("t_order 只应有 1 行（第二次的订单头也被回滚了）").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM rail_order.t_order_item WHERE user_id = ?", userId))
                .as("票只有 1 张").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM rail_inventory.t_stock_flow "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE))
                .as("流水只有 1 行").isEqualTo(1);
    }

    @Test
    @DisplayName("支付：第一次 idempotent=false，第二次 200 且 idempotent=true")
    void pay_idempotent() {
        String orderNo = createOrderOk(user(7));

        ResponseEntity<String> first = postPay(orderNo);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(first).get("idempotent"))
                .as("第一次是真的状态流转").isEqualTo(false);
        assertThat(json(first).get("status")).isEqualTo(1);
        assertThat(json(first).get("statusLabel")).isEqualTo("已支付");

        assertThat(orderStatus(orderNo)).as("库里状态应变成 1").isEqualTo(1);

        /*
         * ⭐ 第二次必须是 200 而不是 409：
         * "重复支付"里，订单**已经是我想要的状态**，所以报成功（幂等）；
         * 与之相对的是下面的"已取消"，那个**永远不可能变成我想要的状态**，
         * 必须报错。判据见 InvalidOrderStateException 的类注释。
         */
        ResponseEntity<String> second = postPay(orderNo);
        assertThat(second.getStatusCode()).as("重复支付是幂等，不是错误").isEqualTo(HttpStatus.OK);
        assertThat(json(second).get("idempotent"))
                .as("第二次没有改变任何东西，必须能从响应上看出来").isEqualTo(true);

        assertThat(orderStatus(orderNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("支付已取消的订单：409（不是「成功」—— 这句推翻了 DDL 注释的措辞）")
    void pay_cancelled() {
        String orderNo = createOrderOk(user(8));

        /*
         * ⚠️⚠️ 阶段 5 **没有取消接口**，所以没有任何代码能把订单变成 status=2。
         * 这个分支只能靠手工 SQL 构造 —— 也就是**必须真的构造一次**，
         * 否则它是一段从没被执行过的代码，而"从没执行过的代码"
         * 和"不存在的代码"在正确性上没有区别。
         */
        jdbc.update("UPDATE rail_order.t_order SET status = 2 WHERE order_no = ?", orderNo);
        assertThat(orderStatus(orderNo)).as("前置条件：订单已变成已取消").isEqualTo(2);

        ResponseEntity<String> response = postPay(orderNo);

        /*
         * 🔴 这一条**偏离了 sql/04_rail_order.sql 与 docs/03-business-flow.md 的措辞**：
         *   那两处写的是"受影响 0 行 = 已被处理过（重复支付 / 已取消），直接返回成功"。
         *   那句话对"重复支付"成立，对"已取消"**是错的** ——
         *   给一个已取消的订单回"支付成功"，是在告诉用户他有一张不存在的票
         *   （票已经被回补、可能已经卖给别人了）。
         *
         * 所以这里断言 409，并且**文档同步时要按本用例修正那两处措辞**。
         */
        assertThat(response.getStatusCode())
                .as("已取消的订单不能报「支付成功」")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(message(response)).contains("已取消");

        assertThat(orderStatus(orderNo)).as("失败的支付不能改状态").isEqualTo(2);
    }

    @Test
    @DisplayName("支付不存在的订单：404")
    void pay_notFound() {
        ResponseEntity<String> response = postPay("999999999999999999999999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("参数校验：空 body → 400 且 details 非空（漏写 @Valid 会变成 500）")
    void createOrder_validationFailed() {
        ResponseEntity<String> response = postOrder("{}");

        /*
         * ⭐⭐ 【这一条是"@Valid 真的生效了"的唯一证据】
         *
         * 漏写 @Valid 是**静默失效**：不会报错、编译通过、启动正常，
         * 只是所有约束都不执行。`{}` 会一路走到 Service，
         * req.userId() 是 null，拆箱成 long 时抛 NullPointerException → **500**。
         * 也就是说"漏写 @Valid"的症状是 **500 而不是 200** ——
         * 如果这里看到 500，先回去检查 @Valid，而不是去查 Service。
         *
         * 同理，如果 spring-boot-starter-validation 依赖缺失，
         * Hibernate Validator 不在类路径上，校验也会**静默跳过**。
         */
        assertThat(response.getStatusCode())
                .as("@Valid 没生效的话这里会是 500 —— 这是它唯一的显形方式")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> body = json(response);
        assertThat(body.get("message")).isEqualTo("请求参数校验未通过");

        /*
         * details 非空是**必须**断言的：
         * docs/api/error-codes.md §三② 记录过一次"details 是空的，
         * 调用方看不出哪个字段错了"的事故。@Valid 是第二个校验入口机制，
         * 如果 details() 里没为它加分支，就会**第二次**退化成空列表
         * （虽然状态码仍然是正确的 400）。见 ApiExceptionHandler#details。
         */
        List<String> details = details(response);
        assertThat(details)
                .as("details 为空的话，调用方只知道 400、不知道哪儿错了")
                .isNotEmpty()
                .contains("userId: 下单人不能为空");

        assertThat(soldCount()).as("校验失败不该碰库存").isZero();
    }

    @Test
    @DisplayName("参数校验：seatType=9 → 400（不是 500，尽管 SeatType.of 会抛异常）")
    void createOrder_seatTypeOutOfRange() {
        ResponseEntity<String> response = postOrder(body(user(9), TRAIN_NO, TEST_DATE, 9));

        /*
         * ⚠️ 这条断言是"@Min/@Max 是**承重的**而不是装饰"的证明：
         *
         * Service 里会调用 SeatType.of(9)，而它遇到未知值**抛
         * IllegalArgumentException**（刻意不返回 null，见 SeatType 的注释）。
         * 如果 @Min(1)/@Max(3) 没拦住，这个异常会被兜底 handler 变成 **500**
         * —— 一个纯客户端错误被记成服务端故障，在监控上就是一次假告警。
         *
         * 参数校验最重要的作用不是"提示友好"，而是**让错误变成 400 而不是 500**。
         */
        assertThat(response.getStatusCode())
                .as("@Min/@Max 没拦住的话，SeatType.of(9) 会让这里变成 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(details(response)).contains("seatType: 席别只有 1/2/3");
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    private static long user(long n) {
        return SENTINEL_USER_BASE + n;
    }

    /**
     * 断言当前连接真的连在预期的地方。
     *
     * <p>⚠️ 存在这个断言是因为 {@code application.yml} 里有
     * {@code spring.config.import: optional:classpath:application-local.yml} ——
     * 这个文件**只要存在就自动生效**。如果它指向另一个数据库，
     * 测试会连到错的库上，症状是"fixture 造了但查不到"，很难定位。
     *
     * <p>第二次查询顺带证明**跨库访问在当前连接上真的通**：
     * 阶段 5 的整个写路径都依赖它（见 OrderService 的类注释）。
     */
    private void assertConnectedToExpectedSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .as("测试连到了非预期的库 —— 检查 classpath 下有没有 application-local.yml")
                    .contains("/rail_train");
        }

        assertThat(count("SELECT COUNT(*) FROM rail_inventory.t_seat_inventory"))
                .as("跨库读 rail_inventory 失败 —— 阶段 5 的扣减路径依赖它")
                .isGreaterThanOrEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM rail_order.t_order"))
                .as("跨库读 rail_order 失败 —— 阶段 5 的下单路径依赖它")
                .isGreaterThanOrEqualTo(0);
    }

    /**
     * 造 fixture：一行的库存（total 可指定，sold_count 从 0 开始）。
     *
     * <p>「先 DELETE 再 INSERT」而不是「有则 UPDATE」：让重复执行的结果
     * 与第一次完全一致，不残留上一次的 sold_count。
     */
    private void insertInventory(int totalCount) {
        jdbc.update("DELETE FROM rail_inventory.t_seat_inventory "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);

        jdbc.update("INSERT INTO rail_inventory.t_seat_inventory "
                        + "(train_id, travel_date, seat_type, price, total_count, sold_count) "
                        + "VALUES (?, ?, ?, ?, ?, 0)",
                trainId, TEST_DATE, SEAT_TYPE, PRICE, totalCount);
    }

    /**
     * 清理本类造的所有数据。
     *
     * <p>⚠️ 顺序：流水 → 票 → 订单 → 库存。虽然库里**没有外键约束**
     * （跨库外键在 MySQL 里做不到，见 sql/ 的说明），顺序不影响执行成功与否，
     * 但按依赖方向删更不容易在将来加约束时出错。
     *
     * <p>三个不同的定位方式，反映了三张表的可定位性差别：
     * <ul>
     *   <li>t_stock_flow / t_order_item → 有 train_id + travel_date + seat_type，
     *       可以**按 fixture 位置定位**，精确且不会误伤</li>
     *   <li>t_order → **没有**车次/日期列，只能按 user_id 哨兵区间定位
     *       （票的位置信息在 t_order_item 上，订单头只记"谁买的"）</li>
     * </ul>
     */
    private void cleanFixtures() {
        jdbc.update("DELETE FROM rail_inventory.t_stock_flow "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);
        jdbc.update("DELETE FROM rail_order.t_order_item "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);
        jdbc.update("DELETE FROM rail_order.t_order WHERE user_id BETWEEN ? AND ?",
                SENTINEL_USER_MIN, SENTINEL_USER_MAX);
    }

    private int soldCount() {
        return count("SELECT sold_count FROM rail_inventory.t_seat_inventory "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);
    }

    private int orderStatus(String orderNo) {
        return count("SELECT status FROM rail_order.t_order WHERE order_no = ?", orderNo);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertThat(value).as("查询返回 null：%s", sql).isNotNull();
        return value;
    }

    private static String body(long userId, String trainNo, LocalDate travelDate, int seatType) {
        return """
                {"userId":%d,"trainNo":"%s","travelDate":"%s","seatType":%d}"""
                .formatted(userId, trainNo, travelDate, seatType);
    }

    private ResponseEntity<String> postOrder(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/order/orders", new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> postPay(String orderNo) {
        // pay 没有请求体。⚠️ 但仍然要带 Content-Type 吗？不需要 ——
        // 没有 @RequestBody 的方法不会解析请求体，也就不会检查 Content-Type。
        return rest.postForEntity("/api/order/orders/" + orderNo + "/pay", null, String.class);
    }

    /** 下单并断言成功，返回订单号。 */
    private String createOrderOk(long userId) {
        ResponseEntity<String> response = postOrder(body(userId, TRAIN_NO, TEST_DATE, SEAT_TYPE));
        assertThat(response.getStatusCode())
                .as("期望下单成功，实际响应体：%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return (String) json(response).get("orderNo");
    }

    private Map<String, Object> json(ResponseEntity<String> response) {
        try {
            Map<String, Object> body = objectMapper.readValue(
                    response.getBody(), new TypeReference<Map<String, Object>>() {
                    });
            assertThat(body).as("响应体为空，无法解析：%s", response.getBody()).isNotNull();
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + response.getBody(), e);
        }
    }

    private String message(ResponseEntity<String> response) {
        Object message = json(response).get("message");
        assertThat(message).as("错误响应体里必须有一句面向人的 message").isNotNull();
        return String.valueOf(message);
    }

    @SuppressWarnings("unchecked")
    private List<String> details(ResponseEntity<String> response) {
        Object details = json(response).get("details");
        assertThat(details).as("错误响应体里的 details 不应为 null（约定是空列表而不是 null）").isNotNull();
        return (List<String>) details;
    }
}
