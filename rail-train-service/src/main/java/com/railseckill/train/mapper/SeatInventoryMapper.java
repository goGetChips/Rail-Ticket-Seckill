package com.railseckill.train.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.railseckill.train.entity.SeatInventory;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 库存 Mapper。
 *
 * <p><b>阶段 5 起，本接口是全项目唯一一处能写
 * {@code rail_inventory.t_seat_inventory} 的地方</b> ——
 * {@link #deductStock} 是库存表唯一的写入口。
 * 其余全部代码对这张表仍然只读。
 *
 * <p>表 {@code rail_inventory.t_seat_inventory} 的 owner 终态是
 * {@code rail-inventory-service}（阶段 8），所以 {@link #deductStock}
 * 阶段 8 迁往**库存服务**，而不是订单服务。
 *
 * @see SeatInventory 实体的类注释里有完整说明
 */
/*
 * =============================================================================
 *  ⚠️⚠️ 「本服务只读它，永不写它」这条注释在阶段 5 被推翻了 —— 这里记录全过程
 * =============================================================================
 *
 *   本类原先的注释写着：**"本服务只读它，永不写它"**，并且专门加了一段警告：
 *
 *     「阶段 5 要做的正是'扣库存'这件事 —— 到那时很容易顺手在余票查询
 *       所在的这个服务里把 UPDATE 也写了，于是'库存的唯一写入者是
 *       inventory/order 侧'这条设计约束在没人注意的情况下被破坏，
 *       而**任何测试都不会失败**。」
 *
 *   这段话当时是**预言**，现在它成真了，而且**它的另一半预言也是对的**：
 *   阶段 5 确实在这个服务里写了 UPDATE。
 *
 *   【所以是"设计出了问题"吗？—— 不是，是"约束被显式改写了"】
 *   上面那段警告自己给出了判据：
 *     「如果哪天这个服务真的开始写库存，那是设计出了问题，
 *       应该停下来讨论，而不是靠接口收窄来兜住。」
 *
 *   阶段 5 规划时**确实停下来讨论了**，结论是：阶段 5 的既定标题是"单体"，
 *   全项目只有一个 Maven 模块，`rail-order-service` 要到阶段 8 才建。
 *   把下单功能放进 train-service，是这个阶段的**范围决定**，
 *   不是有人顺手写错了。
 *
 *   区别在于：**那次讨论发生了，并且被记在案。**
 *   上面那段警告防范的从来不是"写了 UPDATE"这个动作本身，
 *   而是"在没人注意、没有记录、没有测试失败的情况下写了 UPDATE"。
 *   所以这段注释不是被删掉，是被**兑现**了 —— 它成功地让这件事
 *   必须经过一次显式决策才能发生。
 *
 * -----------------------------------------------------------------------------
 *  🔴 阶段 8 的正确迁移目标：inventory-service，不是 order-service
 * -----------------------------------------------------------------------------
 *   这一条最容易搞错，因为下单的代码（OrderService）住在 order 侧，
 *   迁移时很自然会把"它调用的 Mapper"一起搬到 order-service 去。
 *   **那是错的。**
 *
 *   判据是"谁拥有这张表"，不是"谁在调用它"：
 *     · t_seat_inventory 的 owner 终态是 **rail-inventory-service**
 *     · 下单流程**远程调用** inventory-service 的扣减接口（Feign）
 *     · 于是阶段 8 的扣减变成"一次网络调用"，而不是"一条 UPDATE"
 *
 *   这个区别是阶段 8 全部难度的来源：**一旦扣减变成跨进程调用，
 *   阶段 5 这个"扣减 + 落单在同一个本地事务里"的假设就崩了** ——
 *   本地事务不再覆盖两个服务，需要引入补偿（方案 C 的形态：
 *   Redis 预扣 + MQ 异步落库 + 对账）。
 *   完整的转折点论证见 service/OrderService 的类注释。
 *
 * -----------------------------------------------------------------------------
 *  【为什么写方法加在这个已有的 Mapper 里，而不是新建一个】
 * -----------------------------------------------------------------------------
 *   "一个表一个 Mapper"是本项目的既有约定（StationMapper / TrainMapper /
 *   SeatInventoryMapper，各自对应一张表的读写）。
 *
 *   为扣减单独建一个 `StockWriteMapper` 会破坏这个约定，而且它**更危险**：
 *   两个入口会让"谁在写库存"这件事需要 grep 两个文件才能回答，
 *   而审计这张表被谁写过正是这个类注释存在的全部意义。
 * =============================================================================
 */
public interface SeatInventoryMapper extends BaseMapper<SeatInventory> {

    /**
     * 条件 UPDATE 扣减库存 —— 本项目"不超卖"的**唯一实现**。
     *
     * <p>判据全在 {@code AND sold_count < total_count} 这一句上：
     * 它和被扣减的动作在**同一条原子 SQL** 里，中间没有可以被别人插进来的空隙。
     *
     * @param trainId    车次 ID
     * @param travelDate 乘车日期
     * @param seatType   席别（1/2/3，取值见 {@link com.railseckill.train.enums.SeatType}）
     * @return <b>受影响行数</b>，它是一份三态的契约，调用方必须按三种情况分别处理：
     *         <table border="1">
     *           <caption>返回值语义</caption>
     *           <tr><th>返回</th><th>含义</th><th>调用方该做什么</th></tr>
     *           <tr><td>{@code 1}</td><td>扣减成功</td><td>继续下单</td></tr>
     *           <tr><td>{@code 0}</td>
     *               <td><b>正常业务失败</b>：票已售罄（或这一行根本不存在）</td>
     *               <td>返回 409，<b>不是异常、不是 500</b></td></tr>
     *           <tr><td>抛异常</td>
     *               <td><b>系统失败，结果未知</b>：超时、死锁、连接断</td>
     *               <td>让它往上抛，事务回滚</td></tr>
     *         </table>
     *
     *         <p>⚠️ <b>这三个状态必须被区分开</b>，这是
     *         {@code sql/03_rail_inventory.sql} 里明确规定的语义。
     *         把 0 当成失败异常来处理，会在秒杀场景下把"卖完了"
     *         报成"服务器错误"，前端于是提示用户"请稍后重试" ——
     *         而重试永远不会成功，这是最典型的错误处理事故。
     */
    /*
     * =========================================================================
     *  ⭐ 【为什么返回 int，不返回 boolean】
     * =========================================================================
     *   写成 boolean 的话，0 会被压成 false，调用方看到的就只剩
     *   "成功/失败"两态：`if (!deductStock(...)) throw new ...Exception()`。
     *
     *   而 SQL 层面其实有**三个**状态（1 / 0 / 抛异常），
     *   压成 boolean 会把"0 行是**正常业务失败**"这条规格抹掉，
     *   让 0 和抛异常看起来是同一类事情。
     *
     *   MyBatis 天然就把 update 的返回值定义成 int（受影响行数），
     *   所以这里**不需要任何额外代码**，只需要不去抹掉它。
     *   "不做多余转换"在这里就是正确做法。
     *
     * =========================================================================
     *  ⚠️ 【三个参数都必须写 @Param，一个都不能省】
     * =========================================================================
     *   本项目的 `spring-boot-starter-parent` 在 pluginManagement 里给
     *   maven-compiler-plugin 设了 `<parameters>true</parameters>`，
     *   已实测确认生效：编译产物 TrainMapper.class 里带着 MethodParameters
     *   属性，参数名（page / trainNo）被真实保留。
     *
     *   ⚠️ **这正是它危险的地方**：因为参数名保留着，MyBatis 可以按名字
     *   找到它们，所以**今天就算漏写 @Param 也能跑通**。
     *   但这条"能跑通"所依赖的东西**不在本仓库里** ——
     *   我们的两个 pom.xml 里都没有出现过 parameters 这个字，
     *   它完全来自 spring-boot-starter-parent 的默认配置。
     *
     *   一旦有人出于任何理由覆盖了 compiler 插件的 config
     *   （比如为了加注解处理器而重写整段 <configuration>），
     *   `-parameters` 就会静默消失，报错是运行时的：
     *
     *     Parameter 'trainId' not found. Available parameters are
     *     [arg0, arg1, arg2, param1, param2, param3]
     *
     *   而**编译期不会有任何警告**。写 @Param 是把"依赖上游默认值"
     *   换成"我们自己保证"—— 成本是 3 个注解，收益是这条 SQL
     *   永远不依赖第三方 POM 的配置。
     *
     *   这与根 POM 里显式声明 UTF-8 编码属性是同一个判断：
     *   默认值今天是对的，但写出来才算数。
     *
     * =========================================================================
     *  【这条 SQL 写在 XML 里，不写在注解里】
     * =========================================================================
     *   @Update 注解也能放 SQL，但本项目的既有约定是 XML
     *   （见 resources/mapper/）。这一段 SQL 的注释里需要写清
     *   "0 行是业务失败"的语义和那个 `&lt;` 的转义坑，
     *   注解里的字符串字面量写不下这些，也没法在 IDE 里高亮 SQL。
     */
    int deductStock(@Param("trainId") Long trainId,
                    @Param("travelDate") LocalDate travelDate,
                    @Param("seatType") int seatType);
}
