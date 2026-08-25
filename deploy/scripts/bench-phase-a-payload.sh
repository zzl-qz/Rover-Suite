#!/usr/bin/env bash
# 历史第一波：大体响应 A/B（会 git checkout HEAD 三份源码再 build）
# 工作区有未提交改动时别跑。当前代码复测用 bench-phase-a-payload-remasure.sh
# 用法（仓库根目录）：
#   ./deploy/scripts/bench-phase-a-payload.sh
# 环境变量：
#   BENCH_KEEP=1  结束后保留容器
#   SIZES="262144 1048576 4194304"
#   CONCURRENCY=20
#   DURATION=20s
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT/deploy/docker"
RUN_ID="${BENCH_RUN_ID:-$(date +%Y-%m-%d-%H%M%S)-phase-a-payload}"
OUT_ROOT="${BENCH_OUT_ROOT:-/Users/smt/Desktop/stu_obj_docs/Rover-Suite/bench-results}"
OUT="$OUT_ROOT/$RUN_ID"
TIMEOUT="${BENCH_TIMEOUT:-180}"
ADMIN_TOKEN="${BENCH_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
OVERRIDE="$DOCKER_DIR/.bench-gateway-override.yml"
SIZES=(${SIZES:-262144 1048576 4194304})
CONCURRENCY="${CONCURRENCY:-20}"
DURATION="${DURATION:-20s}"
ROUNDS="${ROUNDS:-3}"
PROXY_SRC="$ROOT/rover-gateway-core/src/main/java/com/rover/gateway/core/proxy/HttpProxyClient.java"
CORS_SRC="$ROOT/rover-gateway-core/src/main/java/com/rover/gateway/core/server/CorsHandler.java"
TEST_SRC="$ROOT/rover-gateway-core/src/test/java/com/rover/gateway/core/proxy/HttpProxyClientTest.java"
BACKUP_DIR="$(mktemp -d /tmp/rover-stream-backup.XXXXXX)"

