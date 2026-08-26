#!/usr/bin/env bash
# 本地 Compose 故障演示：
#   1 双实例轮询
#   2 优雅停其中一个（注销）
#   3 强杀其中一个（心跳超时）
#   4 停 Nameserver（缓存转发）
#   5 停最后一个实例（空快照保护 / 对账清空）
# 仓库根目录：
#   ./deploy/scripts/demo-fault.sh
# 环境变量：
#   FAULT_KEEP=1        结束后不 down
#   FAULT_SKIP_BUILD=1  跳过 compose build（镜像已有时）
#   FAULT_TIMEOUT=180   等就绪 / 等摘除的秒数
#   FAULT_RESULTS=路径  结果目录；默认写到桌面私有文档包
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="$ROOT/deploy/docker"
COMPOSE_FILE="$COMPOSE_DIR/docker-compose.yml"
FAULT_FILE="$COMPOSE_DIR/docker-compose.fault.yml"
PROJECT="rover-fault"
TIMEOUT="${FAULT_TIMEOUT:-180}"
ADMIN_TOKEN="${FAULT_ADMIN_TOKEN:-rover-compose-gateway-admin-token}"
HEADER="X-Rover-Admin-Token: ${ADMIN_TOKEN}"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS="${FAULT_RESULTS:-$HOME/Desktop/stu_obj_docs/Rover-Suite/bench-results/${STAMP}-fault-demo}"

COMPOSE=(docker compose -p "$PROJECT" -f "$COMPOSE_FILE" -f "$FAULT_FILE")

mkdir -p "$RESULTS"
LOG="$RESULTS/00-run.log"
exec > >(tee -a "$LOG") 2>&1

cleanup() {
  if [[ "${FAULT_KEEP:-0}" == "1" ]]; then
    echo "FAULT_KEEP=1，保留容器 project=$PROJECT"
    return
  fi
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$COMPOSE_DIR"

curl_admin() {
  curl -fsS -H "$HEADER" "$@"
}

hello_host() {
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("host") or "")'
}

list_hosts() {
  curl_admin "http://127.0.0.1:8889/_manage/instances" | python3 -c '
import json, sys
rows = json.load(sys.stdin)
hosts = []
for row in rows:
    if row.get("serviceName") != "demo-service":
        continue
    hosts.append("%s:%s:%s" % (row.get("host"), row.get("port"), "up" if row.get("healthy") else "down"))
print(",".join(sorted(hosts)) if hosts else "(none)")
'
}

count_healthy() {
  curl_admin "http://127.0.0.1:8889/_manage/instances" | python3 -c '
import json, sys
rows = json.load(sys.stdin)
n = 0
for row in rows:
    if row.get("serviceName") == "demo-service" and row.get("healthy"):
        n += 1
print(n)
'
}

wait_http() {
  local url="$1"
  local name="$2"
  local admin="${3:-}"
  local i=0
  while (( i < TIMEOUT )); do
    if [[ -n "$admin" ]]; then
      if curl -fsS -H "$HEADER" "$url" >/dev/null 2>&1; then
        echo "ok: $name"
        return 0
      fi
    elif curl -fsS "$url" >/dev/null 2>&1; then
      echo "ok: $name"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "timeout waiting for $name: $url" >&2
  "${COMPOSE[@]}" ps >&2 || true
  return 1
}

wait_healthy() {
  local want="$1"
  local label="$2"
  local ready=0
  local i healthy
  for (( i = 0; i < TIMEOUT; i++ )); do
    healthy="$(count_healthy)"
    echo "  ns instances=$(list_hosts) healthy=$healthy"
    if [[ "$healthy" == "$want" ]]; then
      ready=1
      break
    fi
    sleep 1
  done
  if [[ "$ready" != "1" ]]; then
    echo "timeout waiting for $label ($want healthy)" >&2
    "${COMPOSE[@]}" logs --tail=60 nameserver demo demo-b gateway >&2 || true
    return 1
  fi
}

start_svc() {
  local name="$1"
  docker update --restart=unless-stopped "${PROJECT}-${name}-1" >/dev/null 2>&1 || true
  docker start "${PROJECT}-${name}-1" >/dev/null
  echo "started $name"
}

