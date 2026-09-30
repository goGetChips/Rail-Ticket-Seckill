package com.railseckill.train.controller;

import com.railseckill.train.dto.PageResult;
import com.railseckill.train.dto.TrainListItem;
import com.railseckill.train.dto.TrainSegmentItem;
import com.railseckill.train.dto.TrainStationItem;
import com.railseckill.train.service.StationService;
import com.railseckill.train.service.TrainService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车次查询接口。
 *
 * <pre>
 *   GET /api/train/trains?page=1&amp;size=10        车次分页列表
 *   GET /api/train/trains/{trainNo}/stations    某车次的经停站
 *   GET /api/train/trains/search?from=&amp;to=     按出发站 / 到达站查可乘区间
 * </pre>
 *
 * <p>余票查询在 {@link InventoryController}，路径前缀是 {@code /api/inventory}
 * 而不是 {@code /api/train} —— 那里有解释。
 */
/*
 * =============================================================================
 *  【1. 为什么 @RequestMapping("/api/train/trains") 看起来在重复 "train"】
 *
 *   路径结构是 /api/{服务}/{资源}：
 *     · /api/train/stations   → 车次服务下的"车站"资源（阶段 3 已有）
 *     · /api/train/trains     → 车次服务下的"车次"资源（本类）
 *     · /api/inventory/seats  → 库存服务下的"余票"资源（阶段 4 新增）
 *
 *   中间的 train 标识**服务归属**，不是资源名。阶段 8 有了网关，
 *   它会变成一条 /api/train/** → rail-train-service 的路由规则。
 *
 *   确实有点绕。但改成 /api/trains 会让"哪个服务拥有这个接口"
 *   只存在于网关配置里，而这个类本身看不出归属。
 *   在微服务语境下，**路径里带上服务名是有信息的**，不是冗余。
 *
 * =============================================================================
 *  ⭐ 【2. 为什么这里没有 @Validated —— 这是本类最容易"顺手加上"的错误】
 *
 *   很多人会顺手在类上写 @Validated，觉得"要校验就得加它"。**不要加。**
 *
 *   加了之后，校验不再走 Spring MVC 内建的那套，而是走 Spring AOP 代理，
 *   异常类型从 HandlerMethodValidationException 变成
 *   jakarta.validation.ConstraintViolationException。后果：
 *     · 错误信息里的参数名变成 "arg0"（AOP 拿不到真实参数名）
 *     · propertyPath 带方法名前缀 "pageTrains.size"，
 *       把内部方法名暴露给了 API 调用方
 *     · Controller 多一层 CGLIB 代理，排查问题时多一个变量
 *
 *   不加的话，抛 HandlerMethodValidationException，
 *   它的 getParameterValidationResults() 能拿到真实的参数名 "size"。
 *   完整对比见 exception/ApiExceptionHandler.java 的类注释。
 *
 * =============================================================================
 *  【3. @Min / @Max 上为什么要写 message】
 *
 *   Jakarta 约束的 message 默认是英文模板（"{jakarta.validation.constraints.Min.message}"），
 *   会被解析成 "must be greater than or equal to 1" 这种英文。
 *   本项目是中文文档 + 中文注释的项目，接口报错却是英文，读起来割裂。
 *   显式写中文 message 只多几个字符，换来错误响应是可读的。
 *
 * =============================================================================
 *  【4. 为什么校验注解直接写在 @RequestParam 上，而不是定义一个查询 DTO】
 *
 *   分页参数只有两个，且只在列表接口用一次。定义一个 PageQuery 类
 *   会多一个文件，还要多一次对象绑定。
 *
 *   ⚠️ 但如果参数继续增加（加日期、加车次类型、加出发站筛选），
 *     就应该改成 DTO + @Valid。判据是**参数多到"这个方法在做什么"
 *      不再一眼可见的时候**，而不是"参数超过两个就必须改"。
 *     现在两个参数，直接写更清楚。
 * =============================================================================
 */
@RestController
@RequestMapping("/api/train/trains")
public class TrainController {

    private final TrainService trainService;
    private final StationService stationService;

    public TrainController(TrainService trainService, StationService stationService) {
        this.trainService = trainService;
        this.stationService = stationService;
    }

