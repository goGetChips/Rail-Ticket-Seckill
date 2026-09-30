package com.railseckill.train.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 席别库存，对应表 {@code rail_inventory.t_seat_inventory}。
 *
 * <p>表结构见 {@code sql/03_rail_inventory.sql}。
 *
 * <p>字段含义：{@code seatType} 1=商务座 2=一等座 3=二等座。
 *
 * <p><b>⚠️ 注意 {@link TableName} 上的库名前缀：这是一个跨库读取。</b>
 */
/*
 * =============================================================================
 *  ⚠️ 这个类是本项目里唯一一处"跨出了数据所有权边界"的代码
 * =============================================================================
 *
 * 【发生了什么】
 *   t_seat_inventory 的 owner 终态是 rail-inventory-service（8083），
 *   而本服务的 POM 里没有任何库存相关的东西。
 *   但阶段 4 要交付"余票查询"，而 rail-inventory-service **还不存在**
 *   （阶段 8 才拆），所以余票暂时由 train-service 提供。
 *
 *   实现方式是 @TableName("rail_inventory.t_seat_inventory") ——
 *   写全限定表名，让 MySQL 在 rail_train 这个默认库之外取数据。
 *   能成功的原因：账号 rail 对四个库都有 DML 权限（sql/00_init.sql），
 *   **MySQL 的权限是按「账号 × 库」授予的，和当前连接的默认库无关**。
 *
 * 【为什么不用"在 rail_train 里建一张同名的本地表"】
 *   那会造成两份库存数据，而"哪份是权威"立刻变成需要回答的问题。
 *   现在只有一份，方向是明确的。
 *
 * 【代价与风险（必须知道）】
 *   1. 本服务的代码开始依赖 rail_inventory 的**表结构**。
 *      那边改列名，这边编译期不会有任何反应，运行时才炸。
 *   2. 应用层的数据所有权边界在**这一处**被打破了。
 *      所以它必须显眼——这就是为什么这段注释放在第 1 屏而不是文件末尾。
 *   3. 权限上没有任何东西阻止本服务去写 t_seat_inventory。
 *      **代码规范上禁止**：本服务对库存**只读**，扣减是阶段 5 在 order 侧的事。
 *
 * 🔴 【阶段 8 必须做的动作】
 *   1. 把余票查询的 Controller / Service / Mapper / 这个实体
 *      整体搬到 rail-inventory-service
 *   2. 路由从 /api/inventory/** 转发过去（**路径不需要变**，
 *      这是当初定路径时刻意选的，见 InventoryController 的注释）
 *   3. 删掉本服务的这个实体，以及 application.yml 里关于跨库读的说明
 *   到那时，本类的这段注释应该被删掉——**它的存在本身就是债务的证据**。
 *
 * =============================================================================
 *  【为什么这个类不跟 DTO 一样用 record】
 * =============================================================================
 *   实体是给 MyBatis 填的，MyBatis 默认通过反射调用 **setter** 写入。
 *   record 没有 setter、也没有无参构造器，所以实体必须是可变类。
 *   反之 dto 包下的类型只用于**响应序列化**，Java 侧构造一次就不再改，
 *   用 record 更合适 —— 这个分界见 dto/package-info.java。
 * =============================================================================
 */
@TableName("rail_inventory.t_seat_inventory")
public class SeatInventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 车次 ID，指向 rail_train.t_train.id。跨库的"软外键"，数据库层面没有约束。 */
    private Long trainId;

    /** 乘车日期。用 LocalDate，对应 MySQL 的 DATE（只有日期，没有时刻）。 */
    private LocalDate travelDate;

    /** 席别：1=商务座 2=一等座 3=二等座。 */
    private Integer seatType;

    /**
     * 票价。
     *
     * <p>用 {@link BigDecimal} 而不是 double：金额不能用二进制浮点数。
     * 0.1 + 0.2 在 double 里不等于 0.3，累积误差会让对账时"差了一分钱"，
     * 而一分钱的差异极难定位。DECIMAL 是精确的定点小数，BigDecimal 是它在 Java 侧的对应类型。
     */
    private BigDecimal price;

    /** 总票额。 */
    private Integer totalCount;

    /**
     * 已售数量。
     *
     * <p>表上有两个 CHECK 约束保护它：{@code sold_count >= 0} 和
     * {@code sold_count <= total_count}。这是"不超卖"的最后一道防线
     * ——即使应用层逻辑写错，数据库也会拒绝。
     */
    private Integer soldCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /*
     * =========================================================================
     *  【为什么这里没有 getRemaining()（一个刻意的缺席）】
     * =========================================================================
     *   余票 = totalCount - soldCount 是**派生值**，很容易顺手在这里加一个
     *   getRemaining()，反正库里没有这一列、也不影响映射。
     *
     *   不这么做的理由有两条：
     *
     *   1) **一个语义只能有一处定义。** 项目里已经有 dto/SeatAvailability
     *      负责对外表达"余票"这个业务概念（它还带着"凭什么能算出来"的说明）。
     *      实体上再加一个同名概念，就有两个地方能改"余票怎么算"，
     *      改一处漏一处时就出现"接口说的余票"和"别处说的余票"不一致。
     *      这和 sql/03_rail_inventory.sql 里"为什么存 total+sold 而不是
     *      available_count"反对的是同一件事：**冗余的定义是漂移的来源。**
     *
     *   2) **实体应该只镜像表结构。** 实体一旦开始承载业务派生逻辑，
     *      它就同时扮演了"表的映射"和"业务对象"两个角色。
     *      阶段 8 把库存迁到独立的服务时，这类混进去的东西最容易被漏掉。
     *
     *   所以：实体只管搬运列，派生语义放在 DTO 那一层算。
     * =========================================================================
     */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getSoldCount() {
        return soldCount;
    }

    public void setSoldCount(Integer soldCount) {
        this.soldCount = soldCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
