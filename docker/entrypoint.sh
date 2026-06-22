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

# 迁移前判定:这是不是「全新空卷」—— schema_history 表不存在 = 从未迁移过的全新库。
# 迁移来的库(migrate-to-docker 灌的 dump 自带 schema_history)/ 升级的库 / 老用户库 都已有该表 → 非全新。
# 这是「是否清演示数据」的铁信号:只有全新库才清,且自限(清完 schema_history 已在,重启不再清)。
FRESH_DB=no
HAS_HISTORY=$(MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
  -sN -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='schema_history'" \
  2>/dev/null || echo 1)
[[ "${HAS_HISTORY:-1}" == "0" ]] && FRESH_DB=yes
echo "[entrypoint] 全新空库判定:FRESH_DB=$FRESH_DB(schema_history 表存在数=${HAS_HISTORY})"

echo "[entrypoint] 应用数据库迁移(db/apply.sh · schema_history 幂等)..."
DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_USER="$DB_USER" DB_PASS="$DB_PASS" DB_NAME="$DB_NAME" \
  bash /app/db/apply.sh

# 仅「全新空库」清 dev 演示数据 → 空态 + onboarding(与 systemd deploy.sh step10 一致)。
# 迁移/升级/老用户库 FRESH_DB=no → 绝不触碰任何数据。
if [[ "$FRESH_DB" == "yes" ]]; then
  echo "[entrypoint] 全新库 → 清 dev 演示数据(可用 FINANCE_KEEP_DEMO=1 保留)..."
  DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_USER="$DB_USER" DB_PASS="$DB_PASS" DB_NAME="$DB_NAME" \
    bash /app/clean-dev-data.sh || echo "[entrypoint] 清演示数据失败(非致命),继续启动"
else
  echo "[entrypoint] 非全新库(已有 schema_history)→ 保留全部数据,不清理"
fi

echo "[entrypoint] 启动应用 ..."
# shellcheck disable=SC2086
exec java $JAVA_OPTS -Dfile.encoding=UTF-8 -Duser.timezone="${TZ:-Asia/Shanghai}" -jar /app/app.jar
