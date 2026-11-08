#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'HELP'
用法：bash scripts/reset-microservices.sh [--yes] [--dry-run]

停止三个业务服务，删除三个数据库容器及其数据卷，再启动并恢复初始示例数据。
所有业务数据都会丢失；Nacos 数据和管理员账号、旧单体数据库不受影响。
默认交互确认；从任意目录执行均使用本仓库的 compose.yaml 和 .env。
可通过 COMPOSE_PROJECT_NAME 指定项目名，与启动服务时保持一致。

  --yes, -y   跳过交互确认（用于明确授权的自动化）
  --dry-run   仅显示目标数据卷，不停止服务或删除数据
  --help, -h  显示帮助

Docker 需要 root 权限时，请使用 sudo bash scripts/reset-microservices.sh。
HELP
}

confirmed=false
dry_run=false
for argument in "$@"; do
  case "$argument" in
    --yes|-y) confirmed=true ;;
    --dry-run) dry_run=true ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知参数：$argument" >&2; usage >&2; exit 1 ;;
  esac
done

for command in docker python3; do
  command -v "$command" >/dev/null || { echo "缺少命令：$command" >&2; exit 1; }
done

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo_root"
compose=(docker compose --project-directory "$repo_root" -f "$repo_root/compose.yaml")

# 在任何修改之前验证配置与初始化脚本；不输出含数据库密码的完整 Compose 配置。
"${compose[@]}" version >/dev/null
for service in user dish order; do
  [[ -f "scripts/microservices/$service/init.sql" ]] || {
    echo "缺少初始化脚本：scripts/microservices/$service/init.sql" >&2
    exit 1
  }
done
volume_names=$("${compose[@]}" config --format json | python3 -c '
import json, sys
config = json.load(sys.stdin)
volumes = config["volumes"]
targets = []
for service in ("user", "dish", "order"):
    key = service + "-data"
    volume = volumes[key]
    if volume.get("external"):
        sys.exit("拒绝删除外部数据卷：" + key)
    mounts = config["services"][service + "-db"].get("volumes", [])
    if not any(m.get("type") == "volume" and m.get("source") == key and
               m.get("target") == "/var/lib/mysql" for m in mounts):
        sys.exit("数据库挂载与预期不符：" + service + "-db")
    targets.append(volume["name"])
if len(set(targets)) != 3 or volumes["nacos-data"]["name"] in targets:
    sys.exit("业务数据卷重复或与 Nacos 共用，已拒绝重置")
print("\n".join(targets))
')
mapfile -t volumes <<< "$volume_names"

echo "警告：将永久删除三个业务数据库的全部数据，恢复仓库中的初始示例数据。"
echo "目标仓库：$repo_root"
printf '目标数据卷：%s\n' "${volumes[@]}"
echo "保留 Nacos 数据及管理员账号；旧单体数据库不受影响。"
if [[ "$dry_run" == true ]]; then
  echo "预览完成，未修改任何数据。"
  exit 0
fi

if [[ "$confirmed" != true ]]; then
  read -r -p '输入 RESET 确认重置，其他输入取消：' answer || answer=''
  if [[ "$answer" != RESET ]]; then
    echo "已取消，未修改任何数据。"
    exit 0
  fi
fi

# 先确认 Docker 可用，再停止应用。后续失败即退出，不自动重启可能仅部分重置的服务。
docker info >/dev/null
trap 'echo "重置中断：请检查上方错误；业务服务可能仍处于停止状态。" >&2' ERR
echo "[1/4] 停止业务服务……"
"${compose[@]}" stop order-service dish-service user-service
echo "[2/4] 删除数据库容器……"
"${compose[@]}" rm -sf user-db dish-db order-db
echo "[3/4] 删除业务数据卷……"
existing_volumes=$(docker volume ls --format '{{.Name}}')
for volume in "${volumes[@]}"; do
  if grep -Fxq -- "$volume" <<< "$existing_volumes"; then
    docker volume rm "$volume"
  else
    echo "数据卷不存在，跳过：$volume"
  fi
done
echo "[4/4] 重新初始化数据库并启动服务，等待健康检查……"
"${compose[@]}" up -d --wait
echo "重置完成。可运行 bash scripts/smoke-test.sh 或 scripts/rest-api.http 进行验证。"
