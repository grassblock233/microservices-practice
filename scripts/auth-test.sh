#!/usr/bin/env bash

# Resolve .env relative to this script, not to the caller's working directory.
load_auth_test_env() {
  local env_file
  env_file=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)/.env || return 1
  if [[ ! -r "$env_file" ]]; then
    printf 'FAIL 无法读取项目环境文件: %s\n' "$env_file" >&2
    return 1
  fi
  source "$env_file" || return 1
  export DEMO_STUDENT_PASSWORD DEMO_ADMIN_PASSWORD
  GATEWAY_URL=${GATEWAY_URL:-http://localhost:${GATEWAY_PORT:-8080}}
  GATEWAY_URL=${GATEWAY_URL%/}
}

load_auth_test_env || { return 1 2>/dev/null || exit 1; }

# Shared login helpers; sourcing this file does not run the acceptance tests.
gateway_login_json() {
  local username=$1 password_variable=$2
  python3 - "$username" "$password_variable" <<'PY'
import json, os, sys
value = os.environ.get(sys.argv[2])
if not value:
    sys.exit(f'请在项目根目录的 .env 中设置 {sys.argv[2]}（使用部署时设置的演示账号密码）')
print(json.dumps({'username': sys.argv[1], 'password': value}))
PY
}

gateway_student_token() {
  gateway_login_json student1 DEMO_STUDENT_PASSWORD |
    curl --silent --show-error --fail --connect-timeout 5 --max-time 20 \
      -H 'Content-Type: application/json' --data-binary @- "${GATEWAY_URL%/}/api/auth/login" |
    python3 -c 'import json, sys; j=json.load(sys.stdin); assert j["role"]=="STUDENT" and j["token"]; print(j["token"])'
}

# Return after loading configuration and defining the helpers when sourced.
if [[ ${BASH_SOURCE[0]} != "$0" ]]; then
  return 0
fi

set -euo pipefail

for command in curl python3; do
  command -v "$command" >/dev/null || { echo "FAIL 缺少命令: $command" >&2; exit 1; }
done
for name in DEMO_STUDENT_PASSWORD DEMO_ADMIN_PASSWORD; do
  [[ -n ${!name:-} ]] || { echo "FAIL 请在项目根目录的 .env 中设置 $name" >&2; exit 1; }
done

work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

request() {
  local label=$1 expected=$2 method=$3 path=$4 actual
  shift 4
  if ! actual=$(curl --silent --show-error --connect-timeout 5 --max-time 20 \
      --request "$method" --output "$work_dir/response.json" --write-out '%{http_code}' \
      "$GATEWAY_URL$path" "$@"); then
    printf 'FAIL %s: 预期 HTTP %s，实际 HTTP %s（请求失败）\n' "$label" "$expected" "${actual:-000}" >&2
    exit 1
  fi
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL %s: 预期 HTTP %s，实际 HTTP %s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
  printf 'PASS %s: HTTP %s\n' "$label" "$actual"
}

login() {
  local username=$1 password_variable=$2 role=$3 subject=$4
  gateway_login_json "$username" "$password_variable" |
    request "$role 登录" 200 POST /api/auth/login -H 'Content-Type: application/json' --data-binary @-
  if ! TOKEN=$(python3 - "$work_dir/response.json" "$username" "$role" "$subject" 2>/dev/null <<'PY'
import base64, json, re, sys
with open(sys.argv[1]) as f:
    j = json.load(f)
token = j.get('token', '')
_, username, role, subject = sys.argv[1:]
if j.get('username') != username or j.get('role') != role or not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+){2}', token):
    sys.exit(1)
def decode(segment):
    return json.loads(base64.urlsafe_b64decode(segment + '=' * (-len(segment) % 4)))
# Only check the response shape here; the gateway verifies the actual signature.
header_segment, payload_segment, _ = token.split('.')
header, claims = decode(header_segment), decode(payload_segment)
if (header.get('alg') != 'HS512' or 'iss' in claims
        or claims.get('sub') != subject or claims.get('username') != username or claims.get('role') != role
        or (claims.get('studentId') != subject if role == 'STUDENT' else 'studentId' in claims)
        or not all(type(claims.get(field)) is int for field in ('iat', 'exp'))
        or claims['exp'] - claims['iat'] != 3600):
    sys.exit(1)
print(token)
PY
  ); then
    echo "FAIL $role 登录响应：账号、角色或 JWT 声明不符合预期" >&2
    exit 1
  fi
  printf 'PASS %s JWT 声明: HS512，身份匹配，有效期 3600 秒\n' "$role"
}

