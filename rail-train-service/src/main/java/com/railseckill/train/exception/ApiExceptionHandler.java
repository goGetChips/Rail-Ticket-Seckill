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
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
 *  【为什么这里没有一长串 @ExceptionHandler 处理业务异常】
 * =============================================================================
 *   因为**当前没有任何业务异常**。
 *   阶段 4 的接口全是查询：查不到就是 404（用 ResponseEntity.notFound 表达，
 *   不是抛异常），参数非法就是 400（校验抛出）。
 *   没有"余额不足""重复下单"这类需要业务语义的错误 —— 那些属于阶段 5+。
 *
 *   一旦有了，正确的做法是定义业务异常类型再在这里加 @ExceptionHandler，
 *   而不是现在先摆几个空的 handler 等着。**空 handler 会让人以为
 *   那些异常已经被处理了。**
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
     * 面向调用方的一句话说明。
     *
     * <p>刻意只给"这一类错误是什么"，不拼接异常原文 —— 原因见方法上方的日志说明。
     */
    private String message(Exception ex, HttpStatusCode statusCode) {
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
            case 415 -> "不支持的请求内容类型";
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
