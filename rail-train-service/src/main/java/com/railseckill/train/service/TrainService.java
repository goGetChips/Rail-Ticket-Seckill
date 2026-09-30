package com.railseckill.train.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.railseckill.train.dto.PageResult;
import com.railseckill.train.dto.TrainListItem;
import com.railseckill.train.dto.TrainSegmentItem;
import com.railseckill.train.dto.TrainStationItem;
import com.railseckill.train.entity.Train;
import com.railseckill.train.mapper.TrainMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 车次查询。
 *
 * <p>全部是只读方法。车次和经停站的写入由 {@code sql/10_seed_train.sql} 完成，
 * 本项目不提供"录入车次"的接口 —— 那不属于要演示的场景。
 */
/*
 * =============================================================================
 *  关于这个类的几个设计选择
 * =============================================================================
 *
 * 【1. 为什么没有 @Transactional】
 *   这四个方法全是单条 SELECT，没有"读两次然后写"的组合。
 *   单个 SELECT 在 MySQL 默认的 REPEATABLE READ 下自带一致性快照，
 *   不需要额外的事务边界。
 *
 *   加 @Transactional 在这类只读方法上的代价是真实的：
 *     · 每次调用多一次事务开启/提交（多两次数据库往返）
 *     · 占用连接的时间变长
 *   所以**不加是对的选择**，而不是"忘了加"。
 *
 * 【2. 为什么 getByTrainNo 和 listStops 是两个方法，而不是一个返回 null 的方法】
 *   调用方（Controller）需要区分三种情况：
 *     a) 车次不存在            → 404（资源不存在）
 *     b) 车次存在但没有经停站  → 200 + 空数组（资源存在，内容为空）
 *     c) 车次存在且有经停站    → 200 + 数组
 *
 *   如果让 listStops 在"车次不存在"时返回 null，调用方就要靠
 *   "null 还是空数组"来区分 a 和 b —— 而这是**最容易被后续改动破坏的约定**：
 *   某个中间层顺手加一句 `orElse(List.of())` 或者用了
 *   `CollectionUtils.isEmpty()` 判断，两种情况就被合并了，
 *   于是 404 变成 200 + []，而**没有任何测试会失败**。
 *
 *   拆成两个方法后，a 由 getByTrainNo 明确回答，b/c 由 listStops 回答，
 *   每个方法的契约都是单义的、可读的。
 *
 *   代价是 Controller 里多一次查询。对一个只有 3 趟车的教学项目，
 *   用一次多余的查询换掉一个"靠约定维持"的隐式契约，划算。
 *   （真实高并发场景下这笔账要重算 —— 但那时应该用缓存，
 *   而不是把两个查询揉成一个语义模糊的查询。）
 *
 * 【3. 为什么用 LambdaQueryWrapper 查车次，而不是写一个 mapper 方法】
 *   "按 train_no 精确查一条"是标准的单表条件查询，
 *   MP 的 Wrappers 已经能完整表达，不需要 XML。
 *   **判据是"这个查询需不需要 JOIN 或聚合"，不需要就不写 SQL。**
 *   见 mapper/SeatInventoryMapper.java 里同样的判断。
 * =============================================================================
 */
@Service
public class TrainService {

    private final TrainMapper trainMapper;

    public TrainService(TrainMapper trainMapper) {
        this.trainMapper = trainMapper;
    }

    /**
     * 分页查询车次列表。
     *
     * @param pageNo 页码，从 1 开始。调用方已保证 ≥ 1
     * @param size   每页条数。调用方已保证 1 ≤ size ≤ 100
     */
    public PageResult<TrainListItem> pageTrains(long pageNo, long size) {
        /*
         * 这里新建的 Page 对象会被 MyBatis-Plus **原地填充**：
         * selectTrainPage 返回的就是传入的同一个对象（不是副本），
         * 所以下面直接用 page.getXxx() 读即可。
         *
         * ⚠️ 不要写成 `IPage<TrainListItem> result = trainMapper.selectTrainPage(page);`
         *    然后以为 result 和 page 是两个不同的对象 —— 它们是同一个引用。
         *    这个细节有实际影响：如果你新建两个 Page 分别传给两个查询，
         *    它们是独立的；但同一个 Page 对象被复用两次，第二次会覆盖第一次的结果。
         */
        Page<TrainListItem> page = new Page<>(pageNo, size);
        IPage<TrainListItem> result = trainMapper.selectTrainPage(page);

        /*
         * 【为什么用 result.getSize() 而不是直接用传入的 size 参数】
         * 分页插件可能**静默钳制**每页条数（PaginationInnerInterceptor.maxLimit，
         * 本项目配的是 100，见 config/MybatisPlusConfig.java）。
         * 如果这里回显调用方传进来的 size，当它被钳制时
         * 响应里会写 size=10000 但实际只返回 100 条 —— 对调用方来说是个谎。
         * 读回插件实际用的 size，响应才是自洽的。
         *
         * （本项目 Controller 上有 @Max(100)，超限会直接 400，
         *  所以这条路径当前走不到。但"回显请求值还是实际值"这个选择
         *  一旦写错，将来放宽校验时就会踩到。）
         */
        return new PageResult<>(
                result.getRecords(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize(),
                result.getPages());
    }

    /**
     * 按车次号查车次，查不到返回 {@code null}。
     *
     * <p>用 {@code selectOne} 是安全的：t_train 上有唯一索引 {@code uk_train_no}，
     * 数据库层面保证最多命中一行。没有这个唯一索引的话，selectOne 在数据脏了
     * 时会从"返回第一条"变成抛 TooManyResultsException。
     */
    public Train getByTrainNo(String trainNo) {
        return trainMapper.selectOne(
                Wrappers.<Train>lambdaQuery()
                        .eq(Train::getTrainNo, trainNo));
    }

    /**
     * 查询某车次的全部经停站，按站序升序。
     *
     * <p><b>调用方有责任先确认车次存在</b>（用 {@link #getByTrainNo}），
     * 否则"车次不存在"和"车次没有经停站"都会表现为空数组，无法区分。
     * 详见类注释 §2。
     */
    public List<TrainStationItem> listStops(String trainNo) {
        return trainMapper.selectStops(trainNo);
    }

    /**
     * 按出发站 / 到达站查可乘区间。
     *
     * <p>支持中途上车：不要求 {@code fromCode} 是始发站、
     * {@code toCode} 是终到站，只要求这趟车两站都停且顺序正确。
     *
     * <p>返回空列表的原因可能是"这条线路确实没有车"，
     * 也可能是"站码拼错了"。调用方应当先用 {@link StationService#getByCode}
     * 确认两个站码都存在，把后者变成 404 —— 见 TrainController 的处理。
     */
    public List<TrainSegmentItem> searchByStations(String fromCode, String toCode) {
        return trainMapper.selectByFromToStation(fromCode, toCode);
    }
}
