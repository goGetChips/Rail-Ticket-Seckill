package com.railseckill.train.dto;

import java.util.List;

/**
 * 分页查询的响应体。
 *
 * @param records 当前页的数据
 * @param total   满足条件的总条数（不是当前页条数）
 * @param page    当前页码，从 1 开始
 * @param size    每页条数
 * @param pages   总页数
 * @param <T>     记录类型
 */
/*
 * =============================================================================
 *  【为什么不直接把 MyBatis-Plus 的 Page 对象返回给前端】
 * =============================================================================
 *
 *   MP 的 Page 是个"什么都有"的对象，Jackson 会把它**所有** getter 都序列化出去。
 *   实际会出现在响应里的字段包括（不止这些）：
 *
 *     records / total / size / current / pages        ← 这几个是我们想要的
 *     orders                                          ← 排序条件（含内部对象）
 *     optimizeCountSql / optimizeJoinOfCountSql       ← COUNT 语句的优化开关
 *     searchCount                                     ← 是否要执行 COUNT
 *     countId                                         ← 自定义 COUNT 语句的 id
 *     maxLimit                                        ← 每页上限
 *     hitCount                                        ← 命中统计
 *
 *   【为什么这不能接受】
 *   1. **API 契约会随框架版本漂移。** 这些字段是 MP 的内部实现细节，
 *      升级 MP 时它们可能被重命名、删除或新增。
 *      一旦前端依赖了其中任何一个，升级就变成破坏性变更。
 *   2. **它暴露了实现。** `optimizeCountSql: true` 这种字段对调用方毫无意义，
 *      却会出现在 API 文档里，让接口看起来比实际复杂。
 *   3. **`page` 和 `current` 的命名不一致。** MP 内部用 current，
 *      我们对外用 page。如果直接透传，前端要记住"MP 说 current 但文档说 page"。
 *
 *   【代价】
 *   多一个类 + 一次字段拷贝。换来的是：**对外契约由我们定义，不由 MP 定义。**
 *   这个交换在"接口要长期稳定"的场景下总是划算的。
 *
 *   【那 countId / maxLimit 这些配置怎么办】
 *   它们仍然在 MP 的 Page 上配置（见 config/MybatisPlusConfig.java），
 *   只是**不往响应里传**。配置和契约是两回事。
 *
 * =============================================================================
 *  【total / page / size / pages 为什么是 long 而不是 int】
 * =============================================================================
 *   跟 MP 的 IPage 保持一致，避免每次构造都写强制转换。
 *   size 理论上不会超过 100（有 @Max(100) 校验），但类型统一比省几个字节重要。
 *
 * =============================================================================
 *  【为什么用泛型 record 而不是每张表写一个 XxxPage】
 * =============================================================================
 *   分页信封和"装的是什么"无关。写 PageResult<TrainListItem> 就够了，
 *   不需要 TrainPageResult、StationPageResult……
 *   record 支持泛型，Jackson 也能正确反序列化（虽然本项目只序列化、不反序列化）。
 * =============================================================================
 */
public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long size,
        long pages) {
}
