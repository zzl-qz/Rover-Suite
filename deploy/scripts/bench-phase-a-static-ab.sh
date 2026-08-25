#!/usr/bin/env bash
# 同一时段 A/B：无静态快照 vs 有静态快照。
# 每边都先打 Direct，再打 Gateway 静态。Direct 中位差 >10% 视为宿主漂移，整轮作废重跑一次。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT/deploy/docker"
RUN_ID="${BENCH_RUN_ID:-$(date +%Y-%m-%d-%H%M%S)-phase-a-static-ab}"
OUT_ROOT="${BENCH_OUT_ROOT:-/Users/smt/Desktop/stu_obj_docs/Rover-Suite/bench-results}"
OUT="$OUT_ROOT/$RUN_ID"
TIMEOUT="${BENCH_TIMEOUT:-180}"
DIRECT_URL="http://127.0.0.1:8082/api/hello"
GW_URL="http://127.0.0.1:8080/api/hello"
ADMIN_TOKEN="${BENCH_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
OVERRIDE="$DOCKER_DIR/.bench-gateway-override.yml"
BACKUP_DIR="$(mktemp -d /tmp/rover-static-ab.XXXXXX)"
FILTER_SRC="$ROOT/rover-gateway-core/src/main/java/com/rover/gateway/core/filter/RouteAndProxyFilter.java"
RUNTIME_SRC="$ROOT/rover-gateway-core/src/main/java/com/rover/gateway/core/runtime/GatewayRuntime.java"

