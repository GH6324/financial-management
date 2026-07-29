#!/usr/bin/env bash
# 家庭账房 · v0.7 容器入口:等 MySQL → 跑版本化迁移(db/apply.sh)→ 启动应用
# 与 systemd 路径共用同一套 db/migration/V*.sql + schema_history,幂等,从 systemd 迁来的库不重放。
set -euo pipefail

: "${DB_HOST:=db}"
: "${DB_PORT:=3306}"
: "${DB_USER:=finance}"
: "${DB_NAME:=finance}"
: "${DB_PASS:?DB_PASS 未设置}"

# 等 MySQL。必须分清两件事:「服务器还没起来」和「起来了但密码不对」——
# `mysqladmin ping` 在 Access denied 时**也返回 0**(MySQL 文档:服务器有应答就算活着),
# 拿它当就绪判据会把认证失败伪装成「MySQL 就绪」,直到 apply.sh 那步才炸,日志极度误导(v1.6.22 实测)。
# 所以改用真实查询,并按 1045 单独归因、快速失败,不再白等 120s。
echo "[entrypoint] 等待 MySQL ${DB_HOST}:${DB_PORT} ..."
DB_READY=no; AUTH_ERR=""
for i in $(seq 1 60); do
  if PROBE="$(MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -sN -e 'SELECT 1' 2>&1)"; then
    echo "[entrypoint] MySQL 就绪(${DB_USER} 账号已验证)"; DB_READY=yes; break
  fi
  case "$PROBE" in
    *1045*|*"Access denied"*) AUTH_ERR="$PROBE"; break ;;   # 密码不对,再等也不会变
  esac
  if [ "$i" -eq 60 ]; then echo "[entrypoint] 等待 MySQL 超时(120s):$PROBE"; fi
  sleep 2
done

if [ -n "$AUTH_ERR" ]; then
  echo "[entrypoint] ✗ MySQL 拒绝了 ${DB_USER} 的密码:$AUTH_ERR"
  echo "[entrypoint]   最常见原因:数据卷是旧的、.env 里的密码是新的。"
  echo "[entrypoint]   MySQL 只在**第一次初始化数据卷**时写入账号密码;之后换 .env 不会同步进去,"
  echo "[entrypoint]   而命名卷不会随仓库目录一起消失(重新下载仓库 → 新随机密码 → 就会撞上这个)。"
  echo "[entrypoint]   修法:在宿主机仓库目录跑  bash deploy/docker-up.sh"
  echo "[entrypoint]   它会检测到这种情况,并在**不删数据**的前提下把新密码同步进已有数据库。"
  sleep 10   # 放慢 restart:unless-stopped 的重启循环,别把日志刷成瀑布
  exit 1
fi
[ "$DB_READY" = yes ] || { echo "[entrypoint] MySQL 迟迟连不上,放弃"; sleep 10; exit 1; }

# 迁移前判定:这是不是「全新空卷」—— schema_history 表不存在 = 从未迁移过的全新库。
# 迁移来的库(migrate-to-docker 灌的 dump 自带 schema_history)/ 升级的库 / 老用户库 都已有该表 → 非全新。
# 这是「是否清演示数据」的铁信号:只有全新库才清,且自限(清完 schema_history 已在,重启不再清)。
# 注意:这里**不能**用 `|| echo 1` 兜底 —— 那会把「查询失败」和「表存在」压成同一个结果,
# 于是连不上库时照样打印「表存在数=1」,把真实故障(认证失败)完全盖住(v1.6.22 实测就是这么误导的)。
# 查询失败要如实说「判不了」,并按「非全新」处理(fail-safe 方向:宁可不清数据)。
FRESH_DB=no
if HAS_HISTORY="$(MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
  -sN -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='schema_history'" 2>&1)"; then
  [[ "$HAS_HISTORY" == "0" ]] && FRESH_DB=yes
  echo "[entrypoint] 全新空库判定:FRESH_DB=$FRESH_DB(schema_history 表存在数=${HAS_HISTORY})"
else
  echo "[entrypoint] 全新空库判不了(查询失败:${HAS_HISTORY})→ 按「非全新」处理,绝不动任何数据"
  HAS_HISTORY=""
fi

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
