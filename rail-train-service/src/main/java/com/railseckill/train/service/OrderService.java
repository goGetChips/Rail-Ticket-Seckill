package com.railseckill.train.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.railseckill.train.dto.OrderCreated;
import com.railseckill.train.dto.OrderStatusChanged;
import com.railseckill.train.entity.Order;
import com.railseckill.train.entity.OrderItem;
import com.railseckill.train.entity.SeatInventory;
import com.railseckill.train.entity.StockFlow;
import com.railseckill.train.entity.Train;
import com.railseckill.train.enums.OrderStatus;
import com.railseckill.train.enums.SeatType;
import com.railseckill.train.enums.StockChangeType;
import com.railseckill.train.exception.DuplicateOrderException;
import com.railseckill.train.exception.InvalidOrderStateException;
import com.railseckill.train.exception.OrderNotFoundException;
import com.railseckill.train.exception.SeatNotAvailableException;
import com.railseckill.train.mapper.OrderItemMapper;
import com.railseckill.train.mapper.OrderMapper;
import com.railseckill.train.mapper.SeatInventoryMapper;
import com.railseckill.train.mapper.StockFlowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下单与支付。
 *
 * <p><b>这是全项目第一个会写数据库的服务。</b> 阶段 0~4 的所有接口都是只读的，
 * 所以从本类开始，"虽然通过了但结果是错的"（超卖、少卖、重复扣）
 * 才成为可能的失败模式。
 *
 * <p>🔴 <b>本类住在 train-service，但它写下单库和库存库。阶段 8 迁往
 * {@code rail-order-service}（8084），其中扣减库存那一步迁往
 * {@code rail-inventory-service}（8083）。</b>
 */
