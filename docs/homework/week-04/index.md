# week-04 单体应用基本实现与数据库持久化

## 业务建模

### 建模结果
本周先实现**学生提交订单**，完整说明见 [项目提案](../../project-proposal.md)。

### 实现思路

本周计划基于现有 Spring Boot 单体应用中，采用 MySQL 和 Spring Data JPA，完成“学生提交订单”流程，以及菜品查询、学生订单列表查询和订单详情查询。

### 项目结构

在现有 `com.zjgsu.lht` 包下组织业务代码：

```text
com.zjgsu.lht
├── controller    # OrderController、DishController
├── service       # OrderService、DishService
├── repository    # OrderRepository、DishRepository、OrderItemRepository
├── model         # Order、Dish、OrderItem
├── dto           # OrderDtos、DishDtos
└── exception     # GlobalExceptionHandler、资源不存在及业务异常
```

- **Controller**：负责 HTTP 输入输出，接收请求、执行基础参数校验、调用 Service，并返回响应。
- **Service**：组织下单流程，负责业务校验、金额计算和事务控制。
- **Repository**：继承 `JpaRepository`，完成数据库读写及必要的条件查询。
- **Model**：定义 JPA 实体，映射菜品、订单和订单明细三张表。
- **DTO**：使用 Java `record` 在同一业务 DTO 类中集中定义请求、明细和响应类型，避免直接对外返回实体。
- **Exception**：通过 `@RestControllerAdvice` 统一处理异常，使用 `ProblemDetail` 返回错误信息。

各层使用构造器注入依赖，测试目录同样按 `controller`、`service`、`repository` 分包。

### 接口与参数校验

