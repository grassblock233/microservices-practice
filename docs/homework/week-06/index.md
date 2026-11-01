# week-06 微服务拆分

## 1. 服务拆分设计

### 实现思路

按照数据所有权和业务职责划分边界。用户服务管理用户资料和状态；菜品服务管理售价、上下架与库存；订单服务通过 HTTP 获取用户和菜品信息，保存订单及历史快照。原单体中库存与订单的同一事务拆成各服务自己的本地事务，由订单服务编排顺序调用和失败补偿。

| 单体模块或能力 | 微服务 | 数据表 | 应用端口 | 数据库 / 业务账号 |
|---|---|---|---|---|
| 原订单 `userId` 关联的用户能力，新增最小实体 | `user-service` | `users` | 8081 | `campus_user` / `user_app` |
| `DishController`、`DishService`、菜品模型与 Repository | `dish-service` | `dishes`、`stock_operations`、`stock_operation_items` | 8082 | `campus_dish` / `dish_app` |
| `OrderController`、`OrderService`、订单模型与 Repository | `order-service` | `orders`、`order_items`、`order_attempts` | 8083 | `campus_order` / `order_app` |

每个应用只配置自己的一个数据源，不依赖其他服务的实体、Repository 或 JAR。订单中的用户、商家、菜品关联仅保存业务 ID；订单明细只保留指向本地订单表的外键，名称和价格是订单自己的快照。用户、菜品服务均不回调订单服务。

完整的拆分映射、接口契约、数据归属及 Mermaid 架构图见 [microservices-design.md](../../microservices-design.md)。

### 运行命令与结果

在仓库根目录执行：

```bash
sudo docker compose config --quiet
sudo docker compose up -d --build --wait
bash scripts/smoke-test.sh
```

本次创建订单 `34aace04-b269-4278-89d5-741ff214ca6e`，订单容器通过 `RestTemplate` 真实访问用户、菜品容器，最终返回 201，金额 25.00，库存由 18 变为 16。三个应用与各自数据库独立运行，服务边界对应的跨服务流程已贯通。

## 2. 代码拆分

### 工程结构与实现思路

| 工程 | 独立配置与入口 | 业务代码 | 自动测试 |
|---|---|---|---|
| [user-service](../../../services/user-service/) | `pom.xml`、`application.yml`、`LhtApplication` | `UserController`、`UserService`、`UserRepository` | `UserApiTests` |
| [dish-service](../../../services/dish-service/) | `pom.xml`、`application.yml`、`LhtApplication` | `DishController`、`DishService`、`StockController`、`StockService` | `StockServiceTests`、`StockApiTests` |
| [order-service](../../../services/order-service/) | `pom.xml`、`application.yml`、`LhtApplication` | `OrderController`、`OrderService`、`OrderWriter`、`AttemptService`、HTTP 客户端 | `OrderFlowTests` |

基础包均为 `com.zjgsu.lht`，业务代码分别放在 `controller`、`service`、`repository`、`model`、`dto` 子包；订单应用另有 `client` 包。每个工程可单独构建和运行，不扫描其他工程。

下单的实现顺序如下：

1. 校验参数，合并重复菜品数量，拒绝数量溢出。
2. `UserClient` 查询用户，要求存在且为 `ACTIVE`。
3. `DishClient` 批量预校验菜品；失败立即返回，尚未创建本地记录。
4. 生成 UUID 操作号，独立事务提交 `PROCESSING` 下单记录。
5. 菜品服务在一个事务中认领操作号、锁定全部菜品、再次校验并批量扣减，保存名称和价格快照。
6. 订单服务使用扣减返回的权威快照计算金额，在一个本地事务中保存订单、明细并更新下单记录为 `SUCCEEDED`。
7. 提交成功后返回 201；订单保存失败则核对本地提交状态，再决定补偿或记录 `RECOVERY_REQUIRED`。

库存操作使用唯一 `orderNo` 和请求内容摘要去重，同号不同内容返回 409。首次认领使用插入语句，唯一键冲突后整个本地事务重试，避免 ORM 合并覆盖并发的释放记录。菜品按 ID 升序加锁，扣减全部成功或全部回滚。重复释放只回补一次；释放先到会持久化 `RELEASED` 终态，拒绝后来的扣减。

### HTTP 地址与超时

[订单 application.yml](../../../services/order-service/src/main/resources/application.yml) 声明用户、菜品地址及超时，支持 `USER_SERVICE_URL`、`DISH_SERVICE_URL`、`HTTP_CONNECT_TIMEOUT`、`HTTP_READ_TIMEOUT` 覆盖，默认连接超时 2 秒、读取超时 5 秒。

