#!/usr/bin/env bash
# Docker Compose 冒烟：build → up → health/overview/业务路径 → down
# 用法（仓库根目录）：
#   ./deploy/scripts/smoke-compose.sh
# 环境变量：
#   SMOKE_KEEP=1     结束后不 down（排障用）
#   SMOKE_TIMEOUT=120 等待就绪秒数
#   SMOKE_ADMIN_USER / SMOKE_ADMIN_PASSWORD 控制台账号（默认 admin）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="$ROOT/deploy/docker/docker-compose.yml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")
TIMEOUT="${SMOKE_TIMEOUT:-120}"
ADMIN_TOKEN="${SMOKE_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
HEADER=("X-Rover-Admin-Token: $ADMIN_TOKEN")
COOKIE_JAR="$(mktemp)"

cd "$ROOT/deploy/docker"

cleanup() {
  rm -f "$COOKIE_JAR"
  if [[ "${SMOKE_KEEP:-0}" == "1" ]]; then
    echo "SMOKE_KEEP=1，保留容器"
    return
  fi
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> build"
"${COMPOSE[@]}" build

echo "==> up"
"${COMPOSE[@]}" up -d

wait_http() {
  local url="$1"
  local name="$2"
  local extra=()
  if [[ "${3:-}" == "admin" ]]; then
    extra=(-H "${HEADER[0]}")
  fi
  local i=0
  while (( i < TIMEOUT )); do
    if curl -fsS "${extra[@]}" "$url" >/dev/null 2>&1; then
      echo "ok: $name ($url)"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "timeout waiting for $name: $url" >&2
  "${COMPOSE[@]}" ps >&2 || true
  "${COMPOSE[@]}" logs --tail=80 >&2 || true
  return 1
}

echo "==> wait ready (timeout=${TIMEOUT}s)"
wait_http "http://127.0.0.1:8889/_manage/health" "nameserver-health" admin
wait_http "http://127.0.0.1:8080/_manage/health" "gateway-health" admin
wait_http "http://127.0.0.1:9090/api/auth/status" "admin-auth"
CSRF_TOKEN="$(curl -fsS -c "$COOKIE_JAR" http://127.0.0.1:9090/api/auth/status | sed -E 's/.*"csrfToken"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')"
curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  --data-urlencode "username=${SMOKE_ADMIN_USER:-admin}" \
  --data-urlencode "password=${SMOKE_ADMIN_PASSWORD:-admin}" \
  --data-urlencode "_csrf=$CSRF_TOKEN" \
  http://127.0.0.1:9090/login >/dev/null
curl -fsS -b "$COOKIE_JAR" http://127.0.0.1:9090/api/overview >/dev/null
curl -fsS -b "$COOKIE_JAR" http://127.0.0.1:9090/api/metrics >/dev/null
echo "ok: admin sign-in, overview, metrics"

echo "==> business path"
BUSINESS_OK=0
for (( i = 0; i < 60; i++ )); do
  if curl -fsS "http://127.0.0.1:8080/api/hello" >/dev/null 2>&1; then
    BUSINESS_OK=1
    break
  fi
  sleep 1
done
if [[ "$BUSINESS_OK" != "1" ]]; then
  echo "timeout waiting for /api/hello" >&2
  "${COMPOSE[@]}" logs --tail=40 demo gateway >&2 || true
  exit 1
fi
BODY="$(curl -fsS "http://127.0.0.1:8080/api/hello")"
echo "response: $BODY"

echo "==> smoke passed"
