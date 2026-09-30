# 接口错误约定

**更新日期**：2026-09-30
**依据**：本文只记录**在本机实际观测到**的响应形状。未实测过的一律不写。
**范围**：当前只有 `rail-train-service`（8082），6 个查询接口 + 2 个写入接口。

> ⚠️ **本文不是"错误码规范"，是"错误现状记录"。**
> 错误码**枚举**（形如 `SEAT_SOLD_OUT = 1001`）**刻意推迟到阶段 8**。
> 理由见 [§4](#四为什么还没有错误码枚举) —— 现在定就是在编。

> 🔴 **阶段 5 的标注规则**：阶段 5 新增了 **409 与 500** 两类错误，
> 但这两个接口**一次都没跑过**（MySQL 被 DLP 加密，起不来）。
> 所以下面凡是阶段 5 新增的响应体，都标了 **`⚠️ 预期形状（未实测）`**，
> 与实测过的部分**在视觉上严格区分**。
> 跑完之后，要把标注换成真实响应体 —— **不是把标注删掉就算了**。
> 本仓库的硬规则是"禁止编造数字"（[01-project-guide.md §六](../01-project-guide.md)），
> 一段看起来像实测、实际是推演的 JSON 是最坏的东西。

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

400 有**五种**不同的 `message`，来自五个不同的异常。

⚠️ **第五种（阶段 5 新增）尚未实测** —— 前四种是实测过的。

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

**⑤ 请求体（JSON body）校验失败** —— `MethodArgumentNotValidException`　🔴 **阶段 5 新增，未实测**

```console
# ⚠️ 预期形状（未实测）—— 阶段 5 的写入接口从没跑过
$ curl -sS -X POST "http://127.0.0.1:8082/api/order/orders" \
    -H 'Content-Type: application/json' -d '{}'
{"status":400,"error":"Bad Request","message":"请求参数校验未通过",
 "details":["userId: 下单人不能为空","trainNo: 车次号不能为空", ...]}
```

这是本项目**第一个带 `@Valid @RequestBody` 的接口**引入的，和 ① 是"同一类错误
出现在两种参数位置上"：① 是 query 参数（`HandlerMethodValidationException`），
⑤ 是 body（`MethodArgumentNotValidException`）。两者共用同一句
`message: "请求参数校验未通过"` —— 对调用方来说确实是同一件事。

⚠️ **它在 `details()` 里踩了一个必须记下的坑**：`MethodArgumentNotValidException`
有两个取错误列表的方法，**取错了会让 `details` 静默变空**：

| 方法 | 包含 | 用它会怎样 |
| --- | --- | --- |
| `getFieldErrors()` | 只有**字段级**约束（`@NotBlank` 等） | ❌ **丢掉类级约束**（`@Valid` 加在类上、自定义校验注解），那些错误**根本不进 details** |
| `getAllErrors()` | 字段级 + **类级**（global errors） | ✅ 用它 |

⭐ 这**不是一个假想的风险**：它和 [§三②](#400-bad-request) 记录过的
"`details` 曾经是空的"是**同一类 bug 的第二次出现** —— 都是"新增一种参数位置，
`details()` 忘了加分支/分支写漏"。区别只在于第一次是漏了整个分支，
这次是分支里少取了一半。**所以本项目的判据是：每新增一种参数校验入口，
就要有一条"details 必须非空"的实测检查。**

### 409 Conflict　🔴 **阶段 5 新增整类状态码，未实测**

**这是本项目第一个"业务失败"状态码**，也是 [§一](#一总原则成功是原始数据失败才是错误体)
那句"让状态码说成败"第一次真的用上：**没抢到票和参数写错是两件完全不同的事**，
不能都塞进 400。

下单接口有**三个** 409 分支，`details` 都是 `[]`（没有字段级问题），
**靠 `message` 区分**：

| 触发条件 | `message` | 调用方该做什么 |
| --- | --- | --- |
| 这一天/这个席别还没有库存行 | 该席别尚未放票 | **换个日期再来**（不是"卖完了"） |
| `sold_count >= total_count` | 该席别已售罄 | **换车次或换个日期** |
| 同一用户同车同日同席别已有票 | 请勿重复购票 | **去查已有订单**，别再提交 |

支付接口有**一个** 409 分支：

| 触发条件 | `message` | 为什么不是 200 |
| --- | --- | --- |
| 订单当前状态是 `2 已取消` | 订单已取消，不能支付 | 票已回补、可能已卖给别人。回"支付成功"等于告诉用户他有一张**不存在的票** |

⭐ **409 的 `details` 是"定位信息"，不是"逐字段原因"。**
400 的 details 回答"你哪个字段写错了"；409 的 details 回答
"**你该去看哪一条数据**"。所以 409 的 details 目前是 `[]`，
因为它要说的话 `message` 已经说完了 —— 硬凑一个
`["trainNo: 已售罄"]` 反而是在暗示"trainNo 这个字段写错了"。

**为什么"尚未放票"是 409 而不是 404**（这条是阶段 5 拍板的，容易搞反）：

> **404 与 409 的分界是「你指的东西不存在」vs「东西存在但规则不允许」。**
>
> - `trainNo` 拼错 → 车次不存在 → **404**
> - 车次是对的，只是这天没放票 → 库存行不存在 → **409**
>
> 做成 404 会让前端提示"车次不存在"，而用户下一步该做的是**换个日期** ——
> 提示指向了完全错误的动作。

⚠️ **三个 409 分支现在只有 `message` 能区分，而 `message` 不是契约**
（[§二](#二错误体形状) 已写明"不要用它做分支判断"）。
这是一个真实的矛盾，已记入技术债（[status §七](../status/development-status.md) 第 8 条），
**阶段 8 要么给它们错误码，要么承认文案是契约**。

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
| `POST /api/order/orders`（`trainNo` 不存在）🔴 未实测 | 车次不存在 —— **注意不是 409** |
| `POST /api/order/orders/{orderNo}/pay`（订单号不存在）🔴 未实测 | 订单不存在 |

> 把"拼错站码"判成 404 而不是"200 + 空数组"，是因为**调用方要据此区分**
> "我打错字了"和"这条线路没车"——两者的下一步动作完全不同。

🔴 **阶段 5 让这个"空 body"问题的影响面扩大了**（详见 [§五](#五已知不一致404-的-body-是空的)）：
阶段 4 只有**查询**接口会返回 404，客户端拿到了顶多显示"查不到"；
阶段 5 起**写入**接口也返回 404，而写入接口的客户端一定会解析响应体
（要拿订单号、要看失败原因）。**同一个 4xx 家族里，404 是空 body、
400 和 409 是 JSON —— 客户端必须写两个分支。**

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

### 500 Internal Server Error　🔴 **阶段 5 起形状变了，未实测**

```console
# ⚠️ 预期形状（未实测）
{"status":500,"error":"Internal Server Error",
 "message":"服务器内部错误","details":[]}
```

**🔴 这是一处行为变更，读旧文档的人会以为是别的形状。**

阶段 5 之前**没有 catch-all 处理器**，所以未被接管的异常会走 Spring Boot 的 `/error`，
返回的是 Boot 自己的形状：

```json
{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/..."}
```

**与 `ApiError` 是两种形状**，而且问题更严重的是：**这条路径不经过我们的日志**，
监控抓不到。阶段 5 新增了 `@ExceptionHandler(Exception.class)` 把它接管过来，
于是：

| 项 | 阶段 4 | 阶段 5 起（预期） |
| --- | --- | --- |
| 形状 | Boot 的 `/error` 形状（有 `timestamp`/`path`，无 `details`） | `ApiError`（有 `details`，无 `timestamp`/`path`） |
| 日志 | **不经过本项目的日志** ⚠️ | 走 `handleExceptionInternal` 的 5xx 分支，`log.warn` 打完整堆栈 |
| 调用方 | 要写第二套解析 | 和其他错误一样 |

⭐ **为什么这条重要（而不是"顺手统一一下"）**：阶段 5 起
`sql/03_rail_inventory.sql` 里那句 (c)"**抛异常 / 超时 = 系统失败，结果未知**"
第一次成为**真实可能的结局** —— 扣减和落单之间任何一步抛异常，
这次下单的**结果就是不确定的**。这类结局必须能被日志抓到，
不能悄悄走 Boot 的默认错误页。

⚠️ **它不会抢走更具体的 handler**：Spring 按异常类型**最具体者胜出**，
所以 `BusinessException` → 409、`MethodArgumentNotValidException` → 400 都仍然生效。

---

## 四、为什么还没有错误码枚举

`StationController` 的注释里已经写死了这个顺序，这里复述并确认它被执行了：

> 统一的 code 枚举一旦没有错误码规范支撑（`docs/api/` 还没写），
> 会先长出一堆随手写的 code，反而更难统一。
> 等阶段 4 真正出现多种错误场景时，再写 `docs/api/error-codes.md` 并引入包装。
> **顺序反过来做，规范就是编出来的。**

阶段 4 结束时，**顺序是正的**：那时上面每一个状态码都是先真实出现过，才被记下来的。

⚠️ **阶段 5 打破了这一点，而且是刻意的。** 阶段 5 新增的 409 与 500
**还没被观测过**（两个写入接口一次都没跑过），但我们仍然把它们写进来了 ——
理由是：**不写下来，读代码的人就不知道"这个 409 是设计如此"还是"随手返回的"**。
折中的办法是**在视觉上严格区分**（🔴 **未实测** 标注 + 预期响应体的独立代码块），
而不是"没实测就不写"。**跑完之后必须把标注换成真实响应体。**

但**枚举仍然推迟到阶段 8**。⚠️ **注意理由变了** —— 阶段 4 时写的理由是
"真正需要错误码的业务错误属于阶段 5+，现在一个都还没有"，
**阶段 5 之后这句话不成立了**（业务错误有 4 个了）。现在的理由换成：

**① 4 个业务错误，HTTP 状态码 + `message` 够用。**
阶段 5 的业务错误全部是 409，一共 4 种（尚未放票 / 已售罄 / 请勿重复购票 / 订单已取消）。
其中"尚未放票"和"已售罄"在语义上确实是同一类**动作**（换个日期或车次），
真正需要区分的只有"重复购票"（去看已有订单）。**为 4 个错误引进一套枚举，
收益是省一次字符串比较，成本是新增一层要维护的映射。**

**② 真正逼出错误码的约束还没到。**
错误码的价值在"**HTTP 状态码表达不了的**语义"。而这类语义来自
**限流、降级、排队**：`429 排队中（还要等多久）` 和
`429 请求太频繁（别打了）`，`503 降级（这个功能暂时关了）` 和
`503 未预热（系统故障，要告警）` —— **它们状态码相同、语义相反、下一步动作完全相反**。
那套语义要等阶段 8/9 引入网关与 Sentinel 才清楚。

**③ 反过来说，现在最该修的不是"缺枚举"，是"409 的三种情况只有文案能区分"。**
这已经记入技术债（[status §七](../status/development-status.md) 第 8 条），
和枚举一起在阶段 8 定 —— 因为到那时才知道客户端**真的**需要区分哪些。

**现在写枚举 = 给还没出现的需求起名字。**

---

## 五、已知不一致：404 的 body 是空的

| 来源 | 状态码 | body |
| --- | --- | --- |
| 框架抛异常（校验失败、类型错误、方法不对） | 400 / 405 | `ApiError` JSON |
| 业务异常（`BusinessException` 及其子类）🆕 | 409 | `ApiError` JSON |
| 未被接管的异常（catch-all）🆕 | 500 | `ApiError` JSON（**阶段 4 之前是 Boot 的 `/error` 形状**） |
| **Controller 主动 `return ResponseEntity.notFound().build()`** | **404** | **空（`Content-Length: 0`，无 `Content-Type`）** |

⭐ **阶段 5 反而让不一致更突出了**：我们新增的 409 和 500 都统一成了 `ApiError`，
于是 **404 成了全部 4xx/5xx 里唯一一个形状不同的**。
这不是"故意的设计选择"，而是"**它由 Controller 主动返回、不经过 advice**"
这一实现事实的副产品 —— 两个新的错误类恰好都不走这条路，就越发显得它突兀。

**成因**：`ApiExceptionHandler` 继承自 `ResponseEntityExceptionHandler`，
它只接管**被抛出的异常**。而 404 是本项目**主动返回**的，
根本没有异常产生，所以不经过 advice。

**影响**：调用方若无条件 `JSON.parse(response.body)`，遇到 404 会解析失败。
必须先判状态码，或者在解析处兜底。

**🔴 阶段 5 起这个影响面变大了**：

| | 阶段 4 | 阶段 5 起 |
| --- | --- | --- |
| 会返回 404 的接口类型 | 只有**查询** | 查询 **+ 写入** |
| 客户端拿到空 body 的后果 | 显示"查不到"，可以忍 | **解析响应体时直接抛异常** —— 写入接口的客户端必然要解析 body（拿订单号 / 看失败原因）|
| 同族状态码的形状一致性 | 400/405 是 JSON，404 空 —— 两种 | 400/405/409/500 是 JSON，**404 空 —— 它成了唯一一个** |

**为什么现在仍然不改**：

- 阶段 5 的既定范围是「下单 + 扣库存 + 模拟支付」，**不含"统一错误体形状"**。
  顺手改掉它会让"阶段 5 失败的原因"和"改错误体的原因"混在同一步 ——
  这个项目一直坚持**一次只引入一个失败来源**（见 [status §六](../status/development-status.md) 技术债 #5 的同一条理由）。
- 而且**现在仍然没有明确的收益**：404 没有字段级细节可传达，
  `ApiError.details` 必然是 `[]`，body 里全是冗余信息。
  空 body 对 404 是一种合理的 REST 风格。
- ⚠️ **但它的"什么时候必须定"没有变**：**阶段 8 引入网关前**。
  届时"客户端要多写几行"才有真实成本可衡量。

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

### 第二条：空 body 的 POST 必须返回 400，且 details 非空　🔴 阶段 5 新增

```bash
curl -sS -X POST "http://127.0.0.1:8082/api/order/orders" \
  -H 'Content-Type: application/json' -d '{}'
# 期望 400，且 details 非空（必须包含至少一条字段级原因）
```

⭐ **这一条同样是"承重的"，不是顺手测一下**：

| 如果返回 | 说明什么 |
| --- | --- |
| **400 + `details` 非空** | ✅ `@Valid` 生效，且 `details()` 的 `MethodArgumentNotValidException` 分支取对了列表 |
| **500** | 🔴 **`@Valid` 没写**。`request.userId()` 在 `null` 上拆箱 → NPE → 500。这不是"参数没校验"，是**漏了一个注解** |
| **400 + `details: []`** | 🔴 分支写了但**取错了列表**（用了 `getFieldErrors()` 而不是 `getAllErrors()`），或者漏了整个分支 |
| **200** | 🔴 `spring-boot-starter-validation` 依赖没了 —— **静默失效**，和第一条检查是同一个陷阱 |

**为什么它必须是 `{}` 而不是"不带 body"**：不带 body 会让 Spring 抛
`HttpMessageNotReadableException`（又是另一种 400），测不到 `@Valid` 那一条路径。

同一条检查也在 [scripts/perf/stage4-smoke.jmx](../../scripts/perf/stage4-smoke.jmx) 里
（第 7 个采样器），且**期望 404 的那条第 8 个采样器**顺带验证
"车次不存在 → 404 而不是 409"。

---

## 七、相关文档

- [../01-project-guide.md](../01-project-guide.md) —— 项目阅读指南
- [../03-business-flow.md](../03-business-flow.md) —— 验收标准 A1~A6；**§二 解释了为什么"已取消"必须是 409 而不是 200**
- [../status/development-status.md](../status/development-status.md) —— 做到哪了、技术债
- [../decisions/ADR-004-stock-deduction-mysql-cas.md](../decisions/ADR-004-stock-deduction-mysql-cas.md) —— 为什么要区分"未放票"和"售罄"
- `rail-train-service/src/main/java/com/railseckill/train/exception/ApiExceptionHandler.java` —— 实现与完整取舍
- `rail-train-service/src/main/java/com/railseckill/train/exception/BusinessException.java` —— 阶段 5 新增的业务异常基类（自带 `HttpStatus` + `details`）
