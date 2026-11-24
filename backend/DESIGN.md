# 设计说明（DESIGN）

## 1. 我做到了哪一步

- [x] L0：5 / 5 条（建表 / 发布 / 列表 / 详情 / 上下架）
- [x] L1：4 / 4 条（下单 / 订单详情 / 订单列表 / 参数校验）
- [x] L2：全部 5 项（模拟支付 / 并发不超卖 / 鉴权 / 自动化测试 / 索引专项）

一句话：**我做了一个「商品 → 订单 → 支付」的校园好物市集后端，它能在一条命令启动的 H2 内存库上完成发布/上下架（带最简鉴权）、下单扣库存（事务 + 悲观行锁）、按手机号查订单、模拟支付（同步返回 paid、重复支付 409），但还不能做真实的异步支付回调和退款。**

**L2 的计分与完成口径**：题目规则是「任选 1~2 个，每个 6 分，两个封顶 10」。计分上 L2-9 + L2-10 已经拿满 10 分；L2-11（鉴权）与 L2-13（索引专项）不追加计分，但作为完整性与面试素材一并完成——这不是「刷题」，是因为鉴权与索引恰好是评委二轮最可能追问的两个工程点，做完整比留白更有底气。L2-12 自动化测试虽然不在选做计分内，但它是 L2-10「可重跑脚本」的载体，也顺带验证了 L1 下单事务的正确性。

## 2. 我是怎么拆的

| 顺序 | 我做的事 | 为什么排在这个位置 |
|---|---|---|
| 1 | 先写 `schema.sql`（三张表 + 约束） | 题目 §1 明确要求手写，且后续所有接口的行为都由表约束决定（比如 payments 的唯一约束就是支付幂等的兜底）。表先定，接口才有确定的边界 |
| 2 | 工程骨架：统一响应 `{code,message,data}`、全局异常处理、`/api/health` | 这个骨架被后面所有接口复用。先做是避免每个接口各写一遍错误处理，最后尺寸失控 |
| 3 | 商品模块 L0-2~L0-5 | 商品是唯一无外部依赖的模块，能独立跑通全部状态码（400/404/409），用来验证骨架和统一异常映射是对的 |
| 4 | 下单 L1-6 | 依赖商品的 `price`/`stock`/`status`，必须在商品模块之后。这是整个项目唯一**同时**涉及事务和并发的地方，单独花一个阶段 |
| 5 | 订单查询 L1-7/L1-8 | 纯读接口，不涉及事务，放在下单之后是因为需要一个真实存在的订单拿来查 |
| 6 | 支付 L2-9 | **支付依赖订单号**——先有 `orders.order_no`，`payments` 才能通过外键指向它；先做支付没有订单可付 |
| 7 | 并发测试 L2-10 | 依赖一个已完成的下单链路，测试对象就是第 4 步写好的事务 |
| 8 | 补齐自动化测试 L2-12 | 功能全部稳定后才写，避免测试随实现反复改动而白写 |
| 9 | 鉴权 L2-11 | **放在最后，因为它是横切关注点**：拦截器一挂，发布/上下架的调用方式就变了（要带 token）。先做功能、最后加鉴权，已有测试只需统一补一个登录步骤；反过来先做鉴权，之后每个功能测试都要先想 token 从哪来，干扰主流程 |
| 10 | 索引专项 L2-13 | 纯「取证」工作：表结构和查询早已定稿，最后只需用 EXPLAIN 实测对比，产出证据与说明 |

**为什么先建表再做接口，而不是先搭 Controller 再补数据库**：这个项目里「事务」「库存」「金额」这些点一旦表设计错了，后面所有接口都要跟着改。先把 CHECK/UNIQUE/FK 定死，让我后面每次写业务代码都有数据库做保底检查（例如 `total_amount = unit_price * quantity` 直接写成 CHECK 约束），比写完业务再补数据库约束要少一轮返工。

## 3. 我的程序分成哪几块

