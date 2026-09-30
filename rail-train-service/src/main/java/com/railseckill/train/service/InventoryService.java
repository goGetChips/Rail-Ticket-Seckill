package com.railseckill.train.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.railseckill.train.dto.SeatAvailability;
import com.railseckill.train.entity.SeatInventory;
import com.railseckill.train.entity.Train;
import com.railseckill.train.mapper.SeatInventoryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 余票查询。
 *
 * <p><b>🔴 这个类住在 train-service，但它读的是 rail_inventory 的库存表。
 * 阶段 8 要整体迁往 {@code rail-inventory-service}。</b>
 *
 * @see SeatInventory 实体的类注释里有这次跨库读取的完整说明
 */
/*
 * =============================================================================
 *  【为什么阶段 4 就把库存查询写在这里，而不是等阶段 8 拆出 inventory-service】
 * =============================================================================
 *   阶段 4 的目标是"查询接口全部走通"。余票是查票链路上的一环
 *   （用户先查车次，再查这趟车明天还有没有票），缺了它这条链路是断的。
 *
 *   为一个查询提前拆出一个微服务，代价是整个阶段 8 的工作量
 *   （模块、端口、注册中心、网关路由、跨服务调用）——
 *   而阶段 8 的拆分需要有**真实的业务代码**作为拆分对象，
 *   现在拆的话，拆的是一个空壳。
 *
 *   所以顺序是：先在单体里把业务写完整 → 阶段 8 按数据所有权切开。
 *   这条路也让"拆分"这件事有了可对比的基线 ——
 *   阶段 8 能回答"拆之前一次查询走几条 SQL，拆之后走了几条"。
 *
 * =============================================================================
 *  ⚠️ 【这个服务对库存是只读的，而且是强制约定】
 * =============================================================================
 *   SeatInventoryMapper 继承了 BaseMapper，技术上有 update 方法；
 *   账号 rail 权限上也允许写。**唯一阻止这里写库存的是约定。**
 *
 *   为什么必须只读：库存的写入者只能是"扣减库存的那条链路"
 *   （阶段 5 是 MySQL 条件 UPDATE，阶段 7 起是 Redis + 异步落库）。
 *   如果余票查询所在的类也能改库存，那么"库存只增不减、每次变动都有流水"
 *   这个不变式就多了一个不受控的写入点——
 *   而它**不会立刻出错**，只会在某次对账时表现为"数字对不上"。
 *
 *   见 mapper/SeatInventoryMapper.java 里为什么连"写方法"都没收窄的说明。
 *
 * =============================================================================
 *  【为什么方法参数是 Train 实体，而不是 trainNo 字符串】
 * =============================================================================
 *   t_seat_inventory 里存的是 train_id，而这个接口对外用的是 trainNo。
 *   所以必须有一步"trainNo → train_id"的解析，问题是这一步放在哪。
 *
 *   放进这个类的话，它就要依赖 TrainService 或 TrainMapper，
 *   于是 InventoryService 同时知道了车次库和库存库两张表的事，
 *   阶段 8 迁移时要拆的东西更多。
 *
 *   现在这一步留在 Controller：Controller 本来就负责"车次存不存在 → 404"
 *   这个 HTTP 语义判断，顺手把解析好的 Train 传进来，自然且没有重复查询。
 *   InventoryService 只关心"给定一个 train_id 和日期，库存是多少"。
 *
 *   【为什么不用 train_id 作为参数类型】
 *   那样 Controller 就要自己去摸 train.getId()，
 *   而"库存查询需要车次的哪个字段"就泄漏到了 Controller。
 *   传实体则明确表达"这个查询需要一辆真实存在的车"。
 * =============================================================================
 */
@Service
public class InventoryService {

    private final SeatInventoryMapper seatInventoryMapper;

    public InventoryService(SeatInventoryMapper seatInventoryMapper) {
        this.seatInventoryMapper = seatInventoryMapper;
    }

    /**
     * 查询某车次某一天的全部席别余票，按席别升序（商务座 → 一等座 → 二等座）。
     *
     * <p>返回空列表的意思是"这一天还没有放票"（t_seat_inventory 里没有对应行），
     * <b>不是</b>"票卖完了"。票卖完时该行的 {@code remaining} 是 0，行本身仍然在。
     *
     * <p>这两种情况对用户的意义完全不同：
     * "还没放票"是"过几天再来看看"，"已售罄"是"换别的车次吧"。
     * 把它们都表达成空数组是**丢失信息**——所以这里返回的是带 remaining 的对象列表，
     * 让调用方自己区分。
     *
     * @param train 已经确认存在的车次
     * @param date  乘车日期
     */
    public List<SeatAvailability> listSeats(Train train, LocalDate date) {
        /*
         * 【为什么用 lambda 而不是字符串列名】
         * eq("train_id", ...) 这种写法里，列名拼错编译能过，运行时返回空结果——
         * 看起来和"这一天还没放票"一模一样。用 lambda 方法引用，
         * 字段名错误直接编译失败。
         *
         * 【为什么必须写 ORDER BY seat_type】
         * 不写 ORDER BY 时 MySQL 不保证返回顺序。当前数据量下它多半会按
         * 主键（也就是插入顺序）返回，看起来"总是商务座在前"。
         * 但这是**未定义行为**：优化器改走 uk_train_date_seat 索引后，
         * 顺序会变成按索引顺序，于是前端的席位列表顺序莫名变化。
         * 不报错、只在界面上表现为"顺序乱了"。所以只要对顺序有要求就必须显式排序。
         */
        List<SeatInventory> rows = seatInventoryMapper.selectList(
                Wrappers.<SeatInventory>lambdaQuery()
                        .eq(SeatInventory::getTrainId, train.getId())
                        .eq(SeatInventory::getTravelDate, date)
                        .orderByAsc(SeatInventory::getSeatType));

        /*
         * 【为什么用 stream().map().toList() 而不是 for 循环】
         * 这里的动作就是"逐行转换"，没有副作用、没有提前退出、没有累计状态，
         * 用流表达更直接。
         *
         * ⚠️ 注意用的是 toList()（Java 16+），它返回**不可变**列表。
         *    好处是调用方误改会立刻抛异常，而不是悄悄改坏数据；
         *    坏处是如果哪天需要往结果里 add 东西，这里会抛
         *    UnsupportedOperationException —— 那时应该新建一个 ArrayList，
         *    而不是把 toList() 换成 collect(Collectors.toList()) 来消掉异常。
         */
        return rows.stream()
                .map(this::toAvailability)
                .toList();
    }

    /**
     * 实体 → 对外 DTO。
     *
     * <p>这是"库存实体"到"余票"的唯一转换点。把 remaining 的计算放在这里
     * 而不是 SQL 里的理由，见 {@link SeatAvailability} 的类注释。
     */
    private SeatAvailability toAvailability(SeatInventory row) {
        return new SeatAvailability(
                row.getSeatType(),
                row.getPrice(),
                row.getTotalCount(),
                row.getSoldCount(),
                // 表上有 CHECK 约束 sold_count <= total_count，
                // 所以这里不可能为负。刻意不写 Math.max(0, ...)：
                // 真算出负数说明约束被绕过或数据被手工改坏了，
                // 那种情况应该显形，不该被一行 max() 掩盖。
                row.getTotalCount() - row.getSoldCount());
    }
}
