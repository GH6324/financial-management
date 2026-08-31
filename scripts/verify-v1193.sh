#!/usr/bin/env bash
# v1.19.3 · 信用卡支出 · 走用户真实路径的端到端验证
#
# 不是 grep 源码,是:登录 → 打开填报页 → 看下拉里有没有这张卡 → 真的记一笔 → 查库看余额和流水。
# 会写数据,所以先快照后还原(同 scripts/e2e.sh 的做法)。
#
# 金额用固定测试值,不打印任何账户余额绝对值(只打印差值与符号判断)。
set -uo pipefail

BASE="${V_BASE:-http://127.0.0.1:20000}"
DBU=finance; DBP=finance; DBN=finance
USER_=diwa; PASS_=demo1234
CARD=5          # 招行信用卡 · LOAN
MORTGAGE=11     # 房贷-招行 · LOAN
AMT=123.45

CK="$(mktemp)"; DUMP="$(mktemp /tmp/v1193_baseline.XXXXXX.sql)"
PASS=0; FAIL=0; FAILED=()
ok(){   PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad(){  FAIL=$((FAIL+1)); FAILED+=("$1"); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }

restore(){
  mysql -u"$DBU" -p"$DBP" "$DBN" < "$DUMP" 2>/dev/null \
    && echo "▸ beta 数据已还原" || echo "✗ 还原失败!手动:mysql $DBN < $DUMP"
  rm -f "$CK"
}
trap restore EXIT

echo "▸ 快照 beta 基线"
mysqldump --single-transaction --skip-lock-tables -u"$DBU" -p"$DBP" "$DBN" > "$DUMP" 2>/dev/null \
  || { echo "✗ 快照失败,中止"; exit 1; }

xsrf(){ grep XSRF-TOKEN "$CK" | awk '{print $7}' | tail -1; }
: > "$CK"
curl -s -c "$CK" "$BASE/login" -o /dev/null
curl -s -b "$CK" -c "$CK" -X POST "$BASE/login" -H "X-XSRF-TOKEN: $(xsrf)" \
     --data-urlencode "username=$USER_" --data-urlencode "password=$PASS_" -o /dev/null
curl -s -b "$CK" -c "$CK" "$BASE/dashboard" -o /dev/null
GET(){ curl -s -b "$CK" -c "$CK" "$BASE$1"; }
POSTcode(){ local p="$1"; shift; curl -s -o /dev/null -w '%{http_code}' -b "$CK" -c "$CK" \
            -X POST "$BASE$p" -H "X-XSRF-TOKEN: $(xsrf)" "$@"; }

PERIOD=$(mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e \
  "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1;" 2>/dev/null)
echo "▸ 当前 OPEN 账期 id=$PERIOD"
# 必须按 period_id 精确取:beta 预建了账期到 id=351(2038 年那些空期),
# 而 OPEN 期是 162 —— ORDER BY period_id DESC 会取到一条压根没动过的未来期快照,
# 于是 delta 恒为 0,看着像「余额没变」的代码 bug,其实是查询查错了期。
bal(){ mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e \
  "SELECT COALESCE((SELECT end_balance FROM period_snapshot WHERE account_id=$1 AND period_id=$PERIOD),
                   (SELECT end_balance FROM period_snapshot WHERE account_id=$1 AND period_id<$PERIOD ORDER BY period_id DESC LIMIT 1),
                   0);" 2>/dev/null; }
echo

echo "═══ 1 · 填报页:信用卡出现在支出账户下拉里 ═══"
GET "/entry" > /tmp/v1193_entry.html
python3 - <<'PY'
import re,sys
h=open('/tmp/v1193_entry.html',encoding='utf-8').read()
m=re.search(r'<select[^>]*data-expense-acct.*?</select>', h, re.S)
if not m:
    print("SELECT_MISSING"); sys.exit()
seg=m.group(0)
print("HAS_CARD"        if '招行信用卡' in seg else "NO_CARD")
print("HAS_LIABILITY"   if 'data-liability="true"' in seg else "NO_LIABILITY_ATTR")
print("HAS_CASH"        if 'CASH' in seg or '现金' in seg else "NO_CASH")
c=re.search(r'<select[^>]*data-expense-cat.*?</select>', h, re.S)
print("HAS_REPAYMENT_ATTR" if c and 'data-repayment="true"' in c.group(0) else "NO_REPAYMENT_ATTR")
print("HAS_JS"   if 'expense-liability.js' in h else "NO_JS")
print("HAS_HINT" if 'data-expense-liability-hint' in h else "NO_HINT")
PY
R=$(python3 - <<'PY'
import re
h=open('/tmp/v1193_entry.html',encoding='utf-8').read()
m=re.search(r'<select[^>]*data-expense-acct.*?</select>', h, re.S)
seg=m.group(0) if m else ''
c=re.search(r'<select[^>]*data-expense-cat.*?</select>', h, re.S)
cs=c.group(0) if c else ''
print(int(bool(m)), int('招行信用卡' in seg), int('data-liability="true"' in seg),
      int('data-repayment="true"' in cs), int('expense-liability.js' in h),
      int('data-expense-liability-hint' in h))
PY
)
read -r r1 r2 r3 r4 r5 r6 <<< "$R"
[[ "$r1" == 1 ]] && ok "支出账户 select 存在"                || bad "支出账户 select 不存在"
[[ "$r2" == 1 ]] && ok "「招行信用卡」在支出账户候选里(核心诉求)" || bad "信用卡仍然不在支出账户候选里"
[[ "$r3" == 1 ]] && ok "负债账户带 data-liability 标记"       || bad "缺 data-liability 标记,前端无从判断"
[[ "$r4" == 1 ]] && ok "还贷/利息类目带 data-repayment 标记"   || bad "缺 data-repayment 标记"
[[ "$r5" == 1 ]] && ok "expense-liability.js 已引入"          || bad "expense-liability.js 未引入"
[[ "$r6" == 1 ]] && ok "负债提示文案已渲染(默认 hidden)"      || bad "缺提示文案"

echo
echo "═══ 2 · 真的记一笔:信用卡消费 → 欠得更多 ═══"
B0=$(bal $CARD)
CODE=$(POSTcode "/entry/expense" \
  --data-urlencode "periodId=$PERIOD" --data-urlencode "accountId=$CARD" \
  --data-urlencode "categoryCode=consumption" --data-urlencode "amount=$AMT" \
  --data-urlencode "note=v1193 验证 · 刷卡")
B1=$(bal $CARD)
DELTA=$(python3 -c "print(round(float('$B1')-float('$B0'),2))")
echo "  HTTP=$CODE · 余额变化 delta=$DELTA(期望 -$AMT)"
[[ "$CODE" =~ ^(200|302)$ ]] && ok "信用卡消费录入成功(HTTP $CODE)" || bad "信用卡消费被拒(HTTP $CODE)"
[[ "$DELTA" == "-$AMT" ]] && ok "余额方向正确:欠款增加 $AMT(负债存负数,delta 为负)" \
                          || bad "余额方向错误 delta=$DELTA,应为 -$AMT"

FLOW=$(mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e \
  "SELECT COUNT(*) FROM cash_flow WHERE account_id=$CARD AND kind='EXPENSE' AND category_code='consumption' AND amount=$AMT;" 2>/dev/null)
[[ "$FLOW" -ge 1 ]] && ok "cash_flow 落了一条 EXPENSE/consumption(会进本月支出与支出构成)" \
                    || bad "cash_flow 没落账,支出统计里看不见这笔"

echo
echo "═══ 3 · 双计防护:信用卡上不许记「还贷 / 利息支出」 ═══"
for CAT in loan_payment interest_paid; do
  C=$(POSTcode "/entry/expense" \
    --data-urlencode "periodId=$PERIOD" --data-urlencode "accountId=$CARD" \
    --data-urlencode "categoryCode=$CAT" --data-urlencode "amount=$AMT" \
    --data-urlencode "note=v1193 应被拒")
  N=$(mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e \
    "SELECT COUNT(*) FROM cash_flow WHERE account_id=$CARD AND category_code='$CAT';" 2>/dev/null)
  [[ "$N" == 0 ]] && ok "信用卡 + $CAT 被拒,没有落库(HTTP $C)" \
                  || bad "信用卡 + $CAT 竟然落库了 $N 条 —— 支出会双计"
  # v1.19.3 · 被拒时必须回填报页说原因,不能是 500 白页。放开账户候选之后
  # 「信用卡 + 还贷」变成用户点得到的组合(JS 还没加载完时),500 是不可接受的落地。
  [[ "$C" == 302 ]] && ok "  └ 被拒时回到填报页(HTTP 302),不是 500 白页" \
                    || bad "  └ 被拒时返回 HTTP $C,用户看到的是错误页而不是原因"
  # 光有 302 不够 —— 用户得真的看见原因。带 period 取(Spring FlashMap 按 redirect 的
  # 查询参数匹配,不带就拿不到,而浏览器跟随 302 时是带着的)。
  PG="$(GET "/entry?period=$PERIOD")"
  case "$PG" in
    *data-entry-flash-error*) ok "  └ 填报页上显示了拒绝原因(用户看得见)";;
    *) bad "  └ 填报页上没有任何提示,用户只会以为点了没反应";;
  esac
