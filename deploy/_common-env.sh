#!/usr/bin/env bash
# 运维脚本共用:识别部署形态(Docker / systemd 直装)+ 读配置。
# v1.6.28 · 被 backup-now.sh / restore.sh / doctor.sh 共用,避免三份各写一套探测(必然漂移)。
# 用法:在脚本里 `. "$(dirname "$0")/_common-env.sh"`,之后可用:
#   MODE=docker|systemd · DC(compose 命令)· BK_DIR(备份目录)· envval KEY
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

say(){ printf '%s\n' "$*"; }
die(){ printf '\n✗ %s\n' "$*" >&2; exit 1; }

# .env 取值(键缺失输出空、退出 0)
envval(){ grep -E "^$1=" "$REPO_ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2- || true; }

# compose 命令(与 docker-up.sh 同口径:强制 V2)
detect_dc(){
  if docker compose version >/dev/null 2>&1; then echo "docker compose"
  elif command -v docker-compose >/dev/null 2>&1; then
    case "$(docker-compose version --short 2>/dev/null || true)" in 2.*|v2.*) echo "docker-compose" ;; *) echo "" ;; esac
  else echo ""; fi
}

MODE=""; DC=""; BK_DIR=""
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  DC="$(detect_dc)"
  if [[ -n "$DC" ]] && $DC ps --services 2>/dev/null | grep -qx db; then
    MODE=docker; BK_DIR="/data/backups"      # 容器内路径(在 backup 服务里)
  fi
fi
if [[ -z "$MODE" ]]; then
  if [[ -f /etc/finance.env ]] && command -v systemctl >/dev/null 2>&1; then
    MODE=systemd; BK_DIR="${BACKUP_DIR:-/var/backup/finance}"
  fi
fi
[[ -n "$MODE" ]] || die "没识别出部署形态。
  · Docker:确认在仓库根目录、且 \`docker compose ps\` 能看到 db 服务(没起就先 bash deploy/docker-up.sh)
  · systemd 直装:确认 /etc/finance.env 存在"

# Docker 下在 db 容器里跑 mysql/mysqldump(不依赖宿主装 mysql client)
dbx(){ $DC exec -T db "$@"; }
