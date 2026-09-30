package com.railseckill.train.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存变动流水，对应表 {@code rail_inventory.t_stock_flow}。
 *
 * <p>表结构见 {@code sql/03_rail_inventory.sql}。
 *
 * <p>字段含义：{@code changeType} 取值见 {@link com.railseckill.train.enums.StockChangeType}。
 */
/*
 * =============================================================================
 *  ⭐ 这张表存在的唯一理由：让「少卖」变得可查询
 * =============================================================================
 *
 *   t_seat_inventory 上的两个 CHECK 约束只能保证一件事：
 *       sold_count <= total_count        （不超卖）
 *       sold_count >= 0                  （不为负）
 *
 *   它们**证明不了**的是反方向的那一半：**有没有扣了库存却没卖出票？**
 *
 *   举个具体的例子：
 *       第 ② 步 `sold_count + 1` 成功了，
 *       第 ④ 步 `INSERT t_order_item` 却因为某个 bug 失败、
 *       而错误处理又把异常吞掉让事务提交了。
 *   结果：库存少了一张，订单里没有这张票。
 *   **CHECK 约束完全看不见这件事**，因为 sold_count 没有越过任何边界。
 *
 *   有了这张流水表，"订单"和"扣减"之间就有了一条可以用 SQL 连接的纽带：
 *
 *       SELECT o.order_no
 *         FROM rail_order.t_order o
 *         LEFT JOIN rail_inventory.t_stock_flow f
 *                ON f.biz_id = o.order_no AND f.change_type = 2
 *        WHERE o.status <> 2
 *          AND (f.id IS NULL OR f.change_count <> 1);
 *       -- 期望 0 行：每个非取消订单都必须恰好有一条 +1 的确认扣减流水
 *
 *   这是 `docs/05-seckill.md §六` 里原来的 4 条验证 SQL 都做不到的事，
 *   也是阶段 5 决定"提前写这张表"的真正原因
 *   （它本来是给阶段 9 的对账任务设计的）。
 *
 * -----------------------------------------------------------------------------
 *  ⭐ uk_biz_id_type(biz_id, change_type)：幂等防线的第一道答案
 * -----------------------------------------------------------------------------
 *   同一个业务单号 + 同一种变动类型，只能出现一次。
 *   于是"同一条扣减消息被投递两次"这种在 MQ 下**必然发生**的事，
 *   第二次插入流水时会撞唯一键而失败 —— 整个事务回滚，库存不会被重复扣减。
 *
 *   阶段 5 还没有 MQ，所以这条索引今天挡不住什么真实风险。
 *   但**它在阶段 5 就被执行到了**（每次下单插一行），
 *   而不是等到阶段 9 才第一次跑 —— 一条从没执行过的约束
 *   和一个不存在的约束没有区别。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 它也是跨库写的第二张表
 * -----------------------------------------------------------------------------
 *   biz_id 里存的是 rail_order 这边的 order_no，
 *   而表住在 rail_inventory 库里。**没有外键约束**
 *   （全项目不建外键，见 docs/06-database.md §3.6），
 *   所以"这条流水的订单真的存在吗"数据库层面不保证 ——
 *   这正是上面那条 LEFT JOIN 校验要手工做的事。
 *
 *   🔴 阶段 8：这张表的 owner 是 rail-inventory-service，
 *   而写入它的是 order 侧的流程。拆分时要么让 inventory 消费者来写它
 *   （方案 C 的形态），要么接受它跨边界。**这是阶段 8 必须回答的问题之一。**
 * =============================================================================
 */
@TableName("rail_inventory.t_stock_flow")
public class StockFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务单号 = 订单号。
     *
     * <p>叫 biz_id 而不是 order_no，是因为这张表不应该假定
     * "所有的库存变动都来自订单"（回补、手工调账、预热修正都可能产生流水）。
     * 今天它确实总是订单号，但列名的语义更宽。
     *
     * <p>⚠️ 因为名字宽，所以**它没有外键** —— 数据库不知道它指向 t_order。
     * 与 order_no 的对应关系靠应用代码维持，靠上面那条 JOIN 校验来验证。
     */
    private String bizId;

    /**
     * 变动类型：1=预扣 2=确认扣减 3=回补。
     *
     * <p>与 {@link #bizId} 一起构成唯一索引 {@code uk_biz_id_type}。
     */
    private Integer changeType;

    /** 车次 ID，指向 {@code rail_train.t_train.id}。 */
    private Long trainId;

    /** 乘车日期。 */
    private LocalDate travelDate;

    /** 席别：1=商务座 2=一等座 3=二等座。 */
    private Integer seatType;

    /**
     * 变动数量。扣减为正，回补为负
     * （见 {@code sql/03_rail_inventory.sql} 的列注释）。
     *
     * <p>阶段 5 只写 +1。回补的 -1 属于阶段 7。
     *
     * <p>这个符号约定让"某一行库存的流水求和"直接等于它的 sold_count 变化量 ——
     * 对账时不需要区分正负方向，直接 SUM 即可。
     */
    private Integer changeCount;

    /**
     * 备注，如失败原因。
     *
     * <p>阶段 5 写的是"哪个车次哪天哪个席别"这种定位信息。
     * 它不参与任何逻辑，纯粹为了排查时能一眼看懂这行流水是什么。
     */
    private String remark;

    /** 创建时间，由数据库 {@code DEFAULT CURRENT_TIMESTAMP} 生成。流水只增不改，没有 update_time。 */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public Integer getChangeType() {
        return changeType;
    }

    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
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

    public Integer getChangeCount() {
        return changeCount;
    }

    public void setChangeCount(Integer changeCount) {
        this.changeCount = changeCount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
