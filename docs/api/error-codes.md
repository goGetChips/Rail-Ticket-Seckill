# 接口错误约定

**更新日期**：2026-09-29
**依据**：本文只记录**在本机实际观测到**的响应形状。未实测过的一律不写。
**范围**：当前只有 `rail-train-service`（8082），6 个查询接口。

> ⚠️ **本文不是"错误码规范"，是"错误现状记录"。**
> 错误码**枚举**（形如 `SEAT_SOLD_OUT = 1001`）**刻意推迟到阶段 8**。
> 理由见 [§4](#四为什么还没有错误码枚举) —— 现在定就是在编。

---

## 一、总原则：成功是原始数据，失败才是错误体

**没有 `Result<T>` 包装。** 成功响应是 HTTP 200 + 原始数据本身：

```console
$ curl -sS "http://127.0.0.1:8082/api/train/trains?page=1&size=2"
{"records":[...],"total":3,"page":1,"size":2,"pages":2}
```

只有**失败**时才返回统一的错误体 `ApiError`。

**为什么不包装**（这是阶段 2 就想清楚、阶段 4 继续沿用的一条）：

秒杀场景下「这次下单到底成没成」必须能被**非业务组件**读到——
网关的熔断器、负载均衡的重试、监控的告警规则，它们只看 HTTP 状态码，
不会去解析响应体里的 `code` 字段。
一旦把成败藏进 body，这些组件就会认为"200，一切正常"，
于是**扣减失败被统计成成功**。

让状态码说成败，让 body 说细节。

---

## 二、错误体形状

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "请求参数校验未通过",
  "details": ["size: 每页条数必须大于等于 1"]
}
```

| 字段 | 说明 |
| --- | --- |
| `status` | 与 HTTP 状态码**重复**。为了让调用方不用同时读 header 和 body |
| `error` | HTTP 原因短语，来自 `HttpStatus.getReasonPhrase()`。给日志和调试看，**不要用它做分支判断** |
| `message` | 一句话说明**这一类**错误是什么 |
| `details` | 逐字段的失败原因。**永远是数组，永不为 null** |

**两条硬约定**：

1. **`details` 不能是 `null`**。前端写 `details.map(...)` 时 `null` 会抛异常，`[]` 天然安全。
   没有字段级信息时返回 `[]`（如 404 / 405）。
2. **`details` 的顺序不保证**。同一个参数违反多条约束时，消息顺序在两次运行中可以不同。
   实测：`?from=`（空串）一次返回 `["from: 车站电报码必须是 3 位","from: 出发站不能为空"]`，
   另一次是反过来的。**调用方不要依赖数组顺序**，要按内容匹配。

---

## 三、实测观测到的状态码

### 200 OK

成功。也可能是**空数组**，而空数组不是错误：

| 接口 | 空数组的含义 |
| --- | --- |
| `GET /api/train/trains/{trainNo}/stations` | 车次存在，但没有经停站记录 |
| `GET /api/train/trains/search?from=&to=` | 这条线路确实没车（也可能 `from == to`，SQL 条件天然排除） |
| `GET /api/inventory/seats` | **这一天还没有放票**（不是"卖完了"） |

> ⭐ **"还没放票"和"已售罄"必须是两个不同的状态。**
> 前者是"过几天再来"，后者是"换别的车次"。
> 所以售罄时返回的是 `remaining: 0` 的**对象**，不是空数组——
> 用空数组统一表达会丢失这个区别。

### 400 Bad Request

400 有**四种**不同的 `message`，来自四个不同的异常。实测原始输出：

**① 参数校验失败** —— `HandlerMethodValidationException`

```console
$ curl -sS "http://127.0.0.1:8082/api/train/trains?size=0&page=0"
{"status":400,"error":"Bad Request","message":"请求参数校验未通过",
 "details":["page: 页码必须大于等于 1","size: 每页条数必须大于等于 1"]}
```

**② 缺少必填参数** —— `MissingServletRequestParameterException`

```console
$ curl -sS "http://127.0.0.1:8082/api/train/trains/search?to=AOH"
{"status":400,"error":"Bad Request","message":"缺少必需的请求参数",
 "details":["from: 缺少必需的请求参数"]}
```

> 这一条是**实测发现缺了才补的**，值得记一笔：最初只处理了 ①③，
> 上面这个请求返回的 `details` 是空的，调用方看不出缺的是哪个参数。
> 而 `getParameterName()` 本来就带着参数名。**"先跑再看"暴露的问题，靠读代码想不到。**

**③ 参数类型转换失败** —— `MethodArgumentTypeMismatchException`

```console
$ curl -sS "http://127.0.0.1:8082/api/inventory/seats?trainNo=G1&date=abc"
{"status":400,"error":"Bad Request","message":"请求参数类型不正确",
 "details":["date: 无法转换成 LocalDate"]}
