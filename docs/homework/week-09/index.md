# 第 09 周：API Gateway 与 JWT 身份认证

2026 年 11 月 22 日，对本机已部署的校园外卖服务（`http://localhost:8080`）进行了真实 HTTP 验收。该次验收未重新部署、执行迁移或创建订单。本文的实现与命令已按当前 Compose 配置更新；HTTP 结果仍是路由迁移前的现场记录，不是迁移后的复测结果。文档不保存真实密码、密钥或 token。

## API Gateway

**实现思路。** 在 `services/gateway-service/` 新建独立的 Spring Cloud Gateway WebFlux 工程。当前版本在根目录 [`compose.yaml`](../../../compose.yaml) 的 `gateway-service` 下声明启动配置，以及通过 `SPRING_APPLICATION_JSON` 注入的登录、用户、菜品、订单四条路由。路由使用 `lb://user-service`、`lb://dish-service`、`lb://order-service` 作为目标，通过 Nacos 服务发现选择后端；健康接口由网关本地处理，库存内部接口不配置网关路由。

**运行命令。** 在仓库根目录检查已部署服务。若部署还未应用当前 Compose 配置，先执行 `docker compose up -d --build --wait gateway-service`：

```bash
docker compose config --quiet
curl -fsS --max-time 5 -w '\nHTTP %{http_code}\n' http://localhost:8080/actuator/health
for port in 8081 8082 8083; do
  curl -sS -o /dev/null --connect-timeout 2 --max-time 4 \
    -w "localhost:$port HTTP %{http_code}\n" "http://localhost:$port/actuator/health" || true
done
```

**运行结果。** 网关健康检查返回 `200`，JSON 中 `status` 为 `UP`。使用学生 token 查询本人资料、菜品、本人订单列表均返回 `200`，三个业务服务均能经网关访问。对宿主机 `8081`、`8082`、`8083` 的直接请求均未能建立连接（curl 显示 `000`，不是服务端返回的 HTTP 状态）；Compose 解析后的配置也确认三个业务服务没有发布端口。携带管理员 token 访问网关的内部库存路径返回 `404`。

## 认证服务

**实现思路。** `user-service` 的 `/api/auth/login` 查找账号、比对 BCrypt 哈希，并为有效账号签发一小时有效的 HS512 JWT。数据库保存哈希而非密码；学生身份绑定业务用户。演示账号 `student1` 对应 `STUDENT`，`admin` 对应 `ADMIN`，初始密码由环境变量提供。

**运行命令。** 从仓库根目录执行下列命令，验证学生登录；凭证通过管道发送，输出只保留用户名和角色。完整的双角色及错误密码验证使用本文末尾的脚本。

```bash
read -rsp '学生演示密码: ' DEMO_STUDENT_PASSWORD; echo
export DEMO_STUDENT_PASSWORD
GATEWAY_URL=${GATEWAY_URL:-http://localhost:8080}
source scripts/auth-test.sh
gateway_login_json student1 DEMO_STUDENT_PASSWORD |
  curl -fsS -H 'Content-Type: application/json' --data-binary @- "$GATEWAY_URL/api/auth/login" |
  python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["username"], r["role"])'
```

**历史运行结果。** `student1` 和 `admin` 登录均返回 `200`；使用错误密码登录返回 `401`。当时脚本检查了两份 JWT 的算法、签发方、用户标识、角色和有效期。当前脚本已改为验证不含 `iss`，且仅学生令牌含 `studentId`；此处不记录变更后的实测结果。解码仅用于检查声明；受保护 API 调用由网关实际验签。

## 网关鉴权

**实现思路。** 全局过滤器对受保护请求验证 Bearer token，删除客户端传入的身份头并用已验证的 JWT 声明重建；角色限制在网关执行，订单服务继续核对订单归属。完整规则见 [安全设计](../../security.md)。

**运行命令。** 使用上一节导出的学生密码，分别发出匿名请求和携带合法 token 的越权请求：

```bash
curl -sS -o /dev/null -w '匿名订单查询 HTTP %{http_code}\n' \
  "$GATEWAY_URL/api/orders?userId=student-1"
TOKEN=$(gateway_student_token)
curl -sS -o /dev/null -w '学生跨用户查询 HTTP %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" "$GATEWAY_URL/api/orders?userId=student-2"
unset TOKEN
```

**运行结果。** 实际验收结果如下：

| 场景 | 实测结果 |
| --- | --- |
| 无 token 访问用户、菜品、订单路由；携带无效 token | `401` |
| 学生访问本人资料、菜品和本人订单列表 | `200` |
| 学生访问他人资料，或伪造管理员/他人身份访问他人订单 | `403` |
| 学生携带伪造身份头，但访问自己的订单 | `200`，仍按 token 中的本人身份处理 |
| 管理员查询其他用户资料和订单 | `200` |
| 管理员伪造学生身份尝试下单 | `403` |
| 学生提交其他用户的 ID 尝试下单 | `403`，订单服务响应码为 `FORBIDDEN` |

## 测试脚本

**实现思路。** [`scripts/auth-test.sh`](../../../scripts/auth-test.sh) 使用 `curl` 请求真实网关，使用 Python 验证健康响应、登录结果和 JWT 声明，并断言未登录、正确与错误登录、角色授权、跨用户访问、伪造头及内部路径隔离。脚本不发起预期成功的下单请求，不输出密码、JWT 或密钥。

**运行命令。** 在仓库根目录执行，需要 `curl`、`python3` 和已初始化的演示账号：

```bash
read -rsp '学生演示密码: ' DEMO_STUDENT_PASSWORD; echo
read -rsp '管理员演示密码: ' DEMO_ADMIN_PASSWORD; echo
export DEMO_STUDENT_PASSWORD DEMO_ADMIN_PASSWORD
bash scripts/auth-test.sh
```

**运行结果。** 本机部署通过全部 **19 个 HTTP 用例**、两份 JWT 声明检查，以及健康状态和越权订单错误码断言。脚本退出码为 `0`，末行输出 `PASS 网关认证与授权验收全部通过`。如果网络请求失败、状态码不符或内容断言失败，脚本输出 `FAIL` 并以非零状态退出。
