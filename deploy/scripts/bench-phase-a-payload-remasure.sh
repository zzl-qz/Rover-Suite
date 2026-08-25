#!/usr/bin/env bash
# 当前代码大包复测（不 checkout 源码，别跟历史整包 A/B 脚本搞混）
#   GET /api/payload?size=N  —— 对照第一波流式数字；同场再打 outbound=jdk
#   POST /api/ingest         —— 第五波入站管道该看的场景
# 仓库根目录：
#   ./deploy/scripts/bench-phase-a-payload-remasure.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT/deploy/docker"
RUN_ID="${BENCH_RUN_ID:-$(date +%Y-%m-%d-%H%M%S)-phase-a-payload-remasure}"
OUT_ROOT="${BENCH_OUT_ROOT:-/Users/smt/Desktop/stu_obj_docs/Rover-Suite/bench-results}"
OUT="$OUT_ROOT/$RUN_ID"
TIMEOUT="${BENCH_TIMEOUT:-180}"
ADMIN_TOKEN="${BENCH_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
OVERRIDE="$DOCKER_DIR/.bench-gateway-override.yml"
SIZES=(${SIZES:-262144 1048576 4194304})
CONCURRENCY="${CONCURRENCY:-20}"
DURATION="${DURATION:-20s}"
ROUNDS="${ROUNDS:-3}"

