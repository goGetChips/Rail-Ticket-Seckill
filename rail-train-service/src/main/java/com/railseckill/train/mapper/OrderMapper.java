package com.railseckill.train.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.railseckill.train.entity.Order;
import org.apache.ibatis.annotations.Param;

/**
 * 订单 Mapper。
 *
 * <p>插入用 {@code BaseMapper.insert}（见类注释）；只有状态流转需要手写 SQL ——
 * 它必须是**条件** UPDATE，见 {@link #updateStatus}。
 */
/*
 * =============================================================================
 *  ⭐ 【为什么插入用 BaseMapper.insert，不手写 XML <insert>】
 * =============================================================================
 *   不是图省事，是因为**手写容易漏掉自增主键回填**。
 *
 *   OrderService 的流程是"先插 t_order，再拿它的 id 去插 t_order_item"。
 *   所以插入之后必须能读到数据库生成的自增 id：
 *
 *     orderMapper.insert(order);
 *     Long orderId = order.getId();     // ← 这个值必须已经被回填
 *
 *   `IdType.AUTO` 会让 MyBatis-Plus 自动带上 Jdbc3KeyGenerator 完成回填。
 *   而手写 `<insert>` 时**默认没有回填**，必须自己写：
 *
 *     <insert id="insert" useGeneratedKeys="true" keyProperty="id">
 *
 *   漏了它不会有编译错误、不会有启动错误，只在运行时得到
 *   `Column 'order_id' cannot be null`。因为回填这件事发生了但值没拿到，
 *   而报错发生在**另一张表**上 —— 排查方向很容易跑偏。
 *
 *   借用框架已经做对的事，比自己做对了更可靠。
 *
 * =============================================================================
 *  ⚠️ 【为什么 updateStatus 收 int 而不是 OrderStatus 枚举】
 * =============================================================================
 *   枚举看起来更安全（"禁止裸写数字"），但把枚举直接交给 MyBatis 有一个
 *   不容易发现的陷阱：**MyBatis 的默认 EnumTypeHandler 存的是 name()，
 *   不是数据库里的 code。**
 *
 *   也就是说传 OrderStatus.PAID 进去，默认情况下写进数据库的是字符串
 *   "PAID"，而 t_order.status 是 TINYINT —— 于是报类型错误；
 *   就算列是字符串，也会得到 "PAID" 而不是 1，和 DDL 的约定对不上。
 *
 *   要让它按 code 写，得靠 MyBatis-Plus 的 MybatisEnumTypeHandler
 *   或在枚举上打 @EnumValue。那是**给本项目引入一个新的隐式约定**，
 *   而收益只是省掉两个 int 常量。
 *
 *   所以：**枚举在服务端的业务判断里用，跨 SQL 边界的时候显式转成 int。**
 *   转换点就在 OrderService 调用处，一眼可见：
 *
 *     orderMapper.updateStatus(orderNo, OrderStatus.PAID.code(), OrderStatus.PENDING.code())
 *
 *   这个取舍和"对外 DTO 仍用 1/2/3"是同一个原则：
 *   **枚举的价值在服务端内部的类型安全，不在于它出现在每一个边界上。**
 * =============================================================================
 */
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 订单状态的条件流转 —— 状态机的**唯一**实现方式。
     *
     * <p>和库存扣减用的是同一个手法：把「判断当前状态」和「写入新状态」
     * 压进同一条原子 SQL，用受影响行数表达判断结果。
     *
     * @param orderNo        业务订单号
     * @param newStatus      目标状态（0/1/2）
     * @param expectedStatus <b>期望的当前状态</b>。这一条是整个方法的要害 ——
     *                       它是 t_order 上没有 CHECK 约束时的替代品。
     * @return <b>受影响行数</b>：1 = 本次状态流转生效；0 = 订单当前状态
     *         不是 {@code expectedStatus}（已被处理过）。
     *
     *         <p>⚠️ <b>0 行不能一律当成"重复支付，返回成功"。</b>
     *         这是 `sql/04_rail_order.sql` 和 `docs/03-business-flow.md`
     *         里写错的一句话，阶段 5 必须偏离它：
     *
     *         <table border="1">
     *           <caption>0 行的三种真实含义</caption>
     *           <tr><th>当前状态</th><th>含义</th><th>正确响应</th></tr>
     *           <tr><td>1 已支付</td><td>重复支付</td><td>200，幂等成功</td></tr>
     *           <tr><td>2 已取消</td><td><b>订单已取消，不能支付</b></td>
     *               <td><b>409</b>，不是成功</td></tr>
     *           <tr><td>查不到</td><td>订单不存在</td><td>404</td></tr>
     *         </table>
     *
     *         对"已取消"返回成功是错的：那会告诉用户一个**已取消的订单
     *         支付成功了** —— 钱没有对应任何一张票。
     *         正确做法是 0 行之后**再读一次状态**来分类，
     *         具体代码见 OrderService#payOrder。
     */
    /*
     * =========================================================================
     *  ⚠️ SET 里没有 update_time，这是对的
     * =========================================================================
     *   t_order.update_time 的定义是 `ON UPDATE CURRENT_TIMESTAMP`，
     *   由数据库在行真的被修改时自动维护。
     *   在 SET 里再写一次 update_time = NOW() 是多余的，
     *   而且会引入"数据库的时钟"和"NOW() 的时钟"两个来源。
     *   保持单一来源：让列定义去管它。
     *
     * =========================================================================
     *  ⚠️ 为什么 WHERE 里必须是 order_no（有唯一索引），不是 id
     * =========================================================================
     *   两个都能定位到唯一一行（一个有 uk_order_no，一个是主键），
     *   但对外接口收到的是 order_no，**从 order_no 开始查**才能保证
     *   "客户端传什么就检查什么"，不需要先做一次 order_no → id 的翻译。
     *   少一次查询 = 少一个 TOCTOU 的窗口。
     * ===================================================================== */
    int updateStatus(@Param("orderNo") String orderNo,
                     @Param("newStatus") int newStatus,
                     @Param("expectedStatus") int expectedStatus);
}