| 文件 / 模块 | 负责什么 | 为什么单独拆出来 |
|---|---|---|
| `product/ProductController` / `order/OrderController` / `payment/PaymentController` | 只做 HTTP 层的事情：绑定参数、调 Service、包 `ApiResponse` | Controller 不碰事务和 SQL，换框架（比如换回纯 Servlet）时这层可以整体替换 |
| `ProductService` / `OrderService` / `PaymentService` | 业务规则和事务边界。**唯一一个带 `@Transactional` 的层级** | 事务必须在一个完整方法内闭合。如果拆到 Repository 层，跨 Repository 调用就没有原子性了 |
| `ProductRepository` / `OrderRepository` / `PaymentRepository` | 只写 SQL，用 `JdbcTemplate`，所有参数绑定，不做字符串拼接 | 用 JdbcTemplate 而非 ORM，是为了让「条件 UPDATE」「FOR UPDATE」「EXPLAIN」这些和并发/索引直接相关的 SQL 显式可见，评审能一眼看到锁和索引就在哪一行 |
| `common/ErrorCode` | 业务码和 HTTP 状态的唯一来源 | 题目码表 8 个值，散落在各处极容易写错一个。集中成一个枚举，`ErrorCode.STOCK_INSUFFICIENT` 只有一个定义 |
| `common/GlobalExceptionHandler` | 把所有异常（业务异常、参数校验失败、JSON 解析失败、404/405/500 等）收敛成统一结构 | 题目 §3 要求所有接口统一结构——如果每个 Controller 自己 try-catch，一定会有遗漏 |
| `auth/AuthController` / `auth/AuthService` / `auth/AuthInterceptor` | 登录签发随机 token（内存态）、对两个写接口做 Bearer 校验 | 鉴权是横切关注点：放在拦截器里一处生效，而不是在 ProductController 的每个方法开头重复「先验 token」。AuthController 与其他 Controller 一样只做参数绑定 + 调 Service |
| `config/JacksonConfig` / `config/WebConfig` | 只改序列化/反序列化行为（毫秒精度时间、严格类型、UTF-8），**不新建 ObjectMapper** | 见第 6 节「被 AI 带偏」——手工新建 ObjectMapper 会静默丢掉 `spring.jackson.*` 配置 |
| `dto/` 下的 `Create*Request` / `*Response` / `*Query` | 请求/响应/查询条件的数据形状 | 和实体 `Product`/`Order`/`Payment` 分开：实体跟着数据库走，DTO 跟着接口契约走，改任一侧不连带另一侧 |

**如果再加「退款」和「优惠券」，哪里会最先乱掉？**

会是 `OrderService.create()`。现在这个方法是一个线性的「锁商品 → 校验 → 扣减 → 算价 → 插订单」五步，每一步都依赖上一步的状态。加「优惠券」意味着算价那一步不再是一个 `Math.multiplyExact`，而要变成「原价 → 查券 → 校验券有效期/门槛 → 抵扣」，并且这条链路上的每一步都可能失败、都可能需要回滚。再叠加「退款」意味着要能回滚一个已经支付的订单，而现在 `OrderService` 里完全没有这个概念（订单状态机只有 `created → paid`，没有回退路径）。

**具体会最先出问题的是**：
1. 事务边界膨胀——`create()` 会从现在的 40 行涨到 100 行以上，所有校验都挤在一个方法里。
2. 优惠券的并发扣减——现在 `products` 只有一把行锁，`coupons` 会是第二把，两把锁顺序不当就会死锁。
3. 订单状态机复杂度——退款意味着 `paid → refunding → refunded`，`orders.status` 的 `CHECK` 约束要改，所有判断 `status='paid'` 的地方都要重审。

所以如果要做这些，我第一步会把 `OrderService.create()` 按「锁资源 → 校验 → 算价 → 落库」拆成几个内部步骤方法（但**仍然在同一个 `@Transactional` 方法内调用**，避免类内自调用绕过代理这个坑），第二步才是引入新的表和锁。

## 4. 一次完整链路是怎么走的（下单事务）

这是本项目唯一涉及事务和并发的地方，也是代码里注释和测试写得最多的部分。以「库存 5 的商品，扣 2 个」为例：