request '匿名健康检查' 200 GET /actuator/health
if ! python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["status"] == "UP"' "$work_dir/response.json"; then
  echo 'FAIL 网关健康状态不是 UP' >&2
  exit 1
fi
request '匿名访问用户路由' 401 GET /api/users/student-1 -H 'X-User-Role: ADMIN'
request '匿名访问菜品路由' 401 GET '/api/dishes?merchantId=10'
request '匿名访问订单路由' 401 GET '/api/orders?userId=student-1'
request '拒绝无效 token' 401 GET /api/users/student-1 -H 'Authorization: Bearer invalid'
python3 -c 'import json,uuid; print(json.dumps({"username":"student1","password":str(uuid.uuid4())}))' |
  request '拒绝错误密码' 401 POST /api/auth/login -H 'Content-Type: application/json' --data-binary @-
login student1 DEMO_STUDENT_PASSWORD STUDENT student-1
student_auth=(-H "Authorization: Bearer $TOKEN")
login admin DEMO_ADMIN_PASSWORD ADMIN admin
admin_auth=(-H "Authorization: Bearer $TOKEN")
unset TOKEN

request '学生查看本人资料' 200 GET /api/users/student-1 "${student_auth[@]}"
request '学生查看菜品' 200 GET '/api/dishes?merchantId=10' "${student_auth[@]}"
request '学生查看本人订单' 200 GET '/api/orders?userId=student-1' "${student_auth[@]}"
request '学生不可查看他人资料' 403 GET /api/users/student-2 "${student_auth[@]}"
request '伪造身份头不影响查看本人订单' 200 GET '/api/orders?userId=student-1' "${student_auth[@]}" \
  -H 'X-User-Id: student-2' -H 'X-User-Role: ADMIN'
request '伪造身份头不能访问他人订单' 403 GET '/api/orders?userId=student-2' "${student_auth[@]}" \
  -H 'x-user-id: student-2' -H 'X-User-Role: ADMIN' -H 'X-Username: admin' -H 'X-Student-Id: student-2'
request '管理员可以跨用户查资料' 200 GET /api/users/student-2 "${admin_auth[@]}"
request '管理员可以跨用户查订单' 200 GET '/api/orders?userId=student-2' "${admin_auth[@]}"
request '管理员伪造学生身份仍不可下单' 403 POST /api/orders "${admin_auth[@]}" \
  -H 'X-User-Role: STUDENT' -H 'X-User-Id: student-1'
request '学生不能替其他用户下单' 403 POST /api/orders "${student_auth[@]}" \
  -H 'X-User-Id: student-2' -H 'Content-Type: application/json' \
  --data-binary '{"userId":"student-2","merchantId":10,"items":[{"dishId":9223372036854775807,"quantity":1}],"deliveryAddress":"认证验收"}'
if ! python3 -c 'import json,sys; sys.exit(json.load(open(sys.argv[1])).get("code") != "FORBIDDEN")' \
    "$work_dir/response.json" 2>/dev/null; then
  echo 'FAIL 跨用户下单应由订单服务返回 FORBIDDEN' >&2
  exit 1
fi
request '内部扣库存路由不开放' 404 POST /internal/stock/deduct "${admin_auth[@]}"
echo 'PASS 网关认证与授权验收全部通过'