[ClientProperties](../../../services/order-service/src/main/java/com/zjgsu/lht/client/ClientProperties.java) 使用 `@ConfigurationProperties` 绑定配置；[HttpClientConfiguration](../../../services/order-service/src/main/java/com/zjgsu/lht/client/HttpClientConfiguration.java) 将连接超时设置到 JDK `HttpClient`，读取超时设置到 `JdkClientHttpRequestFactory`，交给 `RestTemplate` 使用。应用不在失败后跳过业务校验，不对 HTTP 写请求增加自动重试。

明确业务错误映射为 404 或 409；连接失败为 503、超时为 504、非法响应或非预期服务端响应为 502。客户端还校验下游状态码、操作号、明细、数量和金额格式；202 等非提交成功响应不能作为库存扣减成功的依据。

### 测试命令与结果

本机安装 Java 25、Maven 3.9+ 后，在仓库根目录执行：

```bash
mvn -f services/user-service/pom.xml test
mvn -f services/dish-service/pom.xml test
mvn -f services/order-service/pom.xml test
```

| 工程 | 测试数 | 失败 / 错误 / 跳过 | 主要验证内容 |
|---|---|---|---|
| 用户 | 4 | 0 / 0 / 0 | 有效用户、禁用状态、不存在用户、健康接口 |
| 菜品 | 14 | 0 / 0 / 0 | 批量事务回滚、不可售或跨商家拒绝、操作幂等、数量溢出、并发防超卖、释放与扣减竞争、参数校验 |
| 订单 | 22 | 0 / 0 / 0 | 权威价格快照、预校验不落库、依赖连接或超时错误、订单写入回滚、同步补偿、恢复记录、提交结果核对 |
| 合计 | **40** | **0 / 0 / 0** | 三个独立工程均通过 |

订单事务回滚测试在订单已写入后注入明细刷新失败，验证订单和明细确实回滚，再调用释放接口；补偿失败测试验证 `RECOVERY_REQUIRED` 留痕。自动测试使用 H2 和受控 HTTP 响应，本次 Compose 冒烟另行贯通实际容器间 HTTP 调用。

## 3. 本地编排

### 实现思路

[根 compose.yaml](../../../compose.yaml) 编排三个应用和三个 MySQL 8.4.11 实例。构建上下文分别是各自服务目录；各 [Dockerfile](../../../services/order-service/Dockerfile) 在 Maven/JDK 25 构建阶段执行包含测试的 `clean package`，运行阶段复制 JAR，使用 JRE 25、非 root 用户及健康检查。

| 数据库服务名 | 独立数据库 | 应用账号 | 命名数据卷 | 初始化脚本 |
|---|---|---|---|---|
| `user-db` | `campus_user` | `user_app` | `user-data` | [user/init.sql](../../../scripts/microservices/user/init.sql) |
| `dish-db` | `campus_dish` | `dish_app` | `dish-data` | [dish/init.sql](../../../scripts/microservices/dish/init.sql) |
| `order-db` | `campus_order` | `order_app` | `order-data` | [order/init.sql](../../../scripts/microservices/order/init.sql) |

三个数据库不映射宿主机端口，分别挂载 `/var/lib/mysql`。业务密码和 root 密码由 [.env.example](../../../.env.example) 中对应的六个变量注入，各应用只持有自身业务账号的凭据。数据库初始化表及同库外键不涉及其他服务的数据表。

所有容器加入 `campus-network`。容器之间通过 Compose 服务名通信，`localhost:8081/8082/8083` 用于宿主机访问。数据库健康检查以业务账号执行 `SELECT 1`；应用通过 Actuator 检查本服务数据库，订单启动还等待用户和菜品服务健康。

### 运行命令与结果

首次复制配置并填写六个独立密码后执行：

```bash
cp -n .env.example .env
sudo docker compose config --quiet
sudo docker compose up -d --build --wait
sudo docker compose ps
```

本次构建完成三个应用镜像，并创建独立的 `campus-takeaway_user-data`、`campus-takeaway_dish-data`、`campus-takeaway_order-data` 数据卷。部署输出中以下六个容器全部为 **Healthy**：

```text
campus-takeaway-user-db-1
campus-takeaway-dish-db-1
campus-takeaway-order-db-1
campus-takeaway-user-service-1
campus-takeaway-dish-service-1
campus-takeaway-order-service-1
```