    /**
     * 车次分页列表。
     *
     * <pre>
     *   GET /api/train/trains?page=1&amp;size=10
     *   → {"records":[...],"total":3,"page":1,"size":10,"pages":1}
     * </pre>
     *
     * <p>对应阶段 4 的第一项待做，同时也是 V10b（分页插件运行时验证）——
     * 分页插件没生效时接口照样返回 200，只是 {@code total} 恒为 0、
     * 返回全部数据。**必须核对 total 的值才能确认它真的在工作。**
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，上限 100
     */
    @GetMapping
    public PageResult<TrainListItem> page(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码必须大于等于 1")
            long page,

            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数必须大于等于 1")
            @Max(value = 100, message = "每页条数不能超过 100")
            long size) {
        /*
         * ⚠️ @Min(1) 在 size 上是**承重的，不是装饰**，这一点反直觉：
         *
         *   `?size=0` 不会报错，会生成一条 `LIMIT 0` 的 SQL，
         *   并且**照样白跑一次 COUNT**（分页插件的 searchCount 默认开的），
         *   然后返回 200 + 空数组。
         *
         *   而分页插件的 maxLimit 钳制条件写的是 `size > limit || size < 0`，
         *   **它接不住 0**（0 既不大于上限也不小于 0）。
         *   所以 0 这条路径只能靠 @Min(1) 拦住。
         *
         *   这也是为什么验证清单里必须有一条 `?size=0` 期望 400——
         *   如果返回 200，说明 spring-boot-starter-validation 没生效，
         *   而依赖缺失**不会报任何错**。
         */
        return trainService.pageTrains(page, size);
    }

    /**
     * 某车次的全部经停站，按站序升序。
     *
     * <pre>
     *   GET /api/train/trains/G1/stations
     *   → [{"stationOrder":1,"stationCode":"VNP","stationName":"北京南",...}, ...]
     * </pre>
     *
     * <p><b>首站的 {@code arriveTime} 和末站的 {@code departTime} 是 null</b>，
     * 这是正常建模而不是缺数据，前端要处理。理由见
     * {@link TrainStationItem} 的类注释。
     *
     * <p>车次不存在时返回 <b>404</b>；车次存在但没有经停站记录时返回
     * <b>200 + 空数组</b>。这两个分支的区别很重要 ——
     * "这个车次不存在"和"这个车次存在但没停站"对调用方的意义完全不同。
     */
    @GetMapping("/{trainNo}/stations")
    public ResponseEntity<List<TrainStationItem>> stops(@PathVariable String trainNo) {
        /*
         * 【为什么这里要先查一次车次，而不是直接查经停站】
         * 直接查经停站的话，两种情况的返回值都是空数组，无法区分。
         * 详见 service/TrainService.java 的类注释 §2。
         *
         * 代价是多一次查询。对只有 3 趟车的教学项目，用一次查询换掉
         * 一个"靠约定维持的空数组语义"，划算。
         */
        if (trainService.getByTrainNo(trainNo) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trainService.listStops(trainNo));
    }

    /**
     * 按出发站 / 到达站查可乘区间。
     *
     * <pre>
     *   GET /api/train/trains/search?from=VNP&amp;to=AOH   北京南 → 上海虹桥
     *   GET /api/train/trains/search?from=JNK&amp;to=NJH   济南西 → 南京南（中途上车）
     * </pre>
     *
     * <p><b>支持中途上车</b>：不要求 from 是始发站、to 是终到站，
     * 只要求这趟车两站都停且顺序正确。
     *
     * <p>⚠️ <b>"可售"目前只意味着"区间可达"，不检查余票</b>——
     * 库存是按日期的，而这个查询没有日期参数。
     * 这是一个已知的语义缺口，完整说明见 {@link TrainSegmentItem} 的类注释。
     *
     * <p>站点电报码<b>区分大小写</b>（库里的值是 VNP 这种大写）。
     * 这里刻意不做自动转大写：阶段 3 的
     * {@code /api/train/stations/{code}} 就是精确匹配，
     * 两个接口对同一个参数采用不同的大小写策略比统一更糟。
     *
     * <p>{@code from} 与 {@code to} 相同时返回空数组而不是报错 ——
     * SQL 里的 {@code ts1.station_order < ts2.station_order} 天然排除了
     * "从某站到它自己"这种组合，不需要额外判断。
     *
     * @param from 上车站电报码，3 位
     * @param to   下车站电报码，3 位
     */
    @GetMapping("/search")
    public ResponseEntity<List<TrainSegmentItem>> search(
            @RequestParam
            @NotBlank(message = "出发站不能为空")
            @Size(min = 3, max = 3, message = "车站电报码必须是 3 位")
            String from,

            @RequestParam
            @NotBlank(message = "到达站不能为空")
            @Size(min = 3, max = 3, message = "车站电报码必须是 3 位")
            String to) {

        /*
         * 【为什么要先确认两个站都存在】
         * 不确认的话，「站码拼错了」和「这条线路确实没车」都返回 200 + []。
         * 调用方无法区分"我打错字了"和"这条线路没车"，
         * 而这两种情况该采取的行动完全不同。
         *
         * 拼错站码是**客户端错误**，用 404 表达是准确的
         * ——和 /api/train/stations/NOPE 返回 404 是同一个语义。
         */
        if (stationService.getByCode(from) == null || stationService.getByCode(to) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trainService.searchByStations(from, to));
    }
}
