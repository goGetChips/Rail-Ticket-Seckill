package com.railseckill.train.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单明细（一行 = 一张票），对应表 {@code rail_order.t_order_item}。
 *
 * <p>表结构见 {@code sql/04_rail_order.sql}。
 *
 * <p><b>这张表上的唯一索引 {@code uk_user_train_date_seat} 是整个项目
 * 「不重复购票」的最后一道防线。</b>
 */
/*
 * =============================================================================
 *  ⭐ 【为什么"限购"的唯一索引建在这张表上，而不是 t_order 上】
 * =============================================================================
 *
 *   我们要约束的是："**一个乘车人，同一趟车、同一天、同一种席别，
 *   只能有一张票**"。
 *
 *   被约束的对象是**票**，票在这张表里。所以索引建在这里。
 *   一句话：**约束应该建在它真正约束的那个实体上。**
 *
 *   代价是 user_id 在 t_order 和 t_order_item 里各存一份。
 *   这是**由约束驱动的冗余**，不是设计疏忽。
 *
 *   【它和 t_order.user_id 的语义并不相同，虽然列名一样】
 *     · t_order.user_id      = **下单的人**（谁付的钱）
 *     · t_order_item.user_id = **票的归属人**（谁坐这趟车）
 *   阶段 5 两者总是同一个值（自己给自己买票），所以看不出来。
 *   一旦支持"帮别人买票"，两个列就会分叉，而**唯一索引约束的是后者**。
 *   把这个区别写下来，是因为列名相同最容易让人以为它们是一回事。
 *
 * -----------------------------------------------------------------------------
 *  🔴 【它挡住的具体错误：重复提交】
 * -----------------------------------------------------------------------------
 *   用户在秒杀页面上连点 100 次，或者网络重试导致同一请求到达多次。
 *   如果没有这个索引，每一次都会成功创建一个订单、扣一次库存 ——
 *   于是同一个人买到了 100 张票（如果他付得起钱），库存被一个人吃光。
 *
 *   有了它，第 2~100 次插入明细时会撞 {@code Duplicate entry ... for key
 *   't_order_item.uk_user_train_date_seat'}，MySQL 抛 1062，
 *   Spring 翻译成 DuplicateKeyException。
 *
 *   ⚠️ **关键在于这个异常必须让整个事务回滚，不能被 catch 住然后正常返回。**
 *   因为每一次尝试都已经在 rail_inventory 上扣过一次库存了。
 *   把异常吞掉、返回一个 409，Spring 会认为方法成功 → COMMIT →
 *   库存白扣一张、订单没增加。**不报错、不告警，只有对账时看得出来。**
 *   （见 OrderService 里的警告。）
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【阶段 5 的一个缺口：下单不支持中途上车】
 * -----------------------------------------------------------------------------
 *   from_station_id / to_station_id 两列在阶段 5 **恒等于该车次的
 *   始发站和终到站**，由服务端从 t_train 取，不由客户端传。
 *
 *   而查询接口 `GET /api/train/trains/search` 是支持"济南西 → 南京南"
 *   这种中途上车的。也就是说 **查得到、买不了**。
 *
 *   这是库存粒度（整段，见 sql/03_rail_inventory.sql §1）的必然结果：
 *   库存是按"车次+日期+席别"存的，没有区间维度，所以"哪个区间"这件事
 *   在下单时无从表达，硬塞进去只会得到一张和库存粒度对不上的订单。
 *
 *   记录在这里，是因为"查得到买不了"是用户能直接感知到的行为不一致，
 *   必须在文档里说清（docs/06-database.md 的已知简化清单），
 *   而不是让下一个人以为这是个 bug。
 * =============================================================================
 */
@TableName("rail_order.t_order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属订单的 ID，指向 {@code t_order.id}。
     *
     * <p>⚠️ 它**不是** order_no。这是"对外用订单号、对内用主键"的必然结果：
     * 订单号是给别人看的，内部关联用自增主键（更短、更省的索引）。
     *
     * <p>这个值依赖 {@code t_order} 插入后回填的自增 id。
     * 用 `BaseMapper.insert` 时 MyBatis-Plus 会自动回填（{@code IdType.AUTO}）；
     * ⚠️ 若改成手写 XML {@code <insert>}，必须自己写
     * {@code useGeneratedKeys="true" keyProperty="id"}，
     * 否则这里拿到的是 null，报 {@code Column 'order_id' cannot be null}。
     */
    private Long orderId;

    /** 票的归属人（乘车人）。与 {@code t_order.user_id} 的语义区别见类注释。 */
    private Long userId;

    /** 车次 ID，指向 {@code rail_train.t_train.id}。跨库的"软外键"，数据库层面没有约束。 */
    private Long trainId;

    /** 乘车日期。对应 MySQL 的 DATE（只有日期，没有时刻）。 */
    private LocalDate travelDate;

    /** 席别：1=商务座 2=一等座 3=二等座。取值见 {@link com.railseckill.train.enums.SeatType}。 */
    private Integer seatType;

    /** 上车站 ID。阶段 5 恒等于车次始发站，理由见类注释。 */
    private Long fromStationId;

    /** 下车站 ID。阶段 5 恒等于车次终到站，理由见类注释。 */
    private Long toStationId;

    /**
     * 本张票的票价。
     *
     * <p>⚠️ 这是**下单时刻的快照价**，从 t_seat_inventory.price 抄过来的，
     * 不是外键指向它。理由：票价是会变的（真实系统里会调价、会有折扣），
     * 而"这张票多少钱"必须永远等于**成交当时**的价格 ——
     * 如果只存一个指向库存表的引用，明天调价之后，
     * 昨天卖出的票的金额会跟着变，账就永远对不上了。
     *
     * <p>这是**历史快照**这一类的冗余，和"订单里的收货地址要抄一份而不是引用用户地址簿"
     * 是同一个道理。
     */
    private BigDecimal price;

    /**
     * 创建时间，由数据库 {@code DEFAULT CURRENT_TIMESTAMP} 生成。
     *
     * <p>⚠️ 这张表**没有 update_time**：票一旦卖出就不再修改
     * （退票是另一张流水，不是改这一行）。缺少 update_time 是刻意的。
     */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTrainId() {
        return trainId;
    }

    public void setTrainId(Long trainId) {
        this.trainId = trainId;
    }

    public LocalDate getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(LocalDate travelDate) {
        this.travelDate = travelDate;
    }

    public Integer getSeatType() {
        return seatType;
    }

    public void setSeatType(Integer seatType) {
        this.seatType = seatType;
    }

    public Long getFromStationId() {
        return fromStationId;
    }

    public void setFromStationId(Long fromStationId) {
        this.fromStationId = fromStationId;
    }

    public Long getToStationId() {
        return toStationId;
    }

    public void setToStationId(Long toStationId) {
        this.toStationId = toStationId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