```
POST /api/orders {productId: 2, quantity: 2, buyerName, buyerPhone, address}
  ↓
OrderController.create()          —— 只做参数绑定，@Valid 拦下 quantity=0/"2"/2.5/超 int
  ↓
OrderService.create()  @Transactional  ─┐ 事务开始
  ↓                                      │
  1. SELECT ... FROM products WHERE id=? FOR UPDATE   ← 悲观行锁，其他事务在此排队
   ↓                                      │
     不存在？ → 抛 BusinessException(40401)  → 事务回滚，锁释放
   ↓                                      │
  2. status = 'off'? → 抛 BusinessException(40902) → 事务回滚
   ↓                                      │
  3. stock < quantity? → 抛 BusinessException(40901) → 事务回滚
   ↓                                      │
  4. UPDATE products SET stock = stock - 2
        WHERE id = ? AND stock >= 2        ← 第二道防线：即便锁失效也不会扣成负数
        affected != 1? → 抛 BusinessException(40901) → 事务回滚
   ↓                                      │
  5. unitPrice = product.price()          ← 只读锁内读到的数据库价格
     totalAmount = Math.multiplyExact(unitPrice, 2)   ← 溢出抛 ArithmeticException → 50000
   ↓                                      │
  6. INSERT INTO orders (order_no, ..., unit_price, total_amount, ...) 
        order_no = "SO" + yyyyMMdd + 32位UUID  ← 唯一约束 uk_orders_order_no 兜底
   ↓                                      │
  7. 回读刚插入的订单，返回 201 + 订单数据
  ↓                                      ┘ 事务提交，锁释放
```

**这条链路上，任何一步抛异常，第 4 步已经执行的扣减都会随事务一起回滚**——这一点由 `OrderRollbackTest` 专门验证过：用固定单号替身制造唯一约束冲突，让失败点精确落在「扣库存之后、插订单时」，测试替身在事务内读到库存 4（证明扣减已执行），请求结束后从外部读到 5（证明已回滚）。只断言「最终是 5」是不够的——那也可能是因为扣减根本没执行过。

**支付链路同理**（`PaymentService.pay()`）：

```
POST /api/payments {orderNo, method}
  → SELECT ... FROM orders WHERE order_no=? FOR UPDATE   ← 锁订单行
  → 订单不存在 → 404
  → status = 'paid' → 409 40903（重复支付）
  → INSERT INTO payments (payment_id, order_no, amount, method, status='paid', paid_at)
  → UPDATE orders SET status='paid', paid_at=? WHERE order_no=? AND status='created'
  → 返回 200 + payment
```

两个并发支付同一订单时，后到者在 `FOR UPDATE` 上排队；先到者提交后，后到者读到 `status='paid'` 返回 409。即便这层逻辑失效，`payments.order_no` 上的唯一约束（`uk_payments_order_no`）也会让第二条支付记录插入失败——应用层锁 + 数据库约束两道防线。

## 5. 一个取舍：我选了 A，没选 B

- **可以在哪两个方案里选**：悲观行锁（`SELECT ... FOR UPDATE` + 扣减） / 条件原子 UPDATE（`UPDATE ... WHERE stock >= ?`，只看影响行数）
- **我选了**：**悲观行锁**
- **因为**：

  AI 给出的推荐其实是条件原子 UPDATE，理由更少一条 SELECT、天然原子、不依赖数据库的锁语义。我最终没采纳它，原因有三个：

  1. **题目 L1-6 的错误判定顺序是硬性的**：不存在 → 404、下架 → 40902、库存不足 → 40901。条件 UPDATE 一条 SQL 只能告诉我会不会扣成功，**扣失败时无法区分「商品不存在」「已下架」「库存不足」三种情况**，还得回头再查一次商品才能分清——那这一条 SQL 的简洁性优势就没了，反而变成「先 UPDATE 再 SELECT 回查」两趟。
  2. 行锁方案的判断顺序和题目的叙述顺序**完全同构**：锁住商品 → 检查存在 → 检查状态 → 检查库存 → 扣减。评审读代码时不需要在脑子里做「affected==0 到底对应哪种错误」的翻译。
  3. AI 提出的核心反对理由是「H2 的 `FOR UPDATE` 锁语义不确定」。这一点**可以直接实测验证**，不需要靠信任——实际跑下来 H2 2.3 上 `FOR UPDATE` 正常串行化，10 并发库存 1 的商品恰好 1 成功 9 个 409，见第 7 节的测试证据。

  不过 AI 提的**事务失效清单我全部采纳了**：所有步骤都写在 `create()` 一个 public 方法内（不拆带 `@Transactional` 的子方法，避免类内自调用绕过 Spring 代理）；业务失败一律抛 `BusinessException`（`RuntimeException` 子类），不 catch 吞掉，保证异常能触发回滚。

