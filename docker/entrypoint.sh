#!/usr/bin/env bash
# 家庭账房 · v0.7 容器入口:等 MySQL → 跑版本化迁移(db/apply.sh)→ 启动应用
# 与 systemd 路径共用同一套 db/migration/V*.sql + schema_history,幂等,从 systemd 迁来的库不重放。
set -euo pipefail

: "${DB_HOST:=db}"
: "${DB_PORT:=3306}"
: "${DB_USER:=finance}"
: "${DB_NAME:=finance}"
: "${DB_PASS:?DB_PASS 未设置}"

echo "[entrypoint] 等待 MySQL ${DB_HOST}:${DB_PORT} ..."
for i in $(seq 1 60); do
  if mysqladmin ping -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" --silent >/dev/null 2>&1; then
    echo "[entrypoint] MySQL 就绪"
    break
  fi
  if [ "$i" -eq 60 ]; then echo "[entrypoint] 等待 MySQL 超时(120s)"; exit 1; fi
  sleep 2
done

echo "[entrypoint] 应用数据库迁移(db/apply.sh · schema_history 幂等)..."
DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_USER="$DB_USER" DB_PASS="$DB_PASS" DB_NAME="$DB_NAME" \
  bash /app/db/apply.sh

echo "[entrypoint] 启动应用 ..."
# shellcheck disable=SC2086
exec java $JAVA_OPTS -Dfile.encoding=UTF-8 -Duser.timezone="${TZ:-Asia/Shanghai}" -jar /app/app.jar
