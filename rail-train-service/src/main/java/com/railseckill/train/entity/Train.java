package com.railseckill.train.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 车次，对应表 {@code rail_train.t_train}。
 *
 * <p>表结构见 {@code sql/02_rail_train.sql}。
 *
 * <p>字段含义：{@code trainType} 1=高铁 2=动车 3=普快；{@code status} 1=正常 0=停运。
 */
/*
 * =============================================================================
 *  关于这张表的两个设计事实（都是面试会被追问的点）
 * =============================================================================
 *
 * 【1. 为什么 start_station_id / end_station_id 是"冗余"的】
 *
 *   这两列可以由 t_train_station 的首尾两行推导出来（station_order 最小的和最大的）。
 *   冗余字段是数据漂移的经典来源，所以留它需要理由：
 *
 *     "查北京南→上海虹桥有哪些车" 是最热的查询。
 *     如果只能从 t_train_station 推导，这个查询就要先按区间筛出车次、
 *     再回头确认哪些车次的**全程**起终站是这两个站 —— 多一次自连接。
 *     而 t_train 上有 idx_start_end(start_station_id, end_station_id)，
 *     可以直接命中。（注：那个索引对"只按 end_station_id 查"用不上，
 *     是最左前缀规则，见 sql/02_rail_train.sql 的说明。）
 *
 *   代价是"有唯一的写入者且在同一个事务内"这个前提必须成立。
 *   本项目的写入者是 sql/10_seed_train.sql，一次性灌入，满足；
 *   手工改经停站而不同步改这里，就会产生矛盾数据，
 *   **而且两个查询各自看起来都是对的**——这是最麻烦的地方。
 *
 * 【2. ⚠️ 没有车次日历表，所以表达不了"某天停运"】
 *
 *   真实系统里"G1 每天开行"和"G1 只在周一三五开行"是两回事，
 *   需要一张 t_train_calendar(train_id, run_date) 或者一个开行规则表。
 *   本项目**没有建这张表**，于是：
 *     · status 只能表达"这趟车永久停运"，不能表达"10 月 1 日停运"
 *     · 查"某天的车次"实际上查的是"所有正常车次"，与日期无关
 *
 *   这是一个**已知缺陷**，不是遗漏：阶段 4 的查询接口都不带日期参数
 *   （余票接口带，但那是查库存，不是查车次开行）。
 *   记录在这里，是因为"我的车次查询没有日期维度"这件事
 *   在面试时如果被问到"10 月 1 日的 G1 开不开"，
 *   正确的回答是"我的模型表达不了这个，需要加日历表"，
 *   而不是临时编一个说法。
 * =============================================================================
 */
@TableName("t_train")
public class Train {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 车次号，如 G1。有唯一索引 uk_train_no。 */
    private String trainNo;

    /** 车次类型：1=高铁 2=动车 3=普快。 */
    private Integer trainType;

    /** 始发站 ID，指向 t_station.id。冗余字段，理由见类注释 §1。 */
    private Long startStationId;

    /** 终到站 ID，指向 t_station.id。 */
    private Long endStationId;

    /**
     * 发车时刻。
     *
     * <p>⚠️ 类型是 {@link LocalTime} 而不是 {@link java.util.Date}：
     * t_train.depart_time 是 MySQL 的 {@code TIME} 类型，
     * 它表达的是"一天中的某个时刻"，**不带日期、不带时区**。
     * 用 Date 接会在时区解释上引入问题，而 LocalTime 的语义和 TIME 正好一一对应。
     *
     * <p>MyBatis 内置了 {@code LocalTimeTypeHandler}（3.4.5 起），
     * 所以不需要自己写 TypeHandler，也不需要额外配置。
     *
     * <p>Jackson 把它序列化成 {@code "09:00:00"} 字符串。
     */
    private LocalTime departTime;

    /** 到达时刻。类型理由同 {@link #departTime}。 */
    private LocalTime arriveTime;

    /** 状态：1=正常 0=停运。注意表达不了"某天停运"，见类注释 §2。 */
    private Integer status;

    /** 创建时间，由数据库 DEFAULT CURRENT_TIMESTAMP 生成。 */
    private LocalDateTime createTime;

    /** 更新时间，由数据库 ON UPDATE CURRENT_TIMESTAMP 维护。 */
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
    }

    public Integer getTrainType() {
        return trainType;
    }

    public void setTrainType(Integer trainType) {
        this.trainType = trainType;
    }

    public Long getStartStationId() {
        return startStationId;
    }

    public void setStartStationId(Long startStationId) {
        this.startStationId = startStationId;
    }

    public Long getEndStationId() {
        return endStationId;
    }

    public void setEndStationId(Long endStationId) {
        this.endStationId = endStationId;
    }

    public LocalTime getDepartTime() {
        return departTime;
    }

    public void setDepartTime(LocalTime departTime) {
        this.departTime = departTime;
    }

    public LocalTime getArriveTime() {
        return arriveTime;
    }

    public void setArriveTime(LocalTime arriveTime) {
        this.arriveTime = arriveTime;
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
