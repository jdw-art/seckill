# Seckill 秒杀系统

基于 Spring Boot 3 的多模块秒杀实战项目，覆盖从商品预热、秒杀下单、异步建单到 MQ 失败补偿的完整链路。项目以学习为目的，重点演示秒杀场景下的高并发防护与可靠性设计。

## 技术栈

| 分类 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 3.5.8、Spring MVC、Spring AOP |
| 持久层 | MyBatis 3、MySQL |
| 缓存 | Redis（Lettuce）+ Redisson、Caffeine 本地缓存 |
| 消息队列 | RabbitMQ（Spring AMQP，手动 ACK） |
| 认证 | Sa-Token 1.45.0 |
| 人机校验 | tianai-captcha 1.5.5 行为验证码 |
| 重试 | Spring Retry |
| 工具 | Hutool、Lombok、Log4j2 |
| 构建 | Maven 多模块 |

## 模块结构

```
seckill
├── seckill-app        # 启动入口，聚合各模块
├── seckill-common     # 公共配置、DO/Mapper、Lua 脚本、工具类、异常与响应码
├── seckill-user       # 注册登录、验证码
├── seckill-goods      # 商品列表/详情、活动缓存预热
└── seckill-order      # 令牌、秒杀下单、订单结果查询、MQ 生产与消费、补偿任务
```

## 核心链路

```
获取行为验证码 → 获取秒杀令牌（验证码二次校验 + 令牌大闸）
        ↓
携带 token 秒杀下单（限流 → 队列泄洪 → 令牌一次性校验）
        ↓
Redis Lua 原子校验活动时间 / 一人一单 / 预扣库存
        ↓
写处理中状态 → 发送 RabbitMQ → 立即返回 orderNo
        ↓
消费者手动 ACK：扣 MySQL 库存 + 创建订单 + SSE 通知结果
```

### 高并发防护

- **行为验证码**：获取令牌前要求完成滑块等验证，提高脚本批量刷令牌的成本。
- **令牌大闸**：按 `库存 × 放大系数` 预热名额，限制新令牌发放总量；Lua 内原子完成"复用旧令牌 / 扣名额 / 写新令牌"。
- **用户级令牌**：60 秒有效、一次性使用，Lua 原子比对并删除。
- **接口限流**：`@SeckillRateLimit` + Lua 固定窗口，按用户和 IP 双维度限流。
- **队列泄洪**：固定 20 线程、100 排队的线程池，队列满直接返回 `40009`。
- **缓存预热**：活动商品列表、详情、元数据、库存、令牌大闸提前写入 Redis，并用分布式锁避免并发预热。
- **库存展示**：商品列表/详情优先读取 Redis 实时库存，缓存未命中时回源并补充。

### 可靠性保障

- **Lua 预扣库存**：活动时间、一人一单、库存扣减在 Redis 内原子完成。
- **售罄快速失败**：库存扣到 0 时写入售罄标记，后续请求在入口直接拦截；补偿回补库存时删除标记。
- **MQ 投递确认**：开启 Confirm + Return；Nack/不可路由时按订单号回补 Redis 库存。
- **同步发送重试**：`convertAndSend` 抛 `AmqpException` 时按 1s、2s 退避重试 3 次。
- **发送失败补偿**：重试耗尽写入 `t_seckill_order_send_compensation`，定时任务每 10 秒扫描重发，失败按指数退避，超过 5 次转人工处理。
- **幂等**：用户购买标记值为订单号，补偿回补时校验订单号；订单表 `order_no` 唯一索引防止重复建单。

## Redis Key 一览

| Key | 用途 |
|---|---|
| `seckill:goods:list:{activityId}` | 活动商品列表缓存 |
| `seckill:goods:detail:{activityId}:{goodsId}` | 商品详情缓存 |
| `seckill:activity:goods:meta:{activityId}:{goodsId}` | 下单入口元数据（价格、名称、图片、起止时间） |
| `seckill:stock:{activityId}:{goodsId}` | 秒杀实时库存 |
| `seckill:user:order:{activityId}:{goodsId}:{userId}` | 用户购买标记（值为订单号） |
| `seckill:soldout:{activityId}:{goodsId}` | 售罄标记 |
| `seckill:order:token:{activityId}:{goodsId}:{userId}` | 用户级秒杀令牌，TTL 60s |
| `seckill:token:gate:{activityId}:{goodsId}` | 秒杀令牌大闸名额 |
| `seckill:order:compensation:{orderNo}` | MQ 失败回补上下文 |
| `seckill:order:status:{userId}:{orderNo}` | 订单异步处理状态 |
| `seckill:rate:user:{activityId}:{goodsId}:{userId}` | 用户维度限流计数 |
| `seckill:rate:ip:{activityId}:{goodsId}:{clientIp}` | IP 维度限流计数 |
| `seckill:lock:activity:preheat` | 活动预热分布式锁 |

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/register` | 注册 |
| POST | `/user/login` | 登录，返回 Sa-Token |
| POST | `/captcha/gen` | 生成行为验证码 |
| POST | `/captcha/check` | 校验行为验证码，返回 `captchaId` |
| POST | `/admin/seckill/goods/cache/preheat` | 预热活动商品缓存（管理端） |
| POST | `/seckill/goods/list` | 秒杀商品列表 |
| POST | `/seckill/goods/detail` | 秒杀商品详情 |
| POST | `/seckill/order/token` | 获取秒杀令牌（需登录，携带 `captchaId`） |
| POST | `/seckill/order` | 秒杀下单（需登录，携带 `token`） |
| POST | `/seckill/order/result` | 查询订单处理结果 |
| POST | `/seckill/order/result/subscribe` | SSE 订阅订单结果 |

除注册、登录、验证码与商品查询外，秒杀相关接口需要在请求头携带 `Authorization: Bearer <token>`。

## 本地运行

### 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8
- Redis
- RabbitMQ（需创建虚拟主机 `/seckill`）

### 1. 初始化数据库

执行 [sql/seckill.sql](sql/seckill.sql)，创建业务表和发送失败补偿表。

### 2. 配置密钥

复制 `.env.example` 为 `.env`，填写真实值（`.env` 已被 git 忽略，切勿提交）：

```properties
DB_PASSWORD=...
REDIS_PASSWORD=...
RABBITMQ_USERNAME=...
RABBITMQ_PASSWORD=...
```

连接地址、端口等非敏感配置在 [application-dev.yml](seckill-app/src/main/resources/application-dev.yml) 中修改。

### 3. 启动

```bash
mvn -DskipTests compile
mvn -pl seckill-app -am spring-boot:run
```

或直接运行 `com.jacob.seckill.app.SeckillApplication`，默认端口 `8009`。

### 4. 预热活动

启动后先调用管理端预热接口，将活动商品写入 Redis，否则下单入口会因元数据缺失而失败。

## 秒杀下单示例

```http
POST /seckill/order/token
Authorization: Bearer <token>

{"activityId": 1, "goodsId": 1, "captchaId": "<captchaId>"}
```

```http
POST /seckill/order
Authorization: Bearer <token>

{"activityId": 1, "goodsId": 1, "token": "<后端签发的秒杀令牌>"}
```

下单接口立即返回 `orderNo` 与 `处理中` 状态，最终结果通过 `/seckill/order/result` 轮询或 `/seckill/order/result/subscribe` 订阅。

## 说明

- 本项目为学习项目，部署到生产前还需补充：配置中心、可观测性、定时任务分布式调度、密钥管理、压测与容量评估等。
- `application-prod.yml` 目前为空，切换生产环境前需补全配置。
