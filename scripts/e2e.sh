#!/usr/bin/env bash
# ============================================================================
# e2e.sh · 端到端主线验收(补充 qa-run 的广度冒烟,做纵深真验收)
#   形态:唤起 beta 应用(被测基线)→ 按序调用真实接口 → 用「接口响应 + DB 真实数据」判定功能对错。
#   隔离(策略 A):开跑前 mysqldump 快照基线,trap EXIT 无论成败都还原 + 重启 → 可重复、不污染 beta。
#   6 条主线:1 记账闭环 · 2 账期滚动 · 3 报表成图 · 4 多币种镜头 · 5 收益指标 · 6 LOAN 还款归零。
#   只读主线在前(干净基线),改数据主线殿后。
# ============================================================================
set -u   # 不用 pipefail:curl|grep -q / |head 会提前关管道让 curl 收 SIGPIPE,pipefail 会把这类正常管道误判为失败

BASE="${E2E_BASE:-http://127.0.0.1:20000}"
DBU="${E2E_DBU:-finance}"; DBP="${E2E_DBP:-finance}"; DBN="${E2E_DBN:-finance}"
FAM="${E2E_FAMILY:-1}"
USER_="${E2E_USER:-diwa}"; PASS_="${E2E_PASS:-demo1234}"
CK="$(mktemp)"; DUMP="$(mktemp /tmp/e2e_baseline.XXXXXX.sql)"
PASS=0; FAIL=0; declare -a FAILED=()

db(){ mysql -u"$DBU" -p"$DBP" "$DBN" -sN -e "$1" 2>/dev/null; }
ok(){  echo -e " \033[32mPASS\033[0m $1"; PASS=$((PASS+1)); }
bad(){ echo -e " \033[31mFAIL\033[0m $1 :: ${2:-}"; FAIL=$((FAIL+1)); FAILED+=("$1 :: ${2:-}"); }
eq(){  [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1" "expect=$3 got=$2"; }
ge(){  [ "$(printf '%s\n' "$2" "$3" | sort -g | tail -1)" = "$2" ] && [ "$2" != "$3" ] || [ "$2" = "$3" ] ; }  # $2>=$3
section(){ echo; echo -e "\033[1;36m════ $1 ════\033[0m"; }

restore(){
  echo; echo "▸ 还原 beta 基线(策略 A)..."
  if mysql -u"$DBU" -p"$DBP" "$DBN" < "$DUMP" 2>/dev/null; then
    sudo -n /bin/systemctl restart finance 2>/dev/null && sleep 8
    echo "✓ 已还原 + 重启"
  else
    echo "✗ 还原失败!请手动:mysql $DBN < $DUMP"
  fi
  rm -f "$CK"
}
trap restore EXIT

# ── 快照基线 ──
echo "▸ 快照 beta 基线 → $DUMP"
# --single-transaction:InnoDB MVCC 一致性快照,不加表锁 → 不打断正在运行的 beta 连接池(否则其后登录会失败)
mysqldump --single-transaction --skip-lock-tables -u"$DBU" -p"$DBP" "$DBN" > "$DUMP" 2>/dev/null || { echo "✗ 快照失败,中止(不动 beta)"; exit 1; }
echo "✓ 基线已存($(wc -l < "$DUMP") 行 SQL)"

# ── 登录 + CSRF ──
xsrf(){ grep XSRF-TOKEN "$CK" | awk '{print $7}' | tail -1; }
login(){
  : > "$CK"
  curl -s -c "$CK" "$BASE/login" -o /dev/null                 # 初始 XSRF
  curl -s -b "$CK" -c "$CK" -X POST "$BASE/login" -H "X-XSRF-TOKEN: $(xsrf)" \
       --data-urlencode "username=$USER_" --data-urlencode "password=$PASS_" -o /dev/null   # 登录(Spring 会清掉 XSRF cookie)
  curl -s -b "$CK" -c "$CK" "$BASE/dashboard" -o /dev/null    # 登录后再 GET 一次,拿到新 XSRF cookie 供后续 POST 用
}
GET(){ curl -s -b "$CK" -c "$CK" "$BASE$1"; }                 # -c:GET 也刷新 XSRF cookie
POST(){ local p="$1"; shift; curl -s -b "$CK" -c "$CK" -X POST "$BASE$p" -H "X-XSRF-TOKEN: $(xsrf)" "$@"; }
POSTcode(){ local p="$1"; shift; curl -s -o /dev/null -w '%{http_code}' -b "$CK" -c "$CK" -X POST "$BASE$p" -H "X-XSRF-TOKEN: $(xsrf)" "$@"; }
# 从 reports 内联 data={} 里抽某数组的元素个数
arr_len(){  # data 对象里 `键: [..]` 的元素个数(数逗号+1,兼容日期串/数字数组)
  local a; a="$(printf '%s' "$1" | grep -oE "$2: \[[^]]*\]" | head -1)"; a="${a#*[}"; a="${a%]*}"
  [ -z "$a" ] && { echo 0; return; }
  echo $(( $(printf '%s' "$a" | tr -cd ',' | wc -c) + 1 ))
}

# 等 beta 就绪(自身 restore 会重启 beta,防下次跑撞上启动窗口)
echo -n "▸ 等 beta 就绪"
for i in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health" 2>/dev/null)" = "200" ] && { echo " · up"; break; }
  echo -n "."; sleep 2