sample_hello() {
  local n="$1"
  local i
  local body host
  local demo=0
  local demob=0
  local other=0
  local fail=0
  for (( i = 1; i <= n; i++ )); do
    if body="$(curl -fsS --max-time 5 "http://127.0.0.1:8080/api/hello" 2>/dev/null)"; then
      host="$(printf '%s' "$body" | hello_host)"
      echo "  hello[$i]=$host"
      case "$host" in
        demo) demo=$((demo + 1)) ;;
        demo-b) demob=$((demob + 1)) ;;
        *) other=$((other + 1)) ;;
      esac
    else
      echo "  hello[$i]=FAIL"
      fail=$((fail + 1))
    fi
  done
  echo "counts demo=$demo demo-b=$demob other=$other fail=$fail"
}

# 带状态码：200/host、502、503+原因。最后一个实例那场要用。
probe_hello() {
  python3 - <<'PY'
import json, urllib.error, urllib.request
req = urllib.request.Request("http://127.0.0.1:8080/api/hello")
try:
    with urllib.request.urlopen(req, timeout=5) as resp:
        body = json.loads(resp.read().decode())
        print("%s host=%s" % (resp.status, body.get("host") or ""))
except urllib.error.HTTPError as err:
    reason = err.headers.get("X-Rover-Reject-Reason") or ""
    print("%s reason=%s" % (err.code, reason))
except Exception as err:
    print("000 err=%s" % type(err).__name__)
PY
}

require_counts() {
  python3 - "$1" "${2:-}" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
line = [x for x in text.splitlines() if x.startswith("counts ")][-1]
parts = dict(p.split("=", 1) for p in line.split()[1:])
demo = int(parts["demo"])
demob = int(parts["demo-b"])
fail = int(parts["fail"])
need_both = sys.argv[2] == "both" if len(sys.argv) > 2 else False
only_demo = sys.argv[2] == "demo" if len(sys.argv) > 2 else False
if fail:
    raise SystemExit("need zero failures: " + line)
if need_both and (demo == 0 or demob == 0):
    raise SystemExit("need both hosts: " + line)
if only_demo and (demo == 0 or demob != 0):
    raise SystemExit("need only demo: " + line)
print("ok " + line)
PY
}

echo "==> project=$PROJECT stamp=$STAMP"
echo "==> results=$RESULTS"

echo "==> build + up"
if [[ "${FAULT_SKIP_BUILD:-0}" == "1" ]]; then
  echo "FAULT_SKIP_BUILD=1，跳过 build"
else
  "${COMPOSE[@]}" build
fi
"${COMPOSE[@]}" up -d

echo "==> wait ready (timeout=${TIMEOUT}s)"
wait_http "http://127.0.0.1:8889/_manage/health" "nameserver-health" admin
wait_http "http://127.0.0.1:8080/_manage/health" "gateway-health" admin
echo "==> wait two healthy demo-service instances"
wait_healthy 2 "two instances"

echo
echo "==> act 1: dual instance round-robin (20 sequential curls)"
sample_hello 20 | tee "$RESULTS/01-round-robin.txt"
require_counts "$RESULTS/01-round-robin.txt" both
echo "act 1 pass"

echo
echo "==> act 2: docker stop demo-b (graceful unregister)"
t0="$(date +%s)"
docker stop "${PROJECT}-demo-b-1" >/dev/null
echo "stopped demo-b at t=0"
evicted=""
avoided=""
for (( i = 0; i < TIMEOUT; i++ )); do
  elapsed=$(( $(date +%s) - t0 ))
  healthy="$(count_healthy)"
  hosts="$(list_hosts)"
  echo "  t=${elapsed}s ns=$hosts healthy=$healthy"
  if [[ -z "$evicted" && "$healthy" == "1" && "$hosts" == *demo:8082:up* ]]; then
    evicted="$elapsed"
    echo "  nameserver has one healthy instance at t=${evicted}s"
  fi
  if [[ -n "$evicted" ]]; then
    out="$(sample_hello 10)"
    printf '%s\n' "$out"
    if echo "$out" | grep -q 'counts demo=10 demo-b=0 other=0 fail=0'; then
      avoided="$elapsed"
      echo "  gateway avoided demo-b at t=${avoided}s"
      break
    fi
  fi
  sleep 1
