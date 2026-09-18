#!/usr/bin/env bash
# =============================================================================
# e2e-v123.sh · v1.23 双活跃账期端到端验收(issue #20)
#
# 为什么要单独一条:本版的核心状态(上期与新期同时 OPEN)**静态 grep 验不了**。
# 双 OPEN 下 findCurrentOpen 静默返回最新期,不报错、不进日志 —— 只有真的造出
# 那个状态、走真实 HTTP 路径、再回 DB 查真值,才能知道每个指标锚对了没有。
#
# 造状态的办法:reopen 已关账的上一期。它和当期就同时 OPEN,而上期已自然结束 ——
# 这与「配了 T+2、9/1~9/2 期间」的状态**完全同构**,且不用篡改系统日期。
#
# 用法:bash scripts/e2e-v123.sh
# 前置:beta 跑着 v1.23.0
# =============================================================================
set -uo pipefail

# ⚠ 本脚本刻意**不用** `echo "$VAR" | grep -q` 做判据。
#   `grep -q` 一命中就退出,上游 echo 收到 SIGPIPE 返回 141,而 `set -o pipefail`
#   取管道里最后一个非零码 —— 于是**内容明明在,判据却红**。
#   同一个变量,`grep -c` 过、`grep -q` 挂,现象矛盾得让人先去怀疑产品。
#   一律用 bash 内建的 `[ "$(echo "$VAR" | grep -c 'pat')" -gt 0 ]`(不走管道),正则场景用 `grep -cE` 计数。

BASE="${E2E_BASE:-http://127.0.0.1:20000}"
USER_="${E2E_USER:-diwa}"
PASS_="${E2E_PASS:-demo1234}"
CK=$(mktemp)
DB="mysql -h127.0.0.1 -P3306 -ufinance -pfinance finance -sN -e"

PASS=0; FAIL=0; FAILED=()
ok(){   echo -e "\033[32m PASS \033[0m $1"; PASS=$((PASS+1)); }
bad(){  echo -e "\033[31m FAIL \033[0m $1  ::  ${2:-}"; FAIL=$((FAIL+1)); FAILED+=("$1"); }
sec(){  echo; echo -e "\033[1;36m── $1 ──\033[0m"; }

xsrf(){ grep XSRF-TOKEN "$CK" | awk '{print $7}' | tail -1; }
login(){
  : > "$CK"
  curl -s -c "$CK" "$BASE/login" -o /dev/null
  curl -s -b "$CK" -c "$CK" -X POST "$BASE/login" -H "X-XSRF-TOKEN: $(xsrf)" \
       --data-urlencode "username=$USER_" --data-urlencode "password=$PASS_" -o /dev/null
  curl -s -b "$CK" -c "$CK" "$BASE/dashboard" -o /dev/null
}
GET(){  curl -s -b "$CK" -c "$CK" "$BASE$1"; }
POST(){ local p="$1"; shift; curl -s -b "$CK" -c "$CK" -X POST "$BASE$p" -H "X-XSRF-TOKEN: $(xsrf)" "$@"; }
q(){ $DB "$1" 2>/dev/null | tr -d '\r'; }

login
curl -s -o /dev/null -w "%{http_code}" "$BASE/health" | grep -q 200 || { echo "beta 不健康"; exit 1; }

# ─────────────────────────────────────────────────────────────────────────────
sec "0 · 前置:找出进行期与上一期"

CUR_ID=$(q "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1")
CUR_START=$(q "SELECT period_start FROM period WHERE id=$CUR_ID")
# 必须是**已自然结束**的期。beta 的账期表预建到了 2041,按 period_start DESC 取会拿到未来期 ——
# 那不是补录期(period_end 还没到),双活跃的语义完全不成立。
PREV_ID=$(q "SELECT id FROM period WHERE family_id=1 AND period_end < CURDATE() ORDER BY period_start DESC LIMIT 1")
PREV_START=$(q "SELECT period_start FROM period WHERE id=$PREV_ID")
PREV_STATUS_BEFORE=$(q "SELECT status FROM period WHERE id=$PREV_ID")
echo "进行期=$CUR_ID($CUR_START) · 上一期=$PREV_ID($PREV_START · $PREV_STATUS_BEFORE)"
[ -n "$CUR_ID" ] && [ -n "$PREV_ID" ] || { echo "数据不足,跳过"; exit 1; }

# 基线:单 OPEN 时的报表锚期
ANCHOR_SINGLE=$(GET "/reports" | grep -oP '数据截至 <span style="color:var\(--rust\)">\K[^<]+' | head -1)
[ -z "$ANCHOR_SINGLE" ] && ANCHOR_SINGLE=$(GET "/reports" | grep -oP '(?<=仍在填报中)' | head -1)
echo "单 OPEN 时报表锚:${ANCHOR_SINGLE:-(未解析)}"