- **代价是**：
  1. **多一条 SELECT**：条件 UPDATE 方案一条 SQL 就能完成「判断 + 扣减」，行锁方案要先 `SELECT ... FOR UPDATE` 再 `UPDATE`。对本题这种单机小规模来说可以忽略，但数据量大时锁持有时间更长。
  2. **并发时请求串行排队**：10 个并发请求会在锁上依次等待，最坏情况下第 10 个请求要等前 9 个都提交才能执行。本题规模下（毫秒级事务）不是问题，但如果事务里有慢操作（比如调用外部发短信接口），锁等待就会成为瓶颈——所以本项目的事务方法里**刻意没有任何网络调用**。
  3. **依赖数据库的锁实现**：换数据库（例如某些配置下的 MySQL 取决于引擎）要重新确认 `FOR UPDATE` 的行为。这一点用并发测试固定住了，换数据库时重跑 `ConcurrentOrderTest` 就能验证。

## 6. 我卡得最久的一个问题

- **现象**：`price: "1990"` 这样的字符串本该被拒绝（题目 L1-6 明确 `quantity="2"` 必须 400），但实际测试里**被接受了**，变成了整数 1990。这个问题不是一开始就发现的——是后来补严格类型校验的测试时才暴露出来。

- **我一开始以为**：是 `spring.jackson.mapper.allow-coercion-of-scalars=false` 这个配置没写对，或者配置项名字拼错了。

- **试过什么**：
  1. 检查 `application.yml` 里配置项拼写 —— 没错，用的是官方文档里的全名。
  2. 加了一个测试专用的探针 Controller，只做 JSON → 整数回显，绕过所有业务逻辑 —— **探针接口仍然接受了 `"1990"`**，说明问题不在业务代码，而在更底层的反序列化配置。
  3. 打印 `ObjectMapper.getDeserializationConfig()` 里的 feature 状态 —— 发现 `ALLOW_COERCION_OF_SCALARS` 是 `false`，但请求仍然能通过。

- **最小复现**：一个只有 `@RequestBody` 的接口，body `{"quantity":"2"}`，期望 400，实际 200 且 `quantity=2`。这个复现后来写成了 `JsonStrictnessTest`，至今还在测试套件里跑。

- **真正的原因**：在两处。**第一处**是 `LocalDateTime` 序列化格式不对，当时 AI 给的方案是手工 `new Jackson2ObjectMapperBuilder().build()` 造一个 `ObjectMapper` 来定制格式——这个写法**能跑、时间格式也确实变成了想要的样式**，但手工新建的 `ObjectMapper` **不会继承 `spring.jackson.*` 配置**，`allow-coercion-of-scalars=false` 被静默丢掉了：不报错，只是配置不生效。**第二处**是 `accept-float-as-int` 和 `allow-coercion-of-scalars` 覆盖的是两个不同的特性（标量强转 vs 浮点转整数），只开其中一个，`2.5` 或 `"2"` 总有一个能漏过去。

- **怎么修的**：
  1. 把 Jackson 定制从「手工新建 ObjectMapper」改成「注册一个 `SimpleModule` bean」——Spring Boot 会把容器里的 `Module` 自动装配到它自己构建的 `ObjectMapper` 上，`spring.jackson.*` 配置不受影响（见 `config/JacksonConfig.java`）。
  2. `WebConfig` 里同样只修改已经由 Spring 建好的转换器实例，不新建。
  3. `application.yml` 里两个开关（`allow-coercion-of-scalars`、`accept-float-as-int`）都打开。
  4. 补了两个层次的测试：`JsonStrictnessTest.ObjectMapperFeaturesApplied` 直接断言 ObjectMapper 上这两个 feature 的状态；`OrderCreateTest` 和 `ProductCreateTest` 从 HTTP 层验证 `"2"`、`2.5`、`2.0`、`true` 全部 400。

