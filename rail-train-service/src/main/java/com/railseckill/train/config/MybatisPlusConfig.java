package com.railseckill.train.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>阶段 4 只注册一个插件：分页。
 *
 * <p><b>注意这是本项目第一个 {@code @Configuration} 类。</b>
 * 阶段 3 的 Mapper 扫描是用启动类上的 {@code @MapperScan} 完成的，
 * 不需要任何 Bean 定义；分页插件则必须显式注册——它是通过 MyBatis 的
 * 拦截器机制改写 SQL 的，没有"自动配置"能猜到你想不想要它。
 */
/*
 * =============================================================================
 *  这个类里几个"为什么"。大部分是实测/反编译确认过的，不是推测。
 * =============================================================================
 *
 * 【1. 为什么 PaginationInnerInterceptor 的 import 来自 jsqlparser 包，
 *      而 MybatisPlusInterceptor 来自 extension 包】
 *
 * MyBatis-Plus 3.5.9 起把 JSqlParser 拆成了独立依赖，随之拆的还有类的位置：
 *
 *   类名                                    所在 artifact                   包路径
 *   ─────────────────────────────────────  ──────────────────────────────  ─────────────────────────────────────────────
 *   MybatisPlusInterceptor                 mybatis-plus-extension         com.baomidou.mybatisplus.extension.plugins
 *   PaginationInnerInterceptor             mybatis-plus-jsqlparser        com.baomidou.mybatisplus.extension.plugins.inner
 *   Page                                   mybatis-plus-extension         com.baomidou.mybatisplus.extension.plugins.pagination
 *   IPage                                  mybatis-plus-core              com.baomidou.mybatisplus.core.metadata
 *   DbType                                 mybatis-plus-annotation        com.baomidou.mybatisplus.annotation
 *
 *   ⚠️ 注意 PaginationInnerInterceptor 的**包名前缀是 extension 没错**，
 *      但它的 .class 文件**不在** mybatis-plus-extension 里，在
 *      mybatis-plus-jsqlparser 里。所以 POM 里必须显式引入 jsqlparser
 *      （rail-train-service/pom.xml 里已经有，注释也写了原因）。
 *      只引 starter 的话：编译期就报"找不到符号 PaginationInnerInterceptor"，
 *      比运行时才炸好一些，但一样会浪费时间去猜该加哪个依赖。
 *
 * 【2. 为什么必须给 PaginationInnerInterceptor 传 DbType.MYSQL】
 *
 *   不传（用无参构造）也不会报错：插件内部会对每个查询做一次
 *   "当前数据库是什么方言"的探测，而那个探测**要拿一个数据库连接**。
 *   也就是说每次分页查询都会多一次方言判断的开销，且依赖连接池状态。
 *   明确传 DbType.MYSQL 就是告诉它"不用猜了，就是 MySQL"，这段逻辑直接跳过。
 *
 * 【3. 只暴露一个 @Bean，不要把 PaginationInnerInterceptor 单独注册】
 *
 *   ⚠️ 这是一个**很容易踩、且症状很奇怪**的坑：
 *
 *   MyBatis-Plus 3.5.17 自带一个自动配置 MybatisPlusInnerInterceptorAutoConfiguration，
 *   它上面写着 @ConditionalOnBean(InnerInterceptor.class) 和
 *   @ConditionalOnMissingBean(MybatisPlusInterceptor.class)。
 *   它的作用是把容器里**所有** InnerInterceptor 类型的 Bean 收集起来，
 *   塞进一个默认的 MybatisPlusInterceptor。
 *
 *   所以如果我们既 new 了一个 MybatisPlusInterceptor 注册成 Bean，
 *   又额外把 PaginationInnerInterceptor 注册成 Bean：
 *     · 我们自己的 MybatisPlusInterceptor 存在 → 自动配置因为
 *       @ConditionalOnMissingBean 而退让（这个分支是安全的）
 *     · 但那个裸的 PaginationInnerInterceptor Bean 仍然在容器里
 *   结果是 SqlSessionFactory 可能拿到两个插件实例，
 *   **分页被应用两次，SQL 里出现两个 LIMIT**（或 LIMIT 被拼错）。
 *
 *   所以：插件实例只在下面这个方法里 new，只以 MybatisPlusInterceptor 的形式暴露。
 *
 * 【4. ⭐ maxLimit 和 overflow 的取舍】
 *
 *   setMaxLimit(100L)
 *       限制单页最大条数。⚠️ 它是**静默钳制，不抛异常**：
 *       `?size=10000` 不会报错，只会被悄悄改成 100 并返回 200。
 *       对调用方来说这是个谎——它以为拿到了 10000 条。
 *
 *       所以本项目的**主防线不是它，是 Controller 上的 @Max(100)**：
 *       超限时直接返回 400，让调用方立刻知道参数不合法。
 *       这里的 maxLimit 是第二道防线，兜住那些绕过参数校验的调用路径
 *       （比如将来某个内部服务直接调 Service，不走 Controller）。
 *
 *   setOverflow(false)
 *       页码超出范围时的行为。false = 不溢出：
 *       `?page=999`（只有 1 页数据）返回 200 + 空数组 + 正确的 total，
 *       主查询被跳过（因为知道肯定没数据）。
 *       true = 钳制到最后一页，即 page=999 会返回最后一页的数据。
 *
 *       选 false 的理由：**返回空数组是诚实的**——"你要的第 999 页确实没有数据"。
 *       钳制到最后一页是友善的，但会让调用方的分页循环逻辑
 *       永远算不出"已经到底了"（每次请求都返回数据），
 *       写分页遍历脚本时容易变成死循环。
 *
 * 【5. ⭐ optimizeJoin 对本项目的分页查询完全无效（一个反直觉的实测结论）】
 *
 *   分页插件生成 COUNT 语句时会尝试"优化"：把 JOIN 剥掉，因为
 *   数总数的查询不需要 SELECT 出那些关联字段。
 *   optimizeJoin 默认是 true，看起来很划算。**但在本项目的查询上它是空转。**
 *
 *   它的实现逻辑是：遍历 SQL 里的 JOIN，**一旦遇到第一个非 LEFT JOIN，
 *   就整体放弃优化**（因为 INNER JOIN 会过滤行，剥掉就可能改变行数）。
 *
 *   而我们的车次分页查询用的正是 INNER JOIN（连 t_station 取站名），
 *   所以第一个 JOIN 就让它放弃了。结果是生成的 COUNT 语句带着全部 JOIN：
 *
 *       SELECT COUNT(*) FROM t_train
 *         JOIN t_station s1 ON ...
 *         JOIN t_station s2 ON ...          ← 这两个 JOIN 对计数是多余的
 *
 *   【所以要不要改成 LEFT JOIN 让它优化？不要。】
 *   改成 LEFT JOIN 后 optimizeJoin 会真的开始剥 JOIN，而它判断
 *   "这个 JOIN 能不能剥"的依据是"别名有没有出现在 WHERE 字符串里"，
 *   **它不知道被剥掉的 JOIN 会不会放大行数**。
 *   一旦某个被剥掉的 JOIN 是一对多（比如将来关联 t_train_station），
 *   COUNT 就会静默变小 → total 错误 → 前端算出的总页数错误。
 *   **没有异常，没有日志，只是数字不对。**
 *
 *   我们的查询连的是 t_station，两处都是按主键等值连接，剥了也不改变行数
 *   ——但那是**运气，不是设计**。用一个隐式的全表扫描，
 *   换一个"改天加一个一对多 JOIN 就会静默出错"的隐患，不值得。
 *
 *   【那 COUNT 的代价怎么办】
 *   阶段 4 的表里只有 3 趟车，这个 COUNT 的开销是 0。
 *   **等阶段 6 压测出真实数据，再决定要不要优化——现在优化就是编数字。**
 *   真到那一步，正确的做法是用 page.setCountId("...") 指向一条
 *   手写的、只做必要 JOIN 的 COUNT 语句，而不是依赖 optimizeJoin 的启发式。
 * =============================================================================
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册分页插件。
     *
     * <p>没有这个方法的话，{@code Page} 对象会被 MyBatis 当成普通参数忽略，
     * 查询**照样成功**，只是返回全部数据、且 {@code total} 恒为 0。
     * 也就是说：分页插件没生效不会报错，只会让分页默默失效
     * —— 又一个"看起来正常、其实不对"的失败模式。
     *
     * <p>这也是为什么 V10b（分页插件运行时验证）必须用真实调用来看，
     * 不能靠"依赖引入了"就认为它工作。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 明确方言，避免每次查询都去探测（见类注释 §2）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);

        // 第二道防线，主防线是 Controller 上的 @Max(100)（见类注释 §4）
        pagination.setMaxLimit(100L);

        // 页码越界返回空数组而不是钳到最后一页（见类注释 §4）
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
