# 提交说明（SUBMISSION）

> 复制到你的方向目录（`frontend/` / `backend/` / `testing/`）下填写。
> **评审会照着你写的启动命令真的跑一遍**，所以它必须能跑通。

## 基本信息

| 项 | 内容 |
|---|---|
| 姓名 |  |
| 学号 |  |
| 方向 | 前端 / 后端 / 测试 |
| 分支 | `320210912345`（你的学号，已预建） |
| 实际投入 | ?? 小时 |

## 环境

| 项 | 版本 |
|---|---|
| 操作系统 | macOS 14 / Windows 11 / Ubuntu 22.04 |
| 运行时 | Node 20.x / JDK 17 / Go 1.22 / Python 3.12 |
| 包管理器 | npm / pnpm / Maven / pip |

## 启动方式（必填，要能照抄）

```bash
cd backend

# 启动服务（H2 内存库，无需外部依赖；schema.sql 由 classpath 自动加载）
./mvnw spring-boot:run
# 打开 http://localhost:8080/api/health，应看到 {"code":0,"message":"ok","data":null}
```

> Windows 下把 `./mvnw` 换成 `mvnw.cmd`。首次运行会自动下载依赖。

## 我做到了哪一步

| 层级 | 完成情况 |
|---|---|
| L0 | 做了 5 / 5 条（建表 / 发布 / 列表 / 详情 / 上下架） |
| L1 | 做了 4 / 4 条（下单 / 订单详情 / 订单列表 / 参数校验） |
| L2 | 做了第 10 项（并发不超卖） |

## 测试 / 自检

在 `backend/` 下运行全部测试：

```bash
cd backend
./mvnw test          # Windows 用 mvnw.cmd test
```

预期输出以 `Tests run: 182, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS` 结尾。

其中并发测试单独运行（可重复 5 轮）：

```bash
./mvnw test -Dtest=ConcurrentOrderTest
```

## L2-10 并发不超卖：如何准备数据、运行与核对结果

### 准备数据

无需手工准备。测试类 `ConcurrentOrderTest` 的 `@BeforeEach` 会：

1. 清空 `payments` / `orders` / `products` 三张表；
2. 插入一件**库存为 1、状态为 on** 的商品。

### 运行

```bash
./mvnw test -Dtest=ConcurrentOrderTest
```

测试用 `RANDOM_PORT` 启动真实 Tomcat，用 `CountDownLatch` 让 10 个独立任务
（`Executors.newFixedThreadPool(10)`）同时向 `POST /api/orders` 发请求，
每个任务买 1 件，走真实 HTTP 链路（JDK HttpClient → Controller → `@Transactional`
代理 → `SELECT ... FOR UPDATE` → H2），不重试、不吞异常、不串行化。

### 核对结果（测试内已断言，四步）

| # | 断言 | 依据 |
|---|---|---|
| 1 | 恰好 1 个请求 HTTP 201 | 统计 10 个 `Future.get()` 返回的状态码 |
| 2 | 其余 9 个请求 HTTP 409 且业务码 40901 | 逐条检查响应体的 `code` 字段 |
| 3 | 最终商品库存为 0 | `SELECT stock FROM products WHERE id=?` |
| 4 | 本轮新增订单恰好 1 条 | `SELECT COUNT(*) FROM orders WHERE product_id=?` |

`@RepeatedTest(5)` 连跑 5 轮，每轮重新建数据，验证结果可重复、非偶然通过。

### 为什么这样测是「真并发」而不是「伪装并发」

- 用真实 HTTP + 真实 Tomcat 线程池，而非 MockMvc 的调用线程内执行；
- `CountDownLatch` 两级同步（`ready` 全部就绪 → `startGate` 统一放行）；
- 失败时 `Future.get()` 直接抛出执行异常让测试失败，不捕获业务异常；
- 断言的是数据库最终状态（库存 0、订单 1），不是只看响应码。


## 没做完的地方

| 项 | 状态 | 原因 |
|---|---|---|
|  |  |  |

## 自检清单

- [ ] 删掉依赖目录后，按上面的命令能重新跑起来
- [ ] 没提交 `node_modules` / `target` / `*.db` / `.env`
- [ ] `DESIGN.md`、`AI_USAGE.md` 已填写完整
- [ ] 截图放进了 `screenshots/`（前端）