- **回归测试**：`JsonStrictnessTest`（12 例）+ `OrderCreateTest` 里的类型校验用例 + `OrderValidationTest` 里的 `quantity="2"`/`2.0` 组合顺序用例，全部通过（见第 7 节测试证据）。这个问题之后，凡是涉及「框架自动装配的东西」，我都会先问「这个 bean 是怎么来的」而不是看到一个能用的写法就套上去。

## 7. 没做的事和已知问题

| 项 | 状态 | 原因 |
|---|---|---|
| 异步支付回调 / 轮询 | 没做 | 题目允许「直接同步返回 paid，不用真做异步回调」 |
| token 过期 / 登出 / 多角色 | 没做 | 题目明确「不要求 JWT、不要求过期、不要求角色」；token 存内存，服务重启即全部失效（单机内存 H2 场景的预期行为） |
| 字符串字段接受数字/布尔 JSON 值 | 已知边界 | `ALLOW_COERCION_OF_SCALARS=false` 只拦截「字符串 → 数字」单向，`"buyerName": 123` 会被转成字符串 `"123"` 并正常入库（201）。对 `quantity`/`price`/`stock` 这些**数值字段**严格类型是完整的（400），但字符串字段收到数字不是 400。实测确认过，来源是 Jackson 的已知行为（`docs/决策与遗留问题.md` 有表格记录），**选择接受而非修**：题目没有明确要求字符串字段收到数字必须 400，且不影响任何金额/库存计算 |
| `Accept: application/xml` 返回 406 但响应体为空 | 已知限制 | 内容协商发生在消息转换之前，Spring 判定无法产生可接受类型后直接结束响应，`@ExceptionHandler` 拿不到已提交的响应来填充 body。实际影响极小——本项目只提供 JSON，客户端正常场景不会触发 |
| 分页 COUNT 与 SELECT 是两条独立查询 | 已知限制，选择接受 | 并发写入时 total 与 items 可能有一个瞬时不一致窗口。为它加事务/快照读对这个规模的单机内存库是过度设计，且两条查询共用同一个 WHERE 子句（`ProductRepository.Filter`），保证筛选条件永远一致 |
| 并发验证靠单元测试而非独立脚本 | 已知取舍 | `ConcurrentOrderTest` 用真实 Tomcat + 真实 HTTP 并发，是可重跑的（`mvn test -Dtest=ConcurrentOrderTest`）；但没有额外提供脱离测试框架的独立 shell 脚本，评审需要靠 Maven 跑 |
| 索引避免排序的收益在 H2 上不可得 | 引擎限制（如实记录） | 索引把扫描行数从全表 20001 收敛到 1001（约 20 倍），但 H2 2.x 不沿索引序在 LIMIT 处提前终止，ORDER BY 仍需对 1000 行排序；换支持 index-order scan 的引擎（如 PostgreSQL）同一 DDL 可进一步免排序。详见附录 A |

### 测试证据（本次真实执行）

`./mvnw clean test`，21 个测试类，**242 个用例，0 失败 0 错误 0 跳过**：