```

**④ 校验约束本身未通过，但值非空**（空串走的是 ①）

`?from=` 空串会**同时**触发 `@NotBlank` 和 `@Size(min=3)`，产生两条 detail——
同一个参数违反多条约束就会有多条消息，这是正常的。

### 404 Not Found

```console
$ curl -sS -i "http://127.0.0.1:8082/api/train/trains/NOPE/stations"
HTTP/1.1 404
Content-Length: 0
```

覆盖的场景：

| 请求 | 含义 |
| --- | --- |
| `GET /api/train/stations/NOPE` | 站码不存在 |
| `GET /api/train/trains/NOPE/stations` | 车次不存在 |
| `GET /api/train/trains/search?from=XXX&to=AOH` | **站码拼错**（3 位但库里没有） |
| `GET /api/inventory/seats?trainNo=NOPE&date=...` | 车次不存在 |

> 把"拼错站码"判成 404 而不是"200 + 空数组"，是因为**调用方要据此区分**
> "我打错字了"和"这条线路没车"——两者的下一步动作完全不同。

> 🔴 **⚠️ 一个已知的不一致，必读：404 的响应体是空的，不是 `ApiError`。**
> 见 [§5](#五已知不一致404-的-body-是空的)。

### 405 Method Not Allowed

```console
$ curl -sS -i -X POST "http://127.0.0.1:8082/api/train/trains"
HTTP/1.1 405
Allow: GET
{"status":405,"error":"Method Not Allowed","message":"请求方法不被支持","details":[]}
```

⭐ **`Allow: GET` 这个响应头是必须保留的**：它告诉调用方这个路径支持哪些方法。
重建响应时如果把 headers 丢了，不会报任何错，只是调用方少了一条"能不能换个方法重试"的信息。

---

## 四、为什么还没有错误码枚举

`StationController` 的注释里已经写死了这个顺序，这里复述并确认它被执行了：

> 统一的 code 枚举一旦没有错误码规范支撑（`docs/api/` 还没写），
> 会先长出一堆随手写的 code，反而更难统一。
> 等阶段 4 真正出现多种错误场景时，再写 `docs/api/error-codes.md` 并引入包装。
> **顺序反过来做，规范就是编出来的。**

现在阶段 4 结束了，**顺序是正的**：上面每一个状态码都是先真实出现过，才被记下来的。

但**枚举仍然推迟到阶段 8**，理由是**要枚举的东西现在还不存在**：

- 当前所有错误都是「请求本身有问题」——参数错、资源不存在、方法不对。
  这些**HTTP 状态码已经精确表达**了，再加一层 `code` 是冗余。
- 真正需要错误码的是**业务错误**：库存不足、超出限购、重复下单、订单状态不允许。
  这些属于**阶段 5+**，现在一个都还没有。
- 秒杀场景下错误码会和**限流、降级、排队**耦合（比如 `429 排队中`、
  `503 降级` 要和业务错误码分开），那套语义要等阶段 8/9 才清楚。

**现在写枚举 = 给不存在的错误起名字。**

---

## 五、已知不一致：404 的 body 是空的

| 来源 | 状态码 | body |
| --- | --- | --- |
| 框架抛异常（校验失败、类型错误、方法不对） | 400 / 405 | `ApiError` JSON |
| **Controller 主动 `return ResponseEntity.notFound().build()`** | **404** | **空（`Content-Length: 0`，无 `Content-Type`）** |

**成因**：`ApiExceptionHandler` 继承自 `ResponseEntityExceptionHandler`，
它只接管**被抛出的异常**。而 404 是本项目**主动返回**的，
根本没有异常产生，所以不经过 advice。

**影响**：调用方若无条件 `JSON.parse(response.body)`，遇到 404 会解析失败。
必须先判状态码，或者在解析处兜底。

**为什么现在不改**：

- 阶段 4 的既定范围是「保持 HTTP 语义，**只加校验错误体**」。
  把 404 也改成 `ApiError` 超出了这个范围。
- 而且**现在也没有明确的收益**：404 没有字段级细节可传达，
  `ApiError.details` 必然是 `[]`，body 里全是冗余信息。
  空 body 对 404 是一种合理的 REST 风格。

**🔴 但这将成为一个必须处理的项**，在引入网关（阶段 8）之前定下来。两个选项：

| 选项 | 做法 | 代价 |
| --- | --- | --- |
| A. 保持现状 | 文档写清 404 无 body，客户端自己兜 | 客户端必须分支处理两种形状 |
| B. 统一 | 加一个 `@ExceptionHandler(NoResourceFoundException.class)`，并把 Controller 里所有 `notFound()` 换成抛业务异常 | 多一层间接；`notFound()` 的直观性丢失 |

倾向 **A**，但**不现在拍**——等阶段 8 有了网关和统一的前端调用层，
"客户端要多写几行"这件事才有真实成本可以衡量。现在选就是在猜。

---

## 六、怎么验证校验还活着

⚠️ **`spring-boot-starter-validation` 缺失是静默失效**，不是编译错误。
去掉它，所有 `@Min` / `@NotBlank` 全部无声无息地不生效，接口照样返回 200。

所以下面这条是**唯一**能证明校验在工作的检查：

```bash
curl -sS -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:8082/api/train/trains?size=0"
# 期望 400。返回 200 就说明校验没生效。
```

`?size=0` 这条不只是"顺手测一下"，它是**承重的**：
分页插件的 `maxLimit` 钳制条件写的是 `size > limit || size < 0`，
**接不住 0**（0 既不大于上限也不小于 0），所以 0 只能靠 `@Min(1)` 拦。

同一条检查也在 `scripts/perf/stage4-smoke.jmx` 里（第 5 个采样器），
跑一次 JMeter 会一起验掉。

---

## 七、相关文档

- [../01-project-guide.md](../01-project-guide.md) —— 项目阅读指南
- [../03-business-flow.md](../03-business-flow.md) —— 验收标准 A1~A6
- [../status/development-status.md](../status/development-status.md) —— 做到哪了、技术债
- `rail-train-service/src/main/java/com/railseckill/train/exception/ApiExceptionHandler.java` —— 实现与完整取舍