COMPOSE=(docker compose
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.perf-fair.yml")

if ! command -v hey >/dev/null 2>&1; then
  echo "未找到 hey。先装：brew install hey" >&2
  exit 1
fi

mkdir -p "$OUT"/{00-env,01-buffer,02-stream,docker-stats}

cleanup() {
  # 尽量把流式源码恢复回去，避免压测脚本把工作区留在整包版本
  if [[ -f "$BACKUP_DIR/HttpProxyClient.java" ]]; then
    cp "$BACKUP_DIR/HttpProxyClient.java" "$PROXY_SRC"
    cp "$BACKUP_DIR/CorsHandler.java" "$CORS_SRC"
    cp "$BACKUP_DIR/HttpProxyClientTest.java" "$TEST_SRC"
  fi
  rm -rf "$BACKUP_DIR"
  rm -f "$OVERRIDE"
  if [[ "${BENCH_KEEP:-0}" == "1" ]]; then
    echo "BENCH_KEEP=1，保留容器"
    return
  fi
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

cp "$PROXY_SRC" "$BACKUP_DIR/HttpProxyClient.java"
cp "$CORS_SRC" "$BACKUP_DIR/CorsHandler.java"
cp "$TEST_SRC" "$BACKUP_DIR/HttpProxyClientTest.java"

write_env() {
  {
    echo "# Phase A payload A/B"
    echo
    echo "- run_id: \`$RUN_ID\`"
    echo "- date: $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "- git: \`$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)\`"
    echo "- host: $(uname -s) $(uname -m)"
    echo "- profile: perf-fair"
    echo "- sizes: ${SIZES[*]}"
    echo "- concurrency: $CONCURRENCY"
    echo "- duration: $DURATION"
    echo "- rounds: $ROUNDS"
    echo "- url: /api/payload?size=N"
  } >"$OUT/00-env/00-env.md"
}

write_gateway_override() {
  cat >"$OVERRIDE" <<EOF
services:
  gateway:
    volumes:
      - ${DOCKER_DIR}/config/rover-gateway-static.yml:/app/config/rover-gateway.yml:ro
EOF
}

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
  return 1
}

up_stack() {
  local mode="$1"
  echo "==> rebuild+up mode=$mode"
  write_gateway_override
  "${COMPOSE[@]}" -f "$OVERRIDE" up -d --remove-orphans --build nameserver demo gateway
  "${COMPOSE[@]}" stop admin >/dev/null 2>&1 || true
  wait_http "http://127.0.0.1:8889/_manage/health" "nameserver" \
    -H "X-Rover-Admin-Token: $ADMIN_TOKEN"
  wait_http "http://127.0.0.1:8082/api/health" "demo"
  wait_http "http://127.0.0.1:8080/api/hello" "gateway"
  # 冒烟 payload
  local got
  got="$(curl -fsS -o /tmp/rover-payload.bin -w '%{http_code}:%{size_download}' \
    'http://127.0.0.1:8080/api/payload?size=4096')"
  echo "payload smoke: $got"
  docker stats --no-stream >"$OUT/docker-stats/stats-${mode}-ready.txt" || true
}

switch_to_buffer() {
  echo "==> checkout buffered proxy sources (git HEAD)"
  git -C "$ROOT" checkout HEAD -- \
    "rover-gateway-core/src/main/java/com/rover/gateway/core/proxy/HttpProxyClient.java" \
    "rover-gateway-core/src/main/java/com/rover/gateway/core/server/CorsHandler.java" \
    "rover-gateway-core/src/test/java/com/rover/gateway/core/proxy/HttpProxyClientTest.java"
}

switch_to_stream() {
  echo "==> restore streaming proxy sources"
  cp "$BACKUP_DIR/HttpProxyClient.java" "$PROXY_SRC"
  cp "$BACKUP_DIR/CorsHandler.java" "$CORS_SRC"
  cp "$BACKUP_DIR/HttpProxyClientTest.java" "$TEST_SRC"
}

run_matrix() {
  local mode="$1"
  local outdir="$2"
  mkdir -p "$outdir"
  local size r url file
  for size in "${SIZES[@]}"; do
    url="http://127.0.0.1:8080/api/payload?size=${size}"
    echo "==> warmup $mode size=$size"
    hey -z 5s -c 10 "$url" >"$outdir/warmup_s${size}.txt" || true
    for ((r = 1; r <= ROUNDS; r++)); do
      file="$outdir/${mode}_s${size}_c${CONCURRENCY}_r${r}.txt"
      echo "======== $mode size=$size c=$CONCURRENCY round=$r ========"
      # 压测中采样一次 gateway 内存
      (
        sleep 8
        docker stats --no-stream --format \
          'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' \
          >"$OUT/docker-stats/stats-${mode}-s${size}-r${r}.txt" || true
      ) &
      hey -z "$DURATION" -c "$CONCURRENCY" "$url" | tee "$file"
      wait || true
      sleep 2
    done
    # Direct 对照一轮，排除上游本身成为唯一瓶颈的误判
    echo "==> direct size=$size"
    hey -z "$DURATION" -c "$CONCURRENCY" \
      "http://127.0.0.1:8082/api/payload?size=${size}" \
      | tee "$outdir/direct_s${size}_c${CONCURRENCY}.txt"
  done
}

echo "==> Phase A payload A/B run_id=$RUN_ID"
echo "==> output: $OUT"
write_env

switch_to_buffer
up_stack buffer
run_matrix buffer "$OUT/01-buffer"

switch_to_stream
up_stack stream
run_matrix stream "$OUT/02-stream"

python3 - "$OUT" <<'PY'
import csv, re, sys
from pathlib import Path
out = Path(sys.argv[1])
qps_re = re.compile(r"Requests/sec:\s*([0-9.]+)")
lat_re = {
    "p50": re.compile(r"50%% in ([0-9.]+) secs"),
    "p95": re.compile(r"95%% in ([0-9.]+) secs"),
    "p99": re.compile(r"99%% in ([0-9.]+) secs"),
}
# 兼容单百分号
lat_re2 = {
    "p50": re.compile(r"50% in ([0-9.]+) secs"),
    "p95": re.compile(r"95% in ([0-9.]+) secs"),
    "p99": re.compile(r"99% in ([0-9.]+) secs"),
}
status_re = re.compile(r"\[(\d+)\]\s+(\d+)\s+responses")
rows = []
for p in sorted(out.rglob("*.txt")):
    if p.name.startswith("warmup") or p.name.startswith("stats-"):
        continue
    text = p.read_text(errors="ignore")
    m = qps_re.search(text)
    if not m:
        continue
    statuses = {a: int(b) for a, b in status_re.findall(text)}
    ok = statuses.get("200", 0)
    err = sum(v for k, v in statuses.items() if k != "200")
    def ms(key):
        mm = lat_re[key].search(text) or lat_re2[key].search(text)
        return round(float(mm.group(1)) * 1000, 3) if mm else ""
    rows.append({
        "file": str(p.relative_to(out)),
        "qps": float(m.group(1)),
        "p50_ms": ms("p50"),
        "p95_ms": ms("p95"),
        "p99_ms": ms("p99"),
        "http_200": ok,
        "http_non_200": err,
        "statuses": ";".join(f"{k}:{v}" for k, v in sorted(statuses.items())),
    })
csv_path = out / "summary.csv"
with csv_path.open("w", newline="") as f:
    fields = ["file", "qps", "p50_ms", "p95_ms", "p99_ms", "http_200", "http_non_200", "statuses"]
    w = csv.DictWriter(f, fieldnames=fields)
    w.writeheader()
    w.writerows(rows)
print(f"wrote {csv_path} rows={len(rows)}")
PY

echo "==> Phase A payload A/B done"
echo "    $OUT"
echo "    $OUT/summary.csv"