| 测试类 | 用例数 | 覆盖什么 |
|---|---|---|
| `ProductListTest` | 36 | L0-3：三条件叠加、LIKE 通配符转义、分页边界、越界 400 不夹断 |
| `ProductCreateTest` | 30 | L0-2：字段长度/类型/范围，非法 JSON，SQL 注入按字面量存储 |
| `CommonLayerTest` | 23 | 统一响应结构、异常映射、500 不泄漏堆栈 |
| `ProductDetailAndStatusTest` | 21 | L0-4/L0-5：详情、上下架幂等、非法枚举 |
| `OrderQueryTest` | 20 | L1-7/L1-8：倒序、同时间戳稳定翻页、买家隔离 |
| `OrderValidationTest` | 20 | L1-6：校验顺序短路（quantity 先于商品存在性）、字段长度/空白 |
| `OrderCreateTest` | 17 | L1-6：下单成功、服务端算价、库存不足/下架/不存在、quantity 类型 |
| `JsonStrictnessTest` | 12 | Jackson 严格类型配置确实生效 |
| `SchemaConstraintTest` | 12 | 三表约束：负库存、重复单号、金额不符、重复支付被拒 |
| `PaymentTest` | 10 | L2-9：支付成功、金额服务端决定、重复支付 40903 |
| `AuthTest` | 9 | L2-11：登录签发随机 token、401 业务码 40101/40102、保护范围与公开接口 |
| `ProductBoundaryTest` | 6 | 边界值恰好合法、分页全量遍历不重不漏 |
| `ConcurrentOrderTest` | 5 | L2-10：库存 1，10 并发，恰好 1 成功（`@RepeatedTest` 5 轮） |
| `ConcurrentPaymentTest` | 5 | L2-9 幂等：同一订单 10 并发支付，恰好 1 成功（5 轮） |
| `PurchaseFlowTest` | 4 | 全链路：发布 → 下单 → 支付 → 查询接口可见 |
| `ConcurrentOrderNoUniquenessTest` | 3 | 并发下单 10 个单号互不相同 |
| `ExceptionMappingRegressionTest` | 3 | 415/406 回归、时间格式毫秒精度 |
| `IndexExplainTest` | 2 | L2-13：同一 SQL 加索引前后的 EXPLAIN ANALYZE 与 scanCount 对比 |
| `OrderRollbackTest` | 2 | L1-6 事务：扣库存后插订单失败，库存回滚（双读数证明） |
| `PaymentRollbackTest` | 1 | 支付记录插入失败，订单状态不被更新 |
| `MarketplaceApplicationTests` | 1 | 上下文启动、health 接口 |

## 8. 如果还有一天

1. **给字符串字段（`buyerName`/`title`/`address` 等）补严类型校验**，堵上 `"buyerName": 123` 被静默转成 `"123"` 的边界。技术上不复杂——在现有的 `JacksonConfig` `SimpleModule` 里注册一个自定义 `String` 反序列化器，遇到非文本 token 直接抛 `MismatchedInputException`——但这会改动已经被大量测试覆盖的 DTO 反序列化路径，需要先补一轮回归测试确认不影响已有 242 个用例。排第一，因为它经过权衡仍然选择接受，是全项目唯一一个主动开放的输入口径缺口。

2. **提供一个不依赖 JUnit/Maven 的独立并发验证脚本**（比如 `scripts/concurrent-order.sh`，纯 `curl` + `xargs -P`）。理由：现在的并发验证必须通过 `./mvnw test -Dtest=ConcurrentOrderTest` 才能跑，如果评审环境没有 Maven 或者想快速看一眼「是不是真的不超卖」，当前方式门槛偏高。写在第二位，是因为 `SUBMISSION.md` 已经把测试命令交代清楚，属于锦上添花而不是必需。

3. **给鉴权补上最小生命周期管理：token 过期 + 登出**。现在 token 存服务端内存、永不主动失效（题目明确不要求），但如果这条链路要往真实系统走，第一步就是「怎么让一个 token 失效」——这是一次小型的状态机设计，和支付状态机同类问题，适合当下一轮的练习。

---

## 附录 A：L2-13 索引专项 —— 查询、索引设计与可复核证据

> 题目要求：从已实现的商品或订单查询中选一条 SQL，自行设计并添加索引；在 DESIGN.md 写明查询和典型参数、选择索引列的理由、评审如何复核；用同一份数据和查询参数对比加索引前后的执行计划。

### A.1 选中的查询与典型参数

L1-8 的订单分页查询（`OrderRepository.searchByBuyerPhone` 的数据查询，与接口 `GET /api/orders?buyerPhone=...` 一一对应）：

```sql
SELECT id, order_no, product_id, product_title, quantity, unit_price, total_amount,
       buyer_name, buyer_phone, address, status, created_at, paid_at
FROM orders
WHERE buyer_phone = ?
ORDER BY created_at DESC, id DESC
LIMIT 10 OFFSET 0
```

典型参数：`buyer_phone = '13800000000'`（一个真实买家）、`page = 1`、`pageSize = 10`。这是本系统唯一的买家侧高频查询：手机号等值过滤 + 按时间倒序翻页，订单表随业务增长是数据量最大的表。

### A.2 索引设计与选列理由

```sql
CREATE INDEX idx_orders_buyer_phone_created
    ON orders (buyer_phone, created_at DESC, id DESC);
```