done
login
GET /dashboard | grep -q '净资产' && echo "✓ 登录 $USER_ 成功(dashboard 可达)" || { echo "✗ 登录失败,中止"; exit 1; }

# ============================================================================
section "主线 3 · 报表成图(只读 · DB 对账)"
# 修 bug3:labels 用全期(=debt 长度);分解图数据 = labels-1;负债值 = DB 逐期 LOAN 汇总
H="$(GET /reports)"
L_labels="$(arr_len "$H" labels)"; L_debt="$(arr_len "$H" debt)"; L_dpnl="$(arr_len "$H" decompPnl)"
eq "报表-labels 与 debt 等长(负债曲线不少画一期)" "$L_labels" "$L_debt"
[ "$L_labels" -ge 1 ] && eq "报表-分解图点数=全期-1" "$L_dpnl" "$((L_labels-1))" || bad "报表-无 labels" "labels=$L_labels"
# 负债曲线最后一点 = 最近关账期 LOAN 绝对值汇总(DB 真值,本位币近似:base=CNY)
anchor_pid="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='CLOSED' AND period_start<=CURDATE() ORDER BY period_start DESC LIMIT 1")"
db_debt="$(db "SELECT ROUND(ABS(SUM(ps.end_balance))) FROM period_snapshot ps JOIN account a ON a.id=ps.account_id WHERE ps.period_id=$anchor_pid AND a.type='LOAN'")"
rep_debt_last="$(printf '%s' "$H" | grep -oE 'debt: \[[^]]*\]' | head -1 | grep -oE '[0-9]+(\.[0-9]+)?' | tail -1)"
rep_debt_last_r="$(printf '%.0f' "${rep_debt_last:-0}")"
eq "报表-负债曲线末点 ≈ DB 最近关账期 LOAN 汇总" "$rep_debt_last_r" "$db_debt"
# v0.11.4 · 第四表复用管理页指标配置:账户表出现 data-mcol 指标列(≥ 启用指标数);vs基准 用 pp 不用 %
n_mcol="$(printf '%s' "$H" | grep -oE 'data-mcol="[a-z_]+"' | sort -u | wc -l | tr -d ' ')"
[ "$n_mcol" -ge 3 ] && ok "报表-账户表出现配置化指标列($n_mcol 种 data-mcol)" || bad "报表-账户表缺指标列" ">=3" "$n_mcol"
n_pp="$(printf '%s' "$H" | grep -ocE '(跑赢|跑输|持平) [+-]?[0-9.]+pp' || true)"
n_pct_wrong="$(printf '%s' "$H" | grep -ocE '(跑赢|跑输|持平) [+-]?[0-9.]+%' || true)"
[ "$n_pp" -ge 1 ] && [ "$n_pct_wrong" -eq 0 ] && ok "报表-vs基准用 pp 不用 %(pp=$n_pp · 误%=$n_pct_wrong)" || bad "报表-vs基准单位错" "pp≥1且%=0" "pp=$n_pp %=$n_pct_wrong"
# vs基准量级理智:显示的 xirr − 基准,不应再出现 |pp|>1000 的爆值(修 v0.10.5 cumPnl/净投入)
n_blow="$(printf '%s' "$H" | grep -ocE '[+-]?[0-9]{4,}\.[0-9]+pp' || true)"
eq "报表-vs基准无爆值(|pp|>1000 计数)" "$n_blow" "0"

