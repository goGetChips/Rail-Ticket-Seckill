package com.railseckill.train.order;

import com.railseckill.train.entity.Train;
import com.railseckill.train.enums.SeatType;
import com.railseckill.train.exception.DuplicateOrderException;
import com.railseckill.train.exception.SeatNotAvailableException;
import com.railseckill.train.service.OrderService;
import com.railseckill.train.service.TrainService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发正确性测试 —— <b>阶段 5 的判据就是这一个类</b>。
 *
 * <p>阶段判据（{@code docs/status/development-status.md}）：
 * <b>"用条件 UPDATE 实现并发下不超卖，并用并发测试证明它不超卖"</b>。
 * 前半句由 {@code SeatInventoryMapper.deductStock} 实现，
 * 后半句由本类提供证据。
 *
 * <h2>四个用例</h2>
 * <ol>
 *   <li><b>100 线程抢 20 张票</b> → 恰好 20 成功、80 售罄，库里 sold_count 恰好 20</li>
 *   <li><b>100 线程抢 0 张票</b> → 0 成功、100 售罄（全售罄场景）</li>
 *   <li><b>同一用户 50 并发、票额充足</b> → 只产生 1 单，
 *       且 sold_count 只 +1（<b>跨库事务原子性的直接证据</b>）</li>
 *   <li><b>同一用户 50 并发、只剩 1 张票</b> → 只产生 1 单，
 *       但另 49 个拿到的是<b>「售罄」而不是「重复购票」</b>（产品语义）</li>
 * </ol>
 */
/*
 * =============================================================================
 *  ⚠️⚠️ 【为什么这个测试的失败模式是"假阳性"，不是"假阴性"】
 * =============================================================================
 *   普通测试的担心是"该红的没红"（假阴性）。本类相反：
 *   它**很容易绿，但什么都没证明**。四种典型的自欺欺人：
 *
 *   1. 事务把并发度降到 1
 *      测试方法如果加了 @Transactional，所有线程会共用一个事务/连接，
 *      串行执行 —— 那是在测"单线程下单"，而不是并发。
 *
 *   2. 连接池把并发度降到 10
 *      HikariCP 默认 maximum-pool-size=10。100 个线程抢 10 条连接，
 *      实际只有 10 个同时在跑。测试**照样会通过**，
 *      但它证明的是"10 并发下不超卖"。见下面 properties 的说明。
 *
 *   3. 断言之前不等待线程跑完
 *      下面那句 done.await(...) 的返回值是**承重的**：
 *      不等齐就断言，等于在"部分线程还没跑完"的状态下比较成功数，
 *      有可能恰好凑出 ok == 20 这种假通过。
 *
 *   4. 把非预期异常算进"正常失败数"
 *      如果断言写成 ok + soldOut == 100，那么死锁、连接超时、
 *      MyBatis 参数绑定异常随便来几个，只要 ok 恰好是 20 就通过了。
 *      所以下面**单独收集并断言非预期异常为空**。
 *
 *   ⭐ 一句话：**这个测试绿了不等于对，但红了基本都是真问题。**
 *
 * =============================================================================
 *  ⚠️ 【绝不用 @Transactional（四条理由，任一都足以让测试失效）】
 * =============================================================================
 *   1. fixture 在测试方法的事务里未提交，工作线程走**另外的连接**，
 *      REPEATABLE READ 下看不见 → 全部走"尚未放票"分支，测试测了个寂寞
 *   2. 工作线程各自提交自己的事务，测试结束时的 rollback **管不到**它们
 *      → 清理失效、脏数据留在库里
 *   3. 断言读到的是自己未提交的改动 → 断言永远成立，看不出任何并发失败
 *   4. 测试事务持有的锁与工作线程互等 → **挂起约 50 秒**（锁等待超时）
 *      后集体失败，而报错信息完全指不出真正的原因
 *
 *   所以清理靠 @AfterEach 里的**显式 DELETE**。
 *
 * =============================================================================
 *  ⚠️ 【为什么这个类直接调 OrderService，不学 OrderServiceIT 走 HTTP】
 * =============================================================================
 *   两个测试类的分工是刻意的：
 *
 *     OrderServiceIT      走真实 HTTP —— 因为 400/details/404 只在 HTTP 层存在
 *     本类                 直接调 Service —— 因为需要精确控制并发度
 *
 *   ⚠️ 走 HTTP 反而会让并发度**静默地**掉下来：
 *      TestRestTemplate 在没有 Apache HttpClient 时用 SimpleClientHttpRequestFactory
 *      （基于 HttpURLConnection），而 HttpURLConnection 的
 *      `http.maxConnections` **默认只有 5** —— 每个目标主机最多 5 条并发连接。
 *      100 个线程会被悄悄排成"每批 5 个"，测试照样绿，
 *      但它证明的其实是"5 并发下不超卖"。
 *
 *   ⭐ 真正的 HTTP 层并发验证交给 JMeter（scripts/perf/stage5-order.jmx）——
 *      它有自己的连接池，且会真的经过 Tomcat 线程池、Jackson、@Valid。
 *      两个层次的交叉验证比在 JUnit 里硬凑 HTTP 并发更有信息量。
 * =============================================================================
 */
