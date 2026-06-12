#!/usr/bin/env bash
# 家庭账房 · v0.7 备份 sidecar:每日 mysqldump 到 backups 卷 + 按保留天数清理。
# 复用 app 镜像(自带 mysql client),compose 里覆盖 entrypoint 跑这个。
set -uo pipefail

: "${DB_HOST:=db}"
: "${DB_PORT:=3306}"
: "${DB_USER:=finance}"
: "${DB_NAME:=finance}"
: "${DB_PASS:?DB_PASS 未设置}"
: "${RETENTION_DAYS:=56}"
: "${BACKUP_DIR:=/data/backups}"

mkdir -p "$BACKUP_DIR"
echo "[backup] sidecar 启动 · 每 24h dump 一次 · 保留 ${RETENTION_DAYS} 天 → ${BACKUP_DIR}"

while true; do
  ts=$(date +%Y%m%d-%H%M%S)
  f="${BACKUP_DIR}/finance-${ts}.sql.gz"
  if MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
       -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" 2>/dev/null | gzip > "$f"; then
    echo "[backup] $f ($(du -h "$f" | cut -f1))"
  else
    echo "[backup] 失败 $ts(库未就绪?稍后重试)"; rm -f "$f"
  fi
  find "$BACKUP_DIR" -name 'finance-*.sql.gz' -mtime +"$RETENTION_DAYS" -delete 2>/dev/null || true
  sleep 86400
done