done
{
  echo "evicted_seconds=${evicted:-timeout}"
  echo "avoided_seconds=${avoided:-timeout}"
} | tee "$RESULTS/02-stop-instance.txt"
if [[ -z "$evicted" || -z "$avoided" ]]; then
  echo "act 2 failed" >&2
  exit 1
fi
echo "act 2 pass"

echo
echo "==> restart demo-b for hard-kill act"
start_svc demo-b
wait_healthy 2 "two instances after restart"

echo
echo "==> act 3: docker kill demo-b (no unregister; disable restart first)"
docker update --restart=no "${PROJECT}-demo-b-1" >/dev/null
t_kill="$(date +%s)"
docker kill "${PROJECT}-demo-b-1" >/dev/null
echo "killed demo-b at t=0"
kill_evicted=""
kill_avoided=""
for (( i = 0; i < TIMEOUT; i++ )); do
  elapsed=$(( $(date +%s) - t_kill ))
  healthy="$(count_healthy)"
  hosts="$(list_hosts)"
  echo "  t=${elapsed}s ns=$hosts healthy=$healthy"
  if [[ -z "$kill_evicted" && "$healthy" == "1" && "$hosts" == *demo:8082:up* ]]; then
    kill_evicted="$elapsed"
    echo "  nameserver has one healthy instance at t=${kill_evicted}s"
  fi
  if [[ -n "$kill_evicted" ]]; then
    out="$(sample_hello 10)"
    printf '%s\n' "$out"
    if echo "$out" | grep -q 'counts demo=10 demo-b=0 other=0 fail=0'; then
      kill_avoided="$elapsed"
      echo "  gateway avoided killed demo-b at t=${kill_avoided}s"
      break
    fi
  fi
  sleep 1
done
{
  echo "evicted_seconds=${kill_evicted:-timeout}"
  echo "avoided_seconds=${kill_avoided:-timeout}"
} | tee "$RESULTS/03-kill-instance.txt"
if [[ -z "$kill_evicted" || -z "$kill_avoided" ]]; then
  echo "act 3 failed" >&2
  "${COMPOSE[@]}" logs --tail=80 nameserver gateway demo >&2 || true
  exit 1
fi
echo "act 3 pass"

echo
echo "==> restart demo-b for pause act"
start_svc demo-b
wait_healthy 2 "two instances before pause"

echo
echo "==> act 3b: docker pause demo-b (TCP still up, heartbeats freeze)"
t_pause="$(date +%s)"
docker pause "${PROJECT}-demo-b-1" >/dev/null
echo "paused demo-b at t=0"
pause_evicted=""
pause_avoided=""
for (( i = 0; i < TIMEOUT; i++ )); do
  elapsed=$(( $(date +%s) - t_pause ))
  healthy="$(count_healthy)"
  hosts="$(list_hosts)"
  echo "  t=${elapsed}s ns=$hosts healthy=$healthy"
  if [[ -z "$pause_evicted" && "$healthy" == "1" && "$hosts" == *demo:8082:up* ]]; then
    pause_evicted="$elapsed"
    echo "  nameserver has one healthy instance at t=${pause_evicted}s"
  fi
  if [[ -n "$pause_evicted" ]]; then
    out="$(sample_hello 10)"
    printf '%s\n' "$out"
    if echo "$out" | grep -q 'counts demo=10 demo-b=0 other=0 fail=0'; then
      pause_avoided="$elapsed"
      echo "  gateway avoided paused demo-b at t=${pause_avoided}s"
      break
    fi
  fi
  sleep 1
done
{
  echo "evicted_seconds=${pause_evicted:-timeout}"
  echo "avoided_seconds=${pause_avoided:-timeout}"
} | tee "$RESULTS/03b-pause-instance.txt"
if [[ -z "$pause_evicted" || -z "$pause_avoided" ]]; then
  echo "act 3b failed" >&2
  docker unpause "${PROJECT}-demo-b-1" >/dev/null 2>&1 || true
  "${COMPOSE[@]}" logs --tail=80 nameserver gateway demo >&2 || true
  exit 1
fi
echo "act 3b pass"
docker unpause "${PROJECT}-demo-b-1" >/dev/null 2>&1 || true
start_svc demo-b
wait_healthy 2 "two instances before stopping nameserver"