# ─────────────────────────────────────────────────────────────────────────────
sec "1 · 造双活跃窗口(reopen 上一期)"

POST "/admin/periods/$PREV_ID/reopen" --data-urlencode "reason=v1.23 e2e 验收:造双活跃窗口" -o /dev/null
# PeriodService.reopen 会 deleteByPeriod(completion) —— 那是「重开去改数据」的正确语义。
# 但真实的**补录期**从没被 reopen 过,它的 completion 还在。这里补回来,让状态与真实场景同构;
# 否则测的就不是「宽限期内的补录期」,而是「被重开的历史期」—— 后者收益类本来就该退,不是 bug。
for MID in $(q "SELECT id FROM member WHERE family_id=1 AND archived_at IS NULL"); do
  q "INSERT IGNORE INTO period_member_completion(period_id, member_id) VALUES ($PREV_ID, $MID)" >/dev/null
done
OPEN_CNT=$(q "SELECT COUNT(*) FROM period WHERE family_id=1 AND status='OPEN'")
[ "$OPEN_CNT" = "2" ] && ok "双活跃窗口已建立(2 期 OPEN)" \
                      || bad "没能造出双 OPEN" "当前 OPEN 数=$OPEN_CNT"

# ─────────────────────────────────────────────────────────────────────────────
sec "2 · 填报页:默认落补录期 + 两期并排(FR-625)"

ENTRY=$(GET "/entry")
[ "$(echo "$ENTRY" | grep -c 'entry-perpill')" -gt 0 ] \
  && ok "填报页渲染了双期切换 pill" || bad "填报页没有双期 pill" "dualActive 没生效"
[ "$(echo "$ENTRY" | grep -c 'entry-perpill-on')" -gt 0 ] \
  && ok "当前期高亮(entry-perpill-on)" || bad "两期没有视觉区分" "用户会填错期"
# 默认选中的期 = 补录期
SEL=$(echo "$ENTRY" | grep -oP '<option value="\K[0-9]+(?=" selected)' | head -1)
[ "$SEL" = "$PREV_ID" ] \
  && ok "默认落在补录期(period=$SEL)" \
  || bad "默认没落补录期" "期望 $PREV_ID 实得 ${SEL:-空}"
[ "$(echo "$ENTRY" | grep -cE 'backfill|entry-perpill-off')" -gt 0 ] \
  && ok "另一期可切换(entry-perpill-off)" || bad "另一期没渲染" ""

# ─────────────────────────────────────────────────────────────────────────────
sec "3 · 报表不倒退(FR-627)· 本版最反直觉的退化"

REPORTS=$(GET "/reports")
# 补录期在 beta 上是「填过的已关账期」→ reopen 后 todo 仍是 DONE、completion 仍在
#   → 判定为「已自然结束且填报完成」= 已定稿 → 报表应当仍锚它,而不是退回上上期
ANCHOR_NOW=$(q "SELECT p.period_start FROM period p WHERE p.id = (
  SELECT p2.id FROM period p2 WHERE p2.family_id=1 AND p2.period_start <= CURDATE()
   AND (p2.status='CLOSED' OR (p2.period_end < CURDATE()
        AND NOT EXISTS (SELECT 1 FROM snapshot_todo t WHERE t.period_id=p2.id AND t.status='PENDING')
        AND (SELECT COUNT(*) FROM period_member_completion c WHERE c.period_id=p2.id)
            >= (SELECT COUNT(*) FROM member m WHERE m.family_id=p2.family_id AND m.archived_at IS NULL)))
   ORDER BY p2.period_start DESC LIMIT 1)")
[ "$ANCHOR_NOW" = "$PREV_START" ] \
  && ok "已定稿判据仍认补录期(锚 $ANCHOR_NOW · 没退回上上期)" \
  || bad "报表锚倒退了" "期望 $PREV_START 实得 $ANCHOR_NOW"
[ "$(echo "$REPORTS" | grep -c '仍在填报中')" -gt 0 ] \
  && ok "报表如实标注「仍在填报中 · 数字可能还会变」" \
  || bad "报表没标注宽限中状态" "既不能倒退,也不能假装已定稿"
[[ "$REPORTS" == *"</html>"* ]] && ok "报表页完整渲染(收尾标签在 · 非 chunked 截断)" || bad "报表页渲染异常" "curl 200 不等于渲染成功"

# ─────────────────────────────────────────────────────────────────────────────
sec "4 · 余额轴:估值只写进行期,绝不回写补录期(FR-622)"

