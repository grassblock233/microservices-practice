#!/usr/bin/env bash

# 严格模式，脚本在出错时直接退出
set -euo pipefail
# 检查 python 和 curl 是否安装
for command in curl python3; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
# 读取环境变量
NACOS_CONSOLE_URL=${NACOS_CONSOLE_URL:-http://localhost:18080/}
USER_BASE_URL=${USER_BASE_URL:-http://localhost:8081}
DISH_BASE_URL=${DISH_BASE_URL:-http://localhost:8082}
ORDER_BASE_URL=${ORDER_BASE_URL:-http://localhost:8083}
SMOKE_USER_ID=${SMOKE_USER_ID:-student-1}
SMOKE_MERCHANT_ID=${SMOKE_MERCHANT_ID:-10}
SMOKE_DISH_ID=${SMOKE_DISH_ID:-1}

# 将请求和响应保存在临时目录，并在脚本退出时自动清理
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

# 发送请求并保存响应体；状态码不符合预期时输出响应并退出。
request() {
  local expected=$1 method=$2 url=$3 destination=$4
  shift 4
  local actual
  actual=$(curl --silent --show-error --location --connect-timeout 5 --max-time 30 \
    --request "$method" --output "$destination" --write-out '%{http_code}' "$url" "$@")
  echo "$method $url -> HTTP $actual (expected $expected)"
  if [[ "$actual" != "$expected" ]]; then
    cat "$destination" >&2
    exit 1
  fi
}

# 检查 Nacos 控制台是否可访问，以及业务服务的健康状态是否为 UP。
request 200 GET "$NACOS_CONSOLE_URL" "$work_dir/nacos-console.html"
for base in "$USER_BASE_URL" "$DISH_BASE_URL" "$ORDER_BASE_URL"; do
  request 200 GET "$base/actuator/health" "$work_dir/health.json"
  python3 - "$work_dir/health.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['status']=='UP', 'Health is not UP'
PY
done

# 获取用户信息，以及下单前的菜品库存和订单列表，供后续校验使用。
request 200 GET "$USER_BASE_URL/api/users/$SMOKE_USER_ID" "$work_dir/user.json"
request 200 GET "$DISH_BASE_URL/api/dishes?merchantId=$SMOKE_MERCHANT_ID" "$work_dir/before-dishes.json"
request 200 GET "$ORDER_BASE_URL/api/orders?userId=$SMOKE_USER_ID" "$work_dir/before-orders.json"
# 确认用户可用且菜品库存至少为两份，构造同一菜品分两行、每行一份的下单请求。
python3 - "$work_dir" "$SMOKE_USER_ID" "$SMOKE_MERCHANT_ID" "$SMOKE_DISH_ID" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1]);user,merchant,dish=sys.argv[2],int(sys.argv[3]),int(sys.argv[4])
assert json.loads((p/'user.json').read_text())['status']=='ACTIVE'
rows=json.loads((p/'before-dishes.json').read_text())
d=next((x for x in rows if x['id']==dish),None)
assert d and d['stock']>=2, 'Smoke dish must be on sale with at least two portions'
body=dict(userId=user,merchantId=merchant,items=[dict(dishId=dish,quantity=1),dict(dishId=dish,quantity=1)],deliveryAddress='冒烟测试宿舍',remark='week-07 nacos smoke')
(p/'request.json').write_text(json.dumps(body,ensure_ascii=False))
PY

# 提交订单并校验创建成功。
request 201 POST "$ORDER_BASE_URL/api/orders" "$work_dir/created.json" \
  -H 'Content-Type: application/json' --data-binary "@$work_dir/request.json"
# 提取订单号，并验证其符合 UUID 格式。
order_no=$(python3 - "$work_dir/created.json" <<'PY'
import json,sys,uuid
n=json.load(open(sys.argv[1]))['orderNo'];uuid.UUID(n);print(n)
PY
)

# 查询订单详情、下单后的订单与库存，以及对应的库存扣减操作记录。
request 200 GET "$ORDER_BASE_URL/api/orders/$order_no" "$work_dir/detail.json"
request 200 GET "$ORDER_BASE_URL/api/orders?userId=$SMOKE_USER_ID" "$work_dir/after-orders.json"
request 200 GET "$DISH_BASE_URL/api/dishes?merchantId=$SMOKE_MERCHANT_ID" "$work_dir/after-dishes.json"
request 200 GET "$DISH_BASE_URL/internal/stock-operations/$order_no" "$work_dir/operation.json"

# 校验库存扣减、重复菜品合并、订单状态、金额快照、扣减记录及订单数量变化。
python3 - "$work_dir" "$SMOKE_DISH_ID" <<'PY'
import json,sys
from pathlib import Path
from decimal import Decimal
p=Path(sys.argv[1]);dish=int(sys.argv[2])
# 使用十进制定点数解析金额，避免二进制浮点误差影响金额比较。
def read(f):return json.loads((p/f).read_text(),parse_float=Decimal)
before=next(x for x in read('before-dishes.json') if x['id']==dish)
after=next(x for x in read('after-dishes.json') if x['id']==dish)
created,detail,operation=read('created.json'),read('detail.json'),read('operation.json')
assert before['stock']-after['stock']==2, 'Stock did not decrease by two'
assert created['status']==detail['status']=='PENDING_PAYMENT'
assert len(detail['items'])==1 and detail['items'][0]['quantity']==2
snapshot=detail['items'][0]
assert created['totalAmount']==detail['totalAmount']==snapshot['unitPrice']*2==snapshot['subtotal']
assert operation['orderNo']==created['orderNo'] and operation['status']=='DEDUCTED'
assert operation['items'][0]['unitPrice']==snapshot['unitPrice']
assert len(read('after-orders.json'))==len(read('before-orders.json'))+1
print(f"PASS orderNo={created['orderNo']} amount={created['totalAmount']} stock={before['stock']}->{after['stock']}")
PY

# 构造用户不存在、用户已停用及菜品不存在的请求，验证异常下单场景。
python3 - "$work_dir" <<'PY'
import json,sys,uuid
from pathlib import Path
p=Path(sys.argv[1]);body=json.loads((p/'request.json').read_text())
body['userId']='missing-'+str(uuid.uuid4());(p/'missing-user.json').write_text(json.dumps(body))
body['userId']='student-disabled';(p/'disabled-user.json').write_text(json.dumps(body))
body=json.loads((p/'request.json').read_text());body['items']=[dict(dishId=9223372036854775807,quantity=1)]
(p/'missing-dish.json').write_text(json.dumps(body))
PY

# 逐一校验异常请求的 HTTP 状态码和业务错误码。
for scenario in missing-user disabled-user missing-dish; do
  case "$scenario" in
    missing-user) expected=404; code=USER_NOT_FOUND ;;
    disabled-user) expected=409; code=USER_DISABLED ;;
    missing-dish) expected=404; code=DISH_NOT_FOUND ;;
  esac
  request "$expected" POST "$ORDER_BASE_URL/api/orders" "$work_dir/error.json" \
    -H 'Content-Type: application/json' --data-binary "@$work_dir/$scenario.json"
  python3 - "$work_dir/error.json" "$code" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]));assert body['code']==sys.argv[2],body
print('PASS rejected '+body['code'])
PY
done

# 对比异常请求前后的订单和菜品数据，确保拒绝下单不会产生订单或改变库存。
request 200 GET "$ORDER_BASE_URL/api/orders?userId=$SMOKE_USER_ID" "$work_dir/final-orders.json"
request 200 GET "$DISH_BASE_URL/api/dishes?merchantId=$SMOKE_MERCHANT_ID" "$work_dir/final-dishes.json"
python3 - "$work_dir" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])
def read(f):return json.loads((p/f).read_text())
assert read('final-orders.json')==read('after-orders.json'),'Rejected requests changed orders'
assert read('final-dishes.json')==read('after-dishes.json'),'Rejected requests changed stock'
print('PASS rejected requests left orders and inventory unchanged')
PY

# 输出测试结果及订单号，便于后续验证数据持久化。
echo "All smoke checks passed. Keep orderNo=$order_no for persistence verification."
