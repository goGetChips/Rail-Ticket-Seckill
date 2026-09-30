package com.railseckill.train.exception;

import com.railseckill.train.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Spring MVC 抛出的各类异常统一成 {@link ApiError}。
 *
 * <p>只影响**失败**响应；成功响应仍然是"HTTP 200 + 原始数据"，没有包装。
 *
 * @see ApiError 里解释了为什么这不算"引入统一响应体"
 */
/*
 * =============================================================================
 *  ⭐ 【为什么是"继承 ResponseEntityExceptionHandler"而不是写一堆 @ExceptionHandler】
 * =============================================================================
 *
 *   写法 A（很多教程里的写法）：
 *       @ExceptionHandler(MethodArgumentNotValidException.class)
 *       @ExceptionHandler(MissingServletRequestParameterException.class)
 *       @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
 *       ... 每种异常写一个方法
 *
 *   问题：Spring MVC 内置的异常类型有 20 多种，**你只能覆盖你想到的那些**。
 *       @ExceptionHandler 写在 @RestControllerAdvice 上会**抢占**默认处理，
 *       没覆盖到的那些就落到 Spring Boot 的默认 /error 处理上，
 *       于是同一个接口的 400 有两种不同的响应体形状 ——
 *       取决于抛的是哪种异常。调用方无法统一解析。
 *
 *   写法 B（本项目）：
 *       继承 ResponseEntityExceptionHandler。Spring MVC 内部把所有内置异常
 *       都收敛到**一个**方法上：
 *
 *           protected ResponseEntity<Object> handleExceptionInternal(
 *                   Exception ex, Object body, HttpHeaders headers,
 *                   HttpStatusCode statusCode, WebRequest request)
 *
 *       只要覆盖它，**所有**内置异常自动统一成形，包括将来新增的、我没想到的。
 *
 *   【为什么是 handleExceptionInternal 而不是 handleException】
 *   handleException 是 `public final` 的，**不能覆盖**。
 *   handleExceptionInternal 才是 Spring 为子类留的那个缝。
 *
 *   ⚠️ 重建响应时必须**把 headers 带回去**。
 *      有些异常会在 headers 里放东西（最典型的是 405 Method Not Allowed
 *      会带 Allow 头，告诉调用方这个路径支持哪些方法）。
 *      丢掉 headers 不会报错，只会让响应少一些信息，
 *      而调用方据此判断"能不能换个方法重试"时就拿不到答案了。
 *
 * =============================================================================
 *  ⭐ 【校验失败抛的是 HandlerMethodValidationException，不是 ConstraintViolationException】
 * =============================================================================
 *   这个区别很容易搞错，而搞错的后果是**500 而不是 400**。
 *
 *   两种机制：
 *     · 类上标了 @Validated → 走 Spring AOP 代理 → 抛
 *       jakarta.validation.ConstraintViolationException
 *     · 类上**没有** @Validated → 走 Spring MVC 内建的参数校验 → 抛
 *       org.springframework.web.method.annotation.HandlerMethodValidationException
 *
 *   本项目用后者（Controller 上刻意**不加** @Validated），理由：
 *     · 前者的 ConstraintViolation.getPropertyPath() 带**方法名前缀**，
 *       报出来是 "pageTrains.size" 而不是 "size"，对 API 调用方来说
 *       方法名是内部实现细节，不该出现在错误信息里
 *     · 前者的报错信息里参数名是 "arg0" 这种占位符（AOP 拿不到参数名）
 *     · 前者给 Controller 加了一层 CGLIB 代理，多一个排查变量
 *
 *   HandlerMethodValidationException 的 getParameterValidationResults()
 *   能拿到真实的 MethodParameter，于是 getParameterName() 直接给出 "size"。
 *
 *   ⚠️ 但有个反直觉的前提：**Spring MVC 那套内建校验需要类路径上有 Validator**。
 *      它开头就检查 BEAN_VALIDATION_PRESENT（有没有 Hibernate Validator）。
 *      没有 spring-boot-starter-validation 的话，内建校验**静默跳过**，
 *      注解形同虚设，`?size=0` 照样 200。
 *      这就是为什么 pom.xml 里那个依赖的注释特地写了"缺失是静默失效"。
 *
 * =============================================================================
 *  ⭐ 【阶段 4 的这句话在阶段 5 到期了】
 * =============================================================================
 *   本类原先写着：
 *
 *     「这里没有一长串 @ExceptionHandler 处理业务异常，因为当前没有任何业务异常。
 *       一旦有了，正确的做法是定义业务异常类型再在这里加 @ExceptionHandler，
 *       而不是现在先摆几个空的 handler 等着。**空 handler 会让人以为
 *       那些异常已经被处理了。**」
 *
 *   阶段 5 有了：下单会遇到"未放票 / 售罄 / 重复购票"，
 *   支付会遇到"订单不存在 / 订单已取消"。
 *   所以下面**真的**加了 handler —— 不是提前摆的架子，是有异常要接。
 *
 *   这个演进顺序本身是有示范意义的：**先有异常，再有 handler**。
 *   反过来（先铺好各种 handler）的问题是，你会分不清
 *   "这个分支处理过了" 和 "这个分支没人走过"。
 *
 * -----------------------------------------------------------------------------
 *  ⭐ 【为什么业务异常的 handler 什么都不做，只是交回 handleExceptionInternal】
 * -----------------------------------------------------------------------------
 *   看下面那个 handleBusinessException —— 它一行判断都没有。
 *   这是刻意的：它存在的唯一目的是**把异常类型和 HTTP 状态码接上**，
 *   至于响应体怎么造、日志怎么打，全部沿用上面那个已经写好并验证过的路径。
 *
 *   如果在这里自己 `new ResponseEntity<>(...)`：立刻就有了**第二个**
 *   ApiError 构造点。两处的 error / message / details 取值规则
 *   这次可能一致，下次改一处漏一处就会分叉 ——
 *   而症状是"同一个 409，从不同分支出来的响应体长得不一样"。
 *
 *   顺带一个好处：日志分档自动沿用（409 < 500 → log.debug），
 *   符合"业务失败不是告警"这条判断，不需要在这里再写一次。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 【新增 @ExceptionHandler(Exception.class)：这是一处**响应体行为变更**】
 * -----------------------------------------------------------------------------
 *   阶段 5 之前，这个类没有兜底 handler，所以任何"意想不到的异常"
 *   都会落到 Spring Boot 的默认 `/error` 上，返回：
 *
 *       {"timestamp":"...","status":500,"error":"Internal Server Error","path":"..."}
 *
 *   而本项目其他所有错误响应都是 ApiError 的形状：
 *
 *       {"status":500,"error":"...","message":"...","details":[...]}
 *
 *   **两种形状**意味着前端要写两套解析逻辑，而且更麻烦的是：
 *   那条路径**不经过本类的日志**，所以监控抓不到 ——
 *   "接口返回了漂亮的 JSON"会掩盖"服务其实坏了"。
 *
 *   为什么阶段 5 才补：阶段 5 起，"扣减抛异常 = 系统失败、结果未知"
 *   第一次成为真实可能的结局（见 sql/03_rail_inventory.sql 的 (c) 情形）。
 *   在此之前所有失败都是可枚举的 400/404/405。
 *
 *   ⚠️ **代价（诚实写出来）**：500 的响应体形状变了，文档要同步。
 *      这条改动会影响所有接口，不只是新接口。
 *
 *   ⚠️ 【它不会抢走更具体的 handler】Spring 按异常类型**最具体者胜出**：
 *      MethodArgumentNotValidException 会走继承来的那个 handler（400），
 *      不会掉进这个 Exception 兜底。这一点是机制保证的，不是巧合。
 * =============================================================================
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 所有 Spring MVC 内置异常的收敛点。
     *
     * <p>这里刻意**不**把异常的 message 直接透给调用方：
     * 里面可能含 SQL、类名、文件路径等内部信息。
     * 调用方拿到的是 HTTP 语义 + 字段级明细，完整堆栈进日志。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {

        /*
         * ⚠️ 日志级别按状态码分开，这不是洁癖，是信噪比问题：
         *   4xx 是**调用方**的问题——参数写错、路径写错。
         *       这类错误会大量出现（有人拿 curl 试接口就会产生一堆），
         *       全部按 WARN 打出来会把真正的故障淹掉。所以按 DEBUG。
         *   5xx 是**服务端**的问题——代码 bug、数据库连不上。
         *       这类必须显眼，否则"接口返回了漂亮的 JSON 错误体"
         *       会掩盖掉"服务其实坏了"这个事实。所以按 WARN 打完整堆栈。
         *
         * 【这一步不能省】
         * Spring 默认对已处理的异常只在 DEBUG 级别记一行。
         * 如果我们把异常变成响应体之后就什么都不记，
         * 那么 500 的排查难度会**比不用这个类更高**——
         * 因为默认处理至少还会打点东西。
         */
        if (statusCode.is5xxServerError()) {
            log.warn("请求处理失败（服务端错误）: {} {} -> {}",
                    request.getDescription(false), ex.getClass().getSimpleName(), statusCode.value(), ex);
        } else {
            log.debug("请求处理失败（客户端错误）: {} {} -> {}",
                    request.getDescription(false), ex.getClass().getSimpleName(), statusCode.value());
        }

        HttpStatus status = HttpStatus.resolve(statusCode.value());
        // 兜底：如果状态码不在 HttpStatus 枚举里（非标准码），用 500 的原因短语占位，
        // 避免 getReasonPhrase() 抛异常把错误处理本身搞崩。
        String reasonPhrase = status != null ? status.getReasonPhrase() : "Error";

        ApiError apiError = new ApiError(
                statusCode.value(),
                reasonPhrase,
                message(ex, statusCode),
                details(ex));

        // headers 必须原样带回去（405 的 Allow 头、415 的 Accept 头等都在里面）
        return new ResponseEntity<>(apiError, headers, statusCode);
    }

    /**
     * 业务失败 —— 未放票 / 售罄 / 重复购票 / 订单状态不允许 / 订单不存在。
     *
     * <p>方法体刻意只有一行：把异常交回 {@link #handleExceptionInternal}。
     * 理由见类注释里"为什么业务异常的 handler 什么都不做"。
     *
     * <p>状态码来自 {@link BusinessException#getStatus()} ——
     * 所以"加一个新的业务失败场景"只需要新建一个异常类，
     * **不需要回来改这个文件**。
     *
     * <p>第二个参数传 {@code null} 是照抄继承来的那些 handler 的约定：
     * body 为 null 表示"响应体由 handleExceptionInternal 自己造"。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException ex, WebRequest request) {
        return handleExceptionInternal(ex, null, HttpHeaders.EMPTY, ex.getStatus(), request);
    }

    /**
     * 兜底：任何没被更具体的 handler 接住的异常 → 500。
     *
     * <p>存在的意义是让 5xx 也长成 {@link ApiError} 的样子，
     * 并且**经过本类的日志**（500 走 log.warn + 完整堆栈，监控才抓得到）。
     *
     * <p>⚠️ 它接住的异常意味着"服务端有 bug 或依赖故障"，
     * 所以响应体里**不能**透出异常原文 —— {@link #message} 只给一句话，
     * 完整堆栈进日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception ex, WebRequest request) {
        return handleExceptionInternal(ex, null, HttpHeaders.EMPTY,
                HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * 面向调用方的一句话说明。
     *
     * <p>刻意只给"这一类错误是什么"，不拼接异常原文 —— 原因见方法上方的日志说明。
     */
    private String message(Exception ex, HttpStatusCode statusCode) {
        /*
         * ---- 业务失败：用异常**自己**的 message ----
         *
         * ⚠️ 这是本方法里唯一一条"把异常原文透给调用方"的分支，
         * 所以它是**有前提的**：前提是 BusinessException 的 message
         * 由我们自己写（"该席别已售罄"），而不是从底层异常透传上来的。
         *
         * 这正是为什么 BusinessException 的构造器注释里强调
         * "不要写内部术语、不要拼 SQL" —— 那句话约束的正是这里。
         * 如果哪天有人把 `new SeatNotAvailableException(..., ex.getMessage())`
         * 这样用，SQL 原文就会从这里漏出去。**约束靠构造点自觉，不靠这里过滤。**
         */
        if (ex instanceof BusinessException businessException) {
            return businessException.getMessage();
        }

        /*
         * ---- @Valid @RequestBody 校验失败（阶段 5 新增的路径）----
         *
         * 和 HandlerMethodValidationException 返回**同一句话**是刻意的：
         * 从调用方的角度，"查询参数不合法"和"请求体字段不合法"
         * 是同一类错误（你传的数据不符合要求），没必要让它去分辨。
         */
        if (ex instanceof MethodArgumentNotValidException) {
            return "请求参数校验未通过";
        }

        if (ex instanceof HandlerMethodValidationException) {
            return "请求参数校验未通过";
        }
        if (ex instanceof MethodArgumentTypeMismatchException) {
            return "请求参数类型不正确";
        }
        if (ex instanceof MissingServletRequestParameterException) {
            return "缺少必需的请求参数";
        }
        return switch (statusCode.value()) {
            case 400 -> "请求格式不正确";
            case 404 -> "请求的资源不存在";
            case 405 -> "请求方法不被支持";
            /*
             * 409 这一条是阶段 5 补的。
             *
             * ⚠️ 它**基本走不到** —— 因为 409 在当前项目里只有一个来源
             * （BusinessException），而上面那个分支已经把 message 接走了。
             * 留着它是防御性的：哪天有个不走 BusinessException 的 409
             * （比如 Spring 自己在某个场景抛 Conflict），
             * 至少不会退化成"请求处理失败"这种什么也没说的话。
             *
             * 这不算"为不存在的问题写代码"：它不是一段行为建模，
             * 只是 switch 的一个分支，而且 cost 只有一行。
             */
            case 409 -> "业务规则不允许该操作";
            default -> "请求处理失败";
        };
    }

    /**
     * 逐字段的失败原因。没有可细分的字段时返回空列表。
     *
     * <p><b>返回空列表而不是 null</b>：JSON 里 {@code "details": null}
     * 会让前端写 {@code details.map(...)} 时抛异常，而 {@code []} 天然安全。
     */
    private List<String> details(Exception ex) {
        /*
         * ---- 情况 0：业务失败自带的定位信息（阶段 5）----
         *
         * 注意这里**不是**"逐字段的校验原因"，而是"这次失败针对的是哪趟车哪天"。
         * 区别见 BusinessException#getDetails 的注释。
         */
        if (ex instanceof BusinessException businessException) {
            return businessException.getDetails();
        }

        /*
         * ---- 情况 0.5：@Valid @RequestBody 校验失败（阶段 5 新增）----
         *
         * 🔴 【这一条是必须主动补的洞，而且它是同一类 bug 的第二次出现】
         *
         * 在本类最初的版本里，details() 没有这个分支，于是 @Valid 失败会
         * 一路走到最后的 `return List.of()` —— 响应体变成：
         *
         *     {"message":"请求参数校验未通过","details":[]}
         *
         * 调用方**看不出是哪个字段错了**。而 docs/api/error-codes.md §三②
         * 已经记录过一次同类事故：缺必填查询参数时 details 是空的，
         * 只能在一堆必填参数里猜。这是第二次。
         *
         * ⭐ 为什么这两个分支当初都没想到：
         *   因为它们都**依赖一个新的入口机制**（第一次是
         *   MissingServletRequestParameterException，这次是 @Valid），
         *   而 details() 是"按异常类型分流"的，新增机制就必须新增分支。
         *   **这个方法的维护成本随入口机制的数量增长，不随字段数量增长** ——
         *   所以判据是"有没有引入新的校验入口"，不是"有没有新增字段"。
         *
         * ⚠️⚠️ 【必须用 getAllErrors()，不能用 getFieldErrors()】
         *   getAllErrors() = getFieldErrors()（字段级）+ getGlobalErrors()（类级）。
         *
         *   类级约束指的是**写在类上、跨字段**的校验，比如：
         *       @AssertTrue(message = "上车日期不能晚于下车日期")
         *       public boolean isDateRangeValid() { ... }
         *   或者自定义的类级 @Constraint。
         *
         *   只遍历 getFieldErrors() 会把它们**静默丢掉** ——
         *   失败发生了（所以确实是 400），但 details 是空的，
         *   于是又退化成上面那个"只报错不说是哪错"的状态。
         *   用 getAllErrors() 之后，字段级和类级都能显示，
         *   类级错误的"字段名"用 objectName 兜底（通常是类名）。
         *
         *   本项目现在还没有类级约束 —— 但用 getAllErrors() 的代价是零，
         *   而等到真加了类级约束时，这里不会有人想起来要改。
         */
        if (ex instanceof MethodArgumentNotValidException notValidException) {
            List<String> details = new ArrayList<>();
            for (ObjectError error : notValidException.getBindingResult().getAllErrors()) {
                /*
                 * FieldError 是字段级错误（有具体字段名），
                 * 其余（如 ObjectError）是类级错误，没有字段名可用 ——
                 * 用 objectName（通常是那个类的名字）作为退而求其次的定位。
                 */
                String location = (error instanceof FieldError fieldError)
                        ? fieldError.getField()
                        : error.getObjectName();
                String reason = error.getDefaultMessage();
                details.add(location + ": " + (reason != null ? reason : "取值不合法"));
            }
            return details;
        }

        // ---- 情况 1：@RequestParam 上的约束校验失败（阶段 4 的主路径）----
        if (ex instanceof HandlerMethodValidationException validationException) {
            List<String> details = new ArrayList<>();
            for (ParameterValidationResult result : validationException.getParameterValidationResults()) {
                /*
                 * getMethodParameter().getParameterName() 给出的是**真实的参数名**，
                 * 比如 "size"、"page"、"date"。
                 *
                 * 用它而不是 getMethodParameter().getParameter().getName()——
                 * 后者依赖 Java 8 的 -parameters 编译选项，没开的话拿到的是
                 * "arg0" 这种占位符。（本项目开了，但不依赖它更稳。）
                 *
                 * 返回值可能为 null（极端情况下拿不到名字），所以兜一层。
                 */
                String parameterName = result.getMethodParameter().getParameterName();
                if (parameterName == null) {
                    parameterName = "未知参数";
                }

                // 一个参数可能同时违反多条约束（比如既超上限又不符合格式），
                // 所以内层还要遍历 resolvableErrors。
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    String reason = error.getDefaultMessage();
                    details.add(parameterName + ": " + (reason != null ? reason : "取值不合法"));
                }
            }
            return details;
        }

        // ---- 情况 2：参数类型转换失败（如 ?date=abc 传给 LocalDate）----
        if (ex instanceof MethodArgumentTypeMismatchException mismatchException) {
            Class<?> requiredType = mismatchException.getRequiredType();
            String expected = requiredType != null ? requiredType.getSimpleName() : "目标类型";
            return List.of(mismatchException.getName() + ": 无法转换成 " + expected);
        }

        /*
         * ---- 情况 3：根本没传这个参数（如 /search 只给 to 不给 from）----
         *
         * ⭐ 这一条是**实测发现缺了才补的**，不是预先想到的：
         *    最初只处理了情况 1 和 2，实测 ?to=AOH（不带 from）得到的是
         *    {"message":"请求格式不正确","details":[]} ——
         *    details 是空的，调用方**看不出缺的是哪个参数**，
         *    只能在一堆必填参数里猜。
         *
         *    而 MissingServletRequestParameterException.getParameterName()
         *    本来就带着参数名，不用白不用。
         *
         * 【为什么只判这一个具体类，而不是它的父类 MissingRequestValueException】
         *   父类**没有** getParameterName()，这个方法声明在各个子类上。
         *   父类还有 4 个兄弟子类：MissingPathVariableException /
         *   MissingRequestHeaderException / MissingRequestCookieException /
         *   MissingMatrixVariableException。
         *
         *   但本项目的接口**产生不了**它们：
         *     · 路径变量 {@code /{trainNo}/stations} 只要路由匹配上就一定有值
         *     · 没有用 @RequestHeader / @CookieValue / @MatrixVariable
         *   所以这里不给它们写处理器 —— 写了就是没人走的死代码，
         *   而且会让人误以为那些情况已经考虑过、试过。
         *   等真用上了再加，加的时候记得它们是各自声明 getParameterName() 的。
         */
        if (ex instanceof MissingServletRequestParameterException missingParam) {
            return List.of(missingParam.getParameterName() + ": 缺少必需的请求参数");
        }

        // ---- 情况 4：没有字段级信息的其他错误（404 / 405 等）----
        return List.of();
    }
}