# ============================================================================
section "主线 4 · 多币种镜头(只读 · 比值币种无关 + 金额按 fx 缩放)"
# 紧急储备月数(比值)三币种必须完全相等;净资产(金额)USD 版 ≈ CNY 版 × fx
emg(){ GET "/dashboard?currency=$1" | grep -oE '[0-9]+(\.[0-9]+)? 月' | head -1; }
e_cny="$(emg CNY)"; e_usd="$(emg USD)"; e_hkd="$(emg HKD)"
eq "币种-紧急储备月数 CNY=USD" "$e_cny" "$e_usd"
eq "币种-紧急储备月数 CNY=HKD" "$e_cny" "$e_hkd"

# ============================================================================
section "主线 5 · 收益指标(只读 · 家庭 XIRR 币种无关)"
fxirr(){ GET "/reports?currency=$1" | grep -oE '家庭 XIRR[^%]*%' | head -1 | grep -oE '\-?[0-9]+\.[0-9]+%'; }
x_cny="$(fxirr CNY)"; x_usd="$(fxirr USD)"
[ -n "$x_cny" ] && eq "收益-家庭 XIRR CNY=USD(币种无关)" "$x_cny" "$x_usd" || bad "收益-未取到家庭 XIRR" "cny=$x_cny"

# ============================================================================
section "主线 1 · 记账闭环(改数据 · 录余额+收支+转账 → 精确算术 + 转账双边)"
# 精华取自旧 qa-e2e:录已知起点余额 → 加收入/减支出/转账 → 断言期末 = 起点 + Σ流水(DB 真值)
OPEN_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
ACC_A="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
ACC_B="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL AND id<>$ACC_A ORDER BY id LIMIT 1")"
[ -z "$ACC_B" ] && ACC_B="$(db "SELECT id FROM account WHERE family_id=$FAM AND archived_at IS NULL AND type<>'LOAN' AND id<>$ACC_A ORDER BY id LIMIT 1")"
echo "  开账期=$OPEN_PID · A=$ACC_A · B=$ACC_B"
cfB="$(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$OPEN_PID AND account_id=$ACC_A")"      # 基线已有(快照/还原模型:用增量断言)
trB="$(db "SELECT COUNT(*) FROM transfer WHERE period_id=$OPEN_PID AND from_account_id=$ACC_A")"
POSTcode "/entry/$ACC_A/balance"   --data-urlencode "periodId=$OPEN_PID" --data-urlencode "newBalance=10000" >/dev/null
POSTcode "/entry/$ACC_B/balance"   --data-urlencode "periodId=$OPEN_PID" --data-urlencode "newBalance=5000"  >/dev/null
POSTcode "/entry/$ACC_A/cash-flow" --data-urlencode "periodId=$OPEN_PID" --data-urlencode "kind=INCOME"  --data-urlencode "categoryCode=salary"      --data-urlencode "amount=3000" >/dev/null
POSTcode "/entry/$ACC_A/cash-flow" --data-urlencode "periodId=$OPEN_PID" --data-urlencode "kind=EXPENSE" --data-urlencode "categoryCode=consumption" --data-urlencode "amount=500"  >/dev/null
tcode="$(POSTcode "/entry/$ACC_A/transfer" --data-urlencode "periodId=$OPEN_PID" --data-urlencode "toAccountId=$ACC_B" --data-urlencode "amount=2000")"
eq "记账-转账 HTTP 200" "$tcode" "200"
eq "记账-A 精确算术 期末=起点+收入−支出−转出(10000+3000−500−2000)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$OPEN_PID AND account_id=$ACC_A")" "10500"
eq "记账-B 转账双边 期末=起点+转入(5000+2000)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$OPEN_PID AND account_id=$ACC_B")" "7000"
eq "记账-新增收支流水落库(+2 条)" "$(( $(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$OPEN_PID AND account_id=$ACC_A") - cfB ))" "2"
eq "记账-新增转账落库(+1 条)" "$(( $(db "SELECT COUNT(*) FROM transfer WHERE period_id=$OPEN_PID AND from_account_id=$ACC_A") - trB ))" "1"