done

echo
echo "═══ 4 · 不误伤:现金账户记「还贷」仍然正常 ═══"
CASH=$(mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e \
  "SELECT id FROM account WHERE family_id=1 AND archived_at IS NULL AND type='CASH' LIMIT 1;" 2>/dev/null)
C=$(POSTcode "/entry/expense" \
  --data-urlencode "periodId=$PERIOD" --data-urlencode "accountId=$CASH" \
  --data-urlencode "categoryCode=loan_payment" --data-urlencode "amount=$AMT" \
  --data-urlencode "note=v1193 还贷正常路径")
N=$(mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e \
  "SELECT COUNT(*) FROM cash_flow WHERE account_id=$CASH AND category_code='loan_payment' AND amount=$AMT;" 2>/dev/null)
[[ "$N" -ge 1 ]] && ok "现金账户 + 还贷 正常落库(本来正确的记法没被误伤)" \
                 || bad "现金账户记还贷被误伤了(HTTP $C)"

echo
echo "═══ 5 · 房贷账户也能记消费(A 方案已知代价,确认行为一致) ═══"
C=$(POSTcode "/entry/expense" \
  --data-urlencode "periodId=$PERIOD" --data-urlencode "accountId=$MORTGAGE" \
  --data-urlencode "categoryCode=consumption" --data-urlencode "amount=$AMT" \
  --data-urlencode "note=v1193 房贷消费")
N=$(mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e \
  "SELECT COUNT(*) FROM cash_flow WHERE account_id=$MORTGAGE AND category_code='consumption';" 2>/dev/null)
[[ "$N" -ge 1 ]] && ok "房贷账户可记消费(A 方案的已知代价,与 FR-437「明确不做」一致)" \
                 || bad "房贷账户记消费失败,与 A 方案预期不符(HTTP $C)"

echo
echo "═══════════════════════════════════════"
echo " 总结: PASS=$PASS  FAIL=$FAIL"
echo "═══════════════════════════════════════"
if [[ $FAIL -gt 0 ]]; then printf '  · %s\n' "${FAILED[@]}"; exit 1; fi
exit 0
