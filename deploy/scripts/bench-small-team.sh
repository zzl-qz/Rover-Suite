#!/usr/bin/env bash
# 小团队资源限额压测：只起 Nameserver + Gateway + demo，不启 Admin。
# 叙事：成绩来自「不太够」的限额，不是本机满配堆出来的。
#
# 用法（仓库根目录）：
#   ./deploy/scripts/bench-small-team.sh
#   BENCH_PROFILE=tiny ./deploy/scripts/bench-small-team.sh   # 穷机 2C4G 档
#   BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh         # 测完保留容器
#
# 依赖：Docker Compose、curl、hey（brew install hey）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT/deploy/docker"
PROFILE="${BENCH_PROFILE:-small}"
TIMEOUT="${BENCH_TIMEOUT:-180}"
URL="${BENCH_URL:-http://127.0.0.1:8080/api/hello}"
DIRECT_URL="${BENCH_DIRECT_URL:-http://127.0.0.1:8082/api/hello}"
SKIP_DIRECT="${BENCH_SKIP_DIRECT:-0}"
ADMIN_TOKEN="${BENCH_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
# Compose 演示里 Nameserver adminToken 与 Gateway 相同（见 config/rover-nameserver.yml）
NS_TOKEN="${BENCH_NS_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"

case "$PROFILE" in
  small)
    OVERLAY="$DOCKER_DIR/docker-compose.perf-small.yml"
    PROFILE_DESC="small≈4C8G叙事(GW 2cpu/2g + NS 1cpu/1g + demo 0.5cpu/768m)"
    ;;
  tiny)
    OVERLAY="$DOCKER_DIR/docker-compose.perf-tiny.yml"
    PROFILE_DESC="tiny≈2C4G叙事(GW 1cpu/1.5g + NS 0.5cpu/768m + demo 0.25cpu/512m)"
    ;;
  *)
    echo "未知 BENCH_PROFILE=$PROFILE（仅支持 small|tiny）" >&2
    exit 1
    ;;
esac

if ! command -v hey >/dev/null 2>&1; then
  echo "未找到 hey。先装：brew install hey" >&2
  exit 1
fi

COMPOSE=(docker compose -f "$DOCKER_DIR/docker-compose.yml" -f "$OVERLAY")
# 明确只起三件套，绝不带 admin
SERVICES=(nameserver demo gateway)

cd "$DOCKER_DIR"

cleanup() {
  if [[ "${BENCH_KEEP:-0}" == "1" ]]; then
    echo "BENCH_KEEP=1，保留容器（无 Admin）"
    return
  fi
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> profile: $PROFILE_DESC"
echo "==> build (nameserver demo gateway only)"
"${COMPOSE[@]}" build "${SERVICES[@]}"

echo "==> up (no admin)"
"${COMPOSE[@]}" up -d --remove-orphans "${SERVICES[@]}"
# 若之前残留 admin，顺手停掉，避免偷吃资源
"${COMPOSE[@]}" stop admin >/dev/null 2>&1 || true

wait_http() {
  local url="$1"
  local name="$2"
  shift 2
  local i=0
  while (( i < TIMEOUT )); do
    if curl -fsS "$@" "$url" >/dev/null 2>&1; then
      echo "ok: $name"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "timeout: $name ($url)" >&2
  "${COMPOSE[@]}" ps >&2 || true
  "${COMPOSE[@]}" logs --tail=60 nameserver gateway demo >&2 || true
  return 1
}

echo "==> wait ready (timeout=${TIMEOUT}s)"
wait_http "http://127.0.0.1:8889/_manage/health" "nameserver" \
  -H "X-Rover-Admin-Token: $NS_TOKEN"
wait_http "http://127.0.0.1:8080/_manage/health" "gateway" \
  -H "X-Rover-Admin-Token: $ADMIN_TOKEN"

# demo 注册 + Gateway 订阅有先后，业务路径多等几秒
echo "==> wait business path"
BUSINESS_OK=0
for (( i = 0; i < 60; i++ )); do
  if curl -fsS "$URL" >/dev/null 2>&1; then
    BUSINESS_OK=1
    break
  fi
  sleep 1
done
if [[ "$BUSINESS_OK" != "1" ]]; then
  echo "timeout: business path still failing ($URL)" >&2
  "${COMPOSE[@]}" logs --tail=40 demo gateway nameserver >&2 || true
  exit 1
fi
BODY="$(curl -fsS "$URL")"
echo "ok: business ($URL) -> $BODY"

run_hey() {
  local label="$1"
  local duration="$2"
  local concurrency="$3"
  local target="${4:-$URL}"
  echo
  echo "======== $label : hey -z ${duration} -c ${concurrency} ${target} ========"
  hey -z "$duration" -c "$concurrency" "$target"
}

echo
echo "==> via Gateway (Nameserver 发现 + 转发)"
run_hey "gateway_warmup" "10s" "20"
run_hey "gateway_main_c50" "30s" "50"
run_hey "gateway_push_c100" "30s" "100"

if [[ "$SKIP_DIRECT" == "1" ]]; then
  echo
  echo "==> skip direct baseline (BENCH_SKIP_DIRECT=1)"
else
  echo
  echo "==> direct backend baseline (绕过 Gateway/Nameserver，只打 demo 容器)"
  DIRECT_OK=0
  for (( i = 0; i < 30; i++ )); do
    if curl -fsS "$DIRECT_URL" >/dev/null 2>&1; then
      DIRECT_OK=1
      break
    fi
    sleep 1
  done
  if [[ "$DIRECT_OK" != "1" ]]; then
    echo "direct URL 不通: $DIRECT_URL（perf overlay 是否映射 8082？）" >&2
    exit 1
  fi
  echo "ok: direct ($DIRECT_URL) -> $(curl -fsS "$DIRECT_URL")"
  run_hey "direct_warmup" "10s" "20" "$DIRECT_URL"
  run_hey "direct_main_c50" "30s" "50" "$DIRECT_URL"
fi

echo
echo "==> 填文档：gateway_main_c50 与 direct_main_c50 各抄一行 QPS/P50/P95/P99，算网关开销"
echo "日期=$(date +%F) profile=$PROFILE 限额=$PROFILE_DESC 无Admin"
echo "Gateway: $URL  |  Direct: $DIRECT_URL"
echo "==> bench done"
