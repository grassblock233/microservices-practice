# API 网关与 JWT 安全设计

## 认证流程

1. 客户端向网关发送 `POST /api/auth/login`，请求体为 `{"username":"student1","password":"<本地演示密码>"}`。网关不要求登录请求携带 token，并先清除客户端身份头，再转发给 `user-service`。
2. 用户服务按用户名在 `users` 表定位用户，用 BCrypt 比对同一行的密码哈希，并要求状态为 `ACTIVE`（学生和管理员都一样）。未配置登录凭证的资料仍可查询，但不能登录。账号不存在、密码不对或用户不可用时，统一返回 `401 INVALID_CREDENTIALS`；用户查询响应不返回认证字段。
3. 验证成功返回 `200` 和 `{token, username, role}`。JWT 使用 `HS512` 签名，`sub` 为业务用户 ID，另含 `username`、`role`、`iat`、`exp`，有效期为一小时。仅学生令牌另带 `studentId`；当前用户模型没有独立学号字段，签发时用学生业务 ID 填充。令牌不写入 `iss` 或密码。网关和用户服务通过环境变量共享 `JWT_SECRET`，Base64 解码后至少 64 字节；不同项目或环境不要复用该密钥。
4. 客户端通过 `Authorization: Bearer <token>` 调用受保护接口。网关使用共享密钥验签，检查过期时间及身份字段，不要求 `iss` 或 `iat`，也不额外配置算法白名单；没有凭证或凭证无效返回 `401`，通过认证但权限不足返回 `403`。
5. 网关删除来访请求的 `X-User-Id`、`X-Username`、`X-User-Role`、`X-Student-Id`，从已验证的 JWT 重建前三个身份头，有 `studentId` 声明时才写入 `X-Student-Id`。原始 `Authorization` 随请求向下游传递。订单服务仍通过 `X-User-Id` 和角色检查请求用户与实际订单归属。下游日志不应记录 `Authorization`。

演示账号为 `student1`（`STUDENT`，业务 ID `student-1`）以及 `admin`（`ADMIN`，业务 ID `admin`）。初始密码分别来自 `DEMO_STUDENT_PASSWORD` 和 `DEMO_ADMIN_PASSWORD`，用于为已有学生资料设置凭证和创建管理员用户行。数据库仅保存 BCrypt 哈希，修改环境变量不会覆盖现有密码或改变用户状态。合并后的表结构只支持全新初始化，不迁移旧卷。

## 网关路由与角色权限

默认入口为 `http://localhost:8080`。启动配置和四条路由均声明在根目录 [`compose.yaml`](../compose.yaml) 的 `gateway-service` 下；Compose 通过 `SPRING_APPLICATION_JSON` 注入路由。`lb://` 目标由网关根据 Nacos 服务发现结果选择实例，不使用固定的容器 IP。

| 路由 ID | 方法与路径 | 目标 | 访问规则 |
| --- | --- | --- | --- |
| — | `GET /actuator/health` | 网关本地 | 公开 |
| `login` | `POST /api/auth/login` | `lb://user-service` | 公开 |
| `users` | `GET /api/users/{userId}` | `lb://user-service` | `STUDENT` 只查本人；`ADMIN` 可跨用户查询 |
| `dishes` | `GET /api/dishes?merchantId=…` | `lb://dish-service` | 两种角色均可访问 |
| `orders` | `POST /api/orders` | `lb://order-service` | 只允许 `STUDENT`，请求体中的 `userId` 必须是本人 |
| `orders` | `GET /api/orders?userId=…`、`GET /api/orders/{orderNo}` | `lb://order-service` | `STUDENT` 只查自己的订单；`ADMIN` 可跨用户查询 |
| — | `/internal/**` | 不配置路由 | 不经网关暴露；库存接口保留内部 Feign 调用 |

## 防止绕过网关

Compose 不发布用户、菜品、订单服务的 `8081`、`8082`、`8083` 端口。业务客户端通过网关访问服务；订单服务在容器网络内通过 Nacos 和 Feign 调用用户查询和 `POST /internal/stock/deduct`。网关负责认证和入口级授权，订单服务负责订单归属检查。下游不会独立验证外部 JWT，因此容器网络内可以访问业务服务的工作负载必须可信；部署时不应开放内部端口或接入不可信容器。对外部署应在可信入口启用 HTTPS，避免 Bearer token 明文传输。

部署与密钥配置见 [README](../README.md)，本机 API 验收结果见 [第 09 周记录](homework/week-09/index.md)。