本周提供以下接口，包含创建与查询：

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/orders` | 提交订单 |
| GET | `/api/dishes?merchantId={merchantId}` | 查询指定商家的上架菜品 |
| GET | `/api/orders?userId={userId}` | 查询指定学生的订单列表 |
| GET | `/api/orders/{orderNo}` | 查询订单及其明细 |

学生订单列表接口要求传入非空的 `userId`，通过 `OrderController → OrderService → OrderRepository` 按学生 id 查询，仅返回该学生的订单摘要，包含订单号、总金额、状态和创建时间。查询成功返回 HTTP `200`，没有订单时返回空列表，缺少或传入空白 `userId` 时返回 `400`。

下单请求包含 `userId`、`merchantId`、`items`、`deliveryAddress` 和可选的 `remark`。每条明细只传入 `dishId` 和 `quantity`，菜品名称、单价、小计和订单总金额均由服务端确定。

引入 Bean Validation，通过 `@Valid` 及字段约束检查必填项、明细非空、数量为正整数等条件，并对嵌套明细执行校验。

创建成功后返回 HTTP `201`，响应包含订单号、总金额和待支付状态，`Location` 指向订单详情接口。参数错误返回 `400`，菜品或订单不存在返回 `404`，跨商家、菜品下架或库存不足返回 `409`，并通过 `ProblemDetail` 说明具体原因。

### 下单流程与事务处理

下单时，需要根据业务规则[R1：下单资格校验](../../project-proposal.md)。菜品必须存在、属于请求中的商家、处于上架状态且库存不少于购买数量；任一条件不满足时整单拒绝。该规则在 Service 中实现，与 Controller 的参数格式校验区分处理。

在 `OrderService.createOrder` 中按以下顺序处理：

1. 按 `dishId` 合并重复菜品的购买数量，避免分开校验导致总购买数量超过库存。
2. 查询菜品，逐项校验菜品存在、属于请求商家、处于上架状态且库存充足。
3. 使用 `BigDecimal` 根据数据库中的当前单价计算明细小计和订单总金额。
4. 服务端生成 UUID 作为 `orderNo`，创建待支付订单，记录学生、商家、配送地址、备注及创建时间。
5. 创建订单明细，保存菜品名称、单价、数量和小计快照，并扣减对应库存。
6. 保存订单、明细和库存变更，成功后返回下单结果。

在该方法上使用 `@Transactional`，将订单保存、明细保存和库存扣减纳入同一事务。业务失败时抛出运行时异常，持久化异常继续向外传播，使任一步骤失败后，订单、明细和库存变更全部回滚，避免出现部分菜品已扣库存但订单未创建的情况。

### 数据库与持久化

引入 Spring Data JPA、MySQL 驱动和参数校验依赖，在应用配置中设置数据库连接，连接信息支持通过环境变量提供。

- `Dish`、`Order`、`OrderItem` 分别映射 `dishes`、`orders`、`order_items`。
- 订单通过 `userId`、`merchantId` 记录学生和商家；明细通过 `orderId`、`dishId` 关联订单和菜品，一张订单对应多条明细。
- 金额使用 MySQL `DECIMAL` 类型，与 Java `BigDecimal` 对应。
- 订单内部主键 `id` 使用自增 `BIGINT`；对外订单号 `orderNo` 由服务端生成 UUID，映射到数据库 `order_no` 字段，以 `CHAR(36)` 保存，并设置 `NOT NULL` 和唯一约束，用于对外展示与查询；订单号唯一不代表已实现重复请求幂等。
- 明细保存下单时快照，后续菜品改名、改价不影响历史订单。

准备建表和菜品初始化 SQL，供本地 MySQL 环境使用。本周通过初始化数据准备菜品，不增加菜品管理接口。

### 测试与验证

自动化测试使用 H2，测试配置与本地 MySQL 配置分离；另外在 MySQL 中验证真实落库与重启持久化。

重点覆盖以下场景：

- 接口测试：覆盖全部 4 个业务接口，验证菜品查询、正常下单、订单列表和详情查询，以及参数校验、错误状态码与 `ProblemDetail` 响应；订单列表需验证按 `userId` 筛选、无订单时返回空列表、缺少或传入空白 `userId` 时返回 `400`。
- 业务测试：金额计算、重复菜品合并、菜品不存在、跨商家、下架和库存不足。
- 持久化测试：订单与明细保存正确，库存扣减正确，修改菜品名称或价格后历史快照保持不变。
- 事务测试：在已发生数据库写入后制造失败，待事务结束后重新查询，确认订单、明细及所有库存变更均已回滚。
- MySQL 验证：执行建表和初始化 SQL，完成下单后重启应用，确认订单仍可查询。

本周不实现用户、商家、登录认证、支付及完整 CRUD；下单时 `userId` 从请求体传入，仅校验格式并记录，订单列表查询时从查询参数传入作为筛选条件，不查询用户表。本周暂不处理并发下单和重复请求幂等问题。

## 单体应用实现

### 实现思路

基于 `monolith` 中的 Spring Boot 4.0.8 和 JDK 25，已完成菜品查询、学生提交订单、学生订单列表和订单详情四个接口。代码按 Controller、Service、Repository 分层，使用 DTO 接收请求和返回结果，使用 Bean Validation 校验参数，通过统一异常处理返回 `ProblemDetail`。

下单逻辑集中在 `OrderService.createOrder`：先合并重复菜品数量，再校验菜品是否存在、商家归属、上架状态和库存；使用数据库单价与 `BigDecimal` 计算金额，生成 UUID 订单号和 `PENDING_PAYMENT` 状态的订单，保存明细快照并扣减库存。整个过程使用 `@Transactional`，菜品实体的库存修改由 JPA 脏检查写回数据库，任一步骤失败都会回滚订单、明细和库存变更。

### 运行命令

运行该项目的方法参见[README.md](../../../README.md)

## 数据库持久化

### 实现思路

数据持久化使用 Spring Data JPA 和 MySQL，实体分别映射到 `dishes`、`orders`、`order_items`。订单详情从订单明细读取名称、单价等历史快照；学生订单列表按创建时间倒序、同一时间按内部 id 倒序返回。建表和示例数据由根目录的 [scripts/init.sql](../../../scripts/init.sql) 提供，该项目不会自动建表。

### 运行命令

数据库初始化参见[README.md](../../../README.md)

## 测试与验证

### 实现思路

测试按 Controller、Service 和 Repository 分层组织：接口层模拟 Service，关注请求校验、响应字段、状态码和异常映射；业务层与持久化层基于独立的 H2 测试配置和真实 Repository，验证下单规则、金额计算、数据保存和查询排序。各测试上下文使用独立的内存数据库，与本地 MySQL 配置相互隔离。

用例覆盖正常流程、异常情况和边界条件。涉及持久化的断言先 `flush`、再清空持久化上下文并重新查询，确认数据库中的实际结果，避免一级缓存掩盖问题；同时验证菜品改名、改价后历史订单仍保留下单时的名称、单价和金额快照。事务测试在明细保存环节先确认订单、明细和库存变更已写入数据库，再抛出异常，待 Service 事务结束后在新事务中检查回滚结果。测试类与用例均以中文注释说明测试目的和验证方式。H2 测试不能替代真实 MySQL 的建表与约束验证，也不覆盖重启后的持久化验证。

### 测试项目

| 测试项目 | 测试类 | 主要验证内容 |
|---|---|---|
| 应用启动 | `LhtApplicationTests` | 使用 H2 测试配置加载应用上下文，验证配置与 Bean 依赖能够正常装配。 |
| 菜品查询接口 | `DishControllerTests` | 验证菜品响应字段、库存为零的售罄菜品和空列表；商家编号缺失、空白、非正数或格式错误时返回 `400`，且不调用业务层。 |
| 订单业务接口 | `OrderControllerTests` | 验证下单返回 `201` 与 `Location`、备注可省略、订单列表返回摘要、无订单返回空数组、详情包含明细快照；覆盖必填项缺失、长度越界、嵌套明细、非法数量、JSON 格式错误及列表 `userId` 缺失或空白等 `400` 场景，并检查业务异常映射为 `400/404/409` 的 `ProblemDetail` 响应。 |
| 下单规则与持久化 | `OrderServiceTests` | 验证金额计算、UUID 订单号与待支付状态、重复菜品合并、合并后数量恰好等于库存及数量溢出；逐一覆盖菜品不存在、跨商家、下架和库存不足，确认整单拒绝且不残留订单、明细或库存变更，并检查明细快照及订单摘要、菜品查询结果的映射。 |
| 数据查询与排序 | `BusinessRepositoryTests` | 验证按商家和上架状态筛选菜品、按学生筛选订单、按订单号查找以及空结果；检查菜品和明细按菜品 ID 升序、订单按创建时间及 ID 倒序返回。 |
| 事务回滚 | `OrderTransactionTests` | 先真实保存并 `flush`，再注入异常，待 Service 事务结束后重新查询，确认新增订单、明细和全部库存变更均已回滚，原有数据保持不变。 |

### 运行命令

测试的运行方法参见[README.md](../../../README.md)