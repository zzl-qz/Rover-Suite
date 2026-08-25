#!/usr/bin/env bash
# 同一镜像、同一场 A/B：outbound=jdk vs outbound=netty。
# 只换 YAML 重启 Gateway，不改 jar。每边都先打 Direct。
# Direct c=50 中位差 >10% 整轮作废。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT/deploy/docker"
RUN_ID="${BENCH_RUN_ID:-$(date +%Y-%m-%d-%H%M%S)-phase-a-outbound-ab}"
OUT_ROOT="${BENCH_OUT_ROOT:-/Users/smt/Desktop/stu_obj_docs/Rover-Suite/bench-results}"
OUT="$OUT_ROOT/$RUN_ID"
TIMEOUT="${BENCH_TIMEOUT:-180}"
DIRECT_URL="http://127.0.0.1:8082/api/hello"
GW_URL="http://127.0.0.1:8080/api/hello"
ADMIN_TOKEN="${BENCH_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
OVERRIDE="$DOCKER_DIR/.bench-gateway-override.yml"

COMPOSE=(docker compose
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.perf-fair.yml")

if ! command -v hey >/dev/null 2>&1; then
  echo "未找到 hey。先装：brew install hey" >&2
  exit 1
fi

mkdir -p "$OUT"/{00-env,01-jdk,02-netty,docker-stats}

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
  echo "# Phase A outbound same-session A/B"
  echo
  echo "- run_id: \`$RUN_ID\`"
  echo "- date: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "- rule: 同一 jar，只换 proxy.outbound；Direct 两边中位差 >10% 则作废"
  echo "- profile: perf-fair"
  echo "- order: jdk 先，netty 后"
  echo "- paths: Direct :8082 + Gateway static :8080 /api/hello"
} >"$OUT/00-env/00-env.md"
cp "$DOCKER_DIR/config/rover-gateway-static.yml" "$OUT/00-env/"
cp "$DOCKER_DIR/config/rover-gateway-static-jdk.yml" "$OUT/00-env/"

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
      echo "outbound mismatch: 期望 JDK，日志里没有" >&2
      echo "$logs" | tail -n 80 >&2
      exit 1
    fi
  else
    if ! echo "$logs" | grep -q "出站代理: Netty"; then
      echo "outbound mismatch: 期望 Netty，日志里没有" >&2
      echo "$logs" | tail -n 80 >&2
      exit 1
    fi
  fi
  echo "ok: outbound-log=$mode"
}

up_base() {
  echo "==> build+up demo/nameserver/gateway（先用 netty 配置把镜像打出来）"
  write_gateway_override "$DOCKER_DIR/config/rover-gateway-static.yml"
  "${COMPOSE[@]}" -f "$OVERRIDE" up -d --remove-orphans --build nameserver demo gateway
  "${COMPOSE[@]}" stop admin >/dev/null 2>&1 || true
  wait_http "http://127.0.0.1:8889/_manage/health" "nameserver" \
    -H "X-Rover-Admin-Token: $ADMIN_TOKEN"
  wait_http "$DIRECT_URL" "direct-demo"
}

recreate_gateway() {
  local mode="$1"
  local cfg
  if [[ "$mode" == "jdk" ]]; then
    cfg="$DOCKER_DIR/config/rover-gateway-static-jdk.yml"
  else
    cfg="$DOCKER_DIR/config/rover-gateway-static.yml"
  fi
  write_gateway_override "$cfg"
  echo "==> recreate gateway outbound=$mode"
  "${COMPOSE[@]}" -f "$OVERRIDE" up -d --no-deps --force-recreate gateway
  local i=0
  while (( i < 90 )); do
    if curl -fsS "$GW_URL" >/dev/null 2>&1; then
      echo "ok: gateway-business ($mode)"
      curl -fsS "$GW_URL" | tee "$OUT/00-env/sample-${mode}.json" >/dev/null
      echo
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
    if m:
        qps.append(float(m.group(1)))
print(statistics.median(qps) if qps else 0)
PY
}