/*
 * =============================================================================
 *  ⭐⭐ 核心设计：跨三个库的写，为什么能用**一个本地事务**解决
 * =============================================================================
 *
 *   各表所在的库：
 *     rail_train.t_train                      ← 只读，拿始发/终到站
 *     rail_inventory.t_seat_inventory         ← **写**（扣减）
 *     rail_inventory.t_stock_flow             ← **写**（流水）
 *     rail_order.t_order / t_order_item       ← **写**（订单）
 *
 *   而本服务的数据源 URL 指向的是 `rail_train`。看起来这是跨库事务，
 *   应该需要 XA 或分布式事务框架。**不需要，而且原因很具体：**
 *
 *     ⭐ **InnoDB 的事务是「服务器级」的，不是「库级」的。**
 *
 *   `rail_train` / `rail_inventory` / `rail_order` 是**同一个 MySQL 实例上
 *   的三个 schema**（在 MySQL 里 schema 和 database 是同义词）。
 *   `START TRANSACTION` 是对**那个连接**说的，不是对某个库说的 ——
 *   所以一条连接上的一个事务能覆盖它访问的所有 schema。
 *
 *   实现手法：**全限定表名**（`rail_inventory.t_seat_inventory`）。
 *   先例是 entity/SeatInventory.java 的 @TableName，本阶段三个新实体照抄。
 *   账号 rail 对四个库都有 DML 权限（sql/00_init.sql），
 *   **MySQL 的权限是按「账号 × 库」授予的，和当前连接的默认库无关**。
 *
 * -----------------------------------------------------------------------------
 *  🔴🔴 【阶段 8 拆服务后，上面这段话的每一个字都失效】
 * -----------------------------------------------------------------------------
 *   这是本阶段最需要在代码里留下痕迹的一件事，因为**它会静默失效**：
 *
 *     阶段 8 之后：rail-order-service 和 rail-inventory-service
 *     是两个**进程**，各自连自己的库（甚至可能各自一个实例）。
 *     那时"扣减库存"变成一次**网络调用**（Feign），
 *     而网络调用**不在本地事务里** —— 它可能在对方提交后失败（结果未知），
 *     也可能成功但响应丢失（同样结果未知）。
 *
 *   ⭐ 也就是说：**这个类的正确性依赖"单实例多 schema"这个部署事实，
 *      而不是依赖代码本身。** 部署形态一变，代码一行没改，
 *      正确性却没了 —— 这是最危险的一类前提。
 *
 *   所以这一段必须写在类的第一屏：**它是给阶段 8 的人看的**。
 *   到那时要引入的是补偿机制（方案 C：Redis 预扣 + MQ 异步落库 + 对账），
 *   而不是"把这几个 @TableName 改回去"。
 *
 *   在那之前，这是一笔**有记录的临时债务**，不是疏忽。
 *
 * =============================================================================
 *  ⭐ 【为什么这个类需要 @Transactional，而 TrainService 明确不需要】
 * =============================================================================
 *   TrainService 的类注释里论证了"查询不加事务"：单个 SELECT 自带一致性快照，
 *   加上事务只会多两次数据库往返。那个判断对**只读**成立。
 *
 *   而这里必须加，理由是**「判断」和「写入」之间不能有缝隙**，具体有三处：
 *
 *     ① 库存扣减（② 步）与订单落库（③④ 步）必须同生共死。
 *        否则会出现"票扣了、订单没有"（少卖）或"订单有了、票没扣"（超卖挂账）。
 *     ② 扣减与流水（⑤ 步）必须同生共死，否则对账失去依据。
 *     ③ 库存扣减的**行锁要持有到 COMMIT**。这正是并发正确性的来源：
 *        后到的事务在行锁上等待，等到之后重新求值 WHERE 才发现票没了
 *        （见 SeatInventoryMapper.xml 里"当前读"的说明）。
 *        如果没有事务，锁在语句结束就释放，等待-重新求值这套机制就不存在了。
 *
 *   ⭐ 第 ③ 点最容易被忽略：**事务在这里不只是"原子性"，
 *      它还负责"把锁持有到什么时候"** —— 而锁的持有时间正是并发语义的一部分。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【为什么方法级 @Transactional，不加在类上】
 * -----------------------------------------------------------------------------
 *   "车次不存在 → 404"是 HTTP 语义判断，不该在事务里做
 *   （Controller 里先查车次，查不到直接 404，连事务都不用开）。
 *   事务只包"扣库存 + 写订单"这一小段。
 *
 *   加在类上会让将来任何一个只读方法（比如阶段 6 要加的"查我的订单"）
 *   都白白背上事务开销，而且不会有任何提示。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【为什么刻意不写 rollbackFor = Exception.class】
 * -----------------------------------------------------------------------------
 *   Spring 的默认回滚规则是 RuntimeException + Error。
 *   本项目**零受检异常**（所有业务异常都继承 RuntimeException），
 *   所以默认规则已经覆盖了全部失败路径。
 *
 *   写上它反而有害：它暗示"这里存在受检异常失败路径"，
 *   让读代码的人去找一条不存在的路径。
 *
 *   🔴 **反向约束（这条比上面重要）**：
 *   哪天本项目引入了受检异常（比如某个 SDK 抛 checked exception），
 *   且它出现在这个事务里 —— **这一行必须回来补上**。
 *   漏了它的症状是**静默的**：业务失败了但事务照样提交，
 *   数据处于半完成状态，而且不报任何错。
 *   ★ 这就是"不加 rollbackFor"这个决定的代价，写在这里备查。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【自调用会静默失效】
 * -----------------------------------------------------------------------------
 *   @Transactional 是靠 Spring 生成的**代理**生效的。
 *   所以必须由容器注入的代理来调用，**不能 this.createOrder(...)** ——
 *   自调用绕过了代理，事务注解完全不生效，而且不报任何错。
 *
 *   本类现在是"被 Controller 调用"的叶子节点，没有自调用。
 *   ⚠️ 但将来如果有人在这里加一个 public 方法 A，内部调 public 方法 B，
 *   而 B 上有 @Transactional —— **B 的事务不会生效**。
 *   那时正确做法是把 B 挪到另一个受容器管理的 Bean 里。
 * =============================================================================
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /**
     * 订单号里的时间戳部分。
     *
     * <p>⚠️ 用 {@link DateTimeFormatter} 而不是 {@link java.text.SimpleDateFormat}：
     * 后者**不是线程安全的**（内部有可变状态），而这是一个会被多线程
     * 同时调用的静态字段 —— 用 SimpleDateFormat 的经典症状是
     * 偶发地把两个线程的格式化结果串在一起，产生格式错乱的订单号，
     * 而且只在并发时出现。
     *
     * <p>DateTimeFormatter 是不可变的、线程安全的，可以直接作为静态常量共享。
     */
    private static final DateTimeFormatter ORDER_NO_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /**
     * 进程内的同毫秒序列号。
     *
     * <p>⚠️ 它只保证 **单个 JVM 内**、同一毫秒内不重复。
     * 多实例部署时每个 JVM 各有一份，等于没有。
     */
    private static final AtomicInteger ORDER_NO_SEQUENCE = new AtomicInteger(0);

    private static final int ORDER_NO_SEQUENCE_MAX = 999;

    /** 随机段的取值上界（不含），即 0000~9999。 */
    private static final int ORDER_NO_RANDOM_BOUND = 10_000;

    private final SeatInventoryMapper seatInventoryMapper;

    private final OrderMapper orderMapper;

    private final OrderItemMapper orderItemMapper;

    private final StockFlowMapper stockFlowMapper;

    public OrderService(SeatInventoryMapper seatInventoryMapper,
                        OrderMapper orderMapper,
                        OrderItemMapper orderItemMapper,
                        StockFlowMapper stockFlowMapper) {
        this.seatInventoryMapper = seatInventoryMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.stockFlowMapper = stockFlowMapper;
    }

    /**
     * 下单：扣减库存并创建订单。
     *
     * <p><b>这是全项目唯一一处扣减库存的地方。</b>
     *
     * @param train      已经确认存在的车次（Controller 负责"不存在 → 404"）
     * @param userId     下单人
     * @param travelDate 乘车日期
     * @param seatType   席别
     * @return 创建成功的订单
     * @throws SeatNotAvailableException 未放票（409）或已售罄（409）
     * @throws DuplicateOrderException   同一乘车人已有同车次同日期同席别的票（409）
     */
    /*
     * =========================================================================
     *  ⭐⭐ 【这五步的顺序是设计约束，不是随手排的】
     * =========================================================================
     *
     *   ① SELECT 库存行            ← 读 price，并区分"未放票"与"售罄"
     *   ② UPDATE 条件扣减           ← 不超卖的唯一实现
     *   ③ INSERT t_order
     *   ④ INSERT t_order_item      ← 不重复购票的唯一实现（唯一索引）
     *   ⑤ INSERT t_stock_flow      ← 让"少卖"可查询
     *   COMMIT
     *
     *   ① 必须在 ② 之前，理由见下面 ① 的注释（这是本方法最需要理解的一步）。
     *   ③④⑤ 的顺序可换，但**都必须留在最后** —— 这条要作为对后来者的约束：
     *   **往这个事务里加任何写操作，都加在末尾。** 因为 ①② 是"决定这单能不能成"
     *   的部分，中间插入写操作只会拉长锁的持有时间。
     *
     *   受影响行数的三种含义（sql/03_rail_inventory.sql §4 的规格）：
     *     ② 返回 1  → 成功
     *     ② 返回 0  → **正常业务失败**（售罄），走 409，不是异常、不是 500
     *     ② 抛异常  → **系统失败、结果未知**，事务回滚，走 500
     *
     * -------------------------------------------------------------------------
     *  ⭐ 【① 为什么必须在 ② 之前（读 price 的正确时机）】
     * -------------------------------------------------------------------------
     *   三种结果的对应关系：
     *
     *     ① 读到 null  → 不执行 ②    这一天还没放票      → 409「尚未放票」
     *     ① 读到有行   → ② 返回 1     成功                → 200
     *     ① 读到有行   → ② 返回 0     **售罄**            → 409「已售罄」
     *
     *   没有 ① 就**分不清"没放票"和"售罄"** —— 两者的受影响行数都是 0。
     *   而这个区别是项目硬规则（docs/api/error-codes.md：还没放票 ≠ 已售罄），
     *   因为对用户的下一步动作指向完全不同：一个该等，一个该换。
     *
     *   【"先 UPDATE 再 SELECT 也能区分啊，为什么不行"】
     *   能区分，但那一次 SELECT 会落在**持锁区间内**：
     *   X 锁从 ② 一直持到 COMMIT，而热点行（同一趟车同一天同一席别）
     *   的锁时间每多一毫秒，都在降低这个席别的吞吐上限。
     *   放在 ② 之前读，这次 SELECT 是**快照读、不加锁**，零额外代价。
     *
     *   ⭐⭐ 【① 会不会退化成"先查后改"的经典超卖写法？—— 不会】
     *   sql/03_rail_inventory.sql 点名了那个错误写法：
     *       SELECT sold, total → if (sold < total) UPDATE    ← 两边都读到 99/100，卖出 101
     *   判据是：**① 的结果从不作为扣减的充分条件。**
     *   即使 ① 读到"还有票"，② 照样无条件执行，**由 ② 自己的受影响行数说了算**。
     *   ① 只用于两件事：(a) 取 price，(b) 给"0 行"这个结果**分类**。
     *   —— **判断权始终在 ② 手里，① 连"建议权"都不行使。**
     *   这是"读一次"和"先查后改"的分界，必须能一眼说清。
     *
     * -------------------------------------------------------------------------
     *  ⭐ 【为什么不用 InventoryService.listSeats 复用那两个 SELECT】
     * -------------------------------------------------------------------------
     *   它在 Service 层，看起来是"库存读取的唯一入口"，复用它能少一条查询代码。
     *   不用的理由：**它返回的是 dto/SeatAvailability，一个"余票展示" DTO。**
     *
     *   下单需要的是"price + 这一行存在吗"；展示需要的是"余票几个"。
     *   两者的演进方向不同 —— 并且 ① 的注释已经写明：余票的算法将来会变
     *   （要减去锁定未支付的数量）。让下单路径依赖一个会为展示而改的 DTO，
     *   等于让正确性路径依赖展示层的变化。
     *
     *   ⚠️ 代价是"库存怎么读"现在有两处代码。这是**有意的重复**：
     *      一处服务展示、一处服务扣减，它们的共同点只是"读同一张表"。
     *      等阶段 8 拆服务时，两处会一起去 inventory-service，
     *      那时才判断该不该合并（有真实迁移压力时再决定）。
     * ===================================================================== */
    @Transactional
    public OrderCreated createOrder(Train train, long userId, LocalDate travelDate, SeatType seatType) {
        int seatTypeCode = seatType.code();

        // ① 读库存行 —— 取 price，并区分"未放票"与"售罄"。不为扣减决策提供依据。
        SeatInventory inventory = seatInventoryMapper.selectOne(
                Wrappers.<SeatInventory>lambdaQuery()
                        .eq(SeatInventory::getTrainId, train.getId())
                        .eq(SeatInventory::getTravelDate, travelDate)
                        .eq(SeatInventory::getSeatType, seatTypeCode));
        if (inventory == null) {
            throw new SeatNotAvailableException(
                    SeatNotAvailableException.Reason.NOT_ON_SALE, train.getTrainNo(), travelDate, seatTypeCode);
        }

        // ② 条件 UPDATE 扣减 —— 全项目"不超卖"的唯一实现。
        //    ⚠️ 这里刻意不看 ① 读到的 soldCount/totalCount 做任何判断。
        int affected = seatInventoryMapper.deductStock(train.getId(), travelDate, seatTypeCode);
        if (affected == 0) {
            throw new SeatNotAvailableException(
                    SeatNotAvailableException.Reason.SOLD_OUT, train.getTrainNo(), travelDate, seatTypeCode);
        }
        /*
         * affected 只可能是 1（uk_train_date_seat 是唯一索引，最多命中一行）。
         * 刻意不写 `if (affected != 1) throw ...` 这样的"防御"：
         * 那会给一个**由索引保证不可能**的情况写分支，
         * 而真正的风险（索引被改成非唯一）需要的是改 DDL 时被拦住，
         * 不是运行时的兜底。见 SeatInventoryMapper.xml 里关于这一点的说明。
         */

        // ③ 写订单头
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        // 阶段 5 一个订单只有一张票，所以总额恒等于票价。见 entity/Order#totalAmount。
        order.setTotalAmount(inventory.getPrice());
        order.setStatus(OrderStatus.PENDING.code());
        insertOrderWithOrderNoRetry(order);

        // ④ 写订单明细（票）—— 唯一索引 uk_user_train_date_seat 在这里生效
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setUserId(userId);
        item.setTrainId(train.getId());
        item.setTravelDate(travelDate);
        item.setSeatType(seatTypeCode);
        // 阶段 5 不支持中途上车：这两个站恒等于车次的始发/终到站，由服务端取。
        item.setFromStationId(train.getStartStationId());
        item.setToStationId(train.getEndStationId());
        item.setPrice(inventory.getPrice());
        try {
            orderItemMapper.insert(item);
        } catch (DuplicateKeyException ex) {
            /*
             * 🔴🔴 【这个 catch 里只能 throw，绝不能 return —— 本阶段最危险的坑】
             *
             * 此刻 ② 步的库存扣减**已经执行过了**。如果我们在这里
             * return 一个 409，Spring 的事务拦截器会认为"方法正常结束"→
             * **COMMIT** → 那次扣减被提交，而订单没建。
             *
             * 结果：rail_inventory 的 sold_count 少了一张可卖的票、票没卖出去、
             * 接口返回 409 看起来一切正常、**没有任何日志和告警**。
             * 这就是"少卖"，只有对账能发现（所以本阶段才写了 t_stock_flow，
             * 见 StockFlow 的类注释）。
             *
             * 抛出去 → Spring 标记回滚 → ② 步的扣减一起被撤销 → 数据一致。
             */
            log.debug("重复购票，撞唯一索引 uk_user_train_date_seat: userId={}, trainNo={}, travelDate={}, seatType={}",
                    userId, train.getTrainNo(), travelDate, seatTypeCode, ex);
            throw new DuplicateOrderException(userId, train.getTrainNo(), travelDate, seatTypeCode);
        }

        // ⑤ 写库存流水 —— 让"扣了库存但没卖出票"可被一条 SQL 查出来
        StockFlow flow = new StockFlow();
        flow.setBizId(order.getOrderNo());
        flow.setChangeType(StockChangeType.CONFIRM.code());
        flow.setTrainId(train.getId());
        flow.setTravelDate(travelDate);
        flow.setSeatType(seatTypeCode);
        // 扣减为正、回补为负。方案 A 里扣减就是终态，所以恒为 +1。
        flow.setChangeCount(1);
        flow.setRemark(train.getTrainNo() + " " + travelDate + " " + seatType.label());
        stockFlowMapper.insert(flow);

        return new OrderCreated(
                order.getOrderNo(),
                userId,
                train.getTrainNo(),
                travelDate,
                seatTypeCode,
                inventory.getPrice(),
                OrderStatus.PENDING.code(),
                OrderStatus.PENDING.label());
    }

    /**
     * 模拟支付：把订单从"待支付"改成"已支付"。
     *
     * <p>不对接任何真实支付渠道 —— 本方法的目的是演示
     * <b>用条件 UPDATE 实现状态机 CAS</b>，它对并发/重复请求的处理方式
     * 和库存扣减是同一套手法。
     *
     * @param orderNo 业务订单号
     * @return 流转结果，{@code idempotent=true} 表示本次只是重复确认
     * @throws OrderNotFoundException      订单不存在（404）
     * @throws InvalidOrderStateException  订单已取消，不能支付（409）
     */
    /*
     * =========================================================================
     *  ⭐⭐ 【这里刻意偏离了 DDL 注释里的一句话 —— 那不是笔误，是修正】
     * =========================================================================
     *   `sql/04_rail_order.sql` 和 `docs/03-business-flow.md` 都写着：
     *     「受影响行数为 0 → 订单已被处理过（重复支付 / 已取消），**直接返回成功即可**」
     *
     *   这句话对"重复支付"**对**，对"已取消"**错**。完整论证见
     *   InvalidOrderStateException 的类注释，一句话概括：
     *     **"已经是我想要的状态"可以报成功（幂等）；
     *       "永远不可能变成我想要的状态"必须报错（拒绝）。**
     *
     *   给一个已取消的订单回"支付成功"，是在告诉用户他有一张不存在的票。
     *
     * -------------------------------------------------------------------------
     *  ⭐ 【0 行之后为什么可以再读一次状态，而不算"先查后改"】
     * -------------------------------------------------------------------------
     *   同样是"读了再用"的结构，但和库存扣减的 ① 有本质区别：
     *
     *     · 库存的 ① 是**在写入之前**读，读到的值有被别的线程改掉的可能，
     *       所以它绝不能参与决策 —— 那里的决策权必须留给 ②
     *     · 这里的读**发生在写入之后**（UPDATE 已经执行完且返回 0），
     *       而且**它的结果不参与任何写入** ——
     *       它唯一的用途是**给这次失败生成正确的错误信息**（404 还是 409）
     *
     *   判据：**这次读的结果会不会影响数据库里发生什么。** 不会 → 安全。
     *
     *   ⚠️ 顺带说明一个"看起来有窗口、实际没有"的地方：UPDATE 返回 0 之后、
     *   这次 SELECT 之前，状态理论上又可能变。但那又怎样 ——
     *   订单**已经不可能变成已支付**了（本次 UPDATE 没成功），
     *   所以无论读到哪个状态，正确答案都是"本次支付没生效"。
     *   读到的是 1 还是 2 只决定回 200 还是 409，**两种都是诚实的结果**。
     *
     * -------------------------------------------------------------------------
     *  ⚠️ 【阶段 5 走不到 status=2 这条分支】
     * -------------------------------------------------------------------------
     *   本阶段没有取消接口，所以没有任何代码能把订单变成 2。
     *   这个分支**只能靠手工 UPDATE 到达** —— 验证时必须真的构造一次，
     *   否则它是一段从没被执行过的代码。见 OrderServiceIT 里的对应用例。
     * ===================================================================== */
    @Transactional
    public OrderStatusChanged payOrder(String orderNo) {
        int affected = orderMapper.updateStatus(
                orderNo, OrderStatus.PAID.code(), OrderStatus.PENDING.code());

        if (affected == 1) {
            // 真的完成了状态流转
            return new OrderStatusChanged(
                    orderNo, OrderStatus.PAID.code(), OrderStatus.PAID.label(), false);
        }

        // 0 行 —— 订单当前状态不是"待支付"。三种可能，读一次来分类。
        Order current = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        if (current == null) {
            throw new OrderNotFoundException(orderNo);
        }

        /*
         * ⚠️ OrderStatus.of 遇到未知值会抛 IllegalArgumentException → 500。
         * 这是**刻意的**：跑到这里说明 status 列里有个约定外的数字，
         * 那是数据被改坏了（t_order 上没有 CHECK 约束，见 OrderStatus 的注释），
         * 属于必须显形的故障，不该被兜成一句"业务失败"。
         */
        OrderStatus currentStatus = OrderStatus.of(current.getStatus());

        if (currentStatus == OrderStatus.PAID) {
            // 重复支付：订单本来就已支付，本次没有改变任何东西 → 真幂等
            return new OrderStatusChanged(
                    orderNo, OrderStatus.PAID.code(), OrderStatus.PAID.label(), true);
        }

        // 已取消（status=2），或将来加入的其他状态 → 状态不允许
        throw new InvalidOrderStateException(orderNo, currentStatus, "支付");
    }

    /**
     * 插入订单，订单号撞车时有界重试一次。
     *
     * <p>⚠️ 这里捕获 {@link DuplicateKeyException} 是**可以**的，
     * 和 ④ 步那个"绝对不能 return"的 catch 是两回事 ——
     * 因为此刻**还没有任何其他写操作发生**（库存还没扣），
     * 所以重试对整个事务没有任何副作用。
     */
    /*
     * =========================================================================
     *  ⭐ 订单号生成的三段各保证什么（以及"唯一性到底由谁保证"）
     * =========================================================================
     *   格式：yyyyMMddHHmmssSSS(17) + 序列(3) + 随机(4) = 24 位，装得下 VARCHAR(32)
     *
     *     时间戳   —— **什么都不保证**。同一毫秒内必然重复。
     *     序列     —— 保证**单个 JVM 内**同毫秒不重复（AtomicInteger）。
     *                  多实例部署时每个 JVM 一份，等于没有。
     *     随机段   —— **概率性**保证。4 位只有 1 万种取值，同毫秒同序列
     *                  再撞上同一个随机数的概率不为零。
     *
     *   ⭐⭐ 【结论：唯一性由 uk_order_no 这个唯一索引保证，
     *          不由生成算法保证。】
     *
     *   这是一个刻意的设计立场：**不要把"正确性"寄托在一个概率算法上。**
     *   生成算法的目标是"几乎不冲突"（避免走重试路径），
     *   而"绝不冲突"是数据库索引的职责。
     *   两者的分工必须清楚，否则你会以为"随机数够长应该没问题"，
     *   然后把索引去掉 —— 那才是真正的错误。
     *
     *   【为什么重试是"有界一次"，而不是循环重试】
     *   1062 是**语句级回滚**（只回滚那条 INSERT），事务仍然可用，
     *   所以技术上可以在同一个事务里换号重插 —— 但只重试一次。
     *
     *   因为连续两次冲突说明的不是"运气不好"，而是
     *   **生成器或系统时钟有问题**（比如 NTP 把时钟往回拨了，
     *   导致时间戳段重复且序列已经绕过一轮）。
     *   继续重试会把一个**系统性问题**伪装成偶发失败，
     *   而它应该以 500 的形式暴露出来。
     *
     *   ⚠️ 这里能安全地捕 DuplicateKeyException 还有一个前提：
     *      **t_order 上只有两个唯一性约束**（自增主键 + uk_order_no），
     *      而自增主键不可能冲突，所以这个异常必然来自 order_no。
     *      如果将来给 t_order 加了别的唯一索引，这段重试逻辑就要重新审视 ——
     *      它会把"别的唯一约束冲突"误当成"订单号撞车"而重试一次。
     * ===================================================================== */
    private void insertOrderWithOrderNoRetry(Order order) {
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            log.warn("订单号撞车，换号重插一次（这不该经常发生）: {}", order.getOrderNo());

            /*
             * 清掉上一次插入可能留下的主键值再重插。
             * 插入失败时理论上拿不到自增主键，但这一步是零成本的保险：
             * 如果留着非 null 的 id，MyBatis-Plus 会把它当成"已存在的行"
             * 而改用 UPDATE 语义（或直接把主键写进去），
             * 得到的结果和"新建一行"完全不同。
             */
            order.setId(null);
            order.setOrderNo(generateOrderNo());

            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException secondFailure) {
                // 连续两次 → 生成器/时钟有问题，让它变成 500 而不是继续重试
                throw new IllegalStateException(
                        "订单号连续两次生成冲突，生成器或系统时钟可能有问题：" + order.getOrderNo(),
                        secondFailure);
            }
        }
    }

    /**
     * 生成订单号：时间戳(17) + 进程内序列(3) + 随机(4) = 24 位。
     *
     * <p>⚠️ <b>它不保证唯一</b> —— 唯一性由 {@code uk_order_no} 保证，
     * 冲突时由 {@link #insertOrderWithOrderNoRetry} 换号重插。
     * 详见那个方法的注释。
     */
    private String generateOrderNo() {
        String timestamp = LocalDateTime.now().format(ORDER_NO_TIME_FORMAT);

        /*
         * 序列号取到 999 后回绕到 0，而不是继续增长：
         * 格式宽度是 3 位，溢出会让号码变成 25 位（虽然 VARCHAR(32) 装得下，
         * 但会打破"固定 24 位"这个便于识别和校验的性质）。
         * 回绕引入的重复风险由随机段 + 唯一索引兜住，是有意接受的取舍。
         */
        int sequence = ORDER_NO_SEQUENCE.updateAndGet(
                previous -> previous >= ORDER_NO_SEQUENCE_MAX ? 0 : previous + 1);

        /*
         * 用 ThreadLocalRandom 而不是 new Random()：
         * 后者内部是一个 AtomicLong CAS，在高并发下多个线程会互相竞争同一个种子变量。
         * ThreadLocalRandom 每个线程一份状态，没有竞争 ——
         * 这正是"高并发下生成随机数"的标准做法。
         */
        int random = ThreadLocalRandom.current().nextInt(ORDER_NO_RANDOM_BOUND);

        return timestamp
                + String.format("%03d", sequence)
                + String.format("%04d", random);
    }
}
