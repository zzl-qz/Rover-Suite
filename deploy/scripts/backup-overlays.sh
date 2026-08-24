#!/usr/bin/env bash
# 备份 Gateway / Nameserver 运行时 overlay 与启动 YAML（发版/升级前用）
# 用法：在仓库根目录执行
#   ./deploy/scripts/backup-overlays.sh
#   ./deploy/scripts/backup-overlays.sh /path/to/config
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONFIG_DIR="${1:-$ROOT/config}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${BACKUP_DIR:-$ROOT/backups/config-$STAMP}"

mkdir -p "$OUT_DIR"

copied=0
for name in \
  rover-gateway.yml \
  rover-nameserver.yml \
  routes.overlay.json \
  gateway-runtime.overlay.json \
  nameserver-runtime.overlay.json
do
  src="$CONFIG_DIR/$name"
  if [[ -f "$src" ]]; then
    cp -p "$src" "$OUT_DIR/$name"
    copied=$((copied + 1))
    echo "backed up: $name"
  fi
done

if [[ "$copied" -eq 0 ]]; then
  echo "未找到可备份文件（目录: $CONFIG_DIR）。若用 Docker Compose，可：" >&2
  echo "  ./deploy/scripts/backup-overlays.sh deploy/docker/config" >&2
  exit 1
fi

echo "完成: $OUT_DIR （共 $copied 个文件）"
