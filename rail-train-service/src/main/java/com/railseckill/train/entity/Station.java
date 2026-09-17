package com.railseckill.train.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 车站，对应表 {@code rail_train.t_station}。
 *
 * <p>表结构见 {@code sql/02_rail_train.sql}。
 */
/*
 * =============================================================================
 * 关于这个类，值得说清楚的三件事
 * =============================================================================
 *
 * 【1. 为什么要有实体类，不直接用 Map 接结果】
 * MyBatis 允许把查询结果映射成 Map<String, Object>，省掉实体类。
 * 但那样做的代价是：
 *   · 字段名写错要到**运行时**才发现（map.get("staitonName") 返回 null，不报错）
 *   · IDE 无法补全，重构改字段名时全靠人肉搜索
 *   · 接口返回值没有类型，前端/调用方不知道有哪些字段
 * 实体类把这些错误提前到编译期。**这是"多写一个类"换来的真实收益，不是仪式感。**
 *
 * 【2. 为什么字段名是驼峰，表字段是下划线，却不用写 @TableField】
 * 因为 application.yml 里开了 map-underscore-to-camel-case（本来就是默认值）。
 * 映射规则是机械的：station_code → stationCode。
 * ⚠️ 反过来说，如果哪天有人把表字段改成 stationcode（去掉下划线），
 *    这个映射就断了，读出来永远是 null，**而且不报错**。
 *
 * 【3. 为什么 create_time 用 LocalDateTime 而不是 Date】
 *   · java.util.Date 是可变对象，且它的"年月日时分秒"依赖 JVM 默认时区，
 *     同一个 Date 在不同时区的机器上显示不同 —— 是公认的设计缺陷。
 *   · LocalDateTime 是不可变的，语义明确（"墙上时钟的一个时刻，不带时区"），
 *     和 MySQL 的 DATETIME 类型语义正好对应。
 *     注意 DATETIME 本身**不存时区**，这也是 JDBC URL 里要配 connectionTimeZone
 *     的原因：驱动得知道按哪个时区去解释它。
 *   用 Instant/ZonedDateTime 更严谨，但对一个只在国内运营的购票系统是过度设计——
 *   **这个取舍要能说清楚，而不是"大家都用 LocalDateTime"。**
 * =============================================================================
 */
@TableName("t_station")
public class Station {

    /**
     * 主键。
     *
     * <p>{@code IdType.AUTO} 表示交给数据库的 AUTO_INCREMENT 生成，
     * 等价于在 INSERT 时不带 id 字段、插入后用 LAST_INSERT_ID() 回填。
     *
     * <p>虽然 application.yml 里已经全局配了 id-type: auto，这里仍显式标注：
     * 全局配置是"默认值"，实体上的注解是"这张表的明确约定"。
     * 当某张表真的需要别的策略（比如雪花算法）时，只有在这一处改，
     * 阅读代码的人不需要回头去翻 yml 才能确定主键是怎么来的。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 车站电报码，如 BJP。有唯一索引 uk_station_code，是业务上的天然主键。 */
    private String stationCode;

    /** 车站名，如 北京南。 */
    private String stationName;

    /** 所在城市，如 北京。建了 idx_city_name，支持"按城市查所有车站"。 */
    private String cityName;

    /**
     * 创建时间。
     *
     * <p>由数据库的 DEFAULT CURRENT_TIMESTAMP 生成，**应用侧不赋值**。
     * 这样做的原因：多实例部署时，各台机器的系统时间可能有几毫秒到几秒的偏差，
     * 由数据库统一生成能保证同一批数据的时间是可比的。
     *
     * <p>代价是：这个字段只在 INSERT 时由 DB 填，Java 侧读到的对象在插入前该字段为 null。
     */
    private LocalDateTime createTime;

    /*
     * =========================================================================
     * 关于 getter / setter，以及为什么这里没有写注释
     * =========================================================================
     * 它们不做任何额外的事，注释只会重复方法名。
     *
     * 【但有一个真实的坑必须知道】
     * MyBatis 默认通过**反射调用 setter** 来填充对象，而不是直接写字段。
     * 所以：
     *   · 少写一个 setter  → 该字段读出来永远是 null（不报错）
     *   · setter 写成 private → MyBatis 可能仍能通过反射访问，但行为依赖配置，
     *     不值得赌；保持 public
     * 这两个问题都不会在启动或编译时暴露，只在"查出来的数据少了几个字段"时暴露。
     *
     * 【为什么本次没引入 Lombok】
     * 见 rail-train-service/pom.xml 里的说明——它需要一个额外的编译期注解处理器，
     * 阶段 3 的目标是"用最短路径证明链路通"，不引入干扰变量。
     */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getCityName() {
        return cityName;
    }

    public void setCityName(String cityName) {
        this.cityName = cityName;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
