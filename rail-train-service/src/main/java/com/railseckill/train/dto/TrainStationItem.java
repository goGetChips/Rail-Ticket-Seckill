package com.railseckill.train.dto;

import java.time.LocalTime;

/**
 * 车次经停站中的一站。
 *
 * <p>用于 {@code GET /api/train/trains/{trainNo}/stations}，按 {@code stationOrder} 升序返回。
 *
 * @param stationOrder 站序，从 1 开始，**相对于这趟车自己的运行方向**
 * @param stationCode  电报码
 * @param stationName  站名
 * @param cityName     所在城市
 * @param arriveTime   到达时刻；**始发站为 null**
 * @param departTime   发车时刻；**终到站为 null**
 * @param mileage      累计里程（公里），单位与种子数据一致
 */
/*
 * =============================================================================
 *  ⭐ 【arriveTime / departTime 为 null 是正常的，不是缺数据】
 * =============================================================================
 *   sql/10_seed_train.sql 里刻意把始发站的 arrive_time 和终到站的 depart_time
 *   写成 NULL，因为：
 *     · 始发站没有"到达"这回事（车就是从这开始跑的）
 *     · 终到站没有"发车"这回事（车到这就结束了）
 *
 *   用 00:00:00 表示"没有"是错的 —— 它会让"00:00 到站"和"没有到达时刻"
 *   无法区分。**用 null 表示"不存在"是正确的建模**，
 *   所以这两个字段是可为 null 的，前端要处理。
 *
 *   不是所有场景都能这么干净。对比一下 sql/10_seed_train.sql 里的注释：
 *   "中间站的发车时刻必然晚于到达时刻"这条约束**没能**做成 CHECK 约束，
 *   因为要表达它需要跨行比较（和上一行比），而 CHECK 只能看当前行。
 *   同一个"时刻"话题，一处能建模干净，一处不能 —— 这是数据库约束能力的真实边界。
 *
 * =============================================================================
 *  【为什么都叫 stationOrder，而不是 order 或 seq】
 * =============================================================================
 *   `order` 在 SQL 里是保留字，`seq` 太短没信息量。
 *   而且这个字段的名字必须和表列 station_order 能对上
 *   （map-underscore-to-camel-case 会做 station_order → stationOrder 的机械转换），
 *   用别的名字会让读代码的人怀疑"这两个是不是同一个东西"。
 *
 * =============================================================================
 *  【mileage 的单位】
 * =============================================================================
 *   种子数据里 G1 北京南(0) → 济南西(406) → 南京南(1023) → 上海虹桥(1318)，
 *   单位是公里，量级与真实京沪高铁一致。
 *   但按 sql/10_seed_train.sql 的数据性质说明，这些值是**为演示编写的**，
 *   不是官方里程数据。
 *
 *   ⚠️ 注意 mileage 是"从始发站起算的累计里程"，不是"上一站到这一站的距离"。
 *   要算区间距离需要做减法（TrainSegmentItem 里就是这么算的）。
 *   这类"累计值 vs 增量值"的歧义，是接口文档里最容易漏写的一句话。
 * =============================================================================
 */
public record TrainStationItem(
        Integer stationOrder,
        String stationCode,
        String stationName,
        String cityName,
        LocalTime arriveTime,
        LocalTime departTime,
        Integer mileage) {
}