run_pair() {
  local mode="$1"
  local tag="$2"
  recreate_gateway "$mode"
  "${COMPOSE[@]}" stop gateway >/dev/null 2>&1 || true
  run_hey_matrix "direct" "$DIRECT_URL" "$OUT/$tag"
  "${COMPOSE[@]}" start gateway >/dev/null
  wait_http "$GW_URL" "gateway-after-direct-$mode"
  run_hey_matrix "gw_static" "$GW_URL" "$OUT/$tag"
  docker stats --no-stream >"$OUT/docker-stats/stats-after-${tag}.txt" || true
}

echo "==> outbound A/B run_id=$RUN_ID"
echo "==> output: $OUT"
up_base

run_pair "jdk" "01-jdk"
run_pair "netty" "02-netty"

d1="$(direct_median_c50 "$OUT/01-jdk")"
d2="$(direct_median_c50 "$OUT/02-netty")"
echo "Direct c=50 median: jdk-half=$d1 netty-half=$d2"
python3 - "$d1" "$d2" "$OUT" <<'PY'
import sys
from pathlib import Path
a, b = float(sys.argv[1]), float(sys.argv[2])
out = Path(sys.argv[3])
base = min(a, b) if min(a, b) > 0 else 1
drift = abs(a - b) / base * 100
print(f"direct_drift={drift:.1f}%")
note = out / "00-env" / ("QUALIFIED.md" if drift <= 10 else "UNQUALIFIED.md")
note.write_text(
    f"# {'合格' if drift <= 10 else '不合格'}\n\n"
    f"- Direct c=50 jdk 半场: {a:.1f}\n"
    f"- Direct c=50 netty 半场: {b:.1f}\n"
    f"- drift: {drift:.1f}%\n"
    f"- 门闩: ≤10%\n",
    encoding="utf-8",
)
sys.exit(0 if drift <= 10 else 2)
PY
drift_rc=$?
if [[ $drift_rc -ne 0 ]]; then
  echo "HOST_DRIFT: Direct 差 >10%，这组不能用来下结论" >&2
fi

python3 - "$OUT" <<'PY'
import csv, re, sys
from pathlib import Path
out = Path(sys.argv[1])
qps_re = re.compile(r"Requests/sec:\s*([0-9.]+)")
lat_re = re.compile(r"50%% in ([0-9.]+) secs")
lat_re2 = re.compile(r"50% in ([0-9.]+) secs")
status_re = re.compile(r"\[(\d+)\]\s+(\d+)\s+responses")
total_re = re.compile(r"Total:\s*([0-9.]+) secs")
rows=[]
for p in sorted(out.rglob("*.txt")):
    if p.name.startswith("warmup") or p.name.startswith("stats-") or p.name.startswith("gateway-log"):
        continue
    text=p.read_text(errors="ignore")
    m=qps_re.search(text)
    if not m:
        continue
    statuses={a:int(b) for a,b in status_re.findall(text)}
    mm=lat_re.search(text) or lat_re2.search(text)
    ok=statuses.get("200", 0)
    err=sum(v for k,v in statuses.items() if k != "200")
    dur=total_re.search(text)
    succ = round(ok / float(dur.group(1)), 1) if dur and float(dur.group(1)) > 0 else ""
    rows.append({
        "file": str(p.relative_to(out)),
        "qps": float(m.group(1)),
        "p50_ms": round(float(mm.group(1))*1000, 3) if mm else "",
        "http_200": ok,
        "http_non_200": err,
        "success_qps": succ,
    })
path=out/"summary.csv"
with path.open("w", newline="") as f:
    w=csv.DictWriter(f, fieldnames=["file","qps","p50_ms","http_200","http_non_200","success_qps"])
    w.writeheader()
    w.writerows(rows)
print("wrote", path, "rows", len(rows))
PY

echo "==> outbound A/B done $OUT"
echo "    $OUT/summary.csv"