echo
echo "==> act 4: stop nameserver, cache should still forward"
t_ns="$(date +%s)"
docker stop "${PROJECT}-nameserver-1" >/dev/null
echo "stopped nameserver"
sleep 1
sample_hello 10 | tee "$RESULTS/04-stop-nameserver.txt"
require_counts "$RESULTS/04-stop-nameserver.txt" both
echo "nameserver_stopped_then_curled=1s" >> "$RESULTS/04-stop-nameserver.txt"
echo "act 4 wall=$(( $(date +%s) - t_ns ))s"
echo "act 4 pass"

echo
echo "==> restart nameserver for last-instance act"
start_svc nameserver
wait_http "http://127.0.0.1:8889/_manage/health" "nameserver-health" admin
wait_healthy 2 "two instances after nameserver restart"

echo
echo "==> act 5: leave one instance, then graceful-stop the last one"
docker stop "${PROJECT}-demo-b-1" >/dev/null
wait_healthy 1 "single remaining demo"
echo "stopping last instance demo"
t_last="$(date +%s)"
docker stop "${PROJECT}-demo-1" >/dev/null
ns_empty=""
first_fail=""
first_503=""
stable_503=""
consec_503=0
for (( i = 0; i < TIMEOUT; i++ )); do
  elapsed=$(( $(date +%s) - t_last ))
  if curl -fsS -H "$HEADER" "http://127.0.0.1:8889/_manage/health" >/dev/null 2>&1; then
    healthy="$(count_healthy)"
    hosts="$(list_hosts)"
  else
    healthy="?"
    hosts="(ns down)"
  fi
  probe="$(probe_hello)"
  echo "  t=${elapsed}s ns=$hosts healthy=$healthy probe=$probe"
  if [[ -z "$ns_empty" && "$healthy" == "0" ]]; then
    ns_empty="$elapsed"
    echo "  nameserver empty at t=${ns_empty}s"
  fi
  if [[ -z "$first_fail" && "$probe" != 200* ]]; then
    first_fail="$elapsed"
    echo "  first non-200 at t=${first_fail}s ($probe)"
  fi
  if [[ "$probe" == 503* ]]; then
    if [[ -z "$first_503" ]]; then
      first_503="$elapsed"
      echo "  first 503 at t=${first_503}s ($probe)"
    fi
    consec_503=$((consec_503 + 1))
    if [[ -z "$stable_503" && "$consec_503" -ge 5 ]]; then
      stable_503="$elapsed"
      echo "  stable 503 at t=${stable_503}s"
      break
    fi
  else
    consec_503=0
  fi
  sleep 1
done
{
  echo "ns_empty_seconds=${ns_empty:-timeout}"
  echo "first_fail_seconds=${first_fail:-timeout}"
  echo "first_503_seconds=${first_503:-timeout}"
  echo "stable_503_seconds=${stable_503:-timeout}"
} | tee "$RESULTS/05-last-instance.txt"
if [[ -z "$ns_empty" || -z "$first_fail" || -z "$stable_503" ]]; then
  echo "act 5 failed" >&2
  "${COMPOSE[@]}" logs --tail=80 nameserver gateway >&2 || true
  exit 1
fi
echo "act 5 pass"

cat > "$RESULTS/SUMMARY.md" <<EOF
# Compose 故障演示 ${STAMP}

环境：本机 Docker Compose project \`${PROJECT}\`。
心跳超时默认 15s，健康检查间隔 5s，Gateway 对账默认 30s。数字只代表这一场。

| 场 | 做法 | 结果 |
| --- | --- | --- |
| 1 | 20 次 hello | 见 \`01-round-robin.txt\` |
| 2 | \`docker stop demo-b\`（优雅注销） | NS \`${evicted}s\` / 躲开 \`${avoided}s\` |
| 3 | \`docker kill demo-b\`（强杀，TCP 断） | NS \`${kill_evicted}s\` / 躲开 \`${kill_avoided}s\` |
| 3b | \`docker pause demo-b\`（连接还在、心跳停） | NS \`${pause_evicted}s\` / 躲开 \`${pause_avoided}s\` |
| 4 | \`docker stop nameserver\` | 停掉约 1s 后再打 10 次仍成功 |
| 5 | 停最后一个实例 | NS 空 \`${ns_empty}s\`；首次非 200 \`${first_fail}s\`；稳定 503 \`${stable_503}s\` |

EOF

echo
echo "==> wrote $RESULTS/SUMMARY.md"
echo "==> fault demo passed"
