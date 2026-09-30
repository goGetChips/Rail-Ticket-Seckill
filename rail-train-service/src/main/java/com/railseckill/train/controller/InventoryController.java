package com.railseckill.train.controller;

import com.railseckill.train.dto.SeatAvailability;
import com.railseckill.train.entity.Train;
import com.railseckill.train.service.InventoryService;
import com.railseckill.train.service.TrainService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 余票查询接口。
 *
 * <pre>
 *   GET /api/inventory/seats?trainNo=G1&amp;date=2026-09-30
 * </pre>
 *
 * <p><b>🔴 这个类住在 rail-train-service 里，但它的路径前缀是
 * {@code /api/inventory} —— 这是刻意的，理由见下方注释 1。</b>
 */
/*
 * =============================================================================
 *  ⭐ 【1. 为什么代码在 train-service，路径却是 /api/inventory】
 *
 *   阶段 8 要把库存代码整体搬去 rail-inventory-service（8083）。
 *   搬的时候有一个选择：路径要不要跟着改？
 *
 *   · 如果现在叫 /api/train/seats，搬家后必须改成 /api/inventory/seats
 *     → **对客户端是破坏性变更**。所有调过这个接口的地方都要改，
 *       而且改的时机取决于服务端什么时候重构 —— 这是最糟糕的耦合：
 *       "服务端内部重构"逼着"客户端改代码"。
 *
 *   · 现在就叫 /api/inventory/seats，搬家时**路径不用变**，
 *     只需要在网关加一条路由规则 /api/inventory/** → rail-inventory-service。
 *     → **客户端完全无感。**
 *
 *   代价是当前代码位置和路径前缀对不上，读代码的人会困惑。
 *   所以这里必须写清楚 —— **否则下一个人会以为这是 bug 并"修好"它。**
 *
 *   这正是"面向接口而非实现"在 API 设计上的体现：
 *   路径表达的是**资源的所有权归属**（终态由 inventory-service 拥有），
 *   而不是"当前哪段代码在处理它"。
 *
 * =============================================================================
 *  【2. 为什么 date 是必填，而车次列表接口不需要日期】
 *
 *   这是库存数据的粒度决定的：t_seat_inventory 的业务主键是
 *   (train_id, travel_date, seat_type) —— **同一趟车每一天的库存是独立的行**。
 *
 *   所以"G1 的余票"这个问题本身没有答案，必须问"G1 哪天"。
 *   把 date 做成可选、默认今天，看着更友好，但会制造一个隐患：
 *   调用方以为自己在查"这趟车的余票"，实际查的是"今天的余票"，
 *   而两三天后行为悄悄变了（因为"今天"变了）。
 *   查询类接口**宁可让调用方多写一个参数，也不要替他猜**。
 *
 * =============================================================================
 *  【3. 为什么参数名是 trainNo 而不是 trainId】
 *
 *   对外一律用业务标识（车次号 G1），不用自增主键。
 *   自增 id 是实现细节：重跑一次种子脚本它就会变（10_seed_train.sql 里有
 *   ALTER TABLE ... AUTO_INCREMENT = 1），而车次号是稳定的、人可读的、
 *   能直接拿去 12306 查的。**把主键暴露给外部，就等于承诺它稳定。**
 * =============================================================================
 */
@RestController
@RequestMapping("/api/inventory/seats")
public class InventoryController {

    private final InventoryService inventoryService;
    private final TrainService trainService;

    public InventoryController(InventoryService inventoryService, TrainService trainService) {
        this.inventoryService = inventoryService;
        this.trainService = trainService;
    }

    /**
     * 查询某车次某天的全部席别余票，按席别升序（商务座 → 一等座 → 二等座）。
     *
     * <pre>
     *   GET /api/inventory/seats?trainNo=G1&amp;date=2026-09-30
     *   → [{"seatType":1,"price":1748.00,"totalCount":20,"soldCount":5,"remaining":15},
     *      {"seatType":2,"price":933.00, "totalCount":100,"soldCount":30,"remaining":70},
     *      {"seatType":3,"price":553.00, "totalCount":500,"soldCount":200,"remaining":300}]
     * </pre>
     *
     * <p><b>空数组的意思是"这一天还没有放票"，不是"票卖完了"。</b>
     * 票卖完时该席别的 {@code remaining} 是 0，行本身仍然返回。
     * 这两个状态对用户的意义不同（前者"过几天再来"，后者"换别的车次"），
     * 所以不能用空数组统一表达。
     *
     * <p>车次不存在时返回 <b>404</b> —— 与车次查询接口的语义保持一致。
     *
     * @param trainNo 车次号，如 G1
     * @param date    乘车日期，ISO 格式 {@code yyyy-MM-dd}
     */
    @GetMapping
    public ResponseEntity<List<SeatAvailability>> seats(
            @RequestParam
            @NotBlank(message = "车次号不能为空")
            @Size(max = 20, message = "车次号长度不能超过 20")
            String trainNo,

            /*
             * 【@DateTimeFormat 为什么必须显式写】
             *
             * Spring 其实**能**把 "2026-09-30" 解析成 LocalDate，
             * 但那是靠一层 fallback 解析器兜住的。主格式化器仍然是
             * 按 locale 派生的（因为 WebConversionService 没有开启 ISO 模式），
             * 也就是说得益于 JVM 的 locale 恰好是 zh-CN 才解析得对。
             *
             * 显式声明 ISO 之后，解析规则不再依赖运行环境的 locale。
             * 在一台 locale 不是 zh-CN 的机器上（比如容器里默认 C locale），
             * 依赖 fallback 的代码可能就会出问题，而**本机测不出来**。
             *
             * 这类"依赖环境默认值"的坑，本项目在字符集、时区上已经踩过三次了
             * （见 docs/04-technology.md 第六节），所以这里一律显式。
             */
            @RequestParam
            @NotNull(message = "乘车日期不能为空")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {

        /*
         * 先确认车次存在（复用 TrainService，不重复实现"按车次号查车次"）。
         * 理由和 TrainController 里一样：让"车次不存在"变成一个明确的 404，
         * 而不是和"这天没放票"混在同一个空数组里。
         *
         * 拿到 Train 实体后传给 InventoryService，避免在那边再查一次
         * —— 为什么传实体而不是 trainNo，见 InventoryService 的类注释。
         */
        Train train = trainService.getByTrainNo(trainNo);
        if (train == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(inventoryService.listSeats(train, date));
    }
}