PREV_BAL_BEFORE=$(q "SELECT COALESCE(SUM(end_balance),0) FROM period_snapshot WHERE period_id=$PREV_ID")
POST "/entry/refresh-stocks" -o /dev/null
sleep 2
PREV_BAL_AFTER=$(q "SELECT COALESCE(SUM(end_balance),0) FROM period_snapshot WHERE period_id=$PREV_ID")
[ "$PREV_BAL_BEFORE" = "$PREV_BAL_AFTER" ] \
  && ok "估值刷新后补录期余额逐分未动(余额轴只有一个「现在」)" \
  || bad "估值回写了补录期" "刷新前 $PREV_BAL_BEFORE → 后 $PREV_BAL_AFTER"

# ─────────────────────────────────────────────────────────────────────────────
sec "5 · 月均支出剔除全部 OPEN 期(FR-628)"

# recentClosed 的效果通过 dashboard 的紧急储备(流动资产 ÷ 月均支出)间接可见;
# 这里直接验判据:窗口里不许出现任何 status='OPEN' 的期
OPEN_IN_AVG=$(q "SELECT COUNT(*) FROM period WHERE family_id=1 AND status='OPEN'")
DASH=$(GET "/dashboard")
[[ "$DASH" == *"</html>"* ]] && ok "dashboard 完整渲染" || bad "dashboard 渲染异常" "curl 200 不等于渲染成功"
[ "$OPEN_IN_AVG" = "2" ] && ok "确认此刻确实有 2 期 OPEN(下面的断言才有意义)" || bad "OPEN 数不对" "$OPEN_IN_AVG"

# ─────────────────────────────────────────────────────────────────────────────
sec "6 · 批量导入默认选补录期(FR-626)· issue #20 的正中心"

IMPORT=$(GET "/expense/import")
IMP_PID=$(echo "$IMPORT" | grep -oP 'name="periodId" th:value|name="periodId" value="\K[0-9]+' | head -1)
[ -z "$IMP_PID" ] && IMP_PID=$(echo "$IMPORT" | grep -oP '<input type="hidden" name="periodId" value="\K[0-9]+' | head -1)
[ "$IMP_PID" = "$PREV_ID" ] \
  && ok "导入页默认记到补录期(periodId=$IMP_PID)" \
  || bad "导入默认仍落最新期" "期望 $PREV_ID 实得 ${IMP_PID:-空} —— 9/1 导 8 月账单会整批落错期"
[ "$(echo "$IMPORT" | grep -c 'periodId=')" -gt 0 ] \
  && ok "导入页给出选期入口" || bad "导入页没有选期入口" ""

# ─────────────────────────────────────────────────────────────────────────────
sec "7 · 余额传导 + 守门(FR-623 / FR-624)"

# 找一个补录期里 source_tag=CARRIED_FORWARD 的快照(系统代填、没人确认过)
CF_ACCT=$(q "SELECT account_id FROM period_snapshot WHERE period_id=$CUR_ID AND source_tag='CARRIED_FORWARD' LIMIT 1")
if [ -n "$CF_ACCT" ]; then
  CUR_BAL_BEFORE=$(q "SELECT end_balance FROM period_snapshot WHERE period_id=$CUR_ID AND account_id=$CF_ACCT")
  # 往补录期记一笔影响余额的支出
  POST "/entry/expense" --data-urlencode "periodId=$PREV_ID" --data-urlencode "accountId=$CF_ACCT" \
       --data-urlencode "categoryCode=consumption" --data-urlencode "amount=11.11" \
       --data-urlencode "note=v123-e2e-传导" --data-urlencode "affectsBalance=true" -o /dev/null
  sleep 1
  CUR_BAL_AFTER=$(q "SELECT end_balance FROM period_snapshot WHERE period_id=$CUR_ID AND account_id=$CF_ACCT")
  [ "$CUR_BAL_BEFORE" != "$CUR_BAL_AFTER" ] \
    && ok "CARRIED_FORWARD 快照跟着变了($CUR_BAL_BEFORE → $CUR_BAL_AFTER)" \
    || bad "传导没生效" "补录期余额变了,进行期延续值没跟上"
  NOTE=$(q "SELECT note FROM period_snapshot WHERE period_id=$CUR_ID AND account_id=$CF_ACCT")
  [ "$(echo "$NOTE" | grep -c '补录')" -gt 0 ] \
    && ok "传导可见(note 写明原因)" || bad "传导没留痕" "数字自己变了而不说一声"
else
  echo "  (跳过:进行期里没有 CARRIED_FORWARD 快照)"
fi