三列各有分工，顺序不可换：

| 列 | 角色 | 为什么是它 |
|---|---|---|
| `buyer_phone` | **等值过滤列**，必须放最左 | 查询是 `WHERE buyer_phone = ?`，最左前缀才能让索引参与过滤；放其他列在左，索引就得全扫或直接失效（最左前缀原则） |
| `created_at DESC` | **排序列** | `ORDER BY created_at DESC`，索引声明 DESC 使索引内的物理顺序与查询顺序一致（方向按降序建，不是靠引擎反向扫描） |
| `id DESC` | **决胜列（对翻页正确性负责）** | 业务排序是 `created_at DESC, id DESC`：`created_at` 是 TIMESTAMP，同毫秒并列时必须有确定次序，否则翻页会重复/漏行（L1-8 的设计里就写死了 id DESC 决胜）。把决胜列纳入索引，索引序与查询序完全同构 |

备选与放弃的原因：

- **单独索引 `(buyer_phone)`**：能过滤但不能提供顺序，每页都要对买家的全部历史订单排序，翻到深页代价线性增长；
- **单独索引 `(created_at)`**：查询不含全局时间过滤，只按手机号，此索引对本查询完全用不上；
- **不建索引**：全表扫描 + 全排序，数据量一大分页不可用（下方实测）。
- 为什么**不给 `products.title` 建索引**：L0-3 的 keyword 检索是 `LIKE '%kw%'` 前后通配，B 树索引天然无效（`docs/决策与遗留问题.md` 的 EXPLAIN 实测表，`LOWER()` 与否都是 tableScan）；方向应该是全文/倒排索引，不是本轮的 B 树。

### A.3 评审如何复核（可重跑）

```bash
cd backend
./mvnw test -Dtest=IndexExplainTest
```

`IndexExplainTest` 自带数据构建（20 个买家 × 每人 1000 单 = 2 万行，`created_at` 逐行递增模拟真实增长），对**同一份数据、同一条 SQL、同一组参数**依次执行：

1. `DROP INDEX` → `EXPLAIN ANALYZE`（无索引基线）；
2. 按 schema.sql 原定义重建索引 → `EXPLAIN ANALYZE`。

两份完整执行计划与 `scanCount` 打印在测试输出里，同时作为断言依据——把 `SUBMISSION.md` L2-13 的表格数字复跑一遍即可核对。

### A.4 实测对比（H2 2.x，本次真实输出）

| 场景 | 执行计划（关键行） | scanCount（物理扫描行数） |
|---|---|---|
| 无索引 | `/* PUBLIC.ORDERS.tableScan */` + 内存排序 | **20001**（全表 2 万行 + 1 次边界探测） |
| 有索引 | `/* PUBLIC.IDX_ORDERS_BUYER_PHONE_CREATED: BUYER_PHONE = ?1 */` | **1001**（目标买家 1000 行 + 1 次边界探测） |

**怎么解释这两个数字**：

- 扫描量从「全表 20001」降到「该买家的 1001」，约 **20 倍**收敛——这是索引作为**过滤**的收益，买家的历史订单再多，也只读属于这个买家的那一段；
- 但要**如实说明**：H2 2.x 在本查询形态下没有利用索引序在 `LIMIT 10` 处提前终止（若免排序成立，scanCount 应约 11），`ORDER BY` 仍对 1001 行做了排序。也就是说本引擎拿到的收益是「扫描范围收敛」，不是「免排序」。这是 H2 优化器的能力边界，不是索引设计的问题：索引序（`buyer_phone` 等值 + `created_at DESC` + `id DESC`）与查询序已完全同构，换成支持 index-order scan 提前终止的引擎（如 PostgreSQL）同一 DDL 即可进一步免排序；若在 MySQL 上复核，用同样的 DDL 与数据在 `EXPLAIN` 里看 `Using index condition` / 是否出现 `Using filesort` 对照即可。

**这个结论本身也说明了设计过程**：索引不是加了就一定有想要的全部收益，实测数字（scanCount 20001 → 1001）和「没有免排序」的如实记录，比只说「加了索引变快了」可信——这也是 A.3 提供可重跑测试的原因：任何人随时能验证或推翻我上面每一个数字。