@SpringBootTest(properties = {
        /*
         * HikariCP 默认 maximum-pool-size=10 —— 100 个线程只有 10 个能同时跑。
         * 调到 32 之后的**真实并发度是 min(线程数, 池大小) = 32**，
         * 所以下面断言描述里写的是"32 并发"，不是"100 并发"。
         *
         * ⚠️ 为什么不调到 100：本机 MySQL 的 max_connections=151，
         * 留出余量才能同时跑 JMeter 或别的测试。
         *
         * ⚠️⚠️ 只调**测试上下文**，不动 application.yml ——
         * 池大小本身是阶段 6 要压测的变量之一，现在改生产配置会把
         * "阶段 6 之前"的基准线弄脏。
         */
        "spring.datasource.hikari.maximum-pool-size=32",

        /*
         * mapper 的 debug 日志会把每条 SQL 都打出来。
         * 100 线程 × 5 条 SQL × 4 个用例 = 上千行日志，
         * 真正的异常会被淹没在里面 —— 而"看不见异常"正是假阳性的温床。
         */
        "logging.level.com.railseckill.train.mapper=info"
})
class OrderConcurrencyTest {

    private static final String TRAIN_NO = "G1";

    private static final int SEAT_TYPE = 1;

    private static final SeatType BUSINESS = SeatType.BUSINESS;

    private static final BigDecimal PRICE = new BigDecimal("1748.00");

    /**
     * 与其他两个测试资源错开的日期。
     *
     * <pre>
     *   OrderServiceIT          今天 + 60 天
     *   本类                     今天 + 55 天
     *   stage5-order.jmx        今天 + 70 天
     * </pre>
     * 三者都不同，所以它们**可以同时跑**而不会互相清理对方的 fixture。
     * （fixture 的定位是「车次 + 日期 + 席别」，日期不同就不会重叠。）
     */
    private static final LocalDate TEST_DATE = LocalDate.now().plusDays(55);

    private static final long SENTINEL_USER_BASE = 9_100_000L;

    private static final long SENTINEL_USER_MIN = 9_100_000L;

    private static final long SENTINEL_USER_MAX = 9_199_999L;

    /**
     * 等待所有线程跑完的上限。
     *
     * <p>正常情况几秒内就跑完了。设 120 秒是为了让**慢**和**卡死**能区分开：
     * 超时说明有线程被锁挂住了，此时的断言结果没有意义，
     * 所以下面把 await 的返回值本身也断言了。
     */
    private static final int TIMEOUT_SECONDS = 120;

    @Autowired
    private OrderService orderService;

    @Autowired
    private TrainService trainService;

    @Autowired
    private JdbcTemplate jdbc;

    private Train train;

