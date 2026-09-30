package com.railseckill.train.dto;

import java.time.LocalTime;

/**
 * 车次列表中的一行（全程视角）。
 *
 * <p>用于分页查询 {@code GET /api/train/trains}，回答"有哪些车次"。
 *
 * @param trainNo          车次号，如 G1
 * @param trainType        1=高铁 2=动车 3=普快
 * @param startStationCode 始发站电报码
 * @param startStationName 始发站名
 * @param endStationCode   终到站电报码
 * @param endStationName   终到站名
 * @param departTime       始发站的发车时刻
 * @param arriveTime       终到站的到达时刻
 * @param status           1=正常 0=停运
 */
/*
 * =============================================================================
 *  【为什么站名/站码要在这里出现，明明 t_train 里只存了 id】
 * =============================================================================
 *   让前端拿着 startStationId = 1 自己去查车站接口，是**典型的 N+1 问题**：
 *   列出 20 趟车，前端要再发 40 次请求才能显示出站名。
 *   所以在 SQL 里 JOIN 两次 t_station（别名 s1 / s2）把名字带出来，
 *   一次查询解决。
 *
 *   代价是 SQL 变复杂、且 COUNT 语句也带上了这两个 JOIN
 *   （见 config/MybatisPlusConfig.java 里关于 optimizeJoin 的说明）。
 *   对"列表页"这种批量场景，用一次 JOIN 换掉 N 次往返是明确划算的。
 *
 * =============================================================================
 *  ⭐ 【为什么这个 record 不复用给"按站查车次"的 TrainSegmentItem】
 * =============================================================================
 *   两者看起来很像是可以合并的 —— 都是"一趟车 + 两个站 + 两个时刻"。
 *   但它们的**语义不同**，合并会产生四个"看情况为 null"的字段：
 *
 *     字段          TrainListItem（全程）        TrainSegmentItem（区间）
 *     ────────────  ──────────────────────────  ──────────────────────────
 *     departTime    始发站的发车时刻              乘客上车站的发车时刻
 *     arriveTime    终到站的到达时刻              乘客下车站的到达时刻
 *
 *   拿 G1 举例，全程是 北京南(09:00) → 上海虹桥(13:28)；
 *   但"济南西 → 南京南"这个区间的上下车时刻是 10:24 → 12:18。
 *   共用一条 record 的话，这两个字段装哪一种值取决于调用方，
 *   而**类型系统完全不拦这件事**。
 *
 *   最危险的地方：如果按列名映射（而不是本项目用的显式 constructor resultMap），
 *   "查询里漏写了一列"和"这一列本来就该是 null"**长得一模一样**，
 *   都是 null。分成两个 record 之后，各自要哪些列是明确的，
 *   漏一列就会因为构造参数个数对不上而直接报错。
 *
 * =============================================================================
 *  【为什么没有 startStationId / endStationId】
 * =============================================================================
 *   调用方拿站码（VNP）就能查车站详情，站 id 是实现细节。
 *   把 id 也返回只是增加噪音 —— 这是刻意的"不返回什么"，
 *   和 PageResult 里不返回 MP 内部字段是同一个取舍。
 *
 *   ⚠️ 但要注意：TrainStationItem 里**有** stationCode 也有 stationOrder。
 *   stationOrder 不是实现细节 —— 它是"这站是第几站"，业务含义明确
 *   （乘客需要知道"我这站是第几站"来估算行程）。
 * =============================================================================
 */
public record TrainListItem(
        String trainNo,
        Integer trainType,
        String startStationCode,
        String startStationName,
        String endStationCode,
        String endStationName,
        LocalTime departTime,
        LocalTime arriveTime,
        Integer status) {
}
