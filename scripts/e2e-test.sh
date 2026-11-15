#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT_DIR"
for dependency in docker curl python3; do
  command -v "$dependency" >/dev/null || { echo "缺少命令: $dependency" >&2; exit 1; }
done
DC=(docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/compose.yaml" -f "$ROOT_DIR/compose.scale.yaml")
USER_BASE_URL=${USER_BASE_URL:-http://localhost:8081}
ORDER_BASE_URL=${ORDER_BASE_URL:-http://localhost:8083}
E2E_USER_ID=${E2E_USER_ID:-student-1}
E2E_MERCHANT_ID=${E2E_MERCHANT_ID:-10}
E2E_DISH_ID=${E2E_DISH_ID:-1}
RECOVERY_WAIT_SECONDS=${RECOVERY_WAIT_SECONDS:-120}
[[ "$E2E_USER_ID" =~ ^[a-zA-Z0-9_-]+$ ]] || { echo 'E2E_USER_ID 必须可直接用于 URL 路径' >&2; exit 1; }
[[ "$RECOVERY_WAIT_SECONDS" =~ ^[1-9][0-9]*$ ]] || exit 1
work_dir=$(mktemp -d)
paused_service=''
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ -n "$paused_service" ]]; then
    echo "恢复暂停的服务: $paused_service" >&2
    if ! "${DC[@]}" unpause "$paused_service"; then
      echo "自动恢复失败，请执行 docker compose -f compose.yaml -f compose.scale.yaml unpause $paused_service" >&2
      rc=1
    fi
  fi
  rm -rf -- "$work_dir"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

request() {
  local expected=$1 method=$2 url=$3 destination=$4 actual
  shift 4
  actual=$(curl --silent --show-error --connect-timeout 3 --max-time 30 \
    --request "$method" --output "$destination" --write-out '%{http_code}' "$url" "$@")
  echo "$method $url -> $actual (expected $expected)"
  if [[ "$actual" != "$expected" ]]; then cat "$destination" >&2; exit 1; fi
  python3 - "$destination" <<'PY'
import json, sys
json.load(open(sys.argv[1]))
PY
}

# 多实例模式没有固定宿主机端口；从第一个容器读取同一菜品数据库的数据。
dish_get() {
  "${DC[@]}" exec -T --index 1 dish-service curl -fsS --connect-timeout 3 --max-time 10 \
    "http://localhost:8082$1" > "$2"
}

wait_dependency() {
  local service=$1 port=$2 deadline=$((SECONDS + RECOVERY_WAIT_SECONDS)) ready
  while (( SECONDS < deadline )); do
    ready=1
    local replicas=1
    [[ "$service" == dish-service ]] && replicas=${#dish_ids[@]}
    for ((index=1; index<=replicas; index++)); do
      if ! "${DC[@]}" exec -T --index "$index" "$service" curl -fsS --max-time 3 \
          "http://localhost:$port/actuator/health" > "$work_dir/ready.json" 2>/dev/null \
          || ! python3 - "$work_dir/ready.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['status']=='UP'
PY
      then ready=0; break; fi
    done
    (( ready == 1 )) && return 0
    sleep 2
  done
  echo "$service 未在期限内恢复" >&2
  return 1
}

"${DC[@]}" config --quiet
mapfile -t dish_ids < <("${DC[@]}" ps --status running -q dish-service)
(( ${#dish_ids[@]} >= 2 )) || { echo '请先按 docs/service-communication.md 启动至少两个 dish-service 实例' >&2; exit 1; }
# 不触碰运行前已暂停的容器，避免改变用户先前的故障实验。
for service in user-service dish-service; do
  if [[ -n $("${DC[@]}" ps --status paused -q "$service") ]]; then
    echo "$service 已有暂停实例，请先恢复后再运行脚本" >&2
    exit 1
  fi
done
wait_dependency user-service 8081
wait_dependency dish-service 8082
request 200 GET "$ORDER_BASE_URL/actuator/health" "$work_dir/health.json"
request 200 GET "$USER_BASE_URL/api/users/$E2E_USER_ID" "$work_dir/user.json"
dish_get "/api/dishes?merchantId=$E2E_MERCHANT_ID" "$work_dir/before-dishes.json"
request 200 GET "$ORDER_BASE_URL/api/orders?userId=$E2E_USER_ID" "$work_dir/before-orders.json"
python3 - "$work_dir" "$E2E_USER_ID" "$E2E_MERCHANT_ID" "$E2E_DISH_ID" <<'PY'
import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]);user,merchant,dish=sys.argv[2],int(sys.argv[3]),int(sys.argv[4])
assert json.loads((p/'health.json').read_text())['status']=='UP'
assert json.loads((p/'user.json').read_text())['status']=='ACTIVE'
d=next(x for x in json.loads((p/'before-dishes.json').read_text()) if x['id']==dish)
assert d['stock']>=2, '需要至少两份库存；脚本不会重置数据'
body=dict(userId=user,merchantId=merchant,items=[dict(dishId=dish,quantity=1),dict(dishId=dish,quantity=1)],deliveryAddress='E2E宿舍',remark='week-08-feign-e2e')
(p/'request.json').write_text(json.dumps(body,ensure_ascii=False))
missing=body.copy();missing['items']=[dict(dishId=time.time_ns(),quantity=1)]
(p/'missing-dish.json').write_text(json.dumps(missing))
PY

request 201 POST "$ORDER_BASE_URL/api/orders" "$work_dir/created.json" \
  -H 'Content-Type: application/json' --data-binary "@$work_dir/request.json"
order_no=$(python3 - "$work_dir/created.json" <<'PY'
import json,sys,uuid
n=json.load(open(sys.argv[1]))['orderNo'];uuid.UUID(n);print(n)
PY
)
request 200 GET "$ORDER_BASE_URL/api/orders/$order_no" "$work_dir/detail.json"
request 200 GET "$ORDER_BASE_URL/api/orders?userId=$E2E_USER_ID" "$work_dir/after-orders.json"
dish_get "/api/dishes?merchantId=$E2E_MERCHANT_ID" "$work_dir/after-dishes.json"
dish_get "/internal/stock-operations/$order_no" "$work_dir/operation.json"
python3 - "$work_dir" "$E2E_DISH_ID" <<'PY'
import json,sys
from pathlib import Path
from decimal import Decimal
p=Path(sys.argv[1]);dish=int(sys.argv[2])
def read(f):return json.loads((p/f).read_text(),parse_float=Decimal)
before=next(x for x in read('before-dishes.json') if x['id']==dish)
after=next(x for x in read('after-dishes.json') if x['id']==dish)
created,detail,op=read('created.json'),read('detail.json'),read('operation.json')
assert created['status']==detail['status']=='PENDING_PAYMENT'
assert created['orderNo']==detail['orderNo']==op['orderNo']
assert len(detail['items'])==1 and detail['items'][0]['quantity']==2
snapshot=detail['items'][0]
assert created['totalAmount']==detail['totalAmount']==snapshot['subtotal']==snapshot['unitPrice']*2
assert before['stock']-after['stock']==2
assert op['status']=='DEDUCTED' and op['items'][0]['quantity']==2
assert op['items'][0]['unitPrice']==snapshot['unitPrice']
assert len(read('after-orders.json'))==len(read('before-orders.json'))+1
print('PASS 下单 JSON、历史价格快照、订单数量和库存扣减')
PY

# 通过本次唯一缺失菜品编号关联业务日志，避免把历史请求误认成负载均衡证据。
missing_id=$(python3 - "$work_dir/missing-dish.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['items'][0]['dishId'])
PY
)
for ((i=1; i<=${#dish_ids[@]}*3; i++)); do
  request 404 POST "$ORDER_BASE_URL/api/orders" "$work_dir/error.json" \
    -H 'Content-Type: application/json' --data-binary "@$work_dir/missing-dish.json"
  python3 - "$work_dir/error.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['code']=='DISH_NOT_FOUND'
PY
done
lb_instances=0
for id in "${dish_ids[@]}"; do
  if docker logs "$id" 2>&1 | python3 -c 'import sys; data=sys.stdin.read(); marker="dishIds=["+sys.argv[1]+"]"; sys.exit(0 if any("event=stock_validation_requested" in line and marker in line for line in data.splitlines()) else 1)' "$missing_id"; then
    lb_instances=$((lb_instances+1))
  fi
done
(( lb_instances >= 2 )) || { echo '未观察到两个实例处理本次请求；请等待 Nacos 注册完成后重试' >&2; exit 1; }
echo "PASS LoadBalancer 命中 $lb_instances 个 dish-service 实例"

# 暂停保留连接/注册信息，覆盖读取超时；实例已下线时也可返回 503。
# 此处故障发生在校验阶段，不会发起库存写操作。
for service in user-service dish-service; do
  paused_service=$service
  "${DC[@]}" pause "$service"
  actual=$(curl --silent --show-error --connect-timeout 3 --max-time 30 \
    --output "$work_dir/failure.json" --write-out '%{http_code}' \
    "$ORDER_BASE_URL/api/orders" -H 'Content-Type: application/json' --data-binary "@$work_dir/request.json")
  echo "$service unavailable -> HTTP $actual"
  python3 - "$work_dir/failure.json" "$actual" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]));status=int(sys.argv[2])
assert status in (503,504), (status,body)
assert body['status']==status and body['code'] in ('DEPENDENCY_UNAVAILABLE','DEPENDENCY_TIMEOUT'), body
assert body.get('detail') and not body.get('orderNo'), body
assert 'totalAmount' not in body and body.get('status')!='PENDING_PAYMENT'
PY
  request 200 GET "$ORDER_BASE_URL/api/orders?userId=$E2E_USER_ID" "$work_dir/failure-orders.json"
  python3 - "$work_dir" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])
assert json.loads((p/'after-orders.json').read_text())==json.loads((p/'failure-orders.json').read_text()), '失败被错误保存为订单'
PY
  "${DC[@]}" unpause "$service"
  paused_service=''
  port=8081; [[ "$service" == dish-service ]] && port=8082
  wait_dependency "$service" "$port"
  # 恢复后轮询只读的预校验业务错误，不自动重试下单写请求。
  deadline=$((SECONDS + RECOVERY_WAIT_SECONDS))
  while :; do
    recovered=$(curl --silent --show-error --connect-timeout 3 --max-time 15 \
      --output "$work_dir/recovered.json" --write-out '%{http_code}' \
      "$ORDER_BASE_URL/api/orders" -H 'Content-Type: application/json' --data-binary "@$work_dir/missing-dish.json")
    if [[ "$recovered" == 404 ]] && python3 - "$work_dir/recovered.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['code']=='DISH_NOT_FOUND'
PY
    then break; fi
    if (( SECONDS >= deadline )); then echo '依赖未恢复业务调用' >&2; exit 1; fi
    sleep 2
  done
done
dish_get "/api/dishes?merchantId=$E2E_MERCHANT_ID" "$work_dir/final-dishes.json"
request 200 GET "$ORDER_BASE_URL/api/orders?userId=$E2E_USER_ID" "$work_dir/final-orders.json"
python3 - "$work_dir" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])
for kind in ('orders','dishes'):
 assert json.loads((p/f'after-{kind}.json').read_text())==json.loads((p/f'final-{kind}.json').read_text()), kind
print('PASS 依赖不可用未产生业务成功，恢复后调用链可用，库存与订单保持一致')
PY
echo "全部 E2E 断言通过。保留订单 $order_no"
