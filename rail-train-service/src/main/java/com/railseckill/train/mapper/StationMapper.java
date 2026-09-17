package com.railseckill.train.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.railseckill.train.entity.Station;

/**
 * 车站表的 Mapper。
 *
 * <p>这个接口**一行方法都不需要写**——单表 CRUD 全部由父接口 {@link BaseMapper} 提供。
 * 它是本项目里"代码量最少、但最需要理解"的一个文件。
 */
/*
 * =============================================================================
 *  为什么一个空接口就能用
 * =============================================================================
 *
 * 【1. 泛型参数 Station 是唯一的信息来源】
 * MyBatis-Plus 靠 BaseMapper<Station> 上的泛型反射出实体类 Station，
 * 再读它上面的 @TableName("t_station")，于是知道：
 *     · 操作哪张表      → t_station
 *     · 主键是哪个字段  → id（@TableId）
 *     · 字段怎么映射    → stationCode ↔ station_code
 * 之后 selectById / selectList / insert / updateById 全都据此生成 SQL。
 *
 * ⚠️ 泛型不能省。写成 BaseMapper（裸类型）编译能过，**启动时才报错**，
 *    错误信息是"无法确定实体类型"，而且不会指出是哪一行。别省。
 *
 * 【2. BaseMapper 提供的常用方法（记住这几个就够日常用）】
 *     selectById(id)              按主键查一条
 *     selectList(wrapper)         按条件查多条
 *     selectOne(wrapper)          按条件查一条，**查到多条会抛异常**
 *     selectCount(wrapper)        按条件计数
 *     insert(entity)              插入，主键自动回填到实体
 *     updateById(entity)          按主键更新**非 null 字段**
 *     deleteById(id)              按主键删
 *
 * 【3. 为什么这里不需要写 @Mapper 注解】
 * 启动类上已经有 @MapperScan("com.railseckill.train.mapper")，
 * 它扫描这个包下所有接口并注册成 Bean。两者选一个即可，不要重复。
 *
 * 【4. 什么时候必须自己写 SQL】
 * BaseMapper 只覆盖**单表**操作。以下情况必须自己写：
 *     · 多表 JOIN            —— 比如"查北京→上海的车次"要 JOIN 经停站表
 *     · 复杂的聚合/分组统计
 *     · 需要用到数据库特有语法（如 INSERT ... ON DUPLICATE KEY UPDATE）
 * 写法有两种：注解式（@Select）和 XML 式。
 * **本项目优先用 XML**：本项目 SQL 普遍偏长、带教学注释，XML 里能自由排版，
 * 而注解里的字符串拼接很快就会变得没法读。等真的遇到再展开。
 *
 * 【5. 一个重要提醒：Mapper 里不要写业务逻辑】
 * Mapper 的职责边界是"数据访问"，判断、计算、事务编排都应该在 Service 层。
 * 把 if/else 写进 Mapper 或 XML 的 <if> 里，短期省事，长期会让
 * "这段逻辑到底在哪"变成一个需要全局搜索的问题。
 * =============================================================================
 */
public interface StationMapper extends BaseMapper<Station> {
}