COMPOSE=(docker compose
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.perf-fair.yml")

if ! command -v hey >/dev/null 2>&1; then
  echo "未找到 hey。先装：brew install hey" >&2
  exit 1
fi

mkdir -p "$OUT"/{00-env,01-netty-get,02-netty-post,03-jdk-get,04-jdk-post,bodies,docker-stats}

cleanup() {
  rm -f "$OVERRIDE"
  if [[ "${BENCH_KEEP:-0}" == "1" ]]; then
    echo "BENCH_KEEP=1，保留容器"
    return
  fi
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

{
  echo "# Phase A payload remasure（当前代码，不回退源码）"
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
  echo "- get: /api/payload?size=N"
  echo "- post: /api/ingest"
  echo "- order: netty GET/POST → jdk GET/POST"
} >"$OUT/00-env/00-env.md"
cp "$DOCKER_DIR/config/rover-gateway-static-payload.yml" "$OUT/00-env/"
cp "$DOCKER_DIR/config/rover-gateway-static-jdk-payload.yml" "$OUT/00-env/"

write_gateway_override() {
  local cfg_abs="$1"
  cat >"$OVERRIDE" <<EOF
services:
  gateway:
    volumes:
      - ${cfg_abs}:/app/config/rover-gateway.yml:ro
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

expect_outbound_log() {
  local mode="$1"
  local logs
  logs="$("${COMPOSE[@]}" logs gateway 2>&1 || true)"
  echo "$logs" | tee "$OUT/00-env/gateway-log-${mode}.txt" >/dev/null
  if [[ "$mode" == "jdk" ]]; then
    if ! echo "$logs" | grep -q "出站代理: JDK"; then
      echo "outbound mismatch: 期望 JDK" >&2
      echo "$logs" | tail -n 80 >&2
      exit 1
    fi
  else
    if ! echo "$logs" | grep -q "出站代理: Netty"; then
      echo "outbound mismatch: 期望 Netty" >&2
      echo "$logs" | tail -n 80 >&2
      exit 1
    fi
  fi
  echo "ok: outbound-log=$mode"
}

make_bodies() {
  local size
  for size in "${SIZES[@]}"; do
    head -c "$size" /dev/zero >"$OUT/bodies/body_${size}.bin"
  done
}

up_base() {
  echo "==> build+up nameserver/demo/gateway（netty 大包配置）"
  write_gateway_override "$DOCKER_DIR/config/rover-gateway-static-payload.yml"
  "${COMPOSE[@]}" -f "$OVERRIDE" up -d --remove-orphans --build nameserver demo gateway
  "${COMPOSE[@]}" stop admin >/dev/null 2>&1 || true
  wait_http "http://127.0.0.1:8889/_manage/health" "nameserver" \
    -H "X-Rover-Admin-Token: $ADMIN_TOKEN"
  wait_http "http://127.0.0.1:8082/api/health" "demo"
}

recreate_gateway() {
  local mode="$1"
  local cfg
  if [[ "$mode" == "jdk" ]]; then
    cfg="$DOCKER_DIR/config/rover-gateway-static-jdk-payload.yml"
  else
    cfg="$DOCKER_DIR/config/rover-gateway-static-payload.yml"
  fi
  write_gateway_override "$cfg"
  echo "==> recreate gateway outbound=$mode"
  "${COMPOSE[@]}" -f "$OVERRIDE" up -d --no-deps --force-recreate gateway
  local i=0
  while (( i < 90 )); do
    if curl -fsS "http://127.0.0.1:8080/api/hello" >/dev/null 2>&1; then
      echo "ok: gateway-business ($mode)"
      expect_outbound_log "$mode"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "gateway business path failed ($mode)" >&2
  "${COMPOSE[@]}" logs --tail=80 gateway demo nameserver >&2 || true
  exit 1
}

smoke() {
  local got
  got="$(curl -fsS -o /tmp/rover-payload.bin -w '%{http_code}:%{size_download}' \
    'http://127.0.0.1:8080/api/payload?size=4096')"
  echo "get payload smoke: $got"
  got="$(curl -fsS -o /tmp/rover-ingest.json -w '%{http_code}' \
    -H 'Content-Type: application/octet-stream' \
    --data-binary @<(head -c 4096 /dev/zero) \
    'http://127.0.0.1:8080/api/ingest')"
  echo "post ingest smoke: $got $(cat /tmp/rover-ingest.json)"
}

run_get_matrix() {
  local mode="$1"
  local outdir="$2"
  mkdir -p "$outdir"
  local size r url file
  for size in "${SIZES[@]}"; do
    url="http://127.0.0.1:8080/api/payload?size=${size}"
    echo "==> warmup GET $mode size=$size"
    hey -z 5s -c 10 "$url" >"$outdir/warmup_get_s${size}.txt" || true
    for ((r = 1; r <= ROUNDS; r++)); do
      file="$outdir/${mode}_get_s${size}_c${CONCURRENCY}_r${r}.txt"
      echo "======== GET $mode size=$size c=$CONCURRENCY round=$r ========"
      (
        sleep 8
        docker stats --no-stream --format \
          'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' \
          >"$OUT/docker-stats/stats-${mode}-get-s${size}-r${r}.txt" || true
      ) &
      hey -z "$DURATION" -c "$CONCURRENCY" "$url" | tee "$file"
      wait || true
      sleep 2
    done
    echo "==> direct GET size=$size"
    hey -z "$DURATION" -c "$CONCURRENCY" \
      "http://127.0.0.1:8082/api/payload?size=${size}" \
      | tee "$outdir/direct_get_s${size}_c${CONCURRENCY}.txt"
  done
}

run_post_matrix() {
  local mode="$1"
  local outdir="$2"
  mkdir -p "$outdir"
  local size r url file body
  for size in "${SIZES[@]}"; do
    url="http://127.0.0.1:8080/api/ingest"
    body="$OUT/bodies/body_${size}.bin"
    echo "==> warmup POST $mode size=$size"
    hey -z 5s -c 10 -m POST -T application/octet-stream -D "$body" "$url" \
      >"$outdir/warmup_post_s${size}.txt" || true
    for ((r = 1; r <= ROUNDS; r++)); do
      file="$outdir/${mode}_post_s${size}_c${CONCURRENCY}_r${r}.txt"
      echo "======== POST $mode size=$size c=$CONCURRENCY round=$r ========"
      (
        sleep 8
        docker stats --no-stream --format \
          'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' \
          >"$OUT/docker-stats/stats-${mode}-post-s${size}-r${r}.txt" || true
      ) &
      hey -z "$DURATION" -c "$CONCURRENCY" -m POST -T application/octet-stream -D "$body" "$url" \
        | tee "$file"
      wait || true
      sleep 2
    done
    echo "==> direct POST size=$size"
    hey -z "$DURATION" -c "$CONCURRENCY" -m POST -T application/octet-stream -D "$body" \
      "http://127.0.0.1:8082/api/ingest" \
      | tee "$outdir/direct_post_s${size}_c${CONCURRENCY}.txt"
  done
}

echo "==> payload remasure run_id=$RUN_ID"
echo "==> output: $OUT"
make_bodies
up_base
recreate_gateway "netty"
smoke
run_get_matrix "netty" "$OUT/01-netty-get"
run_post_matrix "netty" "$OUT/02-netty-post"

recreate_gateway "jdk"
smoke
run_get_matrix "jdk" "$OUT/03-jdk-get"
run_post_matrix "jdk" "$OUT/04-jdk-post"

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
lat_re2 = {
    "p50": re.compile(r"50% in ([0-9.]+) secs"),
    "p95": re.compile(r"95% in ([0-9.]+) secs"),
    "p99": re.compile(r"99% in ([0-9.]+) secs"),
}
status_re = re.compile(r"\[(\d+)\]\s+(\d+)\s+responses")
rows = []
for p in sorted(out.rglob("*.txt")):
    if p.name.startswith("warmup") or p.name.startswith("stats-") or p.name.startswith("gateway-log"):
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

echo "==> payload remasure done"
echo "    $OUT"
echo "    $OUT/summary.csv"