# ============================================================================
section "主线 2+6 · 账期滚动 + LOAN 还款归零(改数据 · 开下一期→上期关+LOAN夹零)"
# 构造 bug2 场景:选一个 LOAN,把「新期开启前的最近两期」设成 prevPrev=-72000, prev=0(还平)
LOAN_ACCT="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='LOAN' AND archived_at IS NULL ORDER BY id LIMIT 1")"
P_JUN="$(db "SELECT id FROM period WHERE family_id=$FAM AND period_start='2026-06-01'")"   # prevPrev
P_JUL="$OPEN_PID"                                                                          # prev(当前开账期 2026-07)
echo "  LOAN 账户=$LOAN_ACCT · prevPrev(06)=$P_JUN · prev(07)=$P_JUL"
db "UPDATE period_snapshot SET end_balance=-72000 WHERE period_id=$P_JUN AND account_id=$LOAN_ACCT"
db "INSERT INTO period_snapshot (period_id,account_id,end_balance,submitted_by) VALUES ($P_JUL,$LOAN_ACCT,0,1)
    ON DUPLICATE KEY UPDATE end_balance=0"
prior_before="$(db "SELECT status FROM period WHERE id=$P_JUL")"
code="$(POSTcode /admin/periods/open-next)"
eq "滚动-open-next HTTP 2xx/3xx" "$([ "$code" -ge 200 ] && [ "$code" -lt 400 ] && echo ok || echo "$code")" "ok"
NEW_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND period_start='2026-08-01'")"
eq "滚动-新期 2026-08 已开(OPEN)" "$(db "SELECT status FROM period WHERE id=$NEW_PID")" "OPEN"
eq "滚动-上期 2026-07 已自动关(bug1)" "$(db "SELECT status FROM period WHERE id=$P_JUL")" "CLOSED"
loan_prefill="$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$NEW_PID AND account_id=$LOAN_ACCT")"
eq "LOAN-还平后新期预填夹零(非+72000)(bug2)" "$loan_prefill" "0"
loan_pos="$(db "SELECT COUNT(*) FROM period_snapshot ps JOIN account a ON a.id=ps.account_id WHERE ps.period_id=$NEW_PID AND a.type='LOAN' AND ps.end_balance>0")"
eq "LOAN-新期无任何贷款预填为正" "$loan_pos" "0"
# 精华取自旧 qa-e2e:非 LOAN 账户开新期自动延续上期末(A 在 07 期末=10500 → 08 预填=10500)
eq "滚动-非LOAN账户新期延续上期末(carry 10500)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$NEW_PID AND account_id=$ACC_A")" "10500"

# ============================================================================
echo
echo "════════════════════════════════════════"
echo -e " e2e 总结: \033[32mPASS=$PASS\033[0m  \033[31mFAIL=$FAIL\033[0m"
echo "════════════════════════════════════════"
if [ "$FAIL" -gt 0 ]; then
  echo "失败主线:"; for f in "${FAILED[@]}"; do echo "  · $f"; done
fi
# 退出码交给 trap restore;显式退出码反映结果
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