COMPOSE=(docker compose
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.perf-fair.yml")

if ! command -v hey >/dev/null 2>&1; then
  echo "未找到 hey。先装：brew install hey" >&2
  exit 1
fi

mkdir -p "$OUT"/{00-env,01-nocache,02-cache,docker-stats}

cleanup() {
  if [[ -f "$BACKUP_DIR/RouteAndProxyFilter.java" ]]; then
    cp "$BACKUP_DIR/RouteAndProxyFilter.java" "$FILTER_SRC"
    cp "$BACKUP_DIR/GatewayRuntime.java" "$RUNTIME_SRC"
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

cp "$FILTER_SRC" "$BACKUP_DIR/RouteAndProxyFilter.java"
cp "$RUNTIME_SRC" "$BACKUP_DIR/GatewayRuntime.java"

{
  echo "# Phase A static-cache same-session A/B"
  echo
  echo "- run_id: \`$RUN_ID\`"
  echo "- date: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "- rule: Direct 两边中位差 >10% 则作废重跑"
  echo "- profile: perf-fair"
  echo "- paths: Direct :8082 + Gateway static :8080 /api/hello"
} >"$OUT/00-env/00-env.md"

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

disable_cache() {
  python3 - "$FILTER_SRC" "$RUNTIME_SRC" <<'PY'
from pathlib import Path
import sys
filt, runtime = Path(sys.argv[1]), Path(sys.argv[2])
filt.write_text(filt.read_text().replace(
    "StaticUpstreamCluster.instancesOf(route)",
    "StaticUpstreamCluster.resolve(route)"))
rt = runtime.read_text()
rt = rt.replace("        StaticUpstreamCluster.rebuild(initialRoutes);\n", "")
rt = rt.replace("        StaticUpstreamCluster.rebuild(normalized);\n", "")
runtime.write_text(rt)
print("cache disabled")
PY
}

enable_cache() {
  cp "$BACKUP_DIR/RouteAndProxyFilter.java" "$FILTER_SRC"
  cp "$BACKUP_DIR/GatewayRuntime.java" "$RUNTIME_SRC"
  echo "cache restored"
}

up_stack() {
  local mode="$1"
  write_gateway_override
  echo "==> rebuild+up mode=$mode"
  "${COMPOSE[@]}" -f "$OVERRIDE" up -d --remove-orphans --build nameserver demo gateway
  "${COMPOSE[@]}" stop admin >/dev/null 2>&1 || true
  wait_http "http://127.0.0.1:8889/_manage/health" "nameserver" \
    -H "X-Rover-Admin-Token: $ADMIN_TOKEN"
  wait_http "$DIRECT_URL" "direct-demo"
  local i=0
  while (( i < 90 )); do
    if curl -fsS "$GW_URL" >/dev/null 2>&1; then
      echo "ok: gateway-business ($mode)"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "gateway business path failed ($mode)" >&2
  "${COMPOSE[@]}" -f "$OVERRIDE" logs --tail=80 gateway demo nameserver >&2 || true
  exit 1
}

run_hey_matrix() {
  local label="$1"
  local url="$2"
  local outdir="$3"
  mkdir -p "$outdir"
  echo "==> warmup $label"
  hey -z 5s -c 20 "$url" >"$outdir/warmup_${label}.txt" || true
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
}

direct_median_c50() {
  local dir="$1"
  python3 - "$dir" <<'PY'
import re, statistics, sys
from pathlib import Path
qps=[]
for p in Path(sys.argv[1]).glob("direct_c50_r*.txt"):
    t=p.read_text(errors="ignore")
    m=re.search(r"Requests/sec:\s*([0-9.]+)", t)
    if m: qps.append(float(m.group(1)))
print(statistics.median(qps) if qps else 0)
PY
}

run_pair() {
  local tag="$1"
  up_stack "$tag"
  # Direct 时停 Gateway，避免抢 CPU
  "${COMPOSE[@]}" stop gateway >/dev/null 2>&1 || true
  run_hey_matrix "direct" "$DIRECT_URL" "$OUT/$tag"
  "${COMPOSE[@]}" start gateway >/dev/null
  wait_http "$GW_URL" "gateway-after-direct"
  run_hey_matrix "gw_static" "$GW_URL" "$OUT/$tag"
  docker stats --no-stream >"$OUT/docker-stats/stats-after-${tag}.txt" || true
}

echo "==> static A/B run_id=$RUN_ID"
echo "==> output: $OUT"

attempt=1
while (( attempt <= 2 )); do
  echo "==> attempt $attempt"
  disable_cache
  run_pair "01-nocache"
  enable_cache
  run_pair "02-cache"

  d1="$(direct_median_c50 "$OUT/01-nocache")"
  d2="$(direct_median_c50 "$OUT/02-cache")"
  echo "Direct c=50 median: nocache=$d1 cache=$d2"
  python3 - "$d1" "$d2" "$attempt" <<'PY'
import sys
a,b=float(sys.argv[1]),float(sys.argv[2])
attempt=int(sys.argv[3])
base=min(a,b) if min(a,b)>0 else 1
drift=abs(a-b)/base*100
print(f"direct_drift={drift:.1f}%")
if drift>10:
    sys.exit(2)
sys.exit(0)
PY
  rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "HOST_OK: Direct 漂移可接受"
    break
  fi
  if (( attempt == 2 )); then
    echo "HOST_DRIFT: 两轮 Direct 仍差 >10%，结果标为不合格，不写进对外结论" >&2
    echo "UNQUALIFIED" >"$OUT/00-env/UNQUALIFIED.txt"
    break
  fi
  echo "HOST_DRIFT: Direct 差 >10%，清空 hey 结果后立刻重跑"
  rm -rf "$OUT/01-nocache" "$OUT/02-cache"
  mkdir -p "$OUT/01-nocache" "$OUT/02-cache"
  attempt=$((attempt + 1))
done

python3 - "$OUT" <<'PY'
import csv, re, sys
from pathlib import Path
out = Path(sys.argv[1])
qps_re = re.compile(r"Requests/sec:\s*([0-9.]+)")
lat_re = re.compile(r"50%% in ([0-9.]+) secs")
lat_re2 = re.compile(r"50% in ([0-9.]+) secs")
status_re = re.compile(r"\[(\d+)\]\s+(\d+)\s+responses")
rows=[]
for p in sorted(out.rglob("*.txt")):
    if p.name.startswith("warmup") or p.name.startswith("stats-"):
        continue
    text=p.read_text(errors="ignore")
    m=qps_re.search(text)
    if not m:
        continue
    statuses={a:int(b) for a,b in status_re.findall(text)}
    mm=lat_re.search(text) or lat_re2.search(text)
    rows.append({
        "file": str(p.relative_to(out)),
        "qps": float(m.group(1)),
        "p50_ms": round(float(mm.group(1))*1000, 3) if mm else "",
        "http_200": statuses.get("200", 0),
        "http_non_200": sum(v for k,v in statuses.items() if k!="200"),
    })
path=out/"summary.csv"
with path.open("w", newline="") as f:
    w=csv.DictWriter(f, fieldnames=["file","qps","p50_ms","http_200","http_non_200"])
    w.writeheader()
    w.writerows(rows)
print("wrote", path, "rows", len(rows))
PY

echo "==> static A/B done $OUT"
