package com.railseckill.train.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.railseckill.train.entity.Station;
import com.railseckill.train.mapper.StationMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 车站查询。
 *
 * <p>阶段 3 只有两个只读方法。写方法（录入车站）不属于本项目要演示的场景，
 * 车站数据由 {@code sql/10_seed_train.sql} 一次性灌入。
 */
/*
 * =============================================================================
 *  关于这个类的几个设计选择
 * =============================================================================
 *
 * 【1. 为什么不用 MyBatis-Plus 的 IService / ServiceImpl】
 * MP 提供了 IService<T> 接口和 ServiceImpl<M, T> 实现，写一行
 *     public interface StationService extends IService<Station> {}
 * 就能白拿一整套 CRUD。
 *
 * 本项目**刻意不用**，理由是：
 *   · IService 有 40+ 个方法（saveBatch、page、lambdaQuery、getOneOrNull……），
 *     全都挂在一个类上，但本项目真正用到的只有两三个
 *   · 它的很多方法（如 saveBatch）默认不带事务，用了会踩坑
 *   · **最关键的**：它让"这个 Service 到底对外提供什么能力"变得不可见——
 *     调用方看到的是 40 个方法，而不是"查所有车站"和"按码查车站"这两个明确的能力
 *
 * 代价：CRUD 代码要自己写。本项目只写真正被用到的，所以代价很小。
 * **这是一次有意识的取舍，不是"不知道有 IService"。**
 *
 * 【2. 为什么用构造器注入而不是 @Autowired 字段注入】
 *   · 字段注入的依赖可以是 null —— 单元测试里 new 一个对象，字段是空的，
 *     要跑起来必须靠 Spring 容器或反射，测试反而更麻烦
 *   · 构造器注入的依赖被 final 修饰，**创建后不可变**，也不可能忘赋值
 *   · 依赖在构造器参数上**一眼看全**。字段注入的话，依赖可以散落在类的任何位置，
 *     一个类依赖了 10 个东西都很难发现——这本身就是该拆分的信号
 * Spring 官方从 4.3 起就推荐构造器注入；只有一个构造器时连 @Autowired 都不用写。
 *
 * 【3. 为什么 Mapper 要加 private final】
 * 不是为了好看，是编译器保证：final 字段必须在构造器里赋值，
 * 漏了会编译失败。等于用编译期检查消灭了一整类 NPE。
 * =============================================================================
 */
@Service
public class StationService {

    private final StationMapper stationMapper;

    public StationService(StationMapper stationMapper) {
        this.stationMapper = stationMapper;
    }

    /**
     * 查询全部车站，按电报码升序。
     *
     * <p>本项目车站数量是几十个量级，一次全查没有性能问题。
     * 真实铁路系统有几万个车站，这里必须分页——阶段 4 做车次查询时会引入分页，
     * 车站查询因为量小，保持全量。
     *
     * <p>{@code orderByAsc} 是必须的：**不写 ORDER BY 时，MySQL 返回的顺序是不保证的**。
     * 它在数据量小、走全表扫描时看起来"总是按主键顺序"，一旦数据量变大、
     * 优化器改走别的索引，顺序就会变——而这种变化**不报错、只在页面上表现为"列表顺序莫名变了"**，
     * 极难定位。所以只要对顺序有要求，就必须显式写 ORDER BY。
     */
    public List<Station> listAll() {
        return stationMapper.selectList(
                Wrappers.<Station>lambdaQuery()
                        .orderByAsc(Station::getStationCode));
    }

    /**
     * 按电报码精确查询单个车站，查不到返回 {@code null}。
     *
     * <p>用 lambda 而不是字符串列名，是为了让字段名错误在**编译期**暴露：
     * {@code eq("staiton_code", code)} 这种拼写错误编译能过，运行时才返回空结果。
     *
     * <p>{@code selectOne} 在匹配到多行时会抛 TooManyResultsException。
     * 这里敢用它，是因为 t_station 上有唯一索引 {@code uk_station_code}，
     * **数据库层面保证了最多一行**。如果没有这个唯一索引，selectOne 就是个隐患：
     * 数据脏了会从"返回第一条"变成"直接抛异常"。
     *
     * <p>调用方拿到的可能是 null，这一点在 Controller 里被显式处理成 404，
     * 而不是把 null 一路透传给前端变成"{}"。
     */
    public Station getByCode(String stationCode) {
        return stationMapper.selectOne(
                Wrappers.<Station>lambdaQuery()
                        .eq(Station::getStationCode, stationCode));
    }
}
