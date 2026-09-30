/**
 * 对外响应体（Data Transfer Object）。
 *
 * <p>本包下的类型**只用于 JSON 响应序列化**，不参与数据库映射。
 *
 * <h2>为什么这些类型用 record，而 entity 包下用普通类</h2>
 *
 * <p>这不是风格偏好，是两边的技术要求不同：
 *
 * <table border="1">
 *   <caption>entity 与 dto 的差异</caption>
 *   <tr><th></th><th>entity</th><th>dto</th></tr>
 *   <tr><td>谁构造它</td><td>MyBatis（反射调用 setter）</td><td>Java 代码（new 一次）</td></tr>
 *   <tr><td>构造后是否修改</td><td>可能（反射逐字段填充）</td><td>不会</td></tr>
 *   <tr><td>所以需要</td><td>无参构造器 + setter → 普通类</td><td>不可变 → {@code record} 正好</td></tr>
 * </table>
 *
 * <p>{@code record} 一次性给出全参构造器、访问器、{@code equals}/{@code hashCode}，
 * 且字段天然 final。用它写只读响应体，比手写 5 组 getter/setter 少 40 行样板代码，
 * 而且**"这个对象构造后不会再变"这件事由语言保证**，不是靠约定。
 *
 * <h2>⚠️ record + MyBatis 有一个会静默出错的陷阱</h2>
 *
 * <p>本包的类型都**不**由 MyBatis 直接映射。如果哪天真要这么做，必须先知道：
 *
 * <p>MyBatis 的 {@code argNameBasedConstructorAutoMapping} <b>默认是 false</b>，
 * 此时它把结果集的列**按物理顺序**依次塞给构造参数，<b>完全不比较列名</b>。
 * 也就是说 record 的组件顺序和 SELECT 的列顺序必须严格一致，
 * 错位了就会静默串值（比如 {@code arriveTime} 拿到 {@code depart_time} 的值）
 * ——只要类型兼容就**不会抛任何异常**。
 * 只有当参数个数和列数对不上时才报
 * "Constructor auto-mapping failed. The constructor takes N arguments, but there are only M columns"。
 *
 * <p>本项目的做法是**不依赖这个默认行为**：XML 里为每个 record 写显式的
 * {@code <resultMap>} + {@code <constructor>} + {@code <arg column="...">}，
 * 把"哪一列进哪个参数"写在明处。见
 * {@code rail-train-service/src/main/resources/mapper/TrainMapper.xml}。
 *
 * <p>（另一种做法是打开 {@code arg-name-based-constructor-auto-mapping: true}
 * 让 MyBatis 按名字匹配。本项目选显式 resultMap，是因为它不依赖一个全局开关被正确设置，
 * 且映射关系就写在 SQL 旁边，读的人不用去翻配置。）
 *
 * @see PageResult
 * @see TrainListItem
 * @see TrainStationItem
 * @see TrainSegmentItem
 * @see SeatAvailability
 * @see ApiError
 */
package com.railseckill.train.dto;