# 反向:手填过的不许被覆盖
MAN_ACCT=$(q "SELECT account_id FROM period_snapshot WHERE period_id=$CUR_ID AND source_tag='MANUAL' LIMIT 1")
if [ -n "$MAN_ACCT" ]; then
  MAN_BEFORE=$(q "SELECT end_balance FROM period_snapshot WHERE period_id=$CUR_ID AND account_id=$MAN_ACCT")
  POST "/entry/expense" --data-urlencode "periodId=$PREV_ID" --data-urlencode "accountId=$MAN_ACCT" \
       --data-urlencode "categoryCode=consumption" --data-urlencode "amount=22.22" \
       --data-urlencode "note=v123-e2e-守门" --data-urlencode "affectsBalance=true" -o /dev/null
  sleep 1
  MAN_AFTER=$(q "SELECT end_balance FROM period_snapshot WHERE period_id=$CUR_ID AND account_id=$MAN_ACCT")
  [ "$MAN_BEFORE" = "$MAN_AFTER" ] \
    && ok "用户手填过的余额一分不动(守门生效)" \
    || bad "覆盖了用户手填的余额" "$MAN_BEFORE → $MAN_AFTER · 本版最不能犯的错"
else
  echo "  (跳过:进行期里没有 MANUAL 快照)"
fi

# ─────────────────────────────────────────────────────────────────────────────
sec "8 · 管理页配置项(FR-610 / FR-616)"

ADMINP=$(GET "/admin/periods")
[ "$(echo "$ADMINP" | grep -c 'close-rhythm')" -gt 0 ] && ok "管理页有关账节奏配置表单" || bad "配置项没渲染" ""
[ "$(echo "$ADMINP" | grep -c 'name="rhythm"')" -eq 4 ] && ok "四个档位都在(尺寸样式同一个 class)" || bad "档位数不对" "期望 4 个 radio"
[ "$(echo "$ADMINP" | grep -c 'rhythm-opt')" -gt 0 ] && ok "档位用统一 class(并列元素同尺寸)" || bad "档位样式没统一" ""

POST "/admin/family/close-rhythm" --data-urlencode "rhythm=T2" -o /dev/null
DELAY=$(q "SELECT close_delay_days FROM family WHERE id=1")
AUTO=$(q "SELECT auto_close_enabled FROM family WHERE id=1")
[ "$DELAY" = "2" ] && [ "$AUTO" = "1" ] \
  && ok "配置写入生效(T+2 · 自动关账开)" || bad "配置没写进去" "delay=$DELAY auto=$AUTO"

POST "/admin/family/close-rhythm" --data-urlencode "rhythm=MANUAL" -o /dev/null
AUTO2=$(q "SELECT auto_close_enabled FROM family WHERE id=1")
[ "$AUTO2" = "0" ] && ok "手动模式可切换" || bad "手动模式没生效" "auto=$AUTO2"

# ─────────────────────────────────────────────────────────────────────────────
sec "9 · 主线未被破坏(六个核心页面 200)"

for p in /dashboard /entry /reports /accounts /admin/periods /checkup; do
  c=$(curl -s -b "$CK" -o /dev/null -w "%{http_code}" "$BASE$p")
  [ "$c" = "200" ] && ok "GET $p → 200" || bad "GET $p → $c" ""
done

# ─────────────────────────────────────────────────────────────────────────────
sec "X · 还原现场"

# 删掉本次造的两笔支出
q "UPDATE cash_flow SET deleted_at=NOW(3) WHERE period_id=$PREV_ID AND note LIKE 'v123-e2e-%' AND deleted_at IS NULL" >/dev/null
# 恢复关账节奏为默认 T+0
POST "/admin/family/close-rhythm" --data-urlencode "rhythm=T0" -o /dev/null
# 无条件还原到「只有一期 OPEN」—— beta 的基线状态。
#   不能写成 `if [ "$PREV_STATUS_BEFORE" = CLOSED ]`:连跑两次时第二次读到的就是 OPEN,
#   还原整段被跳过,于是 beta 被留在双活跃状态里 —— 下一个人跑别的 e2e 会莫名其妙。
#   **夹具的清理不该依赖「我跑之前是什么样」,而该声明「我跑完必须是什么样」。**
for PID in $(q "SELECT id FROM period WHERE family_id=1 AND status='OPEN' AND period_end < CURDATE()"); do
  POST "/admin/periods/$PID/force-close" -o /dev/null
done
FINAL_OPEN=$(q "SELECT COUNT(*) FROM period WHERE family_id=1 AND status='OPEN'")
[ "$FINAL_OPEN" = "1" ] && ok "现场已还原(单 OPEN · 节奏回 T+0)" \
                        || bad "现场没还原干净" "仍有 $FINAL_OPEN 期 OPEN"

echo
echo "═══════════════════════════════════════"
echo " v1.23 e2e:PASS=$PASS  FAIL=$FAIL"
echo "═══════════════════════════════════════"
if [ "$FAIL" -gt 0 ]; then printf '  · %s\n' "${FAILED[@]}"; exit 1; fi
exit 0
