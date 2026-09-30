package com.railseckill.train.dto;

import java.time.LocalTime;

/**
 * 按出发站 / 到达站查出的一个"可乘区间"。
 *
 * <p>用于 {@code GET /api/train/trains/search?from=VNP&to=AOH}。
 *
 * <p>一行代表"某趟车可以被乘坐的 from → to 这一段"，
 * <b>不是</b>一趟车的全程信息。时刻是**这段区间上下车站的时刻**，
 * 与 {@link TrainListItem} 的全程时刻不同。
 *
 * @param trainNo             车次号
 * @param trainType           1=高铁 2=动车 3=普快
 * @param fromStationCode     上车站电报码
 * @param fromStationName     上车站名
 * @param toStationCode       下车站电报码
 * @param toStationName       下车站名
 * @param segmentDepartTime   上车站的发车时刻
 * @param segmentArriveTime   下车站的到达时刻
 * @param segmentMileage      区间里程 = 下车站累计里程 - 上车站累计里程
 */
/*
 * =============================================================================
 *  ⭐ 【这个查询为什么必须自连接 t_train_station，而不能用 t_train 的起终站】
 * =============================================================================
 *   最省事的写法是直接查 t_train：
 *
 *       SELECT ... FROM t_train
 *        WHERE start_station_id = (SELECT id FROM t_station WHERE station_code = ?)
 *          AND end_station_id   = (SELECT id FROM t_station WHERE station_code = ?)
 *
 *   **这个写法只能回答"我坐完全程"，回答不了"我中途上车"。**
 *   G1 是 北京南 → 济南西 → 南京南 → 上海虹桥，
 *   用它查"济南西 → 南京南"会返回**空** —— 但这段区间明明有车可坐。
 *
 *   正确做法是自己和自己连接：
 *
 *       FROM t_train t
 *       JOIN t_train_station ts1 ON ts1.train_id = t.id    -- 上车站
 *       JOIN t_train_station ts2 ON ts2.train_id = t.id    -- 下车站
 *      WHERE ts1.station_id = ?          -- from
 *        AND ts2.station_id = ?          -- to
 *        AND ts1.station_order < ts2.station_order        -- ← 关键在这一句
 *
 *   最后那个不等条件才是核心：它同时表达了
 *     a) 这趟车**两站都停**
 *     b) 上车站**排在**下车站前面（否则就是坐反了）
 *
 *   【为什么是 < 而不是 <=】
 *   如果允许相等，同一趟车的同一个站就会匹配上自己，
 *   返回一条"从济南西到济南西"的伪结果。真实系统里还会进一步
 *   要求 to_order - from_order 足够大以容纳停站时间，本项目不做。
 *
 * =============================================================================
 *  【⚠️ 这个查询没有考虑库存，所以"可售"是名义上的】
 * =============================================================================
 *   阶段 4 的验收项写的是"按出发站/到达站查**可售**车次"。
 *   这里返回的"可售"目前只意味着"**这趟车停这两站且顺序正确**"，
 *   **不检查这两站之间还有没有余票**。
 *
 *   为什么不查：库存是**按日期**的（t_seat_inventory 的业务主键含 travel_date），
 *   而这个查询**没有日期参数**。要真的判断"某天可售"，
 *   必须先给这个接口加上 date 参数，再去 JOIN 库存表。
 *   那属于阶段 5（有了真正的库存扣减之后），现在加会得到一个
 *   "参数看起来支持日期、其实逻辑没用到"的假接口。
 *
 *   ⚠️ 这是一个**已知的语义缺口**，不是遗漏。面试被问到
 *   "你这个可售是怎么判定的"时，正确回答是：
 *   "只判了区间可达性，没判余票，因为库存是按日期的而查询不带日期；
 *   要补齐需要加日期维度并 JOIN 库存表"。
 *   而不是含糊地说"考虑了库存"。
 *
 * =============================================================================
 *  【segmentMileage 是算出来的，不是查出来的】
 * =============================================================================
 *   t_train_station.mileage 存的是**从始发站起算的累计里程**。
 *   区间里程 = ts2.mileage - ts1.mileage，在 SQL 里做减法，
 *   不给 t_train_station 加一个"区间里程"列。
 *
 *   理由和 sql/03_rail_inventory.sql 里"为什么不存 available_count"完全一样：
 *   派生值存下来就要维护它和其他列的一致性，而累计里程一改
 *   （比如中途加了一站），所有区间里程都要重算 —— 漏一处就是静默的错误数据。
 *
 *   ⚠️ 边界情况：如果某个站的 mileage 是 NULL（表上允许），
 *   相减的结果也是 NULL。种子数据里所有站都填了 mileage，
 *   所以当前不会出现；但前端应当能接受 null。
 * =============================================================================
 */
public record TrainSegmentItem(
        String trainNo,
        Integer trainType,
        String fromStationCode,
        String fromStationName,
        String toStationCode,
        String toStationName,
        LocalTime segmentDepartTime,
        LocalTime segmentArriveTime,
        Integer segmentMileage) {
}