    @BeforeEach
    void setUp() {
        train = trainService.getByTrainNo(TRAIN_NO);
        assertThat(train).as("库里没有车次 %s —— 先执行 sql/10_seed_train.sql", TRAIN_NO).isNotNull();
        cleanFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // =========================================================================
    //  用例
    // =========================================================================

    @Test
    @DisplayName("判据：100 线程抢 20 张票 → 恰好卖出 20 张，一个不多一个不少")
    void noOversell() throws InterruptedException {
        final int threads = 100;
        final int stock = 20;
        insertInventory(stock);

        // 每个线程一个不同的 userId —— 让 100 个请求都"合法"，
        // 唯一的约束只剩库存。这样失败原因只可能是"售罄"。
        Counters counters = runConcurrently(threads, i -> SENTINEL_USER_BASE + i);
        report("100 线程抢 20 张票", counters);

        /*
         * ⭐ 第一步先证明"测试本身跑完整了"。
         * 没有这两条，下面的数字可能只是"部分线程的结果"。
         */
        assertThat(counters.unexpected)
                .as("出现了预期外的异常，第一种是：%s", counters.unexpected.peek())
                .isEmpty();
        assertThat(counters.attempted())
                .as("成功 + 各种失败 + 异常的总数必须等于线程数，否则有线程的结果丢了")
                .isEqualTo(threads);
        assertThat(counters.deadlock.get())
                .as("观测到死锁 %d 次。它非 0 就是真实的缺陷（用户拿到了 500），"
                        + "请把次数和复现条件记入 docs/troubleshooting/", counters.deadlock.get())
                .isZero();
        assertThat(counters.lockTimeout.get())
                .as("观测到锁等待超时 %d 次", counters.lockTimeout.get())
                .isZero();

        // ---- 核心断言：恰好 20 成功、恰好 80 售罄 ----
        assertThat(counters.ok.get())
                .as("成功数必须**恰好**等于票额。多了是超卖，少了是少卖")
                .isEqualTo(stock);
        assertThat(counters.soldOut.get())
                .as("其余全部应是「已售罄」这个正常业务失败")
                .isEqualTo(threads - stock);
        assertThat(counters.notOnSale.get())
                .as("不该出现「尚未放票」—— fixture 明明造了")
                .isZero();
        assertThat(counters.duplicate.get())
                .as("每个线程的 userId 都不同，不该有重复购票")
                .isZero();

        /*
         * ⭐⭐ 上面全部是线程内的计数。下面才是**数据库的真实状态** ——
         * 判据必须落在库里，因为"计数器对上"和"数据没坏"是两件事
         * （比如扣减被回滚了但线程认为成功了）。
         */
        assertThat(soldCount())
                .as("库里 sold_count 必须恰好等于票额，也必须恰好等于成功数")
                .isEqualTo(stock);

        assertThat(countByFixture("rail_order.t_order_item"))
                .as("票（订单明细）数必须等于票额").isEqualTo(stock);
        assertThat(countByFixture("rail_inventory.t_stock_flow"))
                .as("库存流水数必须等于票额 —— 一条扣减对应一条流水").isEqualTo(stock);
        assertThat(countOrdersOfSentinels())
                .as("订单数必须等于票额").isEqualTo(stock);

        /*
         * 三条"最后防线"级别的 SQL 校验，和 docs/05-seckill.md §六 对应。
         * 放在这里是因为它们只在这个规模的数据上才有意义
         * （单线程用例里这些断言是恒真的）。
         *
         * ① 超卖：sold_count 永远不该超过 total_count
         */
        assertThat(count("SELECT COUNT(*) FROM rail_inventory.t_seat_inventory "
                        + "WHERE sold_count > total_count"))
                .as("出现了超卖！sold_count > total_count 的行数不为 0")
                .isZero();

        /*
         * ② 重复购票：同一个「用户 + 车次 + 日期 + 席别」只能有一张票。
         * ⚠️ 它永远返回 0 行**不是因为业务逻辑写对了，而是因为唯一索引
         *    让 c > 1 物理上不可能** —— 所以这条校验的真实作用是
         *    "证明唯一索引 uk_user_train_date_seat 真的建了"。
         */
        assertThat(count("SELECT COUNT(*) FROM ("
                        + "  SELECT user_id, train_id, travel_date, seat_type "
                        + "  FROM rail_order.t_order_item "
                        + "  GROUP BY user_id, train_id, travel_date, seat_type HAVING COUNT(*) > 1"
                        + ") AS dup"))
                .as("出现了一个用户同一趟车同一天同一席别的多张票 —— 检查唯一索引是否还在")
                .isZero();

        /*
         * ⑤ 少卖：每一张"有效订单"都必须有一条 change_type=2 的扣减流水，
         *    且数量为 1。这条是 CHECK 约束 ck_sold_not_exceed_total
         *    **证明不了的那一半** —— CHECK 只能看到"卖多了"，
         *    看不到"扣了库存但订单没写成功"。
         *
         * ⚠️ 这里加了 user_id 哨兵条件，只检查本次测试的订单 ——
         *    不加的话，种子数据里任何历史订单都会让它失败。
         */
        assertThat(count("SELECT COUNT(*) FROM rail_order.t_order o "
                        + "LEFT JOIN rail_inventory.t_stock_flow f "
                        + "  ON f.biz_id = o.order_no AND f.change_type = 2 "
                        + "WHERE o.user_id BETWEEN ? AND ? "
                        + "  AND o.status <> 2 "
                        + "  AND (f.id IS NULL OR f.change_count <> 1)",
                SENTINEL_USER_MIN, SENTINEL_USER_MAX))
                .as("有订单没有对应的扣减流水（少卖）—— 或流水的数量不是 1")
                .isZero();
    }

    @Test
    @DisplayName("全部售罄：100 线程抢 0 张票 → 0 成功、100 个「已售罄」，且没有订单写入")
    void soldOutFromTheStart() throws InterruptedException {
        final int threads = 100;
        insertInventory(0);

        Counters counters = runConcurrently(threads, i -> SENTINEL_USER_BASE + i);
        report("100 线程抢 0 张票", counters);

        assertThat(counters.unexpected)
                .as("出现了预期外的异常，第一种是：%s", counters.unexpected.peek())
                .isEmpty();
        assertThat(counters.attempted()).isEqualTo(threads);

        assertThat(counters.ok.get()).as("一张票都没有，不该有任何成功").isZero();
        assertThat(counters.soldOut.get()).as("全部应是「已售罄」").isEqualTo(threads);
        assertThat(counters.notOnSale.get()).isZero();

        assertThat(soldCount()).as("sold_count 不该变").isZero();
        assertThat(countByFixture("rail_order.t_order_item")).isZero();
        assertThat(countByFixture("rail_inventory.t_stock_flow")).isZero();
        assertThat(countOrdersOfSentinels()).isZero();
    }

    @Test
    @DisplayName("同一用户 50 并发（票额充足）→ 只 1 单，且扣减只 +1（跨库事务原子性证据）")
    void sameUserConcurrently() throws InterruptedException {
        final int threads = 50;
        final long userId = SENTINEL_USER_BASE + 500;

        /*
         * ⚠️⚠️ 票额必须**远大于**线程数，否则这个用例是白跑的。
         *
         * 如果只放 1 张票：第 1 个线程扣减成功，其余 49 个在**扣库存那一步**
         * 就直接返回 0 → 拿到「售罄」→ 根本走不到 t_order_item 那个
         * 「禁止重复购票」的唯一索引。于是这个用例什么都没证明，
         * 只是把上一个用例又跑了一遍。
         *
         * 放 100 张票，49 个线程才会**真的执行到 INSERT 并撞上唯一索引** ——
         * 那正是我们要测的路径。
         */
        insertInventory(100);

        Counters counters = runConcurrently(threads, i -> userId);
        report("同一用户 50 并发（票额充足）", counters);

        assertThat(counters.unexpected)
                .as("出现了预期外的异常，第一种是：%s", counters.unexpected.peek())
                .isEmpty();
        assertThat(counters.attempted()).isEqualTo(threads);

        assertThat(counters.ok.get()).as("同一用户只能有一张票").isEqualTo(1);
        assertThat(counters.duplicate.get())
                .as("其余 49 个都该撞上唯一索引 → 重复购票")
                .isEqualTo(threads - 1);
        assertThat(counters.soldOut.get())
                .as("票额 100 > 线程 50，不该有售罄").isZero();
        assertThat(counters.deadlock.get())
                .as("观测到死锁 %d 次。锁的获取顺序是统一的（先库存行、后唯一索引），"
                        + "理论上不该有环 —— 但这是实测数字，不是推理结论", counters.deadlock.get())
                .isZero();

        /*
         * ⭐⭐⭐ 【这一条断言是整个阶段 5 最有分量的一个数字】
         *
         *   50 个线程**全部**执行了那条扣减 UPDATE
         *   （每个线程都先扣库存、再写订单、再写票）。
         *   如果每次扣减都独立提交，sold_count 会变成 50。
         *
         *   它最终是 1，意味着：**另外 49 次扣减，被 rail_order 库里
         *   那次唯一索引冲突连带回滚了。**
         *
         *   换句话说：**一个事务同时改了两个库，回滚也同时撤回了两个库。**
         *   这就是"跨库本地事务"这件事的**可执行证据** ——
         *   比任何"理论上 InnoDB 事务是服务器级的"的论证都硬。
         *
         *   ⭐ 反过来说，如果这个数字是 50，说明 OrderService 的 catch 块里
         *   `return` 了而不是 `throw`（Spring 会提交事务），
         *   那就是静默的「少卖」：49 张可卖的票永远卖不出去了，
         *   而接口一直规规矩矩地返回 409，没有任何告警。
         */
        assertThat(soldCount())
                .as("50 次扣减只该留下 1 次 —— 另外 49 次必须被跨库事务一起回滚")
                .isEqualTo(1);

        assertThat(countOrdersOfSentinels()).as("只该有 1 个订单").isEqualTo(1);
        assertThat(countByFixture("rail_order.t_order_item")).as("只该有 1 张票").isEqualTo(1);
        assertThat(countByFixture("rail_inventory.t_stock_flow")).as("只该有 1 条流水").isEqualTo(1);
    }

    @Test
    @DisplayName("同一用户 50 并发（只剩 1 张票）→ 拿到的是「售罄」，不是「重复购票」")
    void sameUserConcurrentlyWithTightStock() throws InterruptedException {
        final int threads = 50;
        final long userId = SENTINEL_USER_BASE + 600;
        insertInventory(1);

        Counters counters = runConcurrently(threads, i -> userId);
        report("同一用户 50 并发（只剩 1 张票）", counters);

        assertThat(counters.unexpected)
                .as("出现了预期外的异常，第一种是：%s", counters.unexpected.peek())
                .isEmpty();
        assertThat(counters.attempted()).isEqualTo(threads);

        assertThat(counters.ok.get()).isEqualTo(1);
        /*
         * 🔴 【这个用例把一条容易写错的产品语义变成了可执行的断言】
         *
         *   直觉上"同一用户并发重复提交"应该报「请勿重复购票」。
         *   但库存只剩 1 张时，扣减那一步就已经把其余 49 个挡回去了 ——
         *   它们拿到的是 **409「该席别已售罄」**。
         *
         *   ⭐ 两个答案**都对**，取决于库存还剩多少。
         *      所以前端**不能依赖 message 文案**判断用户犯了哪个错，
         *      能依赖的只有状态码 409。
         *
         *   ⚠️ 这也是"给 409 加细分错误码"这个需求的**真实来源** ——
         *      它不是洁癖，是因为 message 确实承载不了这个区分。
         *      （错误码本身推迟到阶段 8，见 docs/api/error-codes.md §四。）
         */
        assertThat(counters.soldOut.get())
                .as("库存紧张时，重复提交者拿到的是「售罄」而不是「重复购票」")
                .isEqualTo(threads - 1);
        assertThat(counters.duplicate.get())
                .as("它们根本没走到唯一索引那一步")
                .isZero();

        assertThat(soldCount()).as("只该卖出 1 张").isEqualTo(1);
        assertThat(countOrdersOfSentinels()).as("只该有 1 个订单").isEqualTo(1);
        assertThat(countByFixture("rail_order.t_order_item")).as("只该有 1 张票").isEqualTo(1);
        assertThat(countByFixture("rail_inventory.t_stock_flow")).as("只该有 1 条流水").isEqualTo(1);
    }

    // =========================================================================
    //  并发执行器
    // =========================================================================

    /**
     * 同时启动 {@code threads} 个线程，每个线程调用一次下单，最后按结果分类计数。
     *
     * @param threads  线程数
     * @param userIdFor 第 i 个线程使用的 userId
     */
    private Counters runConcurrently(int threads, LongUnaryOperator userIdFor) throws InterruptedException {
        Counters counters = new Counters();

        /*
         * ⭐ CountDownLatch 是"真正同时开始"的关键。
         *
         * 不用它的话，for 循环里 pool.execute 本身就要花时间：
         * 第 1 个线程可能已经跑完并提交了，第 100 个才刚启动。
         * 那样测出来的不是"100 个请求同时到达"，而是"100 个请求先后到达"——
         * 后者根本产生不了竞争，测试会**轻松通过并且毫无价值**。
         *
         * 所以：所有线程先卡在 startGate 上，等主线程 countDown 的那一刻
         * 一起被放出去。这才是"并发"。
         */
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                long userId = userIdFor.applyAsLong(i);
                pool.execute(() -> {
                    try {
                        startGate.await();
                        orderService.createOrder(train, userId, TEST_DATE, BUSINESS);
                        counters.ok.incrementAndGet();
                    } catch (SeatNotAvailableException e) {
                        // 两种"买不到票"按原因分开计数 —— 它们的区别有意义
                        if (e.getReason() == SeatNotAvailableException.Reason.SOLD_OUT) {
                            counters.soldOut.incrementAndGet();
                        } else {
                            counters.notOnSale.incrementAndGet();
                        }
                    } catch (DuplicateOrderException e) {
                        counters.duplicate.incrementAndGet();
                    } catch (DeadlockLoserDataAccessException e) {
                        // MySQL 1213：死锁，事务被选为牺牲者
                        counters.deadlock.incrementAndGet();
                    } catch (CannotAcquireLockException e) {
                        // MySQL 1205：锁等待超时（DeadlockLoser 的兄弟，先捕更具体的那个）
                        counters.lockTimeout.incrementAndGet();
                    } catch (Throwable t) {
                        /*
                         * ⚠️ 单独收集，绝不混进上面的计数。
                         * 混进去的话，只要 ok 恰好等于票额，测试就会绿 ——
                         * 而死锁、连接池超时、参数绑定异常全都被掩盖了。
                         */
                        counters.unexpected.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }

            startGate.countDown();

            /*
             * ⚠️ 返回值必须断言。不等齐就往下走，等于在
             * "部分线程还没跑完"的状态下比较成功数 ——
             * 有可能恰好凑出 ok == 20 的假通过。
             */
            assertThat(finishGate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("有线程没在 %d 秒内跑完（可能被锁挂住了），此刻的断言没有意义",
                            TIMEOUT_SECONDS)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        return counters;
    }

    /**
     * 把一次并发跑的数字打到标准输出。
     *
     * <p>⚠️ 测试里打 stdout 通常是坏味道，这里是有意的：
     * 阶段 5 的复盘要记**真实的**死锁/超时次数
     * （0 就写"未观测到"，不能写"不会有"）。断言只说"通过/不通过"，
     * 而这些计数需要被看见才能被记录。
     */
    private void report(String scenario, Counters counters) {
        System.out.printf(
                "[并发实测] %s → 成功=%d 售罄=%d 未放票=%d 重复购票=%d 死锁=%d 锁等待超时=%d 非预期异常=%d%n",
                scenario, counters.ok.get(), counters.soldOut.get(), counters.notOnSale.get(),
                counters.duplicate.get(), counters.deadlock.get(), counters.lockTimeout.get(),
                counters.unexpected.size());
        if (!counters.unexpected.isEmpty()) {
            for (Throwable t : counters.unexpected) {
                System.out.printf("[并发实测]   非预期异常：%s%n", t.toString());
            }
        }
    }

    /** 一次并发跑的原始计数。分类的意义见 runConcurrently。 */
    private static final class Counters {

        final AtomicInteger ok = new AtomicInteger();

        final AtomicInteger soldOut = new AtomicInteger();

        final AtomicInteger notOnSale = new AtomicInteger();

        final AtomicInteger duplicate = new AtomicInteger();

        final AtomicInteger deadlock = new AtomicInteger();

        final AtomicInteger lockTimeout = new AtomicInteger();

        final Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        /** 已被归类的线程总数。它应当等于线程数 —— 否则有线程的结果没被记下。 */
        int attempted() {
            return ok.get() + soldOut.get() + notOnSale.get() + duplicate.get()
                    + deadlock.get() + lockTimeout.get() + unexpected.size();
        }
    }

    // =========================================================================
    //  fixture 与查询
    // =========================================================================

    private void insertInventory(int totalCount) {
        jdbc.update("DELETE FROM rail_inventory.t_seat_inventory "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                train.getId(), TEST_DATE, SEAT_TYPE);

        jdbc.update("INSERT INTO rail_inventory.t_seat_inventory "
                        + "(train_id, travel_date, seat_type, price, total_count, sold_count) "
                        + "VALUES (?, ?, ?, ?, ?, 0)",
                train.getId(), TEST_DATE, SEAT_TYPE, PRICE, totalCount);
    }

    private void cleanFixtures() {
        Long trainId = train != null ? train.getId() : trainService.getByTrainNo(TRAIN_NO).getId();

        jdbc.update("DELETE FROM rail_inventory.t_stock_flow "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);
        jdbc.update("DELETE FROM rail_order.t_order_item "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);
        jdbc.update("DELETE FROM rail_order.t_order WHERE user_id BETWEEN ? AND ?",
                SENTINEL_USER_MIN, SENTINEL_USER_MAX);
        jdbc.update("DELETE FROM rail_inventory.t_seat_inventory "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                trainId, TEST_DATE, SEAT_TYPE);
    }

    private int soldCount() {
        return count("SELECT sold_count FROM rail_inventory.t_seat_inventory "
                        + "WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                train.getId(), TEST_DATE, SEAT_TYPE);
    }

    /** 按 fixture 位置（车次 + 日期 + 席别）数某个表有多少行。 */
    private int countByFixture(String table) {
        return count("SELECT COUNT(*) FROM " + table
                        + " WHERE train_id = ? AND travel_date = ? AND seat_type = ?",
                train.getId(), TEST_DATE, SEAT_TYPE);
    }

    /**
     * 数哨兵区间里的订单。
     *
     * <p>⚠️ t_order 表**没有** train_id / travel_date 列，所以它只能按
     * user_id 定位（票的位置信息在 t_order_item 上）。
     * 这正是哨兵区间存在的理由。
     */
    private int countOrdersOfSentinels() {
        return count("SELECT COUNT(*) FROM rail_order.t_order WHERE user_id BETWEEN ? AND ?",
                SENTINEL_USER_MIN, SENTINEL_USER_MAX);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertThat(value).as("查询返回 null：%s", sql).isNotNull();
        return value;
    }
}
