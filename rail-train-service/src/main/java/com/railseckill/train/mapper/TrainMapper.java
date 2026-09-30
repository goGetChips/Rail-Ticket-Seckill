package com.railseckill.train.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.railseckill.train.dto.TrainListItem;
import com.railseckill.train.dto.TrainSegmentItem;
import com.railseckill.train.dto.TrainStationItem;
import com.railseckill.train.entity.Train;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 车次相关的数据访问。
 *
 * <p>{@code StationMapper} 是个空接口（继承 {@code BaseMapper} 就够用了），
 * 这个接口不一样：三个方法都需要 JOIN，**单表 CRUD 生成不出来**，
 * 所以方法声明在这里、SQL 写在 {@code resources/mapper/TrainMapper.xml}。
 *
 * <p>SQL 与本文档的对应关系见 XML 文件。
 */
/*
 * =============================================================================
 *  ⭐ 一个必须知道的约束：每个参数都要写 @Param
 * =============================================================================
 *
 *   【不写会怎样】
 *   报错，而且是运行时才报：
 *
 *       org.apache.ibatis.binding.BindingException:
 *       Parameter 'trainNo' not found. Available parameters are [param1, param2]
 *
 *   【为什么】
 *   MyBatis 需要知道"#{trainNo} 对应哪个 Java 参数"。
 *   它有两种取名方式：
 *     · 编译时带 -parameters（Spring Boot 的父 POM 开了这个）
 *       → 能拿到真实的参数名，理论上不写 @Param 也行
 *     · 但 MyBatis 的 ParamNameResolver 在**参数多于一个、或存在特殊参数时**
 *       会退回到 param1 / param2 这种位置名
 *
 *   **分页方法尤其危险**：IPage 类型的参数也会占一个位置编号，
 *   于是 offset 全部后移，XML 里写的名字和实际可用名字对不上。
 *
 *   【所以本项目的规矩很简单】
 *   **XML 里用到的每一个参数，都在接口上写 @Param，一个不漏。**
 *   不依赖"编译器参数名"这个隐式条件 —— 换 IDE、换编译配置、
 *   甚至换 Maven 版本都可能影响它，而症状是运行时报"参数找不到"。
 *   显式写出来只多几个字符，换来的是不依赖构建配置。
 *
 * =============================================================================
 *  ⚠️ 【XML 里绝对不要引用分页参数本身】
 * =============================================================================
 *   selectTrainPage 的 page 参数**不参与 SQL 绑定**。
 *   它被 PaginationInnerInterceptor 拦截器取走，用来：
 *     · 生成 COUNT 语句
 *     · 给原始 SQL 拼上 LIMIT
 *   如果你在 XML 里写 `#{page}` 或 `#{page.size}`，会报参数找不到 ——
 *   因为那个参数根本不在 SQL 的参数映射里。
 *
 *   **分页方法的 XML 看起来和普通查询一模一样**，
 *   没有任何"分页"的痕迹。分页是拦截器加的，不是写在 SQL 里的。
 *   第一次看会觉得不习惯，但这样 SQL 本身是干净的、
 *   可以直接复制到命令行执行的。
 *
 * =============================================================================
 *  【为什么三个方法都写在这个 Mapper 里，不按表拆】
 * =============================================================================
 *   这三个查询都是"以车次为主体"的读操作，虽然 SQL 里连了
 *   t_station / t_train_station，但**它们不是那两张表的 CRUD**。
 *
 *   本项目的 Mapper 划分依据是**聚合根**（谁是查询的主体），不是
 *   "SQL 里出现了哪张表"。否则每加一个 JOIN 就要纠结该放哪个 Mapper，
 *   最后变成"每个 Mapper 里都有一半别人的查询"。
 *
 *   ⚠️ 注意本项目**没有** TrainStation 实体，也没有 TrainStationMapper：
 *   t_train_station 只作为 JOIN 的中间表出现，从不作为查询主体，
 *   也从不被单独增删改。为它建一个实体是纯粹的负担 —— 一个永远不会被
 *   单独使用的映射类，只会在读代码时制造"它是不是别处也在用"的疑问。
 *   需要写经停站数据的是 sql/10_seed_train.sql，不是 Java 代码。
 * =============================================================================
 */
public interface TrainMapper extends BaseMapper<Train> {

    /**
     * 分页查询车次列表，带始发站和终到站的站码与站名。
     *
     * <p><b>返回值必须声明成 {@code IPage<T>}（或它的子类型），不能是 {@code List<T>}。</b>
     * 声明成 List 的话查询能跑、数据也对，但**总条数会丢失** ——
     * 分页插件执行的那次 COUNT 结果无处可放，直接扔掉，
     * 于是前端拿不到 total，算不出总页数。不报错，只是少了个数字。
     *
     * <p>注意**不需要**在 XML 里写任何分页相关的东西，见类注释。
     *
     * @param page 分页参数。传入的对象会被**原地**填充 records 和 total
     *             —— MyBatis-Plus 返回的就是同一个对象（不是副本），
     *             所以调用方不用接收返回值也能拿到数据。
     *             这里仍然声明返回 IPage，是为了让方法签名自解释。
     */
    IPage<TrainListItem> selectTrainPage(Page<TrainListItem> page);

    /**
     * 查询某车次的全部经停站，按站序升序。
     *
     * <p>查不到车次或该车次没有经停站数据时返回**空列表**（不是 null）——
     * MyBatis 对 List 返回值保证非 null。
     * "车次不存在"和"车次存在但没有经停站"是两种不同的情况，
     * 由 Service 层先查车次是否存在来区分，Mapper 这一层不做这个判断。
     */
    List<TrainStationItem> selectStops(@Param("trainNo") String trainNo);

    /**
     * 按出发站 / 到达站查可乘区间。
     *
     * <p>返回的每一行是"某趟车的 from → to 这一段"，
     * 支持中途上车（不要求 from 是始发站、to 是终到站）。
     *
     * <p>只返回 {@code status = 1}（正常）的车次，停运车次被排除。
     *
     * @param fromCode 上车站电报码
     * @param toCode   下车站电报码
     */
    List<TrainSegmentItem> selectByFromToStation(@Param("fromCode") String fromCode,
                                                @Param("toCode") String toCode);
}
