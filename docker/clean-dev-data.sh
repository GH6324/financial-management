#!/usr/bin/env bash
# 家庭账房 · v0.7.5 · 清 dev 演示数据(让全新 Docker 装好后是「空态」,触发 onboarding 引导,与 systemd deploy.sh step10 一致)
#
# 安全前提:本脚本只由 entrypoint 在「确属全新空库」时调用(迁移前 schema_history 表不存在 = 从未迁移过的全新卷)。
#           迁移来的库 / 升级的库 / 老用户库一定已有 schema_history → entrypoint 根本不会调用本脚本。
# 防线(即使被误调用也尽量不误删):
#   ① FINANCE_KEEP_DEMO=1            → 保留演示数据,跳过(想看填充效果的人用)
#   ② 真实数据互锁(与 step10 同口径):audit_log actor 行 > 50 或 member.id > 2 → 判为有真实数据,跳过不清
#   ③ 只 TRUNCATE 演示性数据表,保留 family / member / 模板 / 运行配置(account_template / family_runtime_config 等)
set -euo pipefail

: "${DB_HOST:=db}"; : "${DB_PORT:=3306}"; : "${DB_USER:=finance}"; : "${DB_NAME:=finance}"
: "${DB_PASS:?DB_PASS 未设置}"
export MYSQL_PWD="$DB_PASS"
q(){ mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" "$@"; }

if [[ "${FINANCE_KEEP_DEMO:-}" == "1" ]]; then
  echo "[clean] FINANCE_KEEP_DEMO=1 → 保留演示数据,跳过清理"
  exit 0
fi

# 真实数据互锁(防止任何情况下误删):有真实操作审计 或 额外成员 → 不清
AUDIT=$(q -sN -e "SELECT COUNT(*) FROM audit_log WHERE actor_member_id IS NOT NULL" 2>/dev/null || echo 0)
EXTRA=$(q -sN -e "SELECT COUNT(*) FROM member WHERE id > 2" 2>/dev/null || echo 0)
if [[ "${AUDIT:-0}" -gt 50 || "${EXTRA:-0}" -gt 0 ]]; then
  echo "[clean] 检测到真实数据(audit=${AUDIT} 额外成员=${EXTRA})→ 判为非全新库,不清理(数据原样保留)"
  exit 0
fi

echo "[clean] 全新库 → 清 dev 演示数据(保留 family/member/模板/运行配置),与 systemd step10 一致"
q <<'SQL'
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE cash_flow; TRUNCATE TABLE transfer; TRUNCATE TABLE period_snapshot;
TRUNCATE TABLE snapshot_todo; TRUNCATE TABLE period_member_completion;
TRUNCATE TABLE fx_rate; TRUNCATE TABLE audit_log; TRUNCATE TABLE backup_log;
TRUNCATE TABLE metrics_recompute_log; TRUNCATE TABLE period_reopen_log;
TRUNCATE TABLE period; TRUNCATE TABLE account;
SET FOREIGN_KEY_CHECKS=1;
SQL
echo "[clean] 完成 · 全家空态,登录后走 onboarding 引导从零开始"
