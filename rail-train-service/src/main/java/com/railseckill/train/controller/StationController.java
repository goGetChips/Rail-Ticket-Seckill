package com.railseckill.train.controller;

import com.railseckill.train.entity.Station;
import com.railseckill.train.service.StationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车站查询接口。
 *
 * <pre>
 *   GET /api/train/stations          列出全部车站
 *   GET /api/train/stations/{code}   按电报码查单个车站
 * </pre>
 */
/*
 * =============================================================================
 *  这个类里几个"为什么"
 * =============================================================================
 *
 * 【1. 为什么路径是 /api/train/stations，而不是 /stations】
 * 前缀 /api/train 有两层含义：
 *   · /api      —— 和静态资源、健康检查（/actuator）区分开，
 *                  将来网关做路由时可以用一条 /api/** 规则覆盖全部业务接口
 *   · /train    —— 标识这个接口属于哪个服务。
 *                  现在只有一个服务，看不出价值；阶段 8 有了网关，
 *                  就会变成 /api/train/** → rail-train-service 的路由规则。
 *                  提前定好命名，阶段 8 的网关配置是机械的，不需要再争论路径该怎么起名。
 *
 * 【2. 为什么用 @RestController 而不是 @Controller + @ResponseBody】
 * @RestController = @Controller + @ResponseBody。
 * 返回值会被 Jackson 序列化成 JSON 直接写进响应体，
 * 而不是被当成"视图名"去找模板文件。
 * 忘了加 @ResponseBody 的症状是：返回一个字符串，浏览器报 404 或下载一个文件，
 * 而不是看到 JSON —— 因为 Spring 拿着 "list" 这个字符串去找叫 list 的页面了。
 *
 * 【3. 为什么这个阶段没有统一的 Result<T> 响应包装】
 * 很多项目会约定所有接口返回 { code: 0, msg: "ok", data: ... }。
 * 本阶段**刻意不引入**，原因：
 *   · 秒杀场景下，"成功/失败"必须能和 HTTP 状态码对上，
 *     否则网关、负载均衡、监控都读不懂业务是否成功
 *   · 统一的 code 枚举一旦没有错误码规范支撑（docs/api/ 还没写），
 *     会先长出一堆随手写的 code，反而更难统一
 * 现在用最朴素的"HTTP 语义 + 原始数据"，
 * 等阶段 4 真正出现多种错误场景时，再写 docs/api/error-codes.md 并引入包装。
 * **顺序反过来做，规范就是编出来的。**
 * =============================================================================
 */
@RestController
@RequestMapping("/api/train/stations")
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    /**
     * 列出全部车站。
     *
     * <p>返回 {@code List<Station>} 时，Spring 会把它交给 Jackson 序列化成 JSON 数组。
     * 查不到任何数据时返回的是空数组 {@code []}，**不是 null**——
     * MP 的 selectList 保证返回非 null 集合。这一点很重要：
     * 如果返回 null，序列化出来是 {@code null}，前端遍历时会直接报错。
     */
    @GetMapping
    public List<Station> listAll() {
        return stationService.listAll();
    }

    /**
     * 按电报码查询单个车站，如 {@code GET /api/train/stations/BJP}。
     *
     * <p>查不到时返回 <b>404</b> 而不是 200 + 空对象。
     *
     * <p>这是 HTTP 语义的重要区别：
     *   · 404 表示"这个资源不存在"，调用方（网关、监控、前端）一看就懂
     *   · 200 + null 表示"请求成功了，内容就是空的"，调用方需要解析响应体才知道失败了
     * 在微服务里这个差别会被放大：网关的重试、熔断、监控告警都依赖状态码。
     * **把语义编码进状态码，等于让所有基础设施免费获得了这个信息。**
     *
     * <p>用 {@code ResponseEntity} 而不是在方法里返回 null，
     * 是因为 null 的语义已经被"没查到"占用了，需要一个额外的通道来表达状态码。
     */
    @GetMapping("/{stationCode}")
    public ResponseEntity<Station> getByCode(@PathVariable String stationCode) {
        Station station = stationService.getByCode(stationCode);
        return station != null ? ResponseEntity.ok(station) : ResponseEntity.notFound().build();
    }
}
