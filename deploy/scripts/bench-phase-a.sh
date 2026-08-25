#!/usr/bin/env bash
# Phase A：Direct / Gateway-static / Gateway-nameserver
# 对应私有文档「瓶颈定位」计划的可执行子集；修正 demo 饿死导致尾巴失真。
#
# 仓库根目录：
#   ./deploy/scripts/bench-phase-a.sh
#   BENCH_KEEP=1 ./deploy/scripts/bench-phase-a.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT/deploy/docker"
RUN_ID="${BENCH_RUN_ID:-$(date +%Y-%m-%d-%H%M%S)-phase-a}"
OUT_ROOT="${BENCH_OUT_ROOT:-/Users/smt/Desktop/stu_obj_docs/Rover-Suite/bench-results}"
OUT="$OUT_ROOT/$RUN_ID"
TIMEOUT="${BENCH_TIMEOUT:-180}"
DIRECT_URL="http://127.0.0.1:8082/api/hello"
GW_URL="http://127.0.0.1:8080/api/hello"
# 与 deploy/docker/config/rover-nameserver.yml 的 adminToken 一致
ADMIN_TOKEN="${BENCH_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
OVERRIDE="$DOCKER_DIR/.bench-gateway-override.yml"

COMPOSE=(docker compose
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.perf-fair.yml")

if ! command -v hey >/dev/null 2>&1; then
  echo "未找到 hey。先装：brew install hey" >&2
  exit 1
fi

mkdir -p "$OUT"/{00-env,01-direct,02-gateway-static,03-gateway-nameserver,docker-stats}

cleanup() {
  rm -f "$OVERRIDE"
  if [[ "${BENCH_KEEP:-0}" == "1" ]]; then
    echo "BENCH_KEEP=1，保留容器"
    return
  fi
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

write_env() {
  {
    echo "# Phase A 压测环境"
    echo
    echo "- run_id: \`$RUN_ID\`"
    echo "- date: $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "- git: \`$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)\`"
    echo "- host: $(uname -s) $(uname -m)"
    echo "- docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
    echo "- runtime: Docker Desktop for Mac"
    echo "- profile: perf-fair（GW 2c/2g + NS 1c/1g + demo 2c/1g）"
    echo "- admin: 不启动"
    echo "- gateway.filters.accessLog: false"
    echo "- gateway.rateLimit.enabled: false"
    echo "- URLs: direct=\`$DIRECT_URL\` gateway=\`$GW_URL\`"
  } >"$OUT/00-env/00-env.md"
  cp "$DOCKER_DIR/docker-compose.yml" "$OUT/00-env/"
  cp "$DOCKER_DIR/docker-compose.perf-fair.yml" "$OUT/00-env/"
  cp "$DOCKER_DIR/config/rover-gateway-static.yml" "$OUT/00-env/"
  cp "$DOCKER_DIR/config/rover-gateway-ns-min.yml" "$OUT/00-env/"
  cp "$DOCKER_DIR/config/demo-application.yml" "$OUT/00-env/"
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

write_gateway_override() {
  local cfg_abs="$1"
  cat >"$OVERRIDE" <<EOF
services:
  gateway:
    volumes:
      - ${cfg_abs}:/app/config/rover-gateway.yml:ro
EOF
}

up_services() {
  local mode="$1"
  local cfg
  if [[ "$mode" == "static" ]]; then
    cfg="$DOCKER_DIR/config/rover-gateway-static.yml"
  else
    cfg="$DOCKER_DIR/config/rover-gateway-ns-min.yml"
  fi
  write_gateway_override "$cfg"

  echo "==> up mode=$mode"
  "${COMPOSE[@]}" -f "$OVERRIDE" up -d --remove-orphans --build nameserver demo gateway
  "${COMPOSE[@]}" stop admin >/dev/null 2>&1 || true

  wait_http "http://127.0.0.1:8889/_manage/health" "nameserver" \
    -H "X-Rover-Admin-Token: $ADMIN_TOKEN"

  wait_http "$DIRECT_URL" "direct-demo"

  local i=0
  while (( i < 90 )); do
    if curl -fsS "$GW_URL" >/dev/null 2>&1; then
      echo "ok: gateway-business ($mode)"
      curl -fsS "$GW_URL" | tee "$OUT/00-env/sample-${mode}.json" >/dev/null
      echo
      docker stats --no-stream >"$OUT/docker-stats/stats-${mode}-ready.txt" || true
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "gateway business path failed ($mode)" >&2
  "${COMPOSE[@]}" -f "$OVERRIDE" logs --tail=120 gateway demo nameserver >&2 || true
  exit 1
}

run_hey_matrix() {
  local label="$1"
  local url="$2"
  local outdir="$3"
  mkdir -p "$outdir"

  echo "==> warmup $label"
  hey -z 5s -c 20 "$url" >"$outdir/warmup.txt" || true

  local c repeats r
  for c in 50 100; do
    if [[ "$c" == "50" ]]; then repeats=5; else repeats=3; fi
    for ((r = 1; r <= repeats; r++)); do
      local file="$outdir/${label}_c${c}_r${r}.txt"
      echo "======== $label c=$c round=$r / $repeats ========"
      hey -z 30s -c "$c" "$url" | tee "$file"
      sleep 2
    done
  done
  docker stats --no-stream >"$OUT/docker-stats/stats-after-${label}.txt" || true
}

echo "==> Phase A run_id=$RUN_ID"
echo "==> output: $OUT"
write_env

echo "==> Direct baseline"
"${COMPOSE[@]}" up -d --remove-orphans --build nameserver demo
"${COMPOSE[@]}" stop admin gateway >/dev/null 2>&1 || true
wait_http "$DIRECT_URL" "direct-demo"
run_hey_matrix "direct" "$DIRECT_URL" "$OUT/01-direct"

up_services static
run_hey_matrix "gw_static" "$GW_URL" "$OUT/02-gateway-static"

up_services nameserver
run_hey_matrix "gw_ns" "$GW_URL" "$OUT/03-gateway-nameserver"

python3 - "$OUT" <<'PY'
import csv, re, sys
from pathlib import Path
out = Path(sys.argv[1])
rows = []
qps_re = re.compile(r"Requests/sec:\s*([0-9.]+)")
lat_re = {
    "p50": re.compile(r"50% in ([0-9.]+) secs"),
    "p95": re.compile(r"95% in ([0-9.]+) secs"),
    "p99": re.compile(r"99% in ([0-9.]+) secs"),
}
status_re = re.compile(r"\[(\d+)\]\s+(\d+)\s+responses")
status_re2 = re.compile(r"\[(\d+)\]\s+(\d+)")
for p in sorted(out.rglob("*.txt")):
    if p.name.startswith("warmup") or p.name.startswith("stats-"):
        continue
    text = p.read_text(errors="ignore")
    m = qps_re.search(text)
    if not m:
        continue
    statuses = {a: int(b) for a, b in status_re.findall(text)}
    if not statuses:
        statuses = {a: int(b) for a, b in status_re2.findall(text)}
    ok = statuses.get("200", 0)
    err = sum(v for k, v in statuses.items() if k != "200")
    def ms(key):
        mm = lat_re[key].search(text)
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

echo "==> Phase A done"
echo "    $OUT"
echo "    $OUT/summary.csv"