原单体配置保存在 [compose.monolith.yaml](../../../compose.monolith.yaml)，原源码和初始化脚本保留。切换和运行命令见 [README](../../../README.md#保留的单体版本)。

## 4. 验证

### 脚本与执行命令

[冒烟脚本](../../../scripts/smoke-test.sh) 可从仓库根目录直接运行，依赖 `curl` 与 `python3`。脚本逐一检查健康、用户、菜品、订单、库存操作及失败场景；错误状态码、错误业务码或数据断言不符合预期时非零退出。每次运行会实际创建一笔订单并扣减两份库存。

```bash
bash scripts/smoke-test.sh
```

### 成功请求与实际数据变化

本次脚本发送的下单内容如下；两个相同菜品条目由服务合并为数量 2：

```http
POST http://localhost:8083/api/orders
Content-Type: application/json

{
  "userId": "student-1",
  "merchantId": 10,
  "items": [
    {"dishId": 1, "quantity": 1},
    {"dishId": 1, "quantity": 1}
  ],
  "deliveryAddress": "冒烟测试宿舍",
  "remark": "week-06 smoke"
}
```

| 检查项 | 本次实际结果 |
|---|---|
| 三个 `GET /actuator/health` | 均为 200，`status=UP` |
| 用户和商家菜品查询 | 均为 200 |
| `POST /api/orders` | 201 |
| 新订单号 | `34aace04-b269-4278-89d5-741ff214ca6e` |
| 总金额、状态 | `25.00`、`PENDING_PAYMENT` |
| 订单明细 | 菜品 1，合并后数量 2，单价 12.50，小计 25.00 |
| 菜品 1 库存 | 18 → 16 |
| 按订单号查询详情 | 200；金额与明细断言通过 |
| 同号库存操作查询 | 200；状态为 `DEDUCTED`，快照单价与订单一致 |
| 学生订单列表 | 成功下单后比请求前增加 1 条 |

对应输出：

```text
PASS orderNo=34aace04-b269-4278-89d5-741ff214ca6e amount=25.00 stock=18->16
```

### 失败请求与实际结果

以下请求均调用 `POST http://localhost:8083/api/orders`，以成功请求为基础替换表中的字段，其余字段保持不变：

| 场景 | 请求差异 | HTTP 状态 | 错误码 |
|---|---|---|---|
| 用户不存在 | `userId` 为 `missing-` 加随机 UUID | 404 | `USER_NOT_FOUND` |
| 用户禁用 | `userId=student-disabled` | 409 | `USER_DISABLED` |
| 菜品不存在 | `items=[{"dishId":9223372036854775807,"quantity":1}]` | 404 | `DISH_NOT_FOUND` |

三次失败后脚本重新查询 `student-1` 的订单列表和商家 10 的菜品列表，与成功下单后的响应比较均相同；没有额外扣减库存或改变该学生的订单列表。自动测试还验证这些预校验失败不会写入本地下单记录、订单或明细。

```text
PASS rejected USER_NOT_FOUND
PASS rejected USER_DISABLED
PASS rejected DISH_NOT_FOUND
PASS rejected requests left orders and inventory unchanged
All smoke checks passed. Keep orderNo=34aace04-b269-4278-89d5-741ff214ca6e for persistence verification.
```

### 远端成功、本地失败的窗口与处理

菜品服务扣减库存提交后，订单服务保存本地订单前，两次事务之间存在网络或进程故障窗口。库存操作成功并不代表订单已经保存，普通 `@Transactional` 无法回滚另一个服务的数据库。

当前实现使用独立提交的 `order_attempts` 跟踪流程。订单写入失败时先按同一操作号核对本地订单；已确认成功则保留扣减，确认无订单则同步调用释放接口。释放成功记录 `FAILED`，释放失败或提交结果不明记录 `RECOVERY_REQUIRED`，并在错误和日志中携带 `orderNo`。扣减结果未知时也使用同号释放，释放终态能阻止迟到请求再次扣库存。

人工处理遗留 `PROCESSING` 或 `RECOVERY_REQUIRED` 时，先确认原请求已结束，再查订单及库存操作；确认没有有效订单后才通过释放 API 补偿，在订单库记录核对依据与处理结果。各服务只修改自己的数据库。代码提供同步补偿和人工核对依据，不提供跨库 ACID、自动恢复任务或客户端重新提交的业务幂等。

停止依赖后的复现命令、容器重建前后订单与库存的比较步骤见 [README 故障演示](../../../README.md#故障演示与恢复) 和 [持久化复现](../../../README.md#数据持久化复现)。这些步骤使用同一验收订单进行查询，普通 `docker compose down` 保留命名卷。
