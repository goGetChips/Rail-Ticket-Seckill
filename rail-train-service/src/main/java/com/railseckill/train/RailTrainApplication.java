package com.railseckill.train;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 车次服务的启动类。
 *
 * <p>这是本项目第一个可运行的 Spring Boot 应用。它刻意做得非常小——
 * 一个启动类 + 一条"查库"链路，目的是先证明**工程链路是通的**，
 * 再往里填业务。参见 README 的路线图：阶段 3 的完成判据就是
 * "应用能启动并查通一张表"。
 */
/*
 * 【@SpringBootApplication 到底是什么】
 * 它是一个组合注解，等价于下面三个：
 *
 *   @SpringBootConfiguration   —— 本质是 @Configuration，声明"这个类是配置类"
 *   @EnableAutoConfiguration   —— 触发自动配置。这是 Spring Boot 的灵魂：
 *                                 它扫描 classpath 上的 jar，按约定决定该装配什么。
 *                                 本例中它看到 spring-boot-starter-web 就启动内嵌 Tomcat，
 *                                 看到 mybatis-plus 和数据源配置就创建 SqlSessionFactory。
 *                                 **这就是为什么我们一行 XML/注解都不用写就能连库。**
 *   @ComponentScan             —— 从当前包（com.railseckill.train）开始向下扫描 @Component。
 *
 * 【@ComponentScan 的"向下"是个硬性约束，不是习惯】
 * 启动类所在包 com.railseckill.train 是扫描的根，它的**子包**才会被扫。
 * 如果哪天有人把某个 Service 放到 com.railseckill.common 下，那个类不会被扫描到——
 * 症状是启动时报 "No qualifying bean of type ..."，而代码看起来完全正常。
 * 所以：**启动类必须放在所有业务代码的最外层包**。
 */
@SpringBootApplication
/*
 * 【@MapperScan 与 @Mapper 二选一，这里为什么选 @MapperScan】
 *
 * 两种写法都能让 MyBatis 找到 Mapper 接口：
 *   A. 每个接口上写 @Mapper      —— 分散在每个文件里
 *   B. 在启动类上写 @MapperScan  —— 集中在一处
 *
 * 选 B 的理由：本项目每个服务的 Mapper 都固定放在 <服务包>.mapper 下，
 * 用一条规则覆盖一个包，比在十几个文件里各贴一个注解更好维护；
 * 而且"这个服务有哪些 Mapper 包"这件事在一个地方就能看到。
 *
 * ⚠️ 两者都不要写重，虽然重复了不会报错，但会让人误以为必须成对出现。
 *
 * 【为什么需要这一步：MyBatis 的 Mapper 是接口，没有实现类】
 * MyBatis 在运行时用 JDK 动态代理给每个 Mapper 接口生成实现类，并注册成 Spring Bean。
 * 没有 @MapperScan（或 @Mapper），这些接口就不会被打包成 Bean，
 * 注入时会报 "No qualifying bean of type 'StationMapper'"——
 * 而 StationMapper.java 明明就在那儿，这是初学阶段最常见的困惑之一。
 */
@MapperScan("com.railseckill.train.mapper")
public class RailTrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(RailTrainApplication.class, args);
    }
}
