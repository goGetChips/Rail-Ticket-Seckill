package com.railseckill.train.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单头，对应表 {@code rail_order.t_order}。
 *
 * <p>表结构见 {@code sql/04_rail_order.sql}。
 *
 * <p>字段含义：{@code status} 取值见 {@link com.railseckill.train.enums.OrderStatus}。
 */
/*
 * =============================================================================
 *  ⚠️ 【{@code @TableName} 上的库名前缀不能省：本服务的数据源指向 rail_train】
 * =============================================================================
 *
 *   本服务（rail-train-service）的连接串是 `jdbc:mysql://.../rail_train`，
 *   所以写 `@TableName("t_order")` 会被解释成 `rail_train.t_order` ——
 *   而那是**另一张不存在的表**。报错是：
 *
 *       Table 'rail_train.t_order' doesn't exist
 *
 *   然后全限定表名 `rail_order.t_order` 才是对的。
 *   这是三个阶段里第二次用这个手法（第一次是
 *   {@link SeatInventory} 的 `rail_inventory.t_seat_inventory`）。
 *
 *   【为什么这个坑特别难提前发现】
 *   它不会在启动时报错。Spring 启动、Bean 装配、MyBatis 的 Mapper 扫描
 *   全都正常，6 个查询接口照样好用。**只有第一次真的执行到这条 SQL 时才炸。**
 *   而那时你很可能在排查"是不是事务配错了"——方向就偏了。
 *
 * -----------------------------------------------------------------------------
 *  【这个实体带来了第二处、也是更深的一处跨库耦合】
 * -----------------------------------------------------------------------------
 *   阶段 4 打破边界一次（train-service 读 rail_inventory），是**读**。
 *   阶段 5 打破了两次、而且是**写**：
 *
 *     · 写 rail_order（订单）
 *     · 写 rail_inventory（扣库存 + 写流水）
 *
 *   三张表在同一个 MySQL 实例上是能用**一个本地事务**覆盖的
 *   （InnoDB 的事务是服务器级的，不是库级的）——
 *   这段论证见 service/OrderService 的类注释。
 *
 *   🔴 阶段 8 拆服务后，这段代码分住 rail-order-service 与
 *   rail-inventory-service 两个进程，本地事务不再覆盖它们，
 *   那时才第一次面对真正的分布式一致性问题。
 *   在那之前，这是一笔**有记录的临时债务**（开发状态 技术债 #14 的延伸）。
 *
 * -----------------------------------------------------------------------------
 *  【为什么类名叫 Order 而不是 TOrder】
 * -----------------------------------------------------------------------------
 *   与 entity 包的既有约定一致：t_ 前缀在类名里被丢掉
 *   （{@code t_station → Station}、{@code t_train → Train}、
 *   {@code t_seat_inventory → SeatInventory}）。
 *   加一个 T 前缀会让同一个包里出现两种命名风格。
 *
 *   唯一需要留意的是 {@code org.springframework.core.annotation.Order}
 *   （一个用于指定 Bean 顺序的注解）。本项目不使用它，
 *   所以不存在冲突；万一将来要用，显式写全限定名或改别名即可 ——
 *   而"同包优先"的解析规则保证本类在包内永远没有歧义。
 * =============================================================================
 */
@TableName("rail_order.t_order")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务订单号，对外暴露的唯一标识。
     *
     * <p>⚠️ <b>不是主键，也不是自增。</b> 理由见 {@code sql/04_rail_order.sql}：
     * 自增主键会泄露业务量（看到 id=1000000 就知道总共卖了一百万单），
     * 而且把它暴露给客户端等于让别人能遍历你的全部订单。
     *
     * <p>有唯一索引 {@code uk_order_no}。生成规则与"唯一性由谁保证"
     * 见 {@code OrderService#generateOrderNo} ——
     * <b>唯一性由这个索引保证，不由生成算法保证。</b>
     */
    private String orderNo;

    /**
     * 下单人。
     *
     * <p>⚠️ 阶段 5 这个值**由请求体传入**（{@code CreateOrderRequest.userId}），
     * 是客户端可以随意伪造的。终态是网关校验 JWT 后注入，
     * 客户端无法伪造成别人的 userId。详见 CreateOrderRequest 的注释。
     *
     * <p>它在 t_order 和 t_order_item 里各存一份。这是**由约束驱动的冗余** ——
     * "一个乘车人同一趟车同一天同席别只能有一张票"这个约束的对象是**票**，
     * 票在 t_order_item 里，所以唯一索引建在那边，userId 就必须在那边也有一份。
     */
    private Long userId;

    /**
     * 订单总额。
     *
     * <p>用 {@link BigDecimal} 而不是 double：金额不能用二进制浮点数。
     * 与 {@link SeatInventory#price} 同一条铁律。
     *
     * <p>阶段 5 一个订单只有一张票，所以它恒等于该票的 price。
     * 保留这个字段（而不是去掉）是因为它是订单的固有属性 ——
     * "订单总额"和"订单里有几张票"是两个概念，将来支持一单多票时
     * 总额的含义不会变，而去掉它再回来改表结构成本更高。
     */
    private BigDecimal totalAmount;

    /**
     * 订单状态：0=待支付 1=已支付 2=已取消。
     *
     * <p>⚠️ <b>t_order 上没有 CHECK 约束</b>，这个字段的取值范围
     * 数据库层面不强制。状态机的正确性完全依赖每次迁移都写成
     * 条件 UPDATE（{@code WHERE order_no = ? AND status = ?}）——
     * 详见 {@link com.railseckill.train.enums.OrderStatus} 的类注释。
     */
    private Integer status;

    /** 创建时间，由数据库 {@code DEFAULT CURRENT_TIMESTAMP} 生成。 */
    private LocalDateTime createTime;

    /** 更新时间，由数据库 {@code ON UPDATE CURRENT_TIMESTAMP} 维护。 */
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
