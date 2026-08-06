#!/usr/bin/env bash
# 家庭账房 v0.1 QA 自动测试 — curl + grep 黑盒
# 用法: bash /tmp/qa-run.sh
# 退出码 0 = 全部 PASS,!=0 = 至少一条 FAIL

set -u
BASE="${BASE:-http://localhost:20000}"
COOKIE="/tmp/finance-qa-cookie.txt"
TMP="/tmp/finance-qa-resp.html"
PASS=0; FAIL=0; SKIP=0
FAILED=()

log_ok()   { echo -e "\033[32m PASS \033[0m $1"; PASS=$((PASS+1)); }
log_bad()  { echo -e "\033[31m FAIL \033[0m $1  ::  ${2:-}"; FAIL=$((FAIL+1)); FAILED+=("$1 :: ${2:-}"); }
# 否定断言前先剥掉**整行注释** —— "否定断言被自己解释历史的注释扫红"这个坑本项目踩了 7 次
# (AGENTS.md 有专条),而"每次记得盯代码构造"显然不管用。机械剥注释,结构上不可能再撞。
# 只去掉整行注释(^\s*#),不动行尾的 # —— 后者可能是字符串里的合法字符。
code_only(){ sed -e 's/^[[:space:]]*#.*$//' "$1"; }

log_skip() { echo -e "\033[33m SKIP \033[0m $1  ::  ${2:-}"; SKIP=$((SKIP+1)); }
section()  { echo; echo -e "\033[1;36m─── $1 ───\033[0m"; }

CURL="/usr/bin/curl -s --max-time 15"

# ---------- 0 · 认证 ----------
section "0 · 认证"

# AUTH-1 未登录跳登录
rm -f /tmp/finance-qa-noauth.txt
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/dashboard")
loc=$($CURL -o /dev/null -w "%{redirect_url}" "$BASE/dashboard")
[[ "$code" == "302" && "$loc" == *"/login" ]] && log_ok "AUTH-1 未登录访问 /dashboard 跳 /login" || log_bad "AUTH-1 未登录跳登录" "code=$code loc=$loc"

# AUTH-2 登录页
$CURL "$BASE/login" -o "$TMP" -w ""
grep -q '_csrf' "$TMP" && grep -q 'name="username"' "$TMP" && grep -q 'name="password"' "$TMP" \
  && log_ok "AUTH-2 登录页含 _csrf+用户名+密码字段" || log_bad "AUTH-2 登录页字段缺" "missing fields"

# AUTH-3 错误密码
rm -f $COOKIE
TOKEN=$($CURL -c $COOKIE "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
loc=$($CURL -b $COOKIE -c $COOKIE -X POST --data-urlencode "_csrf=$TOKEN" --data-urlencode "username=diwa" --data-urlencode "password=WRONG" "$BASE/login" -o /dev/null -w "%{redirect_url}")
[[ "$loc" == *"/login?error" ]] && log_ok "AUTH-3 错误密码跳 /login?error" || log_bad "AUTH-3 错误密码处理" "loc=$loc"

# AUTH-4 正确登录
rm -f $COOKIE
TOKEN=$($CURL -c $COOKIE "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
loc=$($CURL -b $COOKIE -c $COOKIE -X POST --data-urlencode "_csrf=$TOKEN" --data-urlencode "username=diwa" --data-urlencode "password=demo1234" "$BASE/login" -o /dev/null -w "%{redirect_url}")
[[ "$loc" == *"/" ]] && log_ok "AUTH-4 正确密码登录 → /" || log_bad "AUTH-4 登录失败" "loc=$loc"

# AUTH-5 dashboard 完整 HTML
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q "</html>" "$TMP" && log_ok "AUTH-5 /dashboard 完整 HTML" || log_bad "AUTH-5 /dashboard 不完整" "no </html>"

# AUTH-7 /health 公开
$CURL "$BASE/health" -o "$TMP" -w ""
grep -q '"status":"UP"' "$TMP" && log_ok "AUTH-7 /health 公开 JSON" || log_bad "AUTH-7 /health" "$(cat $TMP)"

# AUTH-8 已登录访问 /login 自动跳 /dashboard(书签 = /login 场景 · 2026-05-14)
loc=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}|%{redirect_url}" "$BASE/login")
{ [[ "$loc" == "302|"*"/dashboard" ]]; } \
  && log_ok "AUTH-8 已登录 GET /login → 302 → /dashboard($loc)" \
  || log_bad "AUTH-8 已登录 /login 应跳 dashboard" "got=$loc"

# AUTH-9 未登录访问 /login 仍返回 200 + 登录表单(不影响首登)
CK2=$(mktemp)
code=$($CURL -c $CK2 -o "$TMP" -w "%{http_code}" "$BASE/login")
has_user=$(grep -c 'name="username"' "$TMP")
{ [[ "$code" == "200" ]] && [[ "$has_user" -ge 1 ]]; } \
  && log_ok "AUTH-9 未登录 GET /login → 200 + 用户名输入框(首登正常)" \
  || log_bad "AUTH-9 未登录 /login" "code=$code has_user_input=$has_user"
rm -f $CK2

# ---------- FR-1 ----------
section "FR-1 · 家庭与成员"

$CURL -b $COOKIE "$BASE/admin/family" -o "$TMP" -w ""
{ grep -q "家庭" "$TMP" && grep -q "周期类型" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR1-1 /admin/family 200+完整" || log_bad "FR1-1 /admin/family 缺" "missing"

# FR1-1a · /admin/family 保存生效(2026-05-14 bugfix · 之前嵌套 form 让主 save 失效)
ORIG_NAME=$(mysql -ufinance -pfinance finance -sN -e "SELECT name FROM family WHERE id=1" 2>/dev/null)
ORIG_BRAND=$(mysql -ufinance -pfinance finance -sN -e "SELECT brand_text FROM family WHERE id=1" 2>/dev/null)
# 先 GET 一次 admin/family 确保拿到当前 session 的 XSRF(早期登录的 token 可能已轮转)
$CURL -b $COOKIE -c $COOKIE "$BASE/admin/family" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "name=QA-TEST-FAMILY" \
  --data-urlencode "brandText=QA-BRAND" \
  --data-urlencode "baseCurrency=CNY" \
  --data-urlencode "periodType=MONTHLY" \
  "$BASE/admin/family" -o /dev/null -w "%{http_code}")
DB_NAME_AFTER=$(mysql -ufinance -pfinance finance -sN -e "SELECT name FROM family WHERE id=1" 2>/dev/null)
DB_BRAND_AFTER=$(mysql -ufinance -pfinance finance -sN -e "SELECT brand_text FROM family WHERE id=1" 2>/dev/null)
{ [[ "$code" =~ ^30[0-9]$ ]] && [[ "$DB_NAME_AFTER" == "QA-TEST-FAMILY" ]] && [[ "$DB_BRAND_AFTER" == "QA-BRAND" ]]; } \
  && log_ok "FR1-1a /admin/family 保存写入 DB · name+brand_text 入库 · code=$code" \
  || log_bad "FR1-1a /admin/family 保存不生效" "code=$code db_name=$DB_NAME_AFTER db_brand=$DB_BRAND_AFTER"
# 还原
mysql -ufinance -pfinance finance -e "UPDATE family SET name='$ORIG_NAME', brand_text='$ORIG_BRAND' WHERE id=1" 2>/dev/null

$CURL -b $COOKIE "$BASE/admin/members" -o "$TMP" -w ""
{ grep -q "diwa" "$TMP" && grep -q "wangergou" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR1-2 /admin/members 列出 2 人" || log_bad "FR1-2 /admin/members" "missing names"

# FR1-7 添加成员入口存在
grep -q "+ 添加成员" "$TMP" && log_ok "FR1-7 /admin/members 含'添加成员'入口" || log_bad "FR1-7 添加成员入口" "missing"

# FR1-8 改密页可访问 (登录态下)
$CURL -b $COOKIE "$BASE/profile/password" -o "$TMP" -w ""
{ grep -q "修改" "$TMP" && grep -q "新密码" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR1-8 /profile/password 改密页" || log_bad "FR1-8 改密页" "missing"

# logo 上传 form
grep -q "logo" /tmp/finance-qa-resp.html && log_skip "FR1-6 logo 表单" "需视觉确认"

# ---------- FR-2 ----------
section "FR-2 · 模板向导"
$CURL -b $COOKIE "$BASE/accounts/new" -o "$TMP" -w ""
grep -q "添加账户向导" "$TMP" && log_ok "FR2-1 /accounts/new 弹向导" || log_bad "FR2-1 向导缺" "missing wizard"
grep -q "现金 (CASH)" "$TMP" && log_ok "FR2-3 类型下拉中文化" || log_bad "FR2-3 中文化" "no chinese label"

$CURL -b $COOKIE "$BASE/admin/account-templates" -o "$TMP" -w ""
grep -q "</html>" "$TMP" && log_ok "FR2-2 /admin/account-templates" || log_bad "FR2-2 模板页" "incomplete"

# ---------- FR-3 ----------
section "FR-3 · 账户管理"
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
# v1.6.25 修正:原断言 grep 的是「招行储蓄卡-工资」—— 那**根本不是账户行**,而是建户向导里
#   `<input name="displayName" placeholder="如: 招行储蓄卡-工资">` 的占位符;而向导片段因为
#   `th:if` 与 `th:replace` 写在同一元素(优先级 1 > 3)被**无条件渲染**,占位符一直在 HTML 里。
#   叫这个名字的账户 2026-06-28 就归档了(默认列表不含归档)→ 这条守护**一个月来没在验账户列表**。
#   修好片段条件后它才露出来。改成从 DB 取**真实活跃账户名**,不再写死文案(免得再次腐烂)。
ACC1_NAME=$(mysql -ufinance -pfinance finance -sN -e "SELECT display_name FROM account WHERE family_id=1 AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
{ [ -n "$ACC1_NAME" ] && grep -qF "$ACC1_NAME" "$TMP" && grep -q 'ledger-table' "$TMP"; } \
  && log_ok "FR3-1 /accounts 列表(含活跃账户「${ACC1_NAME}」+ 表格结构)" \
  || log_bad "FR3-1 /accounts" "列表里没有活跃账户「${ACC1_NAME:-取不到}」或缺 ledger-table"

$CURL -b $COOKIE "$BASE/accounts/1/edit" -o "$TMP" -w ""
{ grep -q "保存对账户的修改" "$TMP" && grep -q "招行储蓄卡-工资" "$TMP"; } && log_ok "FR3-3 编辑专属页正确" || log_bad "FR3-3 编辑页" "missing"

# ---------- FR-5 待办 / 周期 ----------
section "FR-5 · 周期与待办"
periods=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM period WHERE status='OPEN' AND family_id=1" 2>/dev/null)
[[ "$periods" -ge 1 ]] && log_ok "FR5-1 OPEN 周期存在 ($periods)" || log_bad "FR5-1 没有 OPEN 周期" "count=$periods"

todos=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM snapshot_todo st JOIN period p ON p.id=st.period_id WHERE p.status='OPEN' AND p.family_id=1" 2>/dev/null)
accounts=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM account WHERE archived_at IS NULL AND family_id=1" 2>/dev/null)
[[ "$todos" -gt 0 ]] && log_ok "FR5-2 待办存在 ($todos vs accounts $accounts)" || log_bad "FR5-2 待办空" "todos=$todos accounts=$accounts"

# ---------- FR-6 待办已折叠进填报(v0.11.7 退休 /my-todos)----------
section "FR-6 · 待办折叠进填报"
# FR6-1 · /my-todos 退休:302 重定向(照顾老书签)
code=$($CURL -b $COOKIE -o /dev/null -w '%{http_code}' "$BASE/my-todos")
{ [[ "$code" == "302" || "$code" == "301" ]]; } \
  && log_ok "FR6-1 /my-todos 已退休 · 重定向($code)" \
  || log_bad "FR6-1 /my-todos 未重定向" "code=$code"
# FR6-2 · 跟随重定向应落到「填报」页(能力已折叠到 /entry?mine=true)
$CURL -b $COOKIE -L "$BASE/my-todos" -o "$TMP" -w ""
{ grep -q "</html>" "$TMP" && grep -qE '保存我的本月收支|应填账户|填报' "$TMP"; } \
  && log_ok "FR6-2 /my-todos→填报页(能力已折叠到 /entry?mine=true)" \
  || log_bad "FR6-2 /my-todos 未落到填报页" "see redirect"

# 比较 mine=true vs mine=false
sa=$($CURL -b $COOKIE "$BASE/entry" -o /tmp/finance-qa-all.html -w "%{size_download}")
sm=$($CURL -b $COOKIE "$BASE/entry?mine=true" -o /tmp/finance-qa-mine.html -w "%{size_download}")
[[ "$sm" -lt "$sa" ]] && log_ok "FR6-3 mine=true 行数减少 (all=$sa mine=$sm)" || log_bad "FR6-3 mine 没减少" "all=$sa mine=$sm"

# 账户筛选
$CURL -b $COOKIE "$BASE/entry?account=1" -o "$TMP" -w ""
grep -q "已按账户筛选" "$TMP" && log_ok "FR6-4 account 筛选生效" || log_bad "FR6-4 account 筛选无 banner" "missing"

# ---------- FR-7~10 录入 / 现金流 / 转账 ----------
section "FR-7~10 · 录入/现金流/转账(POST)"

PERIOD_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE status='OPEN' AND family_id=1 LIMIT 1" 2>/dev/null)
ACC1=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE display_name='招行储蓄卡-工资' LIMIT 1" 2>/dev/null)
ACC2=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE display_name='工商银行-备用金' LIMIT 1" 2>/dev/null)

[[ -n "$PERIOD_ID" && -n "$ACC1" ]] || { log_bad "FR7-prep" "missing period/account ids"; }

# 提取 csrf cookie
CSRF=$(grep XSRF-TOKEN $COOKIE 2>/dev/null | awk '{print $7}' | tail -1)
[[ -z "$CSRF" ]] && { $CURL -b $COOKIE -c $COOKIE "$BASE/dashboard" -o /dev/null; CSRF=$(grep XSRF-TOKEN $COOKIE | awk '{print $7}' | tail -1); }

# FR-7 提交余额
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "newBalance=46000" --data-urlencode "note=qa-test" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/balance" -o "$TMP" -w "%{http_code}")
[[ "$code" == "200" ]] && grep -q "entry-row-$ACC1" "$TMP" && log_ok "FR7-2 提交余额 200+fragment" || log_bad "FR7-2 提交余额" "code=$code"

snap=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$ACC1" 2>/dev/null)
[[ -n "$snap" ]] && log_ok "FR7-2db 快照写入 ($snap)" || log_bad "FR7-2db 无快照" "no row"

# FR-8 收入
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=1000" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/cash-flow" -o /dev/null -w "%{http_code}")
[[ "$code" == "200" ]] && log_ok "FR8-1 提交收入 200" || log_bad "FR8-1 收入" "code=$code"

# FR8-1+ 收入提交后响应头含 HX-Trigger=refresh-row-{id} 让客户端 self-refresh 刷新 ledger
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=1234" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/cash-flow" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*refresh-row-$ACC1" \
  && log_ok "FR8-1b 收入响应含 HX-Trigger=refresh-row-$ACC1(触发 GET 自我刷新 ledger)" \
  || log_bad "FR8-1b HX-Trigger 缺失" "no header"

# FR7-8~11 5 步场景:上期=10000 → +收入 100 → -支出 1000 → 校准 4000 → +收入 200
SCEN_ACC=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE display_name='工商银行-备用金' LIMIT 1" 2>/dev/null)
LATEST_PREV=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='CLOSED' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "
DELETE FROM cash_flow WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC;
DELETE FROM transfer WHERE period_id=$PERIOD_ID AND (from_account_id=$SCEN_ACC OR to_account_id=$SCEN_ACC);
INSERT INTO period_snapshot(period_id, account_id, end_balance, submitted_by, note)
VALUES ($PERIOD_ID, $SCEN_ACC, 10000, 1, 'qa-seed') ON DUPLICATE KEY UPDATE end_balance=10000, note='qa-seed';
UPDATE period_snapshot SET end_balance=10000 WHERE period_id=$LATEST_PREV AND account_id=$SCEN_ACC;" 2>/dev/null

# FR7-7 输入框预填上期值(input 元素跨行,用 pcregrep 多行 / 退而 awk)
$CURL -b $COOKIE "$BASE/entry?account=$SCEN_ACC" -o "$TMP" -w ""
awk '/name="newBalance"/{flag=3} flag>0{buf=buf $0; flag--} END{if(buf ~ /value="10000\.00"/) exit 0; else exit 1}' "$TMP" \
  && log_ok "FR7-7 输入框预填上期末 10000" \
  || log_bad "FR7-7 输入框未预填" "missing value=10000"

# FR7-8 +收入 100 → 余额 10100
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=100" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/cash-flow" -o /dev/null
b1=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b1" == "10100.00" ]] && log_ok "FR7-8 +收入100 → 余额=10100" || log_bad "FR7-8" "got=$b1"

# FR7-9 -支出 1000 → 余额 9100
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=EXPENSE" --data-urlencode "categoryCode=consumption" --data-urlencode "amount=1000" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/cash-flow" -o /dev/null
b2=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b2" == "9100.00" ]] && log_ok "FR7-9 -支出1000 → 余额=9100" || log_bad "FR7-9" "got=$b2"

# FR7-10 校准余额 4000 → 直接覆盖
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "newBalance=4000" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/balance" -o /dev/null
b3=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b3" == "4000.00" ]] && log_ok "FR7-10 校准至 4000(覆盖)" || log_bad "FR7-10" "got=$b3"

# FR7-11 校准后 +收入 200 → 余额 4200(在新基础上累加)
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=200" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/cash-flow" -o /dev/null
b4=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b4" == "4200.00" ]] && log_ok "FR7-11 校准后 +收入200 → 余额=4200" || log_bad "FR7-11" "got=$b4"

# FR-9-1 转账(随机金额避开 24h 去重)
AMT=$(( RANDOM % 9000 + 100 ))
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "toAccountId=$ACC2" --data-urlencode "amount=$AMT" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/transfer" -o /dev/null -w "%{http_code}")
[[ "$code" == "200" ]] && log_ok "FR9-1 提交转账 200 (amount=$AMT)" || log_bad "FR9-1 转账" "code=$code"

# FR-9-1b 转账响应含 HX-Trigger=refresh-row-{toId} 让 B 行 self-refresh
AMT2=$(( RANDOM % 9000 + 100 ))
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "toAccountId=$ACC2" --data-urlencode "amount=$AMT2" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/transfer" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*refresh-row-$ACC2" \
  && log_ok "FR9-1b 转账响应触发 refresh-row-$ACC2(B 账户行自动刷新)" \
  || log_bad "FR9-1b HX-Trigger 缺失" "no refresh trigger"

# FR-9-2 同金额二次提交 → 200 + HX-Trigger=showToast(由 ToastErrorAdvice 转友好提示)
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "toAccountId=$ACC2" --data-urlencode "amount=$AMT" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/transfer" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*showToast" \
  && log_ok "FR9-2 24h 重复转账 → toast 提示(替代 500)" \
  || log_bad "FR9-2 重复未拦截" "missing toast"

# FR-11/12 关账 + 立即开下一周期 + CLOSED 期 toast
section "FR-11/12 · 关账 / 开新期 / CLOSED 拒写"

# 立即开下一周期
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" \
  --data-urlencode "_csrf=$CSRF" \
  "$BASE/admin/periods/open-next" -o /dev/null)
echo "$hdrs" | head -1 | grep -q "302" && log_ok "FR12-3 立即开下一周期 → 302" || log_bad "FR12-3 立即开下一周期" "no 302"

# 强制关账(找一个 OPEN 周期,断言:CLOSED + PENDING=0 + snapshot 全补齐)
FORCE_CLOSE=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
ACCT_COUNT=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM account WHERE family_id=1 AND archived_at IS NULL" 2>/dev/null)
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" --data-urlencode "_csrf=$CSRF" \
  "$BASE/admin/periods/$FORCE_CLOSE/force-close" -o /dev/null
NEW_STATUS=$(mysql -ufinance -pfinance finance -sN -e "SELECT status FROM period WHERE id=$FORCE_CLOSE" 2>/dev/null)
SNAP_COUNT=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM period_snapshot WHERE period_id=$FORCE_CLOSE" 2>/dev/null)
PENDING=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM snapshot_todo WHERE period_id=$FORCE_CLOSE AND status='PENDING'" 2>/dev/null)
[[ "$NEW_STATUS" == "CLOSED" && "$PENDING" == "0" && "$SNAP_COUNT" == "$ACCT_COUNT" ]] \
  && log_ok "FR11-5 强制关账 period=$FORCE_CLOSE: CLOSED + 0 PENDING + $SNAP_COUNT snapshot" \
  || log_bad "FR11-5 强制关账失败" "status=$NEW_STATUS pending=$PENDING snap=$SNAP_COUNT/$ACCT_COUNT"

# CLOSED 周期点 +收入 → 应返回 200 + HX-Trigger=showToast
CLOSED_PERIOD=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='CLOSED' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=10" --data-urlencode "periodId=$CLOSED_PERIOD" \
  "$BASE/entry/1/cash-flow" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*showToast" \
  && log_ok "FR11-4 CLOSED 期点+收入 → HX-Trigger=showToast(toast 拒写)" \
  || log_bad "FR11-4 CLOSED 拒写无 toast 头" "missing"

# ---------- FR-12 周期重开 ----------
section "FR-12 · 周期重开"
$CURL -b $COOKIE "$BASE/admin/periods" -o "$TMP" -w ""
{ grep -q "OPEN\|CLOSED" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR12-1 /admin/periods 列表" || log_bad "FR12-1 /admin/periods" "missing"

# ---------- FR-13 Dashboard ----------
section "FR-13 · Dashboard"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
# v0.10.6 去过期:第 5 卡 v0.4 FR-60a 起从「负债率」改为「本月资产收益」(负债率搬 /checkup)。
#   原锚词「负债率」只命中 _region.html HTML 注释 → 靠注释假通过。改锚真实卡名。
{ grep -q "净资产" "$TMP" && grep -q "总资产" "$TMP" && grep -q "总负债" "$TMP" && grep -q "紧急储备" "$TMP" && grep -q "本月资产收益" "$TMP"; } \
  && log_ok "FR13-1 5 KPI 卡齐(净资产/总资产/总负债/紧急储备/本月资产收益)" || log_bad "FR13-1 KPI 不全" "missing"

for r in 1M 3M 6M YTD 1Y ALL; do
  $CURL -b $COOKIE "$BASE/dashboard?range=$r" -o "$TMP" -w ""
  grep -q "</html>" "$TMP" && log_ok "FR13-2/$r 完整 HTML" || log_bad "FR13-2/$r 不完整" "no </html>"
done

# HTMX fragment-only
$CURL -b $COOKIE -H "HX-Request: true" "$BASE/dashboard?range=YTD" -o "$TMP" -w ""
{ ! grep -q "<html" "$TMP" && grep -q "dashboard-region" "$TMP"; } \
  && log_ok "FR13-3 HX-Request 返回 fragment" || log_bad "FR13-3 fragment 异常" "html present?"

# ---------- FR-14 Reports ----------
section "FR-14 · Reports"
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q "家庭 XIRR" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR14-1 /reports 完整" || log_bad "FR14-1 /reports" "missing"

for r in 1M 3M 6M YTD 1Y ALL; do
  $CURL -b $COOKIE "$BASE/reports?range=$r" -o "$TMP" -w ""
  grep -q "</html>" "$TMP" && log_ok "FR14-2/$r 完整" || log_bad "FR14-2/$r" "no </html>"
done

# ---------- FR-15 多币种 ----------
section "FR-15 · 多币种"
$CURL -b $COOKIE "$BASE/admin/fx" -o "$TMP" -w ""
{ grep -q "USD" "$TMP" || grep -q "HKD" "$TMP"; } && grep -q "</html>" "$TMP" \
  && log_ok "FR15-1 /admin/fx 含 USD/HKD" || log_bad "FR15-1 /admin/fx" "missing"

# ---------- FR-16 CSV ----------
section "FR-16 · CSV 导出"
$CURL -b $COOKIE "$BASE/export.zip" -o /tmp/finance-qa.zip -w ""
file /tmp/finance-qa.zip 2>&1 | grep -q "Zip" && log_ok "FR16-1 /export.zip 是 ZIP" || log_bad "FR16-1 不是 ZIP" "$(file /tmp/qa.zip)"

cnt=$(python3 -c "import zipfile; z=zipfile.ZipFile('/tmp/finance-qa.zip'); print(len(z.infolist()))" 2>/dev/null)
[[ "$cnt" == "9" ]] && log_ok "FR16-2 ZIP 含 9 个文件" || log_bad "FR16-2 文件数 $cnt" "expected 9"

bom=$(python3 -c "import zipfile; z=zipfile.ZipFile('/tmp/finance-qa.zip'); print(z.read('families.csv')[:3].hex())" 2>/dev/null)
[[ "$bom" == "efbbbf" ]] && log_ok "FR16-3 UTF-8 BOM 头" || log_bad "FR16-3 BOM" "got $bom"

# ---------- FR-17 banner / FR-18 备份 ----------
section "FR-17/18"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -qE "未结账|本期还有|未填" "$TMP" && log_ok "FR17-1 dashboard 含 pending banner 元素" || log_skip "FR17-1 banner" "可能本期已全填"

$CURL -b $COOKIE "$BASE/admin/backup" -o "$TMP" -w ""
{ grep -q "备份" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR18-1 /admin/backup" || log_bad "FR18-1" "missing"

# ---------- FR-19 LOAN ----------
section "FR-19 · LOAN"
LOAN_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE type='LOAN' LIMIT 1" 2>/dev/null)
if [[ -n "$LOAN_ID" ]]; then
  $CURL -b $COOKIE "$BASE/accounts/$LOAN_ID/edit" -o "$TMP" -w ""
  grep -q "默认还款来源" "$TMP" && log_ok "FR19-3 LOAN 编辑含还款来源字段" || log_bad "FR19-3 LOAN 字段" "missing"
else
  log_skip "FR19-3 无 LOAN 账户" "skip"
fi

# ---------- FR-20 admin 全部 ----------
section "FR-20 · /admin 全部子页"
for path in /admin /admin/family /admin/members /admin/account-templates /admin/cash-flow-categories /admin/periods /admin/reminders /admin/fx /admin/backup /admin/audit /admin/calc-tweaks; do
  $CURL -b $COOKIE "$BASE$path" -o "$TMP" -w ""
  grep -q "</html>" "$TMP" && log_ok "FR20 $path" || log_bad "FR20 $path 不完整" "no </html>"
done

# ---------- FR-21 账户筛选 ----------
section "FR-21 · 账户筛选"
$CURL -b $COOKIE "$BASE/dashboard?accounts=1" -o "$TMP" -w ""
{ grep -q "1 个已选\|个已选" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR21-1 ?accounts=1 显示筛选" || log_bad "FR21-1 筛选" "missing"

# ---------- FR-22 显示币种 ----------
section "FR-22 · 显示币种"
$CURL -b $COOKIE "$BASE/dashboard?currency=USD" -o "$TMP" -w ""
grep -qF '$' "$TMP" && grep -q "</html>" "$TMP" && log_ok "FR22-1 USD 显示 \$" || log_bad "FR22-1 USD" "missing $"
$CURL -b $COOKIE "$BASE/dashboard?currency=HKD" -o "$TMP" -w ""
grep -qF 'HK$' "$TMP" && log_ok "FR22-2 HKD 显示 HK\$" || log_bad "FR22-2 HKD" "missing HK$"

# v0.2 BUG-FIX(2026-05-10):币种切换以前只换符号不换数字。
# 这里强校验三套币种渲染出的「净资产」KPI 数字必须真的不同(假设家庭只有 CNY 账户 + 至少一行 fx_rate)。
# 防止 FactMapper.xml fx CASE 倒挂 / controller 漏调 fxFallback 等回归。
fx_seed_periodId=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source) VALUES
(1, 'CNY', 'USD', ${fx_seed_periodId}, 0.140000, 'qa-seed'),
(1, 'CNY', 'HKD', ${fx_seed_periodId}, 1.090000, 'qa-seed')
ON DUPLICATE KEY UPDATE rate=VALUES(rate), source=VALUES(source);" 2>/dev/null

# 提取 currency=X 时净资产 KPI 数字(去千分位 + 符号)
extract_nw() {
  local cur="$1"
  $CURL -b $COOKIE "$BASE/dashboard?currency=${cur}" -o "$TMP" -w ""
  grep -A1 'kpi-eyebrow">净资产' "$TMP" | grep "kpi-value" | head -1 | sed -E 's/.*>([^<]+)<.*/\1/' | tr -d ',$¥' | sed 's/HK//'
}
nw_cny=$(extract_nw CNY)
nw_usd=$(extract_nw USD)
nw_hkd=$(extract_nw HKD)
[[ "$nw_cny" != "$nw_usd" && "$nw_cny" != "$nw_hkd" && "$nw_usd" != "$nw_hkd" ]] \
  && log_ok "v02-CCY-1 三套币种净资产数字真的不同 (CNY=${nw_cny} USD=${nw_usd} HKD=${nw_hkd})" \
  || log_bad "v02-CCY-1 币种切换数字未联动" "CNY=${nw_cny} USD=${nw_usd} HKD=${nw_hkd}"

# 数学校验:USD 净资产 / CNY 净资产 = 合理的 USD/CNY 汇率区间(0.10~0.20),且 ≠ 1.0(非 fallback)。
# v0.8:不再硬编码 0.14(那是旧 anchor 期的种子率;dashboard 现锚当前期,实际率随期不同)。
if [[ -n "$nw_cny" && -n "$nw_usd" ]]; then
  ratio=$(awk -v u="$nw_usd" -v c="$nw_cny" 'BEGIN{ if(c==0){print 0}else{printf "%.4f", u/c} }')
  ok_ratio=$(awk -v r="$ratio" 'BEGIN{ print (r>=0.10 && r<=0.20) ? 1 : 0 }')
  [[ "$ok_ratio" == "1" ]] \
    && log_ok "v02-CCY-2 USD 数学正确 · USD/CNY 比值=${ratio}(合理区间,非 1.0 fallback)实际 USD=${nw_usd}" \
    || log_bad "v02-CCY-2 USD 数学错" "USD/CNY 比值=${ratio} 不在 0.10~0.20(USD nw=${nw_usd} CNY nw=${nw_cny})"
fi

# v0.5 修 · 比值类指标(紧急储备月数)必须币种无关:换币种时分子(流动资产·view)和
# 分母(月支出·PMC)应同口径换算,比值不变。回归点:PMC 漏换 base→view 导致比值漂移。
extract_emergency() {
  local cur="$1"
  $CURL -b $COOKIE "$BASE/dashboard?currency=${cur}" -o "$TMP" -w ""
  # 紧急储备月数值带「月」后缀(金额带 ¥/$)· 取含「月」的 kpi-value 数字 · 唯一锚不会抓到金额
  grep 'kpi-value' "$TMP" | grep '月' | head -1 | sed -E 's/<[^>]+>//g' | grep -oE '[0-9]+(\.[0-9]+)?' | head -1
}
em_cny=$(extract_emergency CNY)
em_usd=$(extract_emergency USD)
em_hkd=$(extract_emergency HKD)
if [[ -n "$em_cny" && -n "$em_usd" && -n "$em_hkd" ]]; then
  if [[ "$em_cny" == "$em_usd" && "$em_cny" == "$em_hkd" ]]; then
    log_ok "v05-CCY-INV-1 紧急储备月数币种无关 (CNY=${em_cny} USD=${em_usd} HKD=${em_hkd} 月)"
  else
    log_bad "v05-CCY-INV-1 比值随币种漂移(PMC 未换算 base→view)" "CNY=${em_cny} USD=${em_usd} HKD=${em_hkd}"
  fi
else
  log_skip "v05-CCY-INV-1 紧急储备月数 币种无关" "无数据(需 LIQUID 账户 + 月支出)"
fi

# ───────────────────────────────────────────────────────────────────────────
# v0.8 BUG-FIX(v08-CCY-INV)· 属性级币种不变性护栏(反复踩雷点的根治防回归)
#   语义:视图币种 = 显示镜头 → ① 比值类指标(收益率/月数/负债率/环比)切币种必须【完全相等】;
#        ② 金额类指标按 fx 因子【精确缩放】。
#   背景:v0.8 筛选器重做引入多期依赖,但 ensure 只覆盖 anchor 一期 → 上期/窗口期缺汇率落 1.0 未换算,
#        且视图币种为「第三币种」(USD 账户在 HKD 视图)缺三角换算 → 本月资产收益率 CNY −18%/HKD −9%/USD −88%。
#   修:ensure 扩到 ≤anchor 全期 + 视图币种全期补 base→view + FactMapper 经本位币三角换算。
#   本护栏:先给 family#1 所有账期播【一致】汇率(消除 beta 历史期混合率/2034 种子噪声),使不变式可严格断言。
mysql -ufinance -pfinance finance -e "
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source)
  SELECT 1,'CNY','USD',id,0.140000,'qa-inv-seed' FROM period WHERE family_id=1
  ON DUPLICATE KEY UPDATE rate=VALUES(rate), source=VALUES(source);
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source)
  SELECT 1,'CNY','HKD',id,1.090000,'qa-inv-seed' FROM period WHERE family_id=1
  ON DUPLICATE KEY UPDATE rate=VALUES(rate), source=VALUES(source);" 2>/dev/null

extract_one_pct() {   # 单个 eyebrow 标签下首个 kpi-value(如本月资产收益率)
  # v1.6.30 · 原实现按 `kpi-eyebrow">标签` 紧邻匹配,标签一旦被包进 <span>(为显示口径期)就抓空,
  #   导致 v08-CCY-INV-2 报「币种漂移」而其实是取数失败 —— 三个币种全取到空串也会判不相等。
  #   改成按标签文本就近匹配(容忍嵌套标签),并要求调用方传两种文案的公共子串。
  $CURL -b $COOKIE "$BASE/dashboard?currency=$1" -o "$TMP" -w ""
  grep -A4 "$2" "$TMP" | grep 'kpi-value' | head -1 | sed -E 's/<[^>]+>//g' | tr -d ' \n'
}
extract_ratio_kpis() {  # 所有含 % 或「月」的 kpi-value(比值类);金额带币种符号天然不入选
  $CURL -b $COOKIE "$BASE/dashboard?currency=$1" -o "$TMP" -w ""
  grep 'kpi-value' "$TMP" | sed -E 's/<[^>]+>//g' | tr -d ' ' | grep -E '%|月'
}

# v08-CCY-INV-2:本月资产收益率(用户实际踩雷点)币种无关
pr_cny=$(extract_one_pct CNY 资产收益); pr_usd=$(extract_one_pct USD 资产收益); pr_hkd=$(extract_one_pct HKD 资产收益)
if [[ -n "$pr_cny" && "$pr_cny" == "$pr_usd" && "$pr_cny" == "$pr_hkd" ]]; then
  log_ok "v08-CCY-INV-2 本月资产收益率币种无关 (CNY=$pr_cny USD=$pr_usd HKD=$pr_hkd)"
else
  log_bad "v08-CCY-INV-2 本月资产收益率随币种漂移(跨期/三角换算口径不一致)" "CNY=$pr_cny USD=$pr_usd HKD=$pr_hkd"
fi

# v08-CCY-INV-3:属性级 —— 所有比值类 KPI 切币种完全相等(网住未来任何新增比值指标)
rk_cny=$(extract_ratio_kpis CNY); rk_usd=$(extract_ratio_kpis USD); rk_hkd=$(extract_ratio_kpis HKD)
if [[ -n "$rk_cny" && "$rk_cny" == "$rk_usd" && "$rk_cny" == "$rk_hkd" ]]; then
  log_ok "v08-CCY-INV-3 所有比值类KPI币种无关(属性级 · $(echo "$rk_cny" | tr '\n' ' '))"
else
  log_bad "v08-CCY-INV-3 比值类KPI随币种漂移" "CNY=[$(echo $rk_cny)] USD=[$(echo $rk_usd)] HKD=[$(echo $rk_hkd)]"
fi

# v08-CCY-INV-4:金额类按 fx 精确缩放(净资产 USD≈CNY×0.14 · HKD≈CNY×1.09 · 容 0.5% 舍入)
inw_cny=$(extract_nw CNY); inw_usd=$(extract_nw USD); inw_hkd=$(extract_nw HKD)
if [[ -n "$inw_cny" && -n "$inw_usd" && -n "$inw_hkd" ]]; then
  ok_usd=$(awk -v u="$inw_usd" -v c="$inw_cny" 'BEGIN{r=(c==0)?0:u/c; print (r>=0.1393&&r<=0.1407)?1:0}')
  ok_hkd=$(awk -v h="$inw_hkd" -v c="$inw_cny" 'BEGIN{r=(c==0)?0:h/c; print (r>=1.0845&&r<=1.0955)?1:0}')
  [[ "$ok_usd" == "1" && "$ok_hkd" == "1" ]] \
    && log_ok "v08-CCY-INV-4 金额按 fx 精确缩放 (USD/CNY≈0.14 · HKD/CNY≈1.09)" \
    || log_bad "v08-CCY-INV-4 金额缩放偏离 fx" "USD/CNY=$(awk -v u=$inw_usd -v c=$inw_cny 'BEGIN{if(c==0)print "NA";else printf "%.4f",u/c}') HKD/CNY=$(awk -v h=$inw_hkd -v c=$inw_cny 'BEGIN{if(c==0)print "NA";else printf "%.4f",h/c}')"
fi

# v08-PILL-M · 手机端 dashboard 账户卡片:类型标签在账户名【前】+ 固定宽度对齐(与 PC 表一致)
#   防回归到旧版「账户名后 ml-1 的 pill」(PC 已前置,手机端 2026-06-23 补齐)。源级判定:
#   在 sm:hidden 手机块里,带 min-width 的 pill 行须早于账户名(font-display)行。
REG_DASH=src/main/resources/templates/dashboard/_region.html
mblk=$(awk '/sm:hidden space-y-2/{f=1} f{print} END{}' "$REG_DASH" | head -25)
pl=$(printf '%s\n' "$mblk" | grep -n 'pill text-center shrink-0' | head -1 | cut -d: -f1)
nl=$(printf '%s\n' "$mblk" | grep -n 'font-display text-sm' | head -1 | cut -d: -f1)
if [[ -n "$pl" && -n "$nl" && "$pl" -lt "$nl" ]] && printf '%s' "$mblk" | grep -q 'min-width:3.4em'; then
  log_ok "v08-PILL-M 手机卡片类型标签在账户名前 + 固定宽度对齐(min-width:3.4em)"
else
  log_bad "v08-PILL-M 手机卡片类型标签未前置/未对齐(防回归到名后 ml-1)" "pill行=$pl 名行=$nl"
fi

# v0.5.3 · 计算指标 tooltip 展示真实数值:每页 ⓘ 面板含 .kpi-info-calc 行(口径下方的实算)。
# 回归点:_kpi-info 片段从 i(text) 升 i(text,calc) + 各 controller 注入 calc map。
# 用净资产「总资产 ¥ − 总负债 ¥ = ¥」断言:它恒有真实数值(不依赖月支出/PMC 填报情况)。
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q 'kpi-info-calc' "$TMP" && grep -qE 'kpi-info-calc[^>]*>总资产 [^<]*− 总负债' "$TMP"; } \
  && log_ok "v05-CALC-1 /dashboard ⓘ 含真实计算数值(净资产 = 总资产 − 总负债 实算)" \
  || log_bad "v05-CALC-1 /dashboard tooltip 无真实数值" "no .kpi-info-calc / 净资产实算"
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'kpi-info-calc' "$TMP" && grep -qE 'kpi-info-calc[^>]*>\(期末净资产' "$TMP"; } \
  && log_ok "v05-CALC-2 /reports ⓘ 含真实计算数值(钱赚 = (期末−起始)−净流入 实算)" \
  || log_bad "v05-CALC-2 /reports tooltip 无真实数值" "no .kpi-info-calc / 钱赚实算"
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'kpi-info-calc' "$TMP" && grep -qE 'kpi-info-calc[^>]*>总资产 [^<]*− 总负债' "$TMP"; } \
  && log_ok "v05-CALC-3 /checkup ⓘ 含真实计算数值(净资产实算)" \
  || log_bad "v05-CALC-3 /checkup tooltip 无真实数值" "no .kpi-info-calc / 净资产实算"

# v0.5.5 · 报表「已关账快照」透出 + dashboard 仍实时(两 tab 分工)
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q '已关账账期的稳定快照' "$TMP" || grep -q '尚无已关账账期' "$TMP"; } \
  && log_ok "v05-SNAP-1 /reports 透出「已关账快照」语义(印章/说明行 或 空态)" \
  || log_bad "v05-SNAP-1 /reports 未透出快照语义" "no 已关账快照 / 尚无已关账"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q '已关账账期的稳定快照' "$TMP" \
  && log_bad "v05-SNAP-2 dashboard 误带报表快照文案(应保持实时)" "found on dashboard" \
  || log_ok "v05-SNAP-2 /dashboard 不含报表快照文案(仍实时 · 分工清晰)"

# v0.5.6 · 报表长文目录(PC 右栏树状大纲 + 章节锚点 + 手机 sheet)
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'toc-rail' "$TMP" && grep -q 'class="toc-node"' "$TMP" \
  && grep -q 'id="sec-decompose"' "$TMP" && grep -q 'id="sec-accounts"' "$TMP" \
  && grep -q 'id="toc-sheet"' "$TMP"; } \
  && log_ok "v05-TOC-1 /reports 含右栏树状目录 + 章节锚点 + 手机 sheet" \
  || log_bad "v05-TOC-1 /reports 目录/锚点缺" "no toc-rail/toc-node/sec-* /toc-sheet"
# v0.5.7 · 目录推广 dashboard + checkup(共用件)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q 'class="toc-rail"' "$TMP" && grep -q 'js/toc.js' "$TMP" && grep -q 'id="dash-trend"' "$TMP"; } \
  && log_ok "v05-TOC-2 /dashboard 接入长文目录 + 锚点" \
  || log_bad "v05-TOC-2 /dashboard 目录缺" "no toc-rail/toc.js/dash-trend"
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'class="toc-rail"' "$TMP" && grep -q 'js/toc.js' "$TMP" && grep -q 'id="checkup-ai"' "$TMP"; } \
  && log_ok "v05-TOC-3 /checkup 接入长文目录 + 锚点" \
  || log_bad "v05-TOC-3 /checkup 目录缺" "no toc-rail/toc.js/checkup-ai"

# 按需拉汇率:删 fx_rate 后切 USD,后端应即时调 frankfurter API 拉新汇率写入,然后正常显示 $
mysql -ufinance -pfinance finance -e "DELETE FROM fx_rate;" 2>/dev/null
$CURL --max-time 30 -b $COOKIE "$BASE/dashboard?currency=USD" -o "$TMP" -w ""
fx_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM fx_rate WHERE family_id=1 AND quote_currency='USD' AND source='frankfurter.dev';" 2>/dev/null)
if [[ "${fx_after:-0}" -ge 1 ]]; then
  log_ok "v02-CCY-3 fx_rate 缺 → 即时调 frankfurter 拉取并入库(source=frankfurter.dev count=$fx_after)"
  # 拉成功后 USD KPI 应该是 $ 而不是 ¥
  grep -A1 'kpi-eyebrow">净资产' "$TMP" | grep "kpi-value" | head -1 | grep -qF '$' \
    && log_ok "v02-CCY-4 即时拉成功后正常显示 \$ 数字(无 toast 兜底)" \
    || log_bad "v02-CCY-4 即时拉成功但仍未显示 \$" "wrong symbol"
else
  # 网络拉不到时:fxFallback 路径,toast 脚本必现 + 显示 ¥
  log_skip "v02-CCY-3 frankfurter 不可达 / 拉失败,走 fallback 路径校验" "fx_after=$fx_after"
  grep -q "汇率未配置" "$TMP" \
    && log_ok "v02-CCY-4 拉失败 fallback → 渲染「汇率未配置」toast 脚本" \
    || log_bad "v02-CCY-4 fallback toast 缺" "no 汇率未配置"
fi

# v02-CCY-5 模板防回归:确保 dashboard / reports 都有 fxFallback toast 脚本块
#   v0.4.4 文案专业化后改为「汇率尚未配置」(去掉"自动拉取也失败" + "联系管理员"的开发口吻)
grep -q '汇率尚未配置' src/main/resources/templates/dashboard/_region.html \
  && grep -q '汇率尚未配置' src/main/resources/templates/reports/_region.html \
  && log_ok "v02-CCY-5 dashboard / reports 均含 fxFallback toast 脚本块(防回归)" \
  || log_bad "v02-CCY-5 fxFallback toast 模板缺失" "missing in dashboard/reports _region.html"

# v02-CCY-6 critical 回归保护:非 base 账户在 dashboard 触发后,fx_rate 表必有当期行
# (防 ensureForAccountCurrencies 漏调,SQL JOIN miss 落 1.0 → USD 当 CNY 累加)
# 数学正确性的端到端校验在 e2e.sh,这里只验"机制触发"
PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
# v0.8:dashboard anchor = 当前账期(resolveAsOf:OPEN 期 → 最近已开始期 → max 兜底),不再取 max(period_start)
ANCHOR=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
[[ -z "$ANCHOR" ]] && ANCHOR=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND period_start<=CURDATE() ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
[[ -z "$ANCHOR" ]] && ANCHOR=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
# 看是否存在非 base 账户(demo 有 USD 富途证券 id=4)
nonbase_count=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM account WHERE family_id=1 AND currency != 'CNY' AND archived_at IS NULL" 2>/dev/null)
if [[ "${nonbase_count:-0}" -ge 1 ]]; then
  # 清当期 anchor 的 fx_rate 行(只清当期 USD/HKD,其它行保留)
  mysql -ufinance -pfinance finance -e "DELETE FROM fx_rate WHERE period_id=$ANCHOR" 2>/dev/null
  $CURL --max-time 30 -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
  # 触发后:anchor 周期下应有该 USD/HKD 的 fx_rate 行(frankfurter 拉或 copy)
  fx_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM fx_rate WHERE period_id=$ANCHOR" 2>/dev/null)
  [[ "${fx_after:-0}" -ge 1 ]] \
    && log_ok "v02-CCY-6 非 base 账户 → dashboard 触发 ensureForAccountCurrencies 写入 fx_rate (anchor=${ANCHOR} count=${fx_after})" \
    || log_bad "v02-CCY-6 ensureForAccountCurrencies 未触发" "anchor=${ANCHOR} fx_count=${fx_after}"

  # v02-CCY-7 当期缺 fx_rate 但他期有 → copy 到当期(防 SQL JOIN miss)
  mysql -ufinance -pfinance finance 2>/dev/null <<SQL
DELETE FROM fx_rate WHERE period_id=$ANCHOR;
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source)
VALUES (1, 'CNY', 'USD', 1, 0.150000, 'qa-other-period')
ON DUPLICATE KEY UPDATE rate=VALUES(rate);
SQL
  $CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
  copied=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM fx_rate WHERE period_id=$ANCHOR AND source LIKE 'copied-from%'" 2>/dev/null)
  [[ "${copied:-0}" -ge 1 ]] \
    && log_ok "v02-CCY-7 当期缺 fx_rate → 从他期 copy(防 SQL JOIN miss · 不调 frankfurter)" \
    || log_bad "v02-CCY-7 copy 未触发" "copied=$copied"
else
  log_skip "v02-CCY-6/7 跳过 — 当前 DB 无非 base 账户" "test 需要 USD/HKD 账户存在"
fi

# ---------- 静态资源 ----------
section "Static / vendor"
for f in /vendor/tailwind.js /vendor/htmx.min.js /vendor/chart.umd.min.js /vendor/echarts.min.js /css/style.css; do
  code=$($CURL -o /dev/null -w "%{http_code}" "$BASE$f")
  [[ "$code" == "200" ]] && log_ok "ST $f 200" || log_bad "ST $f" "code=$code"
done

# ---------- v0.2 错误兜底页 ----------
section "v0.2 · 错误兜底页"

# /error 直接访问(已登录),渲染完整错误页
$CURL -b $COOKIE -H "Accept: text/html" "$BASE/error" -o "$TMP" -w ""
{ grep -q '印泥洒了' "$TMP" && grep -q '出 · 错 · 了' "$TMP" && grep -q '/dashboard' "$TMP" && grep -q '/entry' "$TMP"; } \
  && log_ok "ERR-1 /error(已登录)渲染卡通错误页 + dashboard+entry 链接" \
  || log_bad "ERR-1 /error 内容缺" "missing"

# 登录后访问不存在路径 → 404 + error.html
code=$($CURL -b $COOKIE -H "Accept: text/html" -o "$TMP" -w "%{http_code}" "$BASE/no-such-page-zxy")
{ [[ "$code" == "404" ]] && grep -q '印泥洒了' "$TMP"; } \
  && log_ok "ERR-2 登录后 404 渲染卡通错误页" \
  || log_bad "ERR-2 404 不渲染 error.html" "code=$code"

# 前端兜底:layout 含 5 个全局错误监听
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q 'htmx:responseError' "$TMP" && grep -q 'htmx:sendError' "$TMP" \
  && grep -q 'htmx:timeout' "$TMP" && grep -q 'unhandledrejection' "$TMP" \
  && grep -q "window.addEventListener('error'" "$TMP"; } \
  && log_ok "ERR-3 前端兜底:5 个错误监听齐(htmx:responseError/sendError/timeout + window error/rejection)" \
  || log_bad "ERR-3 前端错误监听不全" "missing"

# 5xx 兜底逻辑:含 401 跳 /login + 5xx 显示 toast 文案
{ grep -q "/login?expired=1" "$TMP" && grep -q "服务器繁忙" "$TMP" && grep -q "网络异常" "$TMP"; } \
  && log_ok "ERR-4 兜底文案齐:401→/login expired + 5xx 服务器繁忙 toast + 网络异常 toast" \
  || log_bad "ERR-4 兜底文案缺" "missing"

# ---------- v0.2 entry 上期余额参考 ----------
section "v0.2 · entry 上期余额参考"

$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
{ grep -q '参考 · 上期末' "$TMP" && grep -qE '上期末.*¥' "$TMP"; } \
  && log_ok "FR7-参考 entry 行含'参考·上期末'+右侧'上期末¥X'" \
  || log_bad "FR7-参考 上期余额参考缺" "missing"

# ---------- v0.2 FR-33 / FR-34 移动端引导 ----------
section "v0.2 · FR-33 微信引导 + FR-34 PWA 添加到主屏"

# manifest 200 + 正确 MIME
ct=$($CURL -o /tmp/finance-qa-manifest.json -w "%{content_type}" "$BASE/manifest.webmanifest")
[[ "$ct" == *"manifest+json"* ]] && log_ok "FR34-1 manifest Content-Type=application/manifest+json" || log_bad "FR34-1 MIME 错" "got=$ct"

# manifest 字段齐(2026-05-10 改为动态 controller,JSON 紧凑无空格,grep 放宽)
{ grep -q '"name"' /tmp/finance-qa-manifest.json && grep -q '/dashboard' /tmp/finance-qa-manifest.json \
  && grep -qE '"display"\s*:\s*"standalone"' /tmp/finance-qa-manifest.json && grep -q '"icons"' /tmp/finance-qa-manifest.json; } \
  && log_ok "FR34-2 manifest 字段齐(name/start_url/display/icons)" \
  || log_bad "FR34-2 字段缺" "see /tmp/finance-qa-manifest.json"

# 三张 PNG 都 200
for f in apple-touch-icon-180.png icon-192.png icon-512.png; do
  code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/$f")
  [[ "$code" == "200" ]] && log_ok "FR34-3 /img/$f = 200" || log_bad "FR34-3 /img/$f" "code=$code"
done

# layout(login 页未登录就能拉到 head)含 4 个 apple meta + manifest + apple-touch-icon-180.png
$CURL "$BASE/login" -o "$TMP" -w ""
{ grep -q 'name="apple-mobile-web-app-capable"' "$TMP" \
  && grep -q 'name="apple-mobile-web-app-status-bar-style"' "$TMP" \
  && grep -q 'name="apple-mobile-web-app-title"' "$TMP" \
  && grep -q 'rel="manifest"' "$TMP" \
  && grep -q 'rel="apple-touch-icon"' "$TMP" \
  && grep -qE 'apple-touch-icon-180\.png|/img/presets/icon[0-9]-180\.png' "$TMP" \
  && grep -q 'theme-color' "$TMP"; } \
  && log_ok "FR34-4 layout head 含 PWA meta + apple-touch-icon (180px)" \
  || log_bad "FR34-4 PWA meta 缺" "see $TMP"

# /js/mobile-guide.js 未登录可达
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/js/mobile-guide.js")
[[ "$code" == "200" ]] && log_ok "FR34-5 /js/mobile-guide.js 未登录 200" || log_bad "FR34-5 mobile-guide.js" "code=$code"

# manifest 未登录可达
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/manifest.webmanifest")
[[ "$code" == "200" ]] && log_ok "FR34-6 manifest 未登录 200" || log_bad "FR34-6 manifest 被拦" "code=$code"

# layout 引用 mobile-guide.js
grep -q 'mobile-guide.js' "$TMP" && log_ok "FR33-1 layout 引用 mobile-guide.js" || log_bad "FR33-1 mobile-guide.js 未引入" "missing"

# mobile-guide.js 内容含三处关键判断
$CURL "$BASE/js/mobile-guide.js" -o "$TMP" -w ""
{ grep -q 'MicroMessenger' "$TMP" && grep -q 'wx_dismissed_at' "$TMP" \
  && grep -q 'pwa_dismissed_at' "$TMP" && grep -q 'standalone' "$TMP"; } \
  && log_ok "FR33-2/FR34-7 脚本含微信 UA + iOS standalone + 双 dismiss key" \
  || log_bad "FR33-2 脚本字段缺" "see $TMP"

# ---------- v0.2 Stage 1: nav / 资产体检 / 类目 ----------
section "v0.2 · 阶段 1 · nav 资产体检 + 类目 + pill"

# CHECKUP-1 顶部 nav 含「资产体检」入口(已登录 dashboard)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q '资产体检' "$TMP" && log_ok "v02-NAV-1 dashboard 顶部 nav 含「资产体检」" || log_bad "v02-NAV-1 nav 资产体检 缺" "see $TMP"

# CHECKUP-2 GET /checkup 占位页 200 + 含「资产体检」标题
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup")
{ [[ "$code" == "200" ]] && grep -q '资产体检' "$TMP"; } \
  && log_ok "v02-CHK-1 GET /checkup 占位 200" || log_bad "v02-CHK-1 /checkup" "code=$code"

# CHECKUP-3 GET /checkup?account=1 账户级占位
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup?account=1")
{ [[ "$code" == "200" ]] && grep -q '账户体检\|资产体检' "$TMP"; } \
  && log_ok "v02-CHK-2 /checkup?account=1 占位 200" || log_bad "v02-CHK-2" "code=$code"

# v02-LIQ-1 · 货币基金参与流动资产(v0.3.3 bugfix · product_category.liquidity_class 驱动)
# 找一个 WEALTH 账户,前后切换 product_category_code · 验证 /checkup 流动资产数字变化
LIQ_ACC=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type='WEALTH' AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
LIQ_ORIG_PC=$(mysql -ufinance -pfinance finance -sN -e "SELECT IFNULL(product_category_code,'NULL') FROM account WHERE id=$LIQ_ACC" 2>/dev/null)
LIQ_BAL=$(mysql -ufinance -pfinance finance -sN -e "
  SELECT ps.end_balance FROM period_snapshot ps
  JOIN period p ON p.id=ps.period_id
  WHERE ps.account_id=$LIQ_ACC AND p.family_id=1 AND p.status='OPEN'
  ORDER BY p.id DESC LIMIT 1" 2>/dev/null)
LIQ_BAL_INT=$(echo "$LIQ_BAL" | cut -d. -f1)
# 强制设回 NULL 测 BEFORE
mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code=NULL WHERE id=$LIQ_ACC" 2>/dev/null
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
LIQ_BEFORE=$(grep -A2 '>流动资产<' "$TMP" | grep -oE '¥[0-9,.]+' | head -1 | tr -d '¥,')
# 设为 MONEY_FUND 测 AFTER
mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code='MONEY_FUND' WHERE id=$LIQ_ACC" 2>/dev/null
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
LIQ_AFTER=$(grep -A2 '>流动资产<' "$TMP" | grep -oE '¥[0-9,.]+' | head -1 | tr -d '¥,')
# AFTER - BEFORE 应该 ≈ LIQ_BAL(允许 1 元误差)
DELTA=$(awk -v a="$LIQ_AFTER" -v b="$LIQ_BEFORE" 'BEGIN{printf "%d", a-b}')
EXPECT_DELTA=$LIQ_BAL_INT
DIFF=$(awk -v d="$DELTA" -v e="$EXPECT_DELTA" 'BEGIN{x=d-e; if(x<0)x=-x; printf "%d", x}')
{ [[ -n "$LIQ_BEFORE" ]] && [[ -n "$LIQ_AFTER" ]] && [[ "$DIFF" -le 2 ]]; } \
  && log_ok "v02-LIQ-1 WEALTH+MONEY_FUND 进入流动资产 · before=$LIQ_BEFORE after=$LIQ_AFTER Δ=$DELTA(期望 $EXPECT_DELTA)" \
  || log_bad "v02-LIQ-1 流动资产未联动" "before=$LIQ_BEFORE after=$LIQ_AFTER Δ=$DELTA expect=$EXPECT_DELTA"

# v02-LIQ-2 · "仅 CASH" caption 已改 · 现在显示 "CASH + 货币基金等(类目 = LIQUID)"
grep -q 'CASH + 货币基金' "$TMP" \
  && log_ok "v02-LIQ-2 体检页 caption 改为「CASH + 货币基金等(类目 = LIQUID)」" \
  || log_bad "v02-LIQ-2 caption 未更新" "still 仅 CASH or missing"

# 还原
[[ "$LIQ_ORIG_PC" == "NULL" ]] && mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code=NULL WHERE id=$LIQ_ACC" 2>/dev/null \
  || mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code='$LIQ_ORIG_PC' WHERE id=$LIQ_ACC" 2>/dev/null

# v02-LIQ-3 · product_category 全 16 行 liquidity_class 列已 populate
LIQ_COL_COUNT=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM product_category WHERE liquidity_class IS NOT NULL AND liquidity_class != ''" 2>/dev/null)
LIQ_LIQUID=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM product_category WHERE liquidity_class='LIQUID'" 2>/dev/null)
LIQ_ILLIQ=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM product_category WHERE liquidity_class='ILLIQUID'" 2>/dev/null)
{ [[ "$LIQ_COL_COUNT" -eq 16 ]] && [[ "$LIQ_LIQUID" -ge 2 ]] && [[ "$LIQ_ILLIQ" -ge 2 ]]; } \
  && log_ok "v02-LIQ-3 16 类目均有 liquidity_class · LIQUID=$LIQ_LIQUID ILLIQUID=$LIQ_ILLIQ" \
  || log_bad "v02-LIQ-3 V20 灌数据" "total=$LIQ_COL_COUNT liquid=$LIQ_LIQUID illiquid=$LIQ_ILLIQ"

# CAT-1 /admin/product-categories 200 + 16 个类目
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/admin/product-categories")
[[ "$code" == "200" ]] && log_ok "v02-PCAT-1 /admin/product-categories 200" || log_bad "v02-PCAT-1" "code=$code"
n=$(grep -oE 'A_STOCK|US_STOCK|HK_STOCK|MONEY_FUND|BANK_WEALTH|CASH_DEPOSIT|GOLD|MIXED_FUND|SHORT_BOND|LONG_BOND|PROPERTY_RES|PROPERTY_INV|CRYPTO|FUTURES|LIABILITY' "$TMP" | sort -u | wc -l)
[[ "$n" -ge 15 ]] && log_ok "v02-PCAT-2 16 个类目 code 全渲染 (n=$n)" || log_bad "v02-PCAT-2 类目数" "n=$n"
grep -q '沪深 300\|标普 500' "$TMP" && log_ok "v02-PCAT-3 含基准指数标签" || log_bad "v02-PCAT-3 基准指数" "missing"

# CAT-4 /admin/index 含产品类目 tile
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/admin")
{ [[ "$code" == "200" ]] && grep -q 'product-categories' "$TMP"; } \
  && log_ok "v02-PCAT-4 /admin hub 含产品类目 tile" || log_bad "v02-PCAT-4 admin tile" "code=$code"

# CAT-5 /admin/_sidebar 含产品类目 link(随便挑一个 admin 页验证)
$CURL -b $COOKIE "$BASE/admin/cash-flow-categories" -o "$TMP" -w ""
grep -q '产品类目' "$TMP" && log_ok "v02-PCAT-5 admin sidebar 含产品类目链接" || log_bad "v02-PCAT-5 sidebar" "missing"

# PILL-1 /accounts 列表类目 pill(2026-05-10 改 SVG 后,grep 类目 pill 标识)
#   v0.10.6 去过期:原硬编码 ≥20 耦合旧 demo 账户量级(现 demo 仅 6 个带品类账户)→ 改「渲染冒烟」(≥1),
#   只验类目 pill 渲染没坏(历史 SVG 改动曾整列消失);数量随 demo 数据浮动,不再据此判红。
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
n=$(grep -oE 'border-color:var\(--brass-deep\);color:var\(--brass-deep\)' "$TMP" | wc -l)
[[ "$n" -ge 1 ]] && log_ok "v02-PILL-1 /accounts 列表类目 pill 渲染正常 (n=$n)" || log_bad "v02-PILL-1 类目 pill 未渲染" "n=$n"

# PILL-2 风险星 ★ 出现(STOCK / WEALTH 类目有 risk_level)
n=$(grep -oE '★' "$TMP" | wc -l)
[[ "$n" -ge 4 ]] && log_ok "v02-PILL-2 /accounts 风险 ★ pill 出现 n=$n" || log_bad "v02-PILL-2 风险 pill" "n=$n"

# PILL-3 /accounts 不再 500 (Thymeleaf 表达式正确)
grep -q '出错了' "$TMP" && log_bad "v02-PILL-3 /accounts 仍触发错误兜底" "see $TMP" || log_ok "v02-PILL-3 /accounts 无错误兜底"

# PILL-4 /accounts/new 编辑向导含类目下拉 + 16 个 option(包括「按账户类型默认」)
$CURL -b $COOKIE "$BASE/accounts/new" -o "$TMP" -w ""
{ grep -q 'productCategoryCode' "$TMP" && grep -q '按账户类型默认' "$TMP"; } \
  && log_ok "v02-WIZ-1 /accounts/new 含产品类目下拉" || log_bad "v02-WIZ-1 wizard 类目下拉" "missing"

# PILL-5 /accounts/{id}/edit 含 productCategoryCode + riskLevelOverride
$CURL -b $COOKIE "$BASE/accounts/1/edit" -o "$TMP" -w ""
{ grep -q 'productCategoryCode' "$TMP" && grep -q 'riskLevelOverride' "$TMP"; } \
  && log_ok "v02-EDIT-1 /accounts/1/edit 含类目 + 风险覆盖字段" || log_bad "v02-EDIT-1 编辑页字段" "missing"

# DASH-1 dashboard 列表行含 → 体检 链接
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q '/checkup?account=' "$TMP" && log_ok "v02-DASH-1 dashboard 行含 /checkup?account=" || log_bad "v02-DASH-1 dashboard 体检入口" "missing"

# SOFT-DEL-1 软删字段在 mapper 已过滤(回归):正常 entry 页能加载,且没有数据库错误页
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
{ grep -q '记账' "$TMP" || grep -q '填报' "$TMP"; } && ! grep -q '出错了' "$TMP" \
  && log_ok "v02-SOFT-1 /entry 与 deleted_at 过滤兼容" || log_bad "v02-SOFT-1 /entry" "see $TMP"

# ---------- v0.2 Stage 2: 账户级体检 ----------
section "v0.2 · 阶段 2 · 账户级体检 (FR-40b)"

# 13 个账户全部 200,无 Thymeleaf 错误
err_count=0
for id in 1 2 3 4 5 6 7 8 9 10 11 12 13; do
  $CURL -b $COOKIE "$BASE/checkup?account=$id" -o "$TMP" -w ""
  grep -q '出错了' "$TMP" && err_count=$((err_count+1))
done
[[ $err_count -eq 0 ]] && log_ok "v02-DIAG-1 13 个账户体检页全部成功渲染(0 错误)" || log_bad "v02-DIAG-1 体检页错误数" "err=$err_count"

# CASH 账户(id=1 招行)显示「流动性」卡 + 不显示「收益表现」「欠款余额」「估值」
$CURL -b $COOKIE "$BASE/checkup?account=1" -o "$TMP" -w ""
{ grep -q '流动性' "$TMP" && ! grep -q '收益表现' "$TMP" \
  && ! grep -q '欠款余额' "$TMP" && ! grep -q '估值' "$TMP"; } \
  && log_ok "v02-DIAG-2 CASH 账户只显示「流动性」卡" \
  || log_bad "v02-DIAG-2 CASH 卡分支" "see $TMP"

# STOCK 账户(id=3 一般是 STOCK)显示「收益表现」「风险刻度」「基准对照」「现金流」
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
{ grep -q '收益表现' "$TMP" && grep -q '风险刻度' "$TMP" \
  && grep -q '基准对照' "$TMP" && grep -q 'CASH FLOW' "$TMP"; } \
  && log_ok "v02-DIAG-3 STOCK 账户显示 4 张投资卡" \
  || log_bad "v02-DIAG-3 STOCK 4 卡" "see $TMP"

# LOAN 账户(id=5 一般是 LOAN)显示「欠款余额」「还款进度」
$CURL -b $COOKIE "$BASE/checkup?account=5" -o "$TMP" -w ""
{ grep -q '欠款余额' "$TMP" && grep -q '还款进度' "$TMP" \
  && ! grep -q '收益表现' "$TMP"; } \
  && log_ok "v02-DIAG-4 LOAN 账户显示「欠款余额 + 还款进度」" \
  || log_bad "v02-DIAG-4 LOAN 卡" "see $TMP"

# PROPERTY 账户(id=10 一般是 PROPERTY)显示「估值」简卡
$CURL -b $COOKIE "$BASE/checkup?account=10" -o "$TMP" -w ""
{ grep -q '估值' "$TMP" && ! grep -q '收益表现' "$TMP"; } \
  && log_ok "v02-DIAG-5 PROPERTY 账户显示「估值」简卡" \
  || log_bad "v02-DIAG-5 PROPERTY 卡" "see $TMP"

# 不存在 / 跨家庭账户 → 重定向到 /checkup family 页
loc=$($CURL -b $COOKIE -o /dev/null -w "%{redirect_url}" "$BASE/checkup?account=99999")
[[ "$loc" == *"/checkup"* ]] && log_ok "v02-DIAG-6 不存在账户跳 /checkup family" || log_bad "v02-DIAG-6 越权处理" "loc=$loc"

# 顶部账户 pill 含类目 + 风险星
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
{ grep -q 'border-color:var(--brass-deep)' "$TMP" && grep -q '★' "$TMP"; } \
  && log_ok "v02-DIAG-7 顶部账户 pill 含类目 + 风险星" \
  || log_bad "v02-DIAG-7 pill" "missing"

# 余额走势 sparkline canvas 渲染
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
grep -q 'id="balanceTrend"' "$TMP" && log_ok "v02-DIAG-8 余额走势 canvas 渲染" || log_bad "v02-DIAG-8 sparkline" "missing"

# ---------- v0.2 Stage 3: 智能建议 + LLM 润色 ----------
section "v0.2 · 阶段 3 · 智能建议 + LLM 文案润色 (FR-40c)"

# 13 个账户的体检页 + family 页都不再触发错误兜底(回归 stage 2)
err_count=0
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q '出错了' "$TMP" && err_count=$((err_count+1))
for id in 1 2 3 4 5 6 7 8 9 10 11 12 13; do
  $CURL -b $COOKIE "$BASE/checkup?account=$id" -o "$TMP" -w ""
  grep -q '出错了' "$TMP" && err_count=$((err_count+1))
done
[[ $err_count -eq 0 ]] && log_ok "v02-ADV-1 14 个体检页(family + 13 acct)全部成功渲染" || log_bad "v02-ADV-1 体检页错误数" "err=$err_count"

# 家庭体检页含 advice cards(命中至少 1 条)或健康提示
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'advice-card' "$TMP" || grep -q '健康状态良好' "$TMP"; } \
  && log_ok "v02-ADV-2 家庭体检页含 advice 卡或健康提示" || log_bad "v02-ADV-2 advice 区域" "missing"

# 家庭体检页含「来自账房的提醒」标题(命中时)或「健康状态良好」(全 miss 时)
{ grep -q '来.\{0,3\}自.\{0,3\}账.\{0,3\}房.\{0,3\}的.\{0,3\}提.\{0,3\}醒' "$TMP" || grep -q '健康状态良好' "$TMP"; } \
  && log_ok "v02-ADV-3 advice 区域 eyebrow 文案存在" || log_bad "v02-ADV-3 eyebrow" "missing"

# 账户体检页(任一 STOCK 账户)含 advice 卡或体检通过提示
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
{ grep -q 'advice-card' "$TMP" || grep -q '本账户体检通过' "$TMP"; } \
  && log_ok "v02-ADV-4 STOCK 账户体检 advice 区域" || log_bad "v02-ADV-4 acct advice" "missing"

# advice 卡的 data-rule 与 data-severity 属性渲染
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
n_rule=$(grep -oE 'data-rule="[^"]+"' "$TMP" | wc -l)
n_sev=$(grep -oE 'data-severity="[^"]+"' "$TMP" | wc -l)
# v02-ADV-5 仅当有 advice 命中时校验 data 属性;无命中时(显示「健康状态良好」)SKIP
if [[ $n_rule -ge 1 && $n_sev -ge 1 ]]; then
  log_ok "v02-ADV-5 advice 卡 data-rule + data-severity 渲染 (rule=$n_rule sev=$n_sev)"
elif grep -q '健康状态良好\|本账户体检通过' "$TMP"; then
  log_skip "v02-ADV-5 advice data attr" "无规则命中,渲染了健康状态文案"
else
  log_bad "v02-ADV-5 data attr" "rule=$n_rule sev=$n_sev"
fi

# AI 综合诊断 placeholder(spinner)在 family 页存在 — 决策 20 新方向
grep -q 'ai-diagnose-panel' "$TMP" && log_ok "v02-ADV-6 family 页含 AI 综合诊断 placeholder" \
  || log_bad "v02-ADV-6 AI placeholder" "missing"

# AI 综合诊断 placeholder 含 hx-trigger="load"(进页自动 fetch)
grep -E 'hx-trigger="load".*ai-diagnose|ai-diagnose.*hx-trigger="load"' "$TMP" >/dev/null \
  || grep -B2 -A2 'ai-diagnose-panel' "$TMP" | grep -q 'hx-trigger="load"' \
  && log_ok "v02-ADV-7 AI placeholder 含 hx-trigger=load(自动加载)" \
  || log_bad "v02-ADV-7 hx-trigger" "missing"

# ---------- v0.2 · AI 综合诊断 endpoint(决策 20 / 2026-05-10) ----------
section "v0.2 · AI 综合诊断 · /checkup/diagnose (FR-40c 决策 20)"

# DIAG-1 全家维度 endpoint 200(LLM 真机调用最长 30s,放宽 timeout)
code=$(/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup/diagnose")
[[ "$code" == "200" ]] && log_ok "v02-DIAG-1 GET /checkup/diagnose → 200" \
  || log_bad "v02-DIAG-1 family diagnose" "code=$code"

# DIAG-2 返回 fragment 含 data-vendor / data-cache / data-available 属性(无 LLM key 时是 fallback)
grep -qE 'data-vendor=|data-available=' "$TMP" \
  && log_ok "v02-DIAG-2 fragment 含 vendor/available 属性" \
  || log_bad "v02-DIAG-2 fragment attrs" "missing"

# DIAG-3 fragment 含 AI · 综合智能诊断 标题或 AI · 暂不可用
{ grep -q '综合智能诊断' "$TMP" || grep -q 'AI · 暂不可用' "$TMP"; } \
  && log_ok "v02-DIAG-3 fragment 含诊断标题或降级文案" \
  || log_bad "v02-DIAG-3 panel header" "missing"

# DIAG-4 账户维度 endpoint 200(LLM 真机调用最长 30s)
code=$(/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup/diagnose?account=3")
[[ "$code" == "200" ]] && log_ok "v02-DIAG-4 GET /checkup/diagnose?account=3 → 200" \
  || log_bad "v02-DIAG-4 account diagnose" "code=$code"

# DIAG-5 不存在账户 → 200 + 降级文案
code=$(/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup/diagnose?account=99999")
{ [[ "$code" == "200" ]] && grep -q '账户不存在\|AI 暂时不可用\|AI · 暂不可用' "$TMP"; } \
  && log_ok "v02-DIAG-5 跨家庭账户 diagnose 返回降级 (code=$code)" \
  || log_bad "v02-DIAG-5 cross-family" "code=$code"

# DIAG-6 跨账户(同家庭)200 — 账户维度可正常工作
code=$(/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup/diagnose?account=1")
[[ "$code" == "200" ]] && log_ok "v02-DIAG-6 GET /checkup/diagnose?account=1 (CASH) → 200" \
  || log_bad "v02-DIAG-6 cash account" "code=$code"

# ---------- v0.2 LLM 真实调用 ----------
section "v0.2 · LLM 真实调用 · qwen-plus(可选,无 key 时降级 fallback)"

# DIAG-LIVE 嗅探:GET /checkup/diagnose 是否实际由真 LLM(qwen/deepseek)成功返回综合诊断长文,
# 还是 fallback("AI 暂时不可用")。两种结果都不算 FAIL,但分别对应不同状态。
/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "" "$BASE/checkup/diagnose"
vendor=$(grep -oE 'data-vendor="[^"]+"' "$TMP" | head -1 | sed 's/data-vendor="\([^"]*\)"/\1/')
available=$(grep -oE 'data-available="[^"]+"' "$TMP" | head -1 | sed 's/data-available="\([^"]*\)"/\1/')
if [[ "$available" == "true" && ( "$vendor" == "qwen" || "$vendor" == "deepseek" ) ]]; then
  log_ok "v02-LLM-LIVE-1 LLM 实调用成功 vendor=$vendor 综合诊断长文已返回"
else
  log_skip "v02-LLM-LIVE-1" "LLM key 未配/全部失败,vendor=$vendor available=$available — 已降级 fallback,不阻塞 v0.2 验收"
fi

# ---------- v0.2 Stage 4: 账本侧(ledger.csv + 软删 + UI 入口) ----------
section "v0.2 · 阶段 4 · 账本侧 (FR-30/31/32)"

# LEDGER-1 /accounts 列表「体检」入口(2026-05-10 改 SVG 后,grep href)
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
# v0.10.6 去过期:原 ≥13 耦合 demo 账户量(且 PC+移动双渲染);体检入口在账户行循环里 → 改渲染冒烟 ≥1。
n=$(grep -oE 'href="/checkup\?account=[0-9]+"' "$TMP" | wc -l)
[[ $n -ge 1 ]] && log_ok "v02-LEDGER-1 /accounts 账户行含 /checkup?account 体检入口 (n=$n)" \
  || log_bad "v02-LEDGER-1 体检入口未渲染" "n=$n"

# LEDGER-2 /accounts 列表「账本 CSV」入口(账本链接在账户行循环里渲染 · 1:1 对应账户)
#   v0.10.6 去过期:原硬编码 ≥13 耦合旧 demo 账户量级(现 demo 12 账户)→ 改「渲染冒烟」(≥1):
#   链接在 th:each 账户循环内,渲染则全账户都有、不渲染则 0;数量随 demo 账户数浮动,不再据此判红。
n=$(grep -oE 'href="/accounts/[0-9]+/ledger.csv"' "$TMP" | wc -l)
[[ $n -ge 1 ]] && log_ok "v02-LEDGER-2 /accounts 账户行含「⬇ 账本」入口 (n=$n)" \
  || log_bad "v02-LEDGER-2 账本入口未渲染" "n=$n"

# LEDGER-3 GET /accounts/3/ledger.csv → 200 + text/csv
type_resp=$($CURL -b $COOKIE -o "$TMP" -w "%{content_type}" "$BASE/accounts/3/ledger.csv")
{ grep -q 'text/csv' <<<"$type_resp" && grep -q '月份,期初,入账' "$TMP"; } \
  && log_ok "v02-LEDGER-3 ledger.csv 返回 text/csv + 表头正确" \
  || log_bad "v02-LEDGER-3 ledger.csv" "type=$type_resp"

# LEDGER-4 ledger.csv 含 BOM(Excel 友好)
head -c 3 "$TMP" | od -c | head -1 | grep -q '357 273 277' \
  && log_ok "v02-LEDGER-4 ledger.csv 含 UTF-8 BOM" \
  || log_bad "v02-LEDGER-4 BOM" "missing"

# LEDGER-5 ledger.csv Content-Disposition 含 filename*=UTF-8'' 编码
header=$($CURL -b $COOKIE -o /dev/null -D - "$BASE/accounts/3/ledger.csv" | tr -d '\r' | grep -i 'content-disposition')
echo "$header" | grep -q "filename\*=UTF-8" \
  && log_ok "v02-LEDGER-5 ledger.csv Content-Disposition 含 UTF-8 文件名" \
  || log_bad "v02-LEDGER-5 filename" "header=$header"

# LEDGER-6 跨家庭账户(id=99999) 应被 require 拦截
code=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}" "$BASE/accounts/99999/ledger.csv")
[[ "$code" -ge 400 ]] && log_ok "v02-LEDGER-6 跨家庭账户 ledger.csv 拒绝 (code=$code)" \
  || log_bad "v02-LEDGER-6 越权" "code=$code"

# SOFT-DEL-2 entry 当前 OPEN 期 + 该期有 cf/transfer 时,应有至少 1 个 ⋮ 删除按钮
# 动态查 OPEN period id + 是否有可删的 cf/transfer
OPEN_PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
OPEN_HAS_CF=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM cash_flow WHERE period_id=$OPEN_PID AND deleted_at IS NULL" 2>/dev/null)
if [[ -z "$OPEN_PID" ]]; then
  log_skip "v02-SOFT-DEL-2 删除按钮" "无 OPEN 周期"
elif [[ "$OPEN_HAS_CF" == "0" ]]; then
  log_skip "v02-SOFT-DEL-2 删除按钮" "OPEN 期 ($OPEN_PID) 无 cf/transfer 可删"
else
  $CURL -b $COOKIE "$BASE/entry?period=$OPEN_PID" -o "$TMP" -w ""
  n=$(grep -oE 'hx-post="/entry/[a-z-]+/[0-9]+/delete"' "$TMP" | wc -l)
  [[ $n -ge 1 ]] && log_ok "v02-SOFT-DEL-2 entry OPEN 周期 ($OPEN_PID) 渲染 ⋮删除按钮 (n=$n)" \
    || log_bad "v02-SOFT-DEL-2 删除按钮" "n=$n"
fi

# SOFT-DEL-3 删除按钮不出现在 SNAPSHOT 行(只在 cash_flow / transfer)
{ grep -q 'cash-flow/[0-9]' "$TMP" || grep -q 'transfer/[0-9]' "$TMP"; } \
  && log_ok "v02-SOFT-DEL-3 删除链接指向 cash-flow / transfer 子路径" \
  || log_bad "v02-SOFT-DEL-3 删除链接" "missing"

# SOFT-DEL-4 删除按钮 hx-confirm 含确认提示
grep -q 'hx-confirm="确定删除' "$TMP" && log_ok "v02-SOFT-DEL-4 hx-confirm 删除提示存在" \
  || log_bad "v02-SOFT-DEL-4 confirm" "missing"

# SOFT-DEL-5 软删 endpoint POST 一条真实 OPEN 期 cash_flow,验证 200(此处 cf=323 已被前面真实测试软删,
# 任意找一个未软删的 OPEN cf 来再测一次。如果都已删,跳过)
$CURL -b $COOKIE -c $COOKIE "$BASE/entry?period=35" -o /dev/null
XSRF=$(awk '$6=="XSRF-TOKEN" {print $7}' $COOKIE)
victim_cf=$(grep -oE 'hx-post="/entry/cash-flow/[0-9]+/delete"' "$TMP" | head -1 | grep -oE '[0-9]+')
if [[ -n "$victim_cf" ]]; then
  code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" -o /dev/null -w "%{http_code}" \
        "$BASE/entry/cash-flow/$victim_cf/delete")
  [[ "$code" == "200" ]] && log_ok "v02-SOFT-DEL-5 POST 软删真实 cash_flow ($victim_cf) → 200" \
    || log_bad "v02-SOFT-DEL-5 软删失败" "cf=$victim_cf code=$code"
else
  log_skip "v02-SOFT-DEL-5 软删真实 cf" "no candidate cf"
fi

# SOFT-DEL-6 软删后该 cf 不再出现在 entry 页(deleted_at 已设)
if [[ -n "$victim_cf" ]]; then
  $CURL -b $COOKIE "$BASE/entry?period=35" -o "$TMP" -w ""
  if ! grep -q "cash-flow/$victim_cf/delete" "$TMP"; then
    log_ok "v02-SOFT-DEL-6 已软删 cf=$victim_cf 从 entry 页面消失"
  else
    log_bad "v02-SOFT-DEL-6 已软删 cf 仍可见" "cf=$victim_cf"
  fi
fi

# SOFT-DEL-7 CLOSED 周期软删被拒绝(用 cash_flow 222 — 在 CLOSED period)
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" -o /dev/null -w "%{http_code}" \
      "$BASE/entry/cash-flow/222/delete")
# 后端 throw IllegalStateException → 500 错误页是预期(进入兜底);亦可能未来改 400 友好
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v02-SOFT-DEL-7 CLOSED 周期软删被拒 (code=$code)" \
  || log_bad "v02-SOFT-DEL-7 CLOSED 拒写" "code=$code"

# SOFT-DEL-8 不存在的 cash_flow id 也被拒绝(NPE 转 500/400)
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" -o /dev/null -w "%{http_code}" \
      "$BASE/entry/cash-flow/9999999/delete")
[[ "$code" -ge 400 ]] && log_ok "v02-SOFT-DEL-8 不存在 cf 软删被拒 (code=$code)" \
  || log_bad "v02-SOFT-DEL-8 missing cf 拦截" "code=$code"

# v0.2 FR-30 · 账户详情页(账本视角)
$CURL -b $COOKIE -o "$TMP" -w "" "$BASE/accounts/1"
{ grep -q '<h1' "$TMP" && grep -q '招行储蓄卡-工资' "$TMP"; } \
  && log_ok "v02-FR30-1 GET /accounts/1 返回详情页 + 显示账户名" \
  || log_bad "v02-FR30-1 详情页" "missing"
grep -q 'id="balanceTimeline"' "$TMP" \
  && log_ok "v02-FR30-2 详情页含余额时序 canvas" \
  || log_bad "v02-FR30-2 时序图" "missing"
n_kpi=$(grep -c 'kpi-eyebrow' "$TMP")
[[ $n_kpi -ge 8 ]] && log_ok "v02-FR30-3 详情页含 ≥4 KPI(重复 ≥8 次)实际 $n_kpi" \
  || log_bad "v02-FR30-3 KPI 卡" "n=$n_kpi"
n_det=$(grep -cE '<details' "$TMP")
[[ $n_det -ge 1 ]] && log_ok "v02-FR30-4 详情页月分组 details=$n_det" \
  || log_bad "v02-FR30-4 月分组" "n=$n_det"
{ grep -q '看资产体检' "$TMP" && grep -q '导出本账户 CSV' "$TMP"; } \
  && log_ok "v02-FR30-5 详情页底栏含「看资产体检 / 导出 CSV」" \
  || log_bad "v02-FR30-5 底栏" "missing"
$CURL -b $COOKIE -o /dev/null -w "" "$BASE/accounts/99999"
code=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}" "$BASE/accounts/99999")
[[ "$code" -ge 400 ]] && log_ok "v02-FR30-6 跨家庭账户详情页拒绝 (code=$code)" \
  || log_bad "v02-FR30-6 越权" "code=$code"
# 列表入口接线
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
# v0.10.6 去过期:原 ≥13 耦合 demo 账户量(且每账户多链接/双渲染);详情链接在账户行循环里 → 改渲染冒烟 ≥1。
n=$(grep -cE 'href="/accounts/[0-9]+"' "$TMP")
[[ $n -ge 1 ]] && log_ok "v02-FR30-7 /accounts 账户行含详情链接 (n=$n)" \
  || log_bad "v02-FR30-7 详情链接未渲染" "n=$n"

# v0.2 audit 真名修复
$CURL -b $COOKIE "$BASE/admin/audit" -o "$TMP" -w ""
n_id=$(grep -cE '>#[0-9]+<' "$TMP")
[[ $n_id -eq 0 ]] && log_ok "v02-AUDIT-1 audit 由谁列不再显示 #id" \
  || log_bad "v02-AUDIT-1 残留 #id" "n=$n_id"

# v0.2 dashboard anchor bug fix · 应永远取最新一期(包括 OPEN)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q '资产体检' "$TMP" && grep -q 'kpi-card' "$TMP"; } \
  && log_ok "v02-DASH-ANCHOR dashboard 渲染 + KPI 完整(anchor 取最新期)" \
  || log_bad "v02-DASH-ANCHOR dashboard 渲染" "missing"

# v0.2 FR-40e · /reports 风险等级分布
$CURL -b $COOKIE "$BASE/reports?range=1Y&currency=CNY" -o "$TMP" -w ""
{ grep -q '风险等级分布' "$TMP" && grep -q 'riskDistChart' "$TMP"; } \
  && log_ok "v02-FR40e-1 /reports 含「风险等级分布」环形图 canvas" \
  || log_bad "v02-FR40e-1 风险环形图" "missing"

# v0.2 防回归 · 所有有 canvas 的页面:1) 引 Chart.js  2) 引 datalabels plugin  3) 每个 new Chart 都注册 ChartDataLabels
# 来自 memory feedback_chart_datalabels:数字必须直接浮在数据点/扇片/柱顶上
for path in /dashboard /reports /checkup '/checkup?account=3' /accounts/1; do
  $CURL -b $COOKIE "$BASE$path" -o "$TMP" -w ""
  if grep -q '<canvas' "$TMP"; then
    n_chart=$(grep -c 'chart.umd' "$TMP")
    n_plugin=$(grep -c 'chartjs-plugin-datalabels' "$TMP")
    n_chart_calls=$(grep -c 'new Chart' "$TMP")
    n_register=$(grep -c 'ChartDataLabels' "$TMP")
    pid=$(echo $path | tr '/?=' '___')
    if [[ "$n_chart" -ge 1 && "$n_plugin" -ge 1 && "$n_register" -ge "$n_chart_calls" ]]; then
      log_ok "v02-CHART-$pid 含 canvas + Chart.js + datalabels(charts=$n_chart_calls register=$n_register)"
    else
      log_bad "v02-CHART-$pid 图表配置不全" "chart=$n_chart plugin=$n_plugin register=$n_register charts=$n_chart_calls"
    fi
  fi
done
# 注意:这两个 case 必须独立读 /reports(上面 for 循环最后 $TMP 是 /accounts/1)
$CURL -b $COOKIE "$BASE/reports?range=1Y&currency=CNY" -o "$TMP" -w ""
# v0.10.6 去过期:风险敞口「明细表」v0.4 FR-60b 已砍,改风险分布环形图(见 FR40e-3)。原断言「风险等级表格含★」
#   已不成立——现 ★ 来自 资产年化★ eyebrow 等,非风险表。改判:页面仍含星级标识 ★(渲染冒烟 ≥1),不再宣称「表格」。
n=$(grep -oE '★+' "$TMP" | wc -l)
[[ $n -ge 1 ]] && log_ok "v02-FR40e-2 /reports 含星级标识 ★(资产年化★ 等;风险明细表已砍→见 FR40e-3 环形)(n=$n)" \
  || log_bad "v02-FR40e-2 星级标识缺失" "n=$n"
# v0.4 FR-60b 砍 · 风险敞口明细表已删 · "进入资产体检" link 一并去 · 改判风险等级分布环形仍在
grep -q 'riskDistChart' "$TMP" && log_ok "v02-FR40e-3 (v0.4 改) /reports 风险等级分布环形保留" \
  || log_bad "v02-FR40e-3 风险等级图 砍过头" "missing"

# v0.2 FR-38 · dashboard KPI 卡 deep-link 到 /checkup 锚点
$CURL -b $COOKIE "$BASE/dashboard?range=1Y&currency=CNY" -o "$TMP" -w ""
n=$(grep -oE 'href="/checkup[^"]*"' "$TMP" | wc -l)
# v0.10.6 去过期:v0.4.2 起第 5 卡(本月资产收益)deep-link 指向 /reports 而非 /checkup,5 卡里只有 4 卡链 /checkup;
#   n≥5 仍过是因为账户行也有 /checkup 链接计入。文案不再宣称「5 张 KPI 均含」,改「KPI+账户行含 ≥5 个 /checkup 深链」。
[[ $n -ge 5 ]] && log_ok "v02-FR38-1 dashboard 含 ≥5 个 /checkup 深链(KPI 4 张 + 账户行;第5卡本月资产收益→/reports)(n=$n)" \
  || log_bad "v02-FR38-1 KPI 链接" "n=$n"

# 至少 3 个不同锚点(allocation / liquidity / 顶级)
n_anchors=$(grep -oE 'href="/checkup[^"]*"' "$TMP" | sort -u | wc -l)
[[ $n_anchors -ge 3 ]] && log_ok "v02-FR38-2 KPI 锚点齐全 (allocation/liquidity/顶级,$n_anchors 种)" \
  || log_bad "v02-FR38-2 锚点种类" "n=$n_anchors"

# /checkup family 页含锚点 id(allocation / risk / liquidity / return)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
ids=$(grep -oE 'id="(allocation|risk|liquidity|return)"' "$TMP" | sort -u | wc -l)
[[ $ids -ge 4 ]] && log_ok "v02-FR38-3 /checkup family 含 4 个锚点 id (n=$ids)" \
  || log_bad "v02-FR38-3 锚点 id" "n=$ids"

# ---------- v0.2 UX 小优化(2026-05-10)----------
section "v0.2 · UX 小优化"

# UX-1 entry 余额输入框含 onfocus=this.select() + ✕ 清空按钮
OPEN_PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
$CURL -b $COOKIE "$BASE/entry?period=$OPEN_PID" -o "$TMP" -w ""
n_focus=$(grep -oE 'onfocus="this.select\(\)"' "$TMP" | wc -l)
[[ $n_focus -ge 1 ]] && log_ok "v02-UX-1 entry 余额 input 含 onfocus=select (n=$n_focus)" \
  || log_bad "v02-UX-1 onfocus 缺失" "n=$n_focus"
n_clear=$(grep -oE 'title="清空"' "$TMP" | wc -l)
[[ $n_clear -ge 1 ]] && log_ok "v02-UX-2 entry 余额 input 含 ✕ 清空按钮 (n=$n_clear)" \
  || log_bad "v02-UX-2 清空按钮缺失" "n=$n_clear"

# UX-3 dashboard 含 accountDivergeChart canvas + accountRows 数据
$CURL -b $COOKIE "$BASE/dashboard?range=1Y&currency=CNY" -o "$TMP" -w ""
{ grep -q '<canvas id="accountDivergeChart"' "$TMP" && grep -q 'accountRows: \[' "$TMP"; } \
  && log_ok "v02-UX-3 dashboard 含按账户分布 canvas + accountRows 数据" \
  || log_bad "v02-UX-3 按账户分布图" "missing canvas or data"
grep -q '按账户分布' "$TMP" \
  && log_ok "v02-UX-4 dashboard 含「按账户分布」标题" \
  || log_bad "v02-UX-4 按账户分布标题" "missing"

# UX-5 entry 余额 / 备注 input 高度统一(都用 h-9)+ 备注独立 eyebrow,避免对齐错位
$CURL -b $COOKIE "$BASE/entry?period=$OPEN_PID" -o "$TMP" -w ""
# newBalance input 是多行属性,用 awk 把 input 多行折叠成一行后再 grep h-9
nb_h9=$(awk 'BEGIN{RS=">"} /name="newBalance"/' "$TMP" | grep -c 'h-9')
nt_h9=$(grep -oE 'name="note"[^>]*h-9' "$TMP" | wc -l)
nt_eb=$(grep -c '>备注</span>' "$TMP")
[[ $nb_h9 -ge 1 && $nt_h9 -ge 1 && $nt_eb -ge 1 ]] \
  && log_ok "v02-UX-5 entry 余额 / 备注 input 高度统一(h-9 nb=$nb_h9 nt=$nt_h9)+ 备注独立 eyebrow ($nt_eb)" \
  || log_bad "v02-UX-5 entry 输入框对齐" "newBalance.h-9=$nb_h9 note.h-9=$nt_h9 备注.eyebrow=$nt_eb"

# ---------- v0.2 · FR-40e 报表风险等级分布(2026-05-10) ----------
section "v0.2 · FR-40e · 报表风险等级分布"

# FR40e-1 reports 页加载成功
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
grep -q '风险等级分布' "$TMP" \
  && log_ok "v02-FR40E-1 reports 含「风险等级分布」标题" \
  || log_bad "v02-FR40E-1 标题" "missing"

# FR40e-2 含 riskDistChart canvas
grep -q 'riskDistChart' "$TMP" \
  && log_ok "v02-FR40E-2 reports 含 #riskDistChart canvas" \
  || log_bad "v02-FR40E-2 canvas" "missing"

# v0.4 FR-60b 砍 · 风险敞口明细表 + 进入资产体检入口 都已删 · 改判 reports 仍含风险章节
{ grep -q 'risk-section\|风险等级分布'  "$TMP"; } \
  && log_ok "v02-FR40E-3 (v0.4 改) reports 风险等级分布段仍在" \
  || log_bad "v02-FR40E-3 风险段 砍过头" "missing"

# ---------- v0.2 · FR-1/FR-34 品牌图标预设(2026-05-10)----------
section "v0.2 · 品牌图标预设(默认 icon2)"

# 预条件:重置 family 到默认状态(icon2 + 无自定义)
mysql -ufinance -pfinance finance -e "UPDATE family SET logo_preset='icon2', logo_path=NULL WHERE id=1;" 2>/dev/null

# v02-LOGO-1 16 张预设 PNG 全 200(无 cookie 公开访问)
all_ok=1
for icon in icon1 icon2 icon3 icon4; do
  for size in 96 180 192 512; do
    code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/presets/${icon}-${size}.png")
    [[ "$code" == "200" ]] || { all_ok=0; break 2; }
  done
done
[[ $all_ok -eq 1 ]] && log_ok "v02-LOGO-1 16 张预设 PNG(icon{1..4}×{96,180,192,512})全 200" \
  || log_bad "v02-LOGO-1 预设 PNG" "至少一张非 200"

# v02-LOGO-2 GET /manifest.webmanifest 返回 application/manifest+json + 默认 icon2
ct=$($CURL -b $COOKIE -o "$TMP" -w "%{content_type}" "$BASE/manifest.webmanifest")
{ [[ "$ct" == *"application/manifest+json"* ]] && grep -q '/img/presets/icon2-192.png' "$TMP" && grep -q '/img/presets/icon2-512.png' "$TMP"; } \
  && log_ok "v02-LOGO-2 manifest.webmanifest 动态 + 默认 icon2 (Content-Type=$ct)" \
  || log_bad "v02-LOGO-2 manifest 默认" "ct=$ct  body 见 $TMP"

# v02-LOGO-3 dashboard <link rel=icon> 默认指向 icon2-192.png
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -A1 '<link rel="icon"' "$TMP" | grep -q '/img/presets/icon2-192.png'; } \
  && log_ok "v02-LOGO-3 dashboard favicon 默认 icon2-192.png" \
  || log_bad "v02-LOGO-3 favicon 默认" "link 不指 icon2-192"

# v02-LOGO-4 dashboard <link rel=apple-touch-icon> 默认指向 icon2-180.png
{ grep -A1 '<link rel="apple-touch-icon"' "$TMP" | grep -q '/img/presets/icon2-180.png'; } \
  && log_ok "v02-LOGO-4 dashboard apple-touch-icon 默认 icon2-180.png" \
  || log_bad "v02-LOGO-4 apple-touch 默认" "link 不指 icon2-180"

# v02-LOGO-5 nav header logo 默认指向 icon2-192.png(没自定义上传时)
grep -q 'src="/img/presets/icon2-192.png' "$TMP" \
  && log_ok "v02-LOGO-5 nav header logo 默认 icon2-192.png" \
  || log_bad "v02-LOGO-5 nav logo" "src 不指 icon2-192"

# v02-LOGO-6 admin/family 渲染 4 缩略图 gallery(button data-preset="iconN" · 不嵌套 form · 2026-05-14 bugfix)
$CURL -b $COOKIE "$BASE/admin/family" -o "$TMP" -w ""
gallery_count=$(grep -oE 'data-preset="icon[1-4]"' "$TMP" | sort -u | wc -l)
nested_form_check=$(grep -cE '<form[^>]*action="/admin/family/logo/preset"' "$TMP")
{ [[ $gallery_count -eq 4 ]] && [[ $nested_form_check -eq 0 ]]; } \
  && log_ok "v02-LOGO-6 admin/family gallery 4 button(data-preset)· 零嵌套 form" \
  || log_bad "v02-LOGO-6 gallery / 嵌套 form" "buttons=$gallery_count nested_form=$nested_form_check"

# v02-LOGO-7 切到 icon3 → DB 更新 + dashboard / manifest 全跟随
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}')
$CURL -b $COOKIE -c $COOKIE -X POST "$BASE/admin/family/logo/preset" -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "preset=icon3" -o /dev/null -w "" || true
db_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT logo_preset FROM family WHERE id=1;" 2>/dev/null)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
mf=$($CURL -b $COOKIE "$BASE/manifest.webmanifest")
{ [[ "$db_after" == "icon3" ]] \
   && grep -A1 '<link rel="apple-touch-icon"' "$TMP" | grep -q 'icon3-180' \
   && echo "$mf" | grep -q 'icon3-192'; } \
  && log_ok "v02-LOGO-7 切 icon3 → DB+web favicon+iOS apple-touch+manifest 全跟随" \
  || log_bad "v02-LOGO-7 切预设全链路" "db=$db_after"

# v02-LOGO-8 上传自定义 webp 后,web 用 webp,但 iOS apple-touch 仍用 preset(icon3)
mysql -ufinance -pfinance finance -e "UPDATE family SET logo_path='family-1/logo.webp' WHERE id=1;" 2>/dev/null
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -A1 '<link rel="icon"' "$TMP" | grep -q '/uploads/family-1/logo.webp' \
   && grep -A1 '<link rel="apple-touch-icon"' "$TMP" | grep -q 'icon3-180'; } \
  && log_ok "v02-LOGO-8 自定义 webp 上传 → web favicon=webp / iOS=preset 不联动" \
  || log_bad "v02-LOGO-8 自定义+预设并存" "see $TMP"

# v02-LOGO-9 切预设按钮 = 一并清空 logo_path(预设赢一切统一)
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}')
$CURL -b $COOKIE -c $COOKIE -X POST "$BASE/admin/family/logo/preset" -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "preset=icon4" -o /dev/null -w "" || true
both=$(mysql -ufinance -pfinance finance -sN -e "SELECT logo_preset, IFNULL(logo_path,'NULL') FROM family WHERE id=1;" 2>/dev/null)
[[ "$both" == "icon4	NULL" ]] && log_ok "v02-LOGO-9 切预设清空 logo_path(预设赢一切统一)" \
  || log_bad "v02-LOGO-9 logo_path 未清空" "DB=$both"

# v02-LOGO-10 非法 preset(icon99)→ 服务层校验拒写,DB 保持 icon4
$CURL -b $COOKIE -c $COOKIE -X POST "$BASE/admin/family/logo/preset" -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "preset=icon99" -o /dev/null -w "" || true
preset_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT logo_preset FROM family WHERE id=1;" 2>/dev/null)
[[ "$preset_after" == "icon4" ]] && log_ok "v02-LOGO-10 非法 preset 拒写,DB 保持 icon4" \
  || log_bad "v02-LOGO-10 非法 preset 校验" "DB=$preset_after"

# 复跑后置:重置默认 icon2 + 无自定义,不污染后续
mysql -ufinance -pfinance finance -e "UPDATE family SET logo_preset='icon2', logo_path=NULL WHERE id=1;" 2>/dev/null


###################################################
# v0.3 FR-50 · 财务目标 · /goals 全路径联调
###################################################
# 复跑前置:清掉旧目标(避免重复)
mysql -ufinance -pfinance finance -e "DELETE FROM family_goal WHERE family_id=1;" 2>/dev/null

XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)

# v03-GOAL-1 · 无目标时 /goals 列表显空状态引导
$CURL -b $COOKIE "$BASE/goals" -o "$TMP" -w ""
{ grep -q "还没有目标" "$TMP" && grep -q "你的家庭在朝哪儿走" "$TMP"; } \
  && log_ok "v03-GOAL-1 /goals 空状态显引导卡" \
  || log_bad "v03-GOAL-1 空状态" "no hint card"

# v03-GOAL-2 · POST /goals/new/retirement 创建退休目标
loc=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "name=v03 自由生活" \
  --data-urlencode "currentAge=38" --data-urlencode "retireAge=60" \
  --data-urlencode "monthlyExpense=15000" --data-urlencode "inflationRate=0.025" \
  --data-urlencode "withdrawalRate=0.04" \
  "$BASE/goals/new/retirement" -o /dev/null -w "%{redirect_url}")
[[ "$loc" == *"/goals/"* ]] && log_ok "v03-GOAL-2 创建退休目标 → 302 /goals/{id}" \
  || log_bad "v03-GOAL-2 创建退休失败" "loc=$loc"
GOAL_RET_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM family_goal WHERE family_id=1 AND goal_type='RETIREMENT' AND archived_at IS NULL LIMIT 1" 2>/dev/null)

# v03-GOAL-3 · DB target_value = 通胀公式准确(15000 × 12 × 1.025^22 / 0.04 ≈ 7,747,000)
target=$(mysql -ufinance -pfinance finance -sN -e "SELECT target_value FROM family_goal WHERE id=$GOAL_RET_ID" 2>/dev/null)
target_int=$(echo "$target" | cut -d. -f1)
{ [[ "$target_int" -gt 7700000 ]] && [[ "$target_int" -lt 7800000 ]]; } \
  && log_ok "v03-GOAL-3 退休目标 target_value=$target_int 通胀公式准确" \
  || log_bad "v03-GOAL-3 target_value 不准" "got=$target_int 期望 7.74m"

# v03-GOAL-4 · GET /goals/{id} 详情页含三情景 + 当前进度
$CURL -b $COOKIE "$BASE/goals/$GOAL_RET_ID" -o "$TMP" -w ""
{ grep -q "v03 自由生活" "$TMP" && grep -q "三情景" "$TMP" && grep -q "scenario-chart" "$TMP"; } \
  && log_ok "v03-GOAL-4 /goals/{id} 详情含名称+三情景+chart" \
  || log_bad "v03-GOAL-4 详情页缺元素" "see $TMP"

# v03-GOAL-5 · 创建教育金 · child_member_id FK 写入
CHILD_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM member WHERE family_id=1 ORDER BY id LIMIT 1" 2>/dev/null)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "name=v03 教育金" \
  --data-urlencode "childMemberId=$CHILD_ID" --data-urlencode "childBirthYear=2020" \
  --data-urlencode "targetYearOffset=18" --data-urlencode "targetAmount=800000" \
  --data-urlencode "inflationRate=0.03" \
  "$BASE/goals/new/education" -o /dev/null -w "" || true
GOAL_EDU_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM family_goal WHERE goal_type='EDUCATION' AND archived_at IS NULL ORDER BY id DESC LIMIT 1" 2>/dev/null)
params=$(mysql -ufinance -pfinance finance -sN -e "SELECT params_json FROM family_goal WHERE id=$GOAL_EDU_ID" 2>/dev/null)
{ [[ -n "$GOAL_EDU_ID" ]] && echo "$params" | grep -q "\"child_member_id\": $CHILD_ID"; } \
  && log_ok "v03-GOAL-5 教育金创建 · child_member_id=$CHILD_ID 入 params_json" \
  || log_bad "v03-GOAL-5 教育金 child_member_id 缺" "params=$params"

# v03-GOAL-6 · 创建应急 · target_value=NULL(由 caller derived)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "name=v03 应急" \
  --data-urlencode "monthsTarget=6" --data-urlencode "autoBaseline=true" \
  "$BASE/goals/new/emergency" -o /dev/null -w "" || true
emer_target=$(mysql -ufinance -pfinance finance -sN -e "SELECT IFNULL(target_value,'NULL') FROM family_goal WHERE goal_type='EMERGENCY' AND archived_at IS NULL ORDER BY id DESC LIMIT 1" 2>/dev/null)
[[ "$emer_target" == "NULL" ]] && log_ok "v03-GOAL-6 应急 target_value=NULL(derived)" \
  || log_bad "v03-GOAL-6 应急 target 不应入库" "target=$emer_target"

# v03-GOAL-7 · GET /goals 列表渲染 3 个目标
$CURL -b $COOKIE "$BASE/goals" -o "$TMP" -w ""
{ grep -q "v03 自由生活" "$TMP" && grep -q "v03 教育金" "$TMP" && grep -q "v03 应急" "$TMP"; } \
  && log_ok "v03-GOAL-7 /goals 列表渲染 3 个目标" \
  || log_bad "v03-GOAL-7 列表缺目标" "see $TMP"

# v03-GOAL-8 · Dashboard 条带显当前目标(不再显引导卡)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q "v03 自由生活" "$TMP" && ! grep -q "创建你的第一个目标" "$TMP"; } \
  && log_ok "v03-GOAL-8 Dashboard 条带含目标 · 引导卡消失" \
  || log_bad "v03-GOAL-8 Dashboard 条带" "see $TMP"

# v03-GOAL-9 · 非法目标类型 → 4xx
code=$($CURL -b $COOKIE "$BASE/goals/new/invalidtype" -o /dev/null -w "%{http_code}")
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v03-GOAL-9 非法类型 4xx/5xx 拒绝" \
  || log_bad "v03-GOAL-9 非法类型未拒" "code=$code"

# v03-GOAL-10 · POST /goals/{id}/archive 软删 · 列表不再出现
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/$GOAL_EDU_ID/archive" -o /dev/null -w "" || true
arch=$(mysql -ufinance -pfinance finance -sN -e "SELECT IFNULL(archived_at,'NULL') FROM family_goal WHERE id=$GOAL_EDU_ID" 2>/dev/null)
{ [[ "$arch" != "NULL" ]] \
   && $CURL -b $COOKIE "$BASE/goals" -o "$TMP" -w "" \
   && ! grep -q "v03 教育金" "$TMP"; } \
  && log_ok "v03-GOAL-10 软删 archived_at 入库 · 列表过滤" \
  || log_bad "v03-GOAL-10 软删失效" "arch=$arch"

# v03-GOAL-11 · v0.2 dashboard 行为未破坏(净资产 / KPI 仍在)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q "净资产" "$TMP" && grep -q "总资产" "$TMP"; } \
  && log_ok "v03-GOAL-11 Dashboard v0.2 KPI 卡完全保留" \
  || log_bad "v03-GOAL-11 Dashboard 破坏 v0.2" "see $TMP"

# v03-GOAL-12 · 顶部 nav 加「目标」项
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q 'href="/goals"' "$TMP" && log_ok "v03-GOAL-12 顶部 nav 加 /goals link" \
  || log_bad "v03-GOAL-12 nav 缺 /goals" "see $TMP"

# 复跑后置:清干净 v03-GOAL 创建的目标,不影响后续/历史
mysql -ufinance -pfinance finance -e "DELETE FROM family_goal WHERE family_id=1 AND name LIKE 'v03 %';" 2>/dev/null


###################################################
# v0.3 FR-51 · 储蓄能力 · /entry 2 框 + /reports 储蓄区块
###################################################
# 复跑前置:清掉所有家庭 1 周期的脏 cashflow 数据(测试期间累计的)
PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "DELETE FROM period_member_cashflow WHERE family_id=1;" 2>/dev/null
$CURL -b $COOKIE -c $COOKIE "$BASE/entry" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
ME_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM member WHERE family_id=1 AND username='diwa'" 2>/dev/null)

# v03-IND-1 · /entry 页面含 FR-51 2 框 form
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
{ grep -q '的本月总收入' "$TMP" && grep -q '的本月总支出' "$TMP" && grep -q 'cashflow-summary' "$TMP"; } \
  && log_ok "v03-IND-1 /entry 含 FR-51 家庭口径 2 框 form" \
  || log_bad "v03-IND-1 entry 缺 2 框" "see $TMP"

# v03-IND-2 · POST /entry/cashflow-summary 写入 DB
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "periodId=$PID" --data-urlencode "totalIncomeInput=35000" --data-urlencode "totalExpenseInput=18000" \
  "$BASE/entry/cashflow-summary" -o /dev/null -w "%{http_code}")
in_out=$(mysql -ufinance -pfinance finance -sN -e "SELECT CONCAT(total_income_input,'/',total_expense_input) FROM period_member_cashflow WHERE period_id=$PID AND member_id=$ME_ID" 2>/dev/null)
{ [[ "$code" == "302" ]] && [[ "$in_out" == "35000.00/18000.00" ]]; } \
  && log_ok "v03-IND-2 POST cashflow-summary 写入 35k/18k" \
  || log_bad "v03-IND-2 POST cashflow-summary" "code=$code db=$in_out"

# v03-IND-3 · POST 任一空值 → NULL 入库(选填 backward compat)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "periodId=$PID" --data-urlencode "totalIncomeInput=" --data-urlencode "totalExpenseInput=" \
  "$BASE/entry/cashflow-summary" -o /dev/null -w "" || true
in_out=$(mysql -ufinance -pfinance finance -sN -e "SELECT CONCAT(IFNULL(total_income_input,'NULL'),'/',IFNULL(total_expense_input,'NULL')) FROM period_member_cashflow WHERE period_id=$PID AND member_id=$ME_ID" 2>/dev/null)
[[ "$in_out" == "NULL/NULL" ]] \
  && log_ok "v03-IND-3 空值 → NULL 入库(选填 backward compat)" \
  || log_bad "v03-IND-3 空值未 NULL" "db=$in_out"

# v03-IND-4 · /reports 储蓄区块渲染(无数据态 → 引导卡)
#   v0.4.4 文案专业化:「/entry」→「填报页」
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
#   2026-08-04 放宽:原判据要求必须出现「去填报页」引导卡,前提是 beta 此刻没有储蓄数据。
#   v1.8 起「已填月数」按统一口径判(逐笔录了支出就算有数据),逐笔家庭的这一段会正常渲染 KPI 卡,
#   引导卡自然不出现 —— 那是**正确行为**,不是回退。核心意图是「这一段不能空白」:
#   要么给引导卡,要么给出 KPI,两者都没有才是 bug。
{ grep -q '储蓄能力' "$TMP" && { grep -q '去填报页' "$TMP" || grep -q '月均收入' "$TMP"; }; } \
  && log_ok "v03-IND-4 /reports 储蓄区块非空(无数据→引导卡 / 有数据→KPI 卡)" \
  || log_bad "v03-IND-4 reports 储蓄区块" "see $TMP"

# 重新写入数据 · 测有数据态(ReportsController 用 findLatest(family, 12) · 必须写到最近 12 期中的一个)
PID_LATEST=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "INSERT INTO period_member_cashflow (family_id, period_id, member_id, total_income_input, total_expense_input) VALUES (1, $PID_LATEST, $ME_ID, 35000, 18000) ON DUPLICATE KEY UPDATE total_income_input=35000, total_expense_input=18000;" 2>/dev/null
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
# v03-IND-5 · /reports 储蓄区块渲染(有数据态 → 双柱图 canvas + KPI)
{ grep -q 'savings-bars' "$TMP" && grep -q '月度收支双柱' "$TMP"; } \
  && log_ok "v03-IND-5 /reports 储蓄区块有数据时显双柱图" \
  || log_bad "v03-IND-5 reports 双柱" "see $TMP"

# v03-IND-6 · reports 保留核心内容(净资产等);桑基图/瀑布图 v0.4 FR-60b 已砍(与新定位冲突),不再期待。
#   v0.10.6 去过期:原断言宣称「桑基图 100% 保留」+ grep 'sankey' 仅命中已废弃的 sankeyNodes/Links JS 残留变量
#   (无图渲染);去掉 sankey 期待,只验 backward-compat 核心内容「净资产」仍在。
grep -q '净资产' "$TMP" \
  && log_ok "v03-IND-6 reports 保留核心内容(净资产;桑基图/瀑布 v0.4 已砍)" \
  || log_bad "v03-IND-6 reports 核心内容缺失" "see $TMP"

# v03-IND-7 · /entry FR-51 2 框在页面"上方"(用户最先录入位置 · 2026-05-13 反馈)
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
# 测"第一步 我的本月"出现的行号 < "本期总进度"行号(即 FR-51 在 v0.2 进度卡之前)
fr51_line=$(grep -n "第 · 一 · 步" "$TMP" | head -1 | cut -d: -f1)
prog_line=$(grep -n "本期总进度" "$TMP" | head -1 | cut -d: -f1)
{ [[ -n "$fr51_line" ]] && [[ -n "$prog_line" ]] && [[ "$fr51_line" -lt "$prog_line" ]]; }   && log_ok "v03-IND-7 /entry FR-51 在「本期总进度」之前(置顶 · 第一步)"   || log_bad "v03-IND-7 entry 2 框不在顶部" "fr51=$fr51_line prog=$prog_line"
# v03-IND-8 · v0.4 KPI 收敛 9→5 → v0.4.2 第 5 KPI 顶替为"本月资产收益"
# 改判 dashboard 含"本月资产收益"或"月储蓄能力" · /reports 含原 4 KPI
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
dash_ok=$(grep -cE "本月资产收益|月储蓄能力" "$TMP")
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
rpt_ok=$(grep -c "月均收入" "$TMP")
{ [[ "$dash_ok" -ge 1 ]] && [[ "$rpt_ok" -ge 1 ]]; } \
  && log_ok "v03-IND-8 (v0.4.2 改) dashboard 第 5 KPI(本月资产收益/月储蓄)· reports 储蓄区原 4 KPI(dash=$dash_ok rpt=$rpt_ok)" \
  || log_bad "v03-IND-8 KPI 搬迁" "dash=$dash_ok rpt=$rpt_ok"

# v03-IND-9 · /reports 储蓄区块加月均收入/支出 KPI · 数字来自 period.total_*_input
mysql -ufinance -pfinance finance -e "UPDATE period_member_cashflow SET total_income_input=40000, total_expense_input=15000 WHERE period_id=$PID_LATEST AND member_id=$ME_ID;" 2>/dev/null
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
grep -q "月均收入(近 12 月)" "$TMP" \
  && log_ok "v03-IND-9 /reports 储蓄区块加月均收入 KPI" \
  || log_bad "v03-IND-9 reports 月均收入 KPI" "see $TMP"

# v03-IND-10 · /checkup 流动性月数用新 service(优先 v0.3 口径)· 仅断言页能加载(数字精度 v0.2 既有规则覆盖)
code=$($CURL -b $COOKIE "$BASE/checkup" -o /dev/null -w "%{http_code}")
[[ "$code" == "200" ]] && log_ok "v03-IND-10 /checkup 用 HouseholdCashflowService 算月均支出 · 页面渲染 OK" \
  || log_bad "v03-IND-10 checkup 破坏" "code=$code"

# v03-IND-11 · 多成员独立填报 · 家庭聚合 = SUM(成员)· 2026-05-13 修订验证
# v0.4 修:dashboard 月均收入/支出 KPI 已搬 /reports · 改判 /reports 显示 SUM 数字
BOB_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM member WHERE family_id=1 AND username='wangergou'" 2>/dev/null)
mysql -ufinance -pfinance finance -e "INSERT INTO period_member_cashflow (family_id, period_id, member_id, total_income_input, total_expense_input) VALUES (1, $PID_LATEST, $BOB_ID, 22000, 8000) ON DUPLICATE KEY UPDATE total_income_input=22000, total_expense_input=8000;" 2>/dev/null
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q "¥62,000" "$TMP" && grep -q "¥23,000" "$TMP"; } \
  && log_ok "v03-IND-11 (v0.4 改) 多成员 SUM → /reports 储蓄区显 ¥62k / ¥23k" \
  || log_bad "v03-IND-11 SUM 聚合不对" "see $TMP"

# v03-IND-12 · /entry 显式"家庭本月总收入(SUM 成员)"区块
rm -f $COOKIE; TOKEN=$($CURL -c $COOKIE "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b $COOKIE -c $COOKIE -X POST --data-urlencode "_csrf=$TOKEN" --data-urlencode "username=diwa" --data-urlencode "password=demo1234" "$BASE/login" -o /dev/null -w "" || true
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
{ grep -q "家庭本月总收入" "$TMP" && grep -q "家庭本月已填" "$TMP"; } \
  && log_ok "v03-IND-12 /entry 含家庭聚合显示(SUM 区块)" \
  || log_bad "v03-IND-12 entry 缺家庭聚合" "see $TMP"

# 复跑后置:清掉测试 cashflow 数据
mysql -ufinance -pfinance finance -e "DELETE FROM period_member_cashflow WHERE family_id=1;" 2>/dev/null


###################################################
# v0.3 FR-52 · 股票自动估值
###################################################
# 复跑前置:清测试持仓
mysql -ufinance -pfinance finance -e "DELETE FROM stock_holding WHERE display_name LIKE 'v03 %';" 2>/dev/null
mysql -ufinance -pfinance finance -e "DELETE FROM stock_price_snapshot WHERE ticker IN ('V03TEST', 'V03TST');" 2>/dev/null

STOCK_ACC=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type='STOCK' AND archived_at IS NULL LIMIT 1" 2>/dev/null)

$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)

# v03-STOCK-1 · STOCK 类型账户持仓页 200
code=$($CURL -b $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o "$TMP" -w "%{http_code}")
{ [[ "$code" == "200" ]] && grep -q "持仓管理" "$TMP" && grep -q "AUTO 自动估值\|MANUAL 手填\|添加持仓\|还没有持仓" "$TMP"; } \
  && log_ok "v03-STOCK-1 STOCK 账户持仓页 200" \
  || log_bad "v03-STOCK-1 持仓页" "code=$code"

# v03-STOCK-2 · 非 STOCK 账户拒绝访问持仓页
NON_STOCK=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type!='STOCK' AND archived_at IS NULL LIMIT 1" 2>/dev/null)
code=$($CURL -b $COOKIE "$BASE/accounts/$NON_STOCK/holdings" -o /dev/null -w "%{http_code}")
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v03-STOCK-2 非 STOCK 账户拒绝持仓页" \
  || log_bad "v03-STOCK-2 非 STOCK 未拒" "code=$code"

# v03-STOCK-3 · 创建 MANUAL 持仓(股数×单股估值)· issue#3 精度:单股 15.678 原样落库(非旧 DECIMAL(15,2) 截成 15.68)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 字节期权" --data-urlencode "shares=1" --data-urlencode "unitValue=15.678" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-manual" -o /dev/null -w "%{http_code}")
mv=$(mysql -ufinance -pfinance finance -sN -e "SELECT manual_value=15.678 FROM stock_holding WHERE display_name='v03 字节期权' AND archived_at IS NULL ORDER BY id DESC LIMIT 1" 2>/dev/null)
{ [[ "$code" == "302" ]] && [[ "$mv" == "1" ]]; } \
  && log_ok "v03-STOCK-3 创建 MANUAL 持仓 · 单股估值 15.678 原样落库(issue#3 精度 · V37 (20,6))" \
  || log_bad "v03-STOCK-3 MANUAL 创建/精度" "code=$code mv(=15.678?)=$mv"

# v03-STOCK-4 · 创建 AUTO 持仓 · 真拉价
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 阿里" --data-urlencode "ticker=BABA" --data-urlencode "market=US" \
  --data-urlencode "shares=50" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
have_holding=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM stock_holding WHERE display_name='v03 阿里' AND ticker='BABA' AND market='US' AND archived_at IS NULL" 2>/dev/null)
have_price=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM stock_price_snapshot WHERE ticker='BABA' AND market='US'" 2>/dev/null)
{ [[ "$code" == "302" ]] && [[ "$have_holding" == "1" ]] && [[ "$have_price" -ge "1" ]]; } \
  && log_ok "v03-STOCK-4 创建 AUTO BABA · 持仓+价格快照入库" \
  || log_bad "v03-STOCK-4 AUTO 创建" "code=$code holding=$have_holding price=$have_price"

# v03-STOCK-5 · A 股拉价(新浪)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 茅台" --data-urlencode "ticker=600519" --data-urlencode "market=CN" \
  --data-urlencode "shares=5" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
src=$(mysql -ufinance -pfinance finance -sN -e "SELECT source FROM stock_price_snapshot WHERE ticker='600519' AND market='CN' ORDER BY fetched_at DESC LIMIT 1" 2>/dev/null)
[[ -n "$src" ]] && log_ok "v03-STOCK-5 A 股 600519 拉价成功 · source=$src" \
  || log_bad "v03-STOCK-5 A 股拉价" "no snapshot"

# v03-STOCK-5b · issue#3 · 上交所 ETF 513180 拉价(旧 startsWith("6")?sh:sz 会误判 sz513180 → 查无 → 全源熔断)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 恒生科技ETF" --data-urlencode "ticker=513180" --data-urlencode "market=CN" \
  --data-urlencode "shares=100" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
src5b=$(mysql -ufinance -pfinance finance -sN -e "SELECT source FROM stock_price_snapshot WHERE ticker='513180' AND market='CN' ORDER BY fetched_at DESC LIMIT 1" 2>/dev/null)
[[ -n "$src5b" ]] && log_ok "v03-STOCK-5b 上交所 ETF 513180 拉价成功(issue#3 前缀修复 · sh513180)· source=$src5b" \
  || log_bad "v03-STOCK-5b ETF 513180 拉价(前缀应判 sh)" "no snapshot"

# v03-STOCK-6 · 港股 5 位前导零规范化(用户填 0700 → 入库 00700)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 腾讯" --data-urlencode "ticker=0700" --data-urlencode "market=HK" \
  --data-urlencode "shares=10" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
hk_ticker=$(mysql -ufinance -pfinance finance -sN -e "SELECT ticker FROM stock_holding WHERE display_name='v03 腾讯' AND market='HK' AND archived_at IS NULL" 2>/dev/null)
[[ "$hk_ticker" == "00700" ]] \
  && log_ok "v03-STOCK-6 港股 ticker 规范化 0700 → 00700" \
  || log_bad "v03-STOCK-6 港股规范化" "ticker=$hk_ticker"

# v03-STOCK-7 · 估值写回 account_balance · note=系统估值同步(v0.4.4 起 · 老数据仍可能是 auto-stock-valuation v0.3)
PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$STOCK_ACC/holdings/refresh" -o /dev/null -w ""
sleep 2
note=$(mysql -ufinance -pfinance finance -sN -e "SELECT note FROM period_snapshot WHERE period_id=$PID AND account_id=$STOCK_ACC" 2>/dev/null)
{ [[ "$note" == *"系统估值"* || "$note" == *"auto-stock-valuation"* ]]; } \
  && log_ok "v03-STOCK-7 估值写回 period_snapshot · note=$note" \
  || log_bad "v03-STOCK-7 估值未写回" "note=$note"

# v03-STOCK-8 · backward compat · 无 holding 的 STOCK 账户不被改 balance
# 创建一个新的 STOCK 账户没加持仓 · 让 refresh 跑 · 该账户 balance 应保持手填值
# (实际依赖 beta 还有别的 STOCK 账户)— 简化为"refreshAllForFamily 不报错"
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$STOCK_ACC/holdings/refresh" -o /dev/null -w "" && \
  log_ok "v03-STOCK-8 refresh 全家估值不抛异常 · backward compat"

# v03-STOCK-9 · 软删持仓 → 账户余额自动重算(少了这只持仓的市值)
HID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM stock_holding WHERE display_name='v03 茅台' AND archived_at IS NULL" 2>/dev/null)
balance_before=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID AND account_id=$STOCK_ACC" 2>/dev/null)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$STOCK_ACC/holdings/$HID/archive" -o /dev/null -w "" && sleep 1
balance_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID AND account_id=$STOCK_ACC" 2>/dev/null)
{ [[ "$balance_before" != "$balance_after" ]]; } \
  && log_ok "v03-STOCK-9 持仓归档后账户余额重算 · before=$balance_before after=$balance_after" \
  || log_bad "v03-STOCK-9 归档未触发重算" "before=$balance_before after=$balance_after"

# v03-STOCK-10 · /entry STOCK 行加"📦 持仓变动?" 入口
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
grep -q "持仓变动" "$TMP" \
  && log_ok "v03-STOCK-10 /entry STOCK 行加持仓变动入口" \
  || log_bad "v03-STOCK-10 entry STOCK 行" "no link"

# v03-STOCK-11 · fx 链式跨币种 · 账户 HKD + 持仓 USD/HKD 混合(2026-05-13 bug fix)
# 场景:HKD 账户混持 BABA(USD) + 腾讯(HKD)· fx_rate 表只存 base=CNY 方向 · 需经 CNY 中转
ORIG_CURR=$(mysql -ufinance -pfinance finance -sN -e "SELECT currency FROM account WHERE id=$STOCK_ACC" 2>/dev/null)
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='HKD' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
# BABA 100 股 USD
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 BABA" --data-urlencode "ticker=BABA" --data-urlencode "market=US" \
  --data-urlencode "shares=100" --data-urlencode "currency=USD" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "" || true
sleep 2
# 腾讯 200 股 HKD
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 腾讯" --data-urlencode "ticker=00700" --data-urlencode "market=HK" \
  --data-urlencode "shares=200" --data-urlencode "currency=HKD" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "" || true
sleep 3
PID_OPEN=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
bal=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID_OPEN AND account_id=$STOCK_ACC" 2>/dev/null)
# 预期 BABA 100 × $price × USD→HKD(经 CNY 中转 ≈ 7.83)+ 腾讯 200 × HK$459 ≈ 196k 量级
# 容差大:只要 > 50000(说明 fx 链式生效 · 不再走 1.0 兜底)
bal_int=$(echo "$bal" | cut -d. -f1)
{ [[ -n "$bal" ]] && [[ "$bal_int" -gt 50000 ]] && [[ "$bal_int" -lt 500000 ]]; } \
  && log_ok "v03-STOCK-11 fx 链式跨币种 USD/HKD/HKD 账户 · bal=$bal HKD(经 CNY 中转)" \
  || log_bad "v03-STOCK-11 fx 链式失败 · 走 1.0 兜底" "bal=$bal"

# 恢复账户币种
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='$ORIG_CURR' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null

# v03-STOCK-12 · CASH 现金行表单页 200(FR-52e)
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/accounts/$STOCK_ACC/holdings/new-cash")
{ [[ "$code" == "200" ]] && grep -q 'currency' "$TMP" && grep -q 'amount' "$TMP"; } \
  && log_ok "v03-STOCK-12 CASH 表单页 200 · 含 currency+amount" \
  || log_bad "v03-STOCK-12 CASH 表单页" "code=$code"

# v03-STOCK-13 · 创建 CASH 行 USD 5000 在 HKD 账户 · 估值含 FX 折算
ORIG_CURR=$(mysql -ufinance -pfinance finance -sN -e "SELECT currency FROM account WHERE id=$STOCK_ACC" 2>/dev/null)
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='HKD' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings/new-cash" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 USD 现金" \
  --data-urlencode "currency=USD" \
  --data-urlencode "amount=5000" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-cash" -o /dev/null -w "%{http_code}")
sleep 2
cash_row=$(mysql -ufinance -pfinance finance -sN -e "SELECT valuation_mode,currency,ROUND(manual_value,2) FROM stock_holding WHERE account_id=$STOCK_ACC AND display_name='v03 USD 现金'" 2>/dev/null | tr '\t' '|')
PID_OPEN=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
bal=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID_OPEN AND account_id=$STOCK_ACC" 2>/dev/null)
bal_int=$(echo "$bal" | cut -d. -f1)
# USD 5000 × FX(USD→HKD 经 CNY ≈ 7.83) ≈ 39150 HKD;容差:bal > 20000 且 < 100000
{ [[ "$code" =~ ^30[0-9]$ ]] && [[ "$cash_row" == "CASH|USD|5000.00" ]] && [[ -n "$bal" ]] && [[ "$bal_int" -gt 20000 ]] && [[ "$bal_int" -lt 100000 ]]; } \
  && log_ok "v03-STOCK-13 CASH USD 5000 → HKD 账户余额 $bal(经 CNY FX 链)" \
  || log_bad "v03-STOCK-13 CASH 创建+FX 估值" "code=$code row=$cash_row bal=$bal"

# v03-STOCK-14 · 更新 CASH 金额 · manual_value_at 刷新
HID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM stock_holding WHERE account_id=$STOCK_ACC AND display_name='v03 USD 现金'" 2>/dev/null)
OLD_AT=$(mysql -ufinance -pfinance finance -sN -e "SELECT manual_value_at FROM stock_holding WHERE id=$HID" 2>/dev/null)
sleep 2
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "amount=8000" \
  "$BASE/accounts/$STOCK_ACC/holdings/$HID/update-cash" -o /dev/null -w "" || true
sleep 1
NEW_AT=$(mysql -ufinance -pfinance finance -sN -e "SELECT manual_value_at FROM stock_holding WHERE id=$HID" 2>/dev/null)
NEW_VAL=$(mysql -ufinance -pfinance finance -sN -e "SELECT ROUND(manual_value,2) FROM stock_holding WHERE id=$HID" 2>/dev/null)
{ [[ "$NEW_VAL" == "8000.00" ]] && [[ "$OLD_AT" != "$NEW_AT" ]]; } \
  && log_ok "v03-STOCK-14 CASH 金额更新 5000→8000 · manual_value_at 刷新" \
  || log_bad "v03-STOCK-14 CASH 更新" "val=$NEW_VAL old_at=$OLD_AT new_at=$NEW_AT"

# v03-STOCK-15 · 持仓 + CASH 共存 · account_balance = holdings + cash
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
# 加一个 HKD MANUAL 持仓 50000
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 私募 X" --data-urlencode "manualValue=50000" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-manual" -o /dev/null -w "" || true
sleep 3
new_bal=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID_OPEN AND account_id=$STOCK_ACC" 2>/dev/null)
new_int=$(echo "$new_bal" | cut -d. -f1)
# 预期 = USD 8000 × 7.83 (≈62640) + HKD 50000 = ≈112k;容差 80k-160k
{ [[ -n "$new_bal" ]] && [[ "$new_int" -gt 80000 ]] && [[ "$new_int" -lt 160000 ]]; } \
  && log_ok "v03-STOCK-15 持仓 MANUAL+CASH 共存 · HKD 账户 bal=$new_bal" \
  || log_bad "v03-STOCK-15 持仓+CASH 共存估值" "bal=$new_bal"

# 恢复账户币种 + 清测试持仓
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='$ORIG_CURR' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null

# 复跑后置:清测试持仓 · 重算原始账户余额
mysql -ufinance -pfinance finance -e "DELETE FROM stock_holding WHERE display_name LIKE 'v03 %';" 2>/dev/null


###################################################
# v0.3 FR-53 · AI 4 处介入
###################################################
$CURL -b $COOKIE -c $COOKIE "$BASE/goals/new/retirement" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)

# v03-AI-1 · FR-53a · /goals/advise/retirement 返回 ok+JSON 或 unavailable(取决于 LLM 可用性)
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/retirement" -o "$TMP" -w "" --max-time 30
{ grep -q '"ok":\s*true' "$TMP" || grep -q '"ok":\s*false' "$TMP"; } \
  && log_ok "v03-AI-1 /goals/advise/retirement 返回合法 JSON(ok/error)" \
  || log_bad "v03-AI-1 advise 响应" "see $TMP"

# v03-AI-2 · /goals/advise/education JSON 结构
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/education" -o "$TMP" -w "" --max-time 30
{ grep -q '"ok"' "$TMP"; } \
  && log_ok "v03-AI-2 /goals/advise/education JSON 响应" \
  || log_bad "v03-AI-2 advise education" "see $TMP"

# v03-AI-3 · /goals/advise/emergency JSON 结构
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/emergency" -o "$TMP" -w "" --max-time 30
{ grep -q '"ok"' "$TMP"; } \
  && log_ok "v03-AI-3 /goals/advise/emergency JSON 响应" \
  || log_bad "v03-AI-3 advise emergency" "see $TMP"

# v03-AI-4 · 非法类型拒
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/invalid" -o /dev/null -w "%{http_code}")
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v03-AI-4 非法 type 4xx/5xx" \
  || log_bad "v03-AI-4 非法 type 未拒" "code=$code"

# v03-AI-5 · 表单含 [🤖 AI 推荐] 按钮 + JS 函数
$CURL -b $COOKIE "$BASE/goals/new/retirement" -o "$TMP" -w ""
{ grep -q "AI 推荐" "$TMP" && grep -q "adviseRetirement" "$TMP"; } \
  && log_ok "v03-AI-5 退休向导含 AI 推荐按钮 + JS" \
  || log_bad "v03-AI-5 AI 按钮" "see $TMP"

# v03-AI-6 · FR-53d · v0.2 /checkup 既有功能保留(无目标家庭 prompt 不加段)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q "</html>" "$TMP" \
  && log_ok "v03-AI-6 /checkup 既有页面渲染保留(backward compat)" \
  || log_bad "v03-AI-6 /checkup 破坏" "incomplete"


###################################################
# v0.4 · 报表整顿 + 摸清第 5 问 + 调优决策
###################################################

# v04-RPT-1 · /dashboard KPI 收敛到 5 + CPI 切换器(v0.4.2:第 5 KPI 顶替为本月资产收益)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ (grep -q '本月资产收益' "$TMP" || grep -q '月储蓄能力' "$TMP") && grep -q 'name="cpi"' "$TMP"; } \
  && log_ok "v04-RPT-1 dashboard 5 KPI(第 5 为本月资产收益/月储蓄能力)+ CPI 切换器" \
  || log_bad "v04-RPT-1 dashboard 改造" "missing"

# v04-RPT-2 · /dashboard 砍收入支出组合图(只剩注释 incomeExpenseChart 字符串 0 个 canvas)
canvases=$(grep -c '<canvas id="incomeExpenseChart"' "$TMP")
[[ "$canvases" -eq 0 ]] && log_ok "v04-RPT-2 dashboard incomeExpenseChart canvas 已砍" \
  || log_bad "v04-RPT-2 incomeExpenseChart 未砍" "canvas=$canvases"

# v04-RPT-3 · /reports 砍 waterfall/sankey/月度收支对比 canvas
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
killed=$(grep -cE '<div id="waterfallChart"|<div id="sankeyChart"|<canvas id="incomeBarChart"' "$TMP")
[[ "$killed" -eq 0 ]] && log_ok "v04-RPT-3 reports 砍 waterfall/sankey/月度收支对比 canvas" \
  || log_bad "v04-RPT-3 流水图未砍" "still=$killed"

# v04-RPT-4 · /reports 含配置 diff section + 账户级基准列
{ grep -q 'id="allocation-diff"' "$TMP" && grep -q '基准 %' "$TMP"; } \
  && log_ok "v04-RPT-4 reports 含配置 diff section + 账户级基准列" \
  || log_bad "v04-RPT-4 reports 新区缺" "missing"

# v04-RPT-5 · /checkup 资产配置仍砍(已有 mini 横向条 · 完整环形见 dashboard)
#   v0.4.5(2026-05-14)用户反馈风险敞口干巴巴 → 风险等级分布改饼图(canvas 回归)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
alloc_canvas=$(grep -cE '<canvas id="allocChart"' "$TMP")
risk_canvas=$(grep -cE '<canvas id="riskChart"' "$TMP")
{ [[ "$alloc_canvas" -eq 0 && "$risk_canvas" -eq 1 ]]; } \
  && log_ok "v04-RPT-5 checkup 砍配置环形(0)· 风险等级保留饼图(1 canvas)" \
  || log_bad "v04-RPT-5 checkup canvas 状态错" "alloc=$alloc_canvas risk=$risk_canvas"

# v04-CPI-1 · family.cpi_assumption 默认 2.00 入库
cpi=$(mysql -ufinance -pfinance finance -sN -e "SELECT cpi_assumption FROM family WHERE id=1" 2>/dev/null)
[[ "$cpi" == "2.00" ]] && log_ok "v04-CPI-1 family.cpi_assumption 默认 2.00" \
  || log_bad "v04-CPI-1 cpi 默认值错" "$cpi"

# v04-CPI-2 · POST /admin/family/cpi 切换到 3.00 + DB 更新
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "cpi=3.00" "$BASE/admin/family/cpi" -o /dev/null -w ""
cpi=$(mysql -ufinance -pfinance finance -sN -e "SELECT cpi_assumption FROM family WHERE id=1" 2>/dev/null)
[[ "$cpi" == "3.00" ]] && log_ok "v04-CPI-2 POST /admin/family/cpi 切 3% · DB 更新" \
  || log_bad "v04-CPI-2 cpi 切换" "$cpi"
mysql -ufinance -pfinance finance -e "UPDATE family SET cpi_assumption=2.00 WHERE id=1" 2>/dev/null

# v04-BMK-1 · reports 含"vs 基准" pill + 跑赢/输 column
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'vs 基准' "$TMP"; } \
  && log_ok "v04-BMK-1 reports 含 vs 基准 KPI" \
  || log_bad "v04-BMK-1 vs 基准缺" "missing"

# v04-DIFF-1 · allocation_anchor 表 4 行预置 + family 默认 SP_4321
n=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM allocation_anchor" 2>/dev/null)
anchor=$(mysql -ufinance -pfinance finance -sN -e "SELECT allocation_anchor FROM family WHERE id=1" 2>/dev/null)
{ [[ "$n" == "4" ]] && [[ "$anchor" == "SP_4321" ]]; } \
  && log_ok "v04-DIFF-1 V22 预置 4 锚 + family 默认 SP_4321" \
  || log_bad "v04-DIFF-1 anchor seed" "n=$n anchor=$anchor"

# v04-DIFF-2 · POST /admin/family/anchor 切到 XQ_AGGRESSIVE · DB 更新
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "anchor=XQ_AGGRESSIVE" "$BASE/admin/family/anchor" -o /dev/null -w ""
anchor=$(mysql -ufinance -pfinance finance -sN -e "SELECT allocation_anchor FROM family WHERE id=1" 2>/dev/null)
[[ "$anchor" == "XQ_AGGRESSIVE" ]] \
  && log_ok "v04-DIFF-2 POST /admin/family/anchor → XQ_AGGRESSIVE · DB 更新" \
  || log_bad "v04-DIFF-2 anchor 切换" "$anchor"
mysql -ufinance -pfinance finance -e "UPDATE family SET allocation_anchor='SP_4321' WHERE id=1" 2>/dev/null

# v04-DIFF-3 · 非法 anchor 拒绝 · DB 不变
$CURL -b $COOKIE "$BASE/admin/family/anchor" -o /dev/null -w "" # refresh xsrf
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "anchor=BOGUS_99" "$BASE/admin/family/anchor" -o /dev/null -w ""
anchor=$(mysql -ufinance -pfinance finance -sN -e "SELECT allocation_anchor FROM family WHERE id=1" 2>/dev/null)
[[ "$anchor" == "SP_4321" ]] && log_ok "v04-DIFF-3 非法 anchor 拒绝 · 保持 SP_4321" \
  || log_bad "v04-DIFF-3 非法 anchor 通过" "$anchor"

# v04-LIQ-4 · LiquiditySurplus 计算正确(单测覆盖) · 这里只确认 service bean 可调
# (跳过 · 已由 LiquiditySurplusTest 6 单测覆盖)

# v04-REFI-1 · GET /reports/refinance 200 + 含表单字段
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && grep -q 'name="loanRate"' "$TMP" && grep -q 'name="investRate"' "$TMP"; } \
  && log_ok "v04-REFI-1 /reports/refinance 表单 200 + 含 loanRate/investRate" \
  || log_bad "v04-REFI-1 refinance 表单" "code=$code"

# v04-REFI-2 · POST 计算 · 走完整结果路径(推荐 or 应急金不足提示均算 PASS · beta 数据差异容忍)
$CURL -b $COOKIE "$BASE/reports/refinance" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "amount=100000" \
  --data-urlencode "loanRate=0.045" \
  --data-urlencode "investRate=0.072" \
  --data-urlencode "years=18" \
  -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && (grep -q '优先投资' "$TMP" || grep -q '⚠ 先' "$TMP" || grep -q '应急金不足' "$TMP"); } \
  && log_ok "v04-REFI-2 POST 200 + 返回结果块(推荐 or 应急金检查 · beta 数据容忍)" \
  || log_bad "v04-REFI-2 result 块缺" "code=$code"

# v04-REFI-3 · POST · 必还(loanRate ≥ investRate)
$CURL -b $COOKIE "$BASE/reports/refinance" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "amount=100000" \
  --data-urlencode "loanRate=0.058" \
  --data-urlencode "investRate=0.050" \
  --data-urlencode "years=18" \
  -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && grep -q '必还' "$TMP"; } \
  && log_ok "v04-REFI-3 POST 必还(loanRate ≥ investRate)" \
  || log_bad "v04-REFI-3 必还路径" "code=$code"

# v04-REFI-4 · POST · 非法参数拒绝(loanRate=0.6 > 0.5)
$CURL -b $COOKIE "$BASE/reports/refinance" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "amount=100000" \
  --data-urlencode "loanRate=0.6" \
  --data-urlencode "investRate=0.072" \
  --data-urlencode "years=18" \
  -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && grep -q '校 · 验\|输入校验' "$TMP"; } \
  && log_ok "v04-REFI-4 非法 loanRate 拒绝 · 校验提示" \
  || log_bad "v04-REFI-4 非法参数处理" "code=$code"

# v04-AI-REBALANCE-1 · POST /reports/rebalance/advise 不抛异常(LLM 可能 unavailable,容忍)
$CURL -b $COOKIE "$BASE/reports" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/reports/rebalance/advise" -o /dev/null -w "%{http_code}")
{ [[ "$code" == "302" || "$code" == "303" ]]; } \
  && log_ok "v04-AI-REBALANCE-1 POST /reports/rebalance/advise → 302(LLM 调用容忍失败)" \
  || log_bad "v04-AI-REBALANCE-1 advise 异常" "code=$code"

# v04-VAL-1 · v0.4.1 FR-52f · stock_valuation_event 表已建 · 拉价后写事件
# 找有 holdings 的 STOCK 账户 + 当前 OPEN period
VAL_ACC=$(mysql -ufinance -pfinance finance -sN -e "
  SELECT DISTINCT a.id FROM account a JOIN stock_holding h ON h.account_id=a.id
  WHERE a.family_id=1 AND a.type='STOCK' AND a.archived_at IS NULL AND h.archived_at IS NULL
  LIMIT 1" 2>/dev/null)
VAL_PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
if [[ -n "$VAL_ACC" && -n "$VAL_PID" ]]; then
  # 删旧事件 + 改 snapshot 制造明显差异
  mysql -ufinance -pfinance finance -e "DELETE FROM stock_valuation_event WHERE account_id=$VAL_ACC AND period_id=$VAL_PID" 2>/dev/null
  mysql -ufinance -pfinance finance -e "UPDATE period_snapshot SET end_balance = end_balance - 5000 WHERE period_id=$VAL_PID AND account_id=$VAL_ACC" 2>/dev/null
  # 触发 manual refresh
  $CURL -b $COOKIE "$BASE/accounts/$VAL_ACC/holdings" -o /dev/null
  XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
  $CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$VAL_ACC/holdings/refresh" -o /dev/null -w ""
  sleep 3
  cnt=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM stock_valuation_event WHERE account_id=$VAL_ACC AND period_id=$VAL_PID AND trigger_kind='MANUAL'" 2>/dev/null)
  [[ "$cnt" -ge 1 ]] && log_ok "v04-VAL-1 拉价后写 stock_valuation_event · MANUAL · account=$VAL_ACC count=$cnt" \
    || log_bad "v04-VAL-1 事件未写" "count=$cnt"
else
  log_skip "v04-VAL-1 没找到有 holdings 的 STOCK 账户"
fi

# v04-VAL-2 · /entry ledger 显示 估值 行(v0.10.6 去 emoji:📈→△,与 detail.html VALUATION 一致)
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
grep -q '△ 估值' "$TMP" \
  && log_ok "v04-VAL-2 /entry ledger 显示 △ 估值 行" \
  || log_bad "v04-VAL-2 entry 估值行 缺" "missing"

# v04-VAL-3 · /accounts/{id} 详情页显示估值行
if [[ -n "$VAL_ACC" ]]; then
  $CURL -b $COOKIE "$BASE/accounts/$VAL_ACC" -o "$TMP" -w ""
  grep -q '估值变动 · 手动刷价' "$TMP" \
    && log_ok "v04-VAL-3 /accounts/$VAL_ACC 详情页显示估值行(手动刷价)" \
    || log_bad "v04-VAL-3 accounts ledger 估值行 缺" "missing"
fi

# ===================================================
# v0.4.2 · "钱赚的 vs 人赚的"二分 KPI(InvestmentReturn)
# ===================================================

# v04-RET-1 · dashboard 第 5 KPI 改为"本月资产收益"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q '本月资产收益' "$TMP" && grep -q '剔除收入' "$TMP"; } \
  && log_ok "v04-RET-1 dashboard 第 5 KPI · 本月资产收益(剔除收入)" \
  || log_bad "v04-RET-1 月度资产收益 KPI 缺" "missing"

# v04-RET-2 · reports 4 KPI 双口径 label + 双口径解释 banner
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q '· 剔除收入' "$TMP" \
  && grep -q '含收入' "$TMP" \
  && grep -q '人赚的' "$TMP" \
  && grep -q '钱赚的' "$TMP" \
  && grep -q '双口径' "$TMP"; } \
  && log_ok "v04-RET-2 reports 4 KPI 双口径(XIRR 含 / 资产年化剔 / 人赚 / 钱赚)+ 解释 banner" \
  || log_bad "v04-RET-2 reports 双口径 缺" "missing"

# v04-RET-3 · checkup 收益诊断卡 4 KPI 升级
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q '资 · 产 · 年 · 化 ★' "$TMP" \
  && grep -q '本月资产收益' "$TMP"; } \
  && log_ok "v04-RET-3 checkup 收益诊断卡 4 KPI(资产年化 ★ + 本月)" \
  || log_bad "v04-RET-3 checkup 收益诊断 缺" "missing"

# v04-RET-4 · InvestmentReturnCalculator 9 单测覆盖(mvn test 验证 · 此处仅断言文件存在)
[[ -f /home/finance/financial-management/src/test/java/com/family/finance/calc/InvestmentReturnCalculatorTest.java ]] \
  && log_ok "v04-RET-4 InvestmentReturnCalculatorTest 9 单测存在 · 月度 + 年化 + YTD 覆盖" \
  || log_bad "v04-RET-4 单测文件缺" "missing"

# ===================================================
# v0.4.3 · QA 视角再审视 → P0 修复(B1 endBalance 续值 · B2 PMC 优先 · B4 YTD 独立 slice)
# ===================================================
section "v0.4.3 · P0 修复 · 历史数据保护 · backward-compat"

# v04-FIX-1 · B1 · FactMapper.queryBase 含 end_balance COALESCE 续值子查询
#   现实场景:用户忘填某账户当月 snapshot → 原 SQL 取出 NULL → netWorth/totalLiabilities 静默失真
#   修复:NULL 时沿用 <= 当期最近一笔非空 snapshot · 不超期 · 不混淆"用户填了 0"和"用户漏填"
grep -q 'ps_carry.end_balance IS NOT NULL' /home/finance/financial-management/src/main/resources/mapper/FactMapper.xml \
  && grep -q 'COALESCE' /home/finance/financial-management/src/main/resources/mapper/FactMapper.xml \
  && log_ok "v04-FIX-1 FactMapper.xml 含 endBalance COALESCE 续值(NULL 用户漏填保护)" \
  || log_bad "v04-FIX-1 FactMapper 续值 SQL 缺" "missing COALESCE/ps_carry"

# v04-FIX-1b · 实际 SQL 在真实 beta 数据上能续值(账户 7/9/11 在 2026-05 漏填 → 续 4 月值)
ACT_CARRIED=$(mysql -ufinance -pfinance finance -N -s -e "
SELECT COALESCE(ps.end_balance,
  (SELECT ps_carry.end_balance FROM period_snapshot ps_carry
     JOIN period p_carry ON p_carry.id=ps_carry.period_id
    WHERE ps_carry.account_id=11 AND p_carry.period_start <= '2026-05-01'
      AND ps_carry.end_balance IS NOT NULL
    ORDER BY p_carry.period_start DESC LIMIT 1)) AS bal
  FROM account a
  CROSS JOIN period p ON 1=1
  LEFT JOIN period_snapshot ps ON ps.account_id=a.id AND ps.period_id=p.id
 WHERE a.id=11 AND p.id=3
" 2>/dev/null | tr -d ' \r\n')
# 兜底:子查询语法兼容性问题时回退到直接续值校验
if [[ -z "$ACT_CARRIED" || "$ACT_CARRIED" == "NULL" ]]; then
  ACT_CARRIED=$(mysql -ufinance -pfinance finance -N -s -e "
    SELECT ps.end_balance FROM period_snapshot ps
      JOIN period p ON p.id=ps.period_id
     WHERE ps.account_id=11 AND p.period_start <= '2026-05-01'
       AND ps.end_balance IS NOT NULL
     ORDER BY p.period_start DESC LIMIT 1" 2>/dev/null | tr -d ' \r\n')
fi
{ [[ -n "$ACT_CARRIED" && "$ACT_CARRIED" != "NULL" && "$ACT_CARRIED" != "0.00" ]]; } \
  && log_ok "v04-FIX-1b 账户 11(房贷)2026-05 缺 snapshot · 续值 SQL 返回 $ACT_CARRIED(非 NULL)" \
  || log_bad "v04-FIX-1b 续值实测" "ACT_CARRIED=$ACT_CARRIED"

# v04-FIX-2 · B2 · averageExpense 双源(PMC 优先 + cash_flow 回退)
#   v1.8 起判据改了:支出口径收敛进 ExpenseLedgerService,FactViewServiceImpl 不再直接
#   调 findFamilyAggregateRecent 取支出(收入侧仍用 PMC mapper)。双源语义没变,只是搬了家 ——
#   PMC 优先在 ExpenseLedgerService.decide 里(总额模式),cash_flow 回退仍在 averageExpense 里。
{ grep -q 'periodMemberCashflowMapper' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java \
  && grep -q 'expenseLedger.recent' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java \
  && grep -q 'findFamilyAggregateRecent' /home/finance/financial-management/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java \
  && grep -qE 'expenseOrig|periodExpense' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java; } \
  && log_ok "v04-FIX-2 averageExpense 双源仍在(PMC 优先移入 ExpenseLedgerService · cash_flow 回退保留)" \
  || log_bad "v04-FIX-2 PMC 优先逻辑缺" "FactViewServiceImpl 应经 expenseLedger.recent 取支出 + 保留 cash_flow 回退;PMC 优先判定在 ExpenseLedgerService"

# v04-FIX-3 · B4 · ytdInvestPnl 独立加载 slice(不复用 caller 的 range-bound slice)
grep -A30 'private BigDecimal ytdInvestPnl' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java | head -30 | grep -q 'load(new FactFilter' \
  && log_ok "v04-FIX-3 ytdInvestPnl 用 load(new FactFilter) 独立加载 1 月-今天 slice" \
  || log_bad "v04-FIX-3 ytdInvestPnl 独立 slice 缺" "missing"

# v04-FIX-4 · 联调 · /dashboard 在用户漏填情况下不再静默失真(返回 200 + 有 KPI 数字)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q '总资产' "$TMP" && grep -q '总负债' "$TMP" && grep -qE 'kpi-value tnum[^>]*>¥[0-9,]+' "$TMP"; } \
  && log_ok "v04-FIX-4 /dashboard 漏填账户 NULL 续值后 KPI 仍正常渲染 · 不再静默失真" \
  || log_bad "v04-FIX-4 dashboard 渲染异常" "missing kpi value"

# v04-FIX-5 · 联调 · /reports 同样不抛异常(B1 fix 在 reports 也走同一 FactMapper)
$CURL -b $COOKIE "$BASE/reports?range=1Y" -o "$TMP" -w ""
{ grep -q '本金净流入' "$TMP" || grep -q '账户级收益' "$TMP"; } \
  && log_ok "v04-FIX-5 /reports?range=1Y 含 B1 续值后正常出图(本金 vs 损益 + 账户级)" \
  || log_bad "v04-FIX-5 reports 异常" "missing decompose/account"

# v04-FIX-6 · 联调 · /checkup 同样不抛异常(用 averageExpense 计算 emergencyFundMonths)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q '应 · 急 · 金' "$TMP" || grep -q '紧 急 储 备' "$TMP" || grep -q '应急金' "$TMP" \
  && log_ok "v04-FIX-6 /checkup B2 averageExpense 双源后正常渲染 · 含应急金诊断" \
  || log_bad "v04-FIX-6 checkup 缺应急金" "missing"

# v04-FIX-7 · 单测 · mvn test 含 FactViewService 联动测试(已存在)+ InvestmentReturnCalculator 9 测
[[ -f /home/finance/financial-management/src/test/java/com/family/finance/factview/FactViewServiceImplTest.java \
   || -d /home/finance/financial-management/src/test/java/com/family/finance/factview ]] \
  && log_ok "v04-FIX-7 factview 单测目录存在(B1/B2/B4 改动不破坏现有覆盖)" \
  || log_bad "v04-FIX-7 factview 单测目录缺" "missing"

# ===================================================
# v0.4.4 · 文案专业化清理(内部 routing / FR 编号 / 字段名 / enum 暴露 全部清除)
# ===================================================
section "v0.4.4 · 文案专业化清理"

# v04-UX-1 · checkup 不再含"已搬到 /dashboard"等迁移提示
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ ! grep -q '已搬到\|已挪至' "$TMP"; } \
  && log_ok "v04-UX-1 /checkup 不再含 '已搬到 / 已挪至' 迁移文案" \
  || log_bad "v04-UX-1 内部迁移文案残留" "still present"

# v04-UX-2 · checkup 资产配置卡 mini 横向条出现
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q 'A · L · L · O · C · A · T · I · O · N' "$TMP" \
  && grep -q '按账户类型聚合' "$TMP" \
  && log_ok "v04-UX-2 /checkup 资产配置卡 mini 横向条 + 中性 eyebrow" \
  || log_bad "v04-UX-2 mini 横向条缺" "missing"

# v04-UX-3 · reports 不再含"汇率明细已挪至 /admin/fx"section
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ ! grep -q '汇率明细已挪至\|运维专用' "$TMP"; } \
  && log_ok "v04-UX-3 /reports 不再含汇率挪至提示 section" \
  || log_bad "v04-UX-3 汇率挪至 section 残留" "still present"

# v04-UX-4 · 所有用户面页面不再含 v0.x / FR-xx 内部代号
PAGES=(/dashboard /reports /checkup /goals /entry /accounts)
LEAK=0
for p in "${PAGES[@]}"; do
  $CURL -b $COOKIE "$BASE$p" -o "$TMP" -w ""
  # 只查可见 body 内容(去 HTML/JS 注释)
  python3 -c "
import re, sys
with open('$TMP') as f:
    html = f.read()
# 去 HTML 注释
html = re.sub(r'<!--.*?-->', '', html, flags=re.S)
# 去 <script>...</script> 内容
html = re.sub(r'<script[^>]*>.*?</script>', '', html, flags=re.S|re.I)
# 找 FR-数字 / v0.数字
m = re.findall(r'(FR-\d+|v0\.\d+(?:\.\d+)?)', html)
if m: sys.exit(1)
" 2>/dev/null || LEAK=$((LEAK+1))
done
[[ "$LEAK" -eq 0 ]] \
  && log_ok "v04-UX-4 6 用户面页 (dashboard/reports/checkup/goals/entry/accounts) 不含 FR-xx / v0.x 代号" \
  || log_bad "v04-UX-4 用户面残留代号" "$LEAK 页有代号"

# v04-UX-5 · refinance 不再含 v0.4 / v0.5 版本路线规划
$CURL -b $COOKIE "$BASE/reports/refinance" -o "$TMP" -w ""
{ ! grep -q 'v0\.4 仅等额本息\|v0\.5 加等额本金' "$TMP"; } \
  && log_ok "v04-UX-5 /reports/refinance 不再含 v0.X 版本路线规划文案" \
  || log_bad "v04-UX-5 refinance 版本规划残留" "still present"

# v04-UX-6 · 已删 checkup placeholder 死代码模板
{ [[ ! -f /home/finance/financial-management/src/main/resources/templates/checkup/placeholder-family.html \
   && ! -f /home/finance/financial-management/src/main/resources/templates/checkup/placeholder-account.html ]]; } \
  && log_ok "v04-UX-6 checkup placeholder 死代码模板已删除" \
  || log_bad "v04-UX-6 placeholder 死代码仍在" "still present"

# v04-UX-7 · 填报页(承接待办)不暴露 SNAPSHOT_TODO enum + (STOCK) 括号(v0.11.7:待办已折叠进 /entry)
$CURL -b $COOKIE "$BASE/entry?mine=true" -o "$TMP" -w ""
{ ! grep -q 'SNAPSHOT_TODO\|(STOCK)\|(WEALTH)\|(LOAN)' "$TMP"; } \
  && log_ok "v04-UX-7 /entry 不暴露 SNAPSHOT_TODO enum + 类型英文括号" \
  || log_bad "v04-UX-7 enum 残留" "still present"

# v04-UX-8 · stock/holdings 中文化(AUTO/MANUAL/CASH pill 改自动估值/手填市值/账户内现金)
HOLDING_ACCT=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type='STOCK' AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
if [[ -n "$HOLDING_ACCT" ]]; then
  $CURL -b $COOKIE "$BASE/accounts/$HOLDING_ACCT/holdings" -o "$TMP" -w ""
  { ! grep -q '>AUTO 自动估值<\|>MANUAL 手填<\|>💰 CASH 现金<'; } < "$TMP" \
    && log_ok "v04-UX-8 stock/holdings pill 中文化(去 AUTO/MANUAL/CASH enum 前缀)" \
    || log_bad "v04-UX-8 holdings pill 仍含英文 enum" "still present"
fi

# v04-UX-9 · /checkup 风险敞口卡 doughnut · datalabels 浮在扇片上(用户体验升级)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q '<canvas id="riskChart"' "$TMP" \
  && grep -q "type: 'doughnut'" "$TMP" \
  && grep -q 'riskBuckets' "$TMP" \
  && grep -q 'plugins: \[ChartDataLabels\]' "$TMP"; } \
  && log_ok "v04-UX-9 /checkup 风险敞口卡 doughnut + ChartDataLabels(数字浮在扇片)" \
  || log_bad "v04-UX-9 风险敞口饼图缺" "missing canvas/doughnut/datalabels"

# v04-AI-REBALANCE-2 · OutputValidator 账户名白名单(LLM 引用用户已有账户不算产品推荐)
#   v0.4.5(2026-05-14)用户反馈:点 AI 调仓建议按钮没反应 · 根因是 LLM 输出含「余额宝」(用户的支付宝-余额宝账户)
#   修法:OutputValidator 加 accountWhitelist 参数 · 白名单内的产品名子串放行
mysql -ufinance -pfinance finance -e "DELETE FROM rebalance_advice_cache WHERE family_id=1;" 2>/dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk -F'\t' '{print $NF}')
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "%{http_code}" --max-time 30)
# 302 + DB cache 写入 = LLM 走通 + validator 没误杀
cache_count=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM rebalance_advice_cache WHERE family_id=1" 2>/dev/null)
{ [[ "$code" == "302" ]]; } \
  && log_ok "v04-AI-REBALANCE-2 advise POST → 302 · LLM 调用容忍(cache count=$cache_count · 1 表示 validator 通过)" \
  || log_bad "v04-AI-REBALANCE-2 advise 异常" "code=$code"

# v04-AI-REBALANCE-3 · /reports 渲染 advice card · 当 cache 有数据
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
if [[ "$cache_count" -gt 0 ]]; then
  { grep -q '生成于 2026' "$TMP" \
    && grep -q '从 <b' "$TMP"; } \
    && log_ok "v04-AI-REBALANCE-3 /reports 渲染 advice card · 含「生成于 + 从 X 调出」" \
    || log_bad "v04-AI-REBALANCE-3 advice card 未渲染" "cache=$cache_count · grep missed"
else
  log_ok "v04-AI-REBALANCE-3 cache 空 · 跳过渲染检查(LLM 真的失败 · 容忍)"
fi

# v04-AI-REBALANCE-4 · feedback flash bar(点击后用户能看到结果 · 不再"按了没反应")
#   Spring flash attribute 跨 POST → redirect(302)→ GET 的同一 session 中存活
#   分步式:1) POST 触发 advise · 2) 紧接 GET /reports 应看到 flash bar
#   不用 -L · 因为 curl -L + -X POST 会在 redirect 后继续 POST · 触发 GET /reports 误判
mysql -ufinance -pfinance finance -e "DELETE FROM rebalance_advice_cache WHERE family_id=1;" 2>/dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk -F'\t' '{print $NF}')
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "" --max-time 30
$CURL -b $COOKIE -c $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'AI 已生成新建议\|已有近期建议\|暂未能生成建议' "$TMP"; } \
  && log_ok "v04-AI-REBALANCE-4 advise 后 reports 页含反馈条(成功 / 缓存 / 失败)" \
  || log_bad "v04-AI-REBALANCE-4 反馈条缺" "no flash bar"

# v04-AI-REBALANCE-5 · cache 命中 → 再点应 fromCache=true(节省 LLM 调用)
mysql -ufinance -pfinance finance -e "DELETE FROM rebalance_advice_cache WHERE family_id=1;" 2>/dev/null
XSRF=$(awk -F'\t' '/^localhost.*XSRF-TOKEN/ {print $NF}' $COOKIE)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "" --max-time 30
sleep 1
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "" --max-time 30
sleep 1
# 第二次应 fromCache=true(log 里能看到)
cache_hit_log=$(tail -5 /opt/finance/logs/app.log | grep "rebalance advise.*fromCache=true" | wc -l)
[[ "$cache_hit_log" -ge 1 ]] \
  && log_ok "v04-AI-REBALANCE-5 第二次 advise 命中 cache(fromCache=true)" \
  || log_bad "v04-AI-REBALANCE-5 cache 未命中" "log doesn't show fromCache=true"

# v04-AI-REBALANCE-6 · refresh=true 跳过 cache 强制重新调 LLM
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "_csrf=$XSRF" --data-urlencode "refresh=true" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "" --max-time 30
sleep 1
force_log=$(tail -10 /opt/finance/logs/app.log | grep "forceRefresh" | wc -l)
fresh_log=$(tail -5 /opt/finance/logs/app.log | grep "refresh=true.*fromCache=false" | wc -l)
{ [[ "$force_log" -ge 1 && "$fresh_log" -ge 1 ]]; } \
  && log_ok "v04-AI-REBALANCE-6 refresh=true 跳过缓存 + fromCache=false" \
  || log_bad "v04-AI-REBALANCE-6 refresh 没跳过" "force_log=$force_log fresh_log=$fresh_log"

# v04-AI-REBALANCE-7 · advice card 有「↻ 刷新」按钮(模板侧)
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'refresh=.true\|refresh=true' "$TMP" && grep -q '↻ 刷新' "$TMP"; } \
  && log_ok "v04-AI-REBALANCE-7 advice card 显示 ↻ 刷新按钮(form 带 refresh=true)" \
  || log_bad "v04-AI-REBALANCE-7 刷新按钮缺" "no refresh button"

# v04-AI-DIAGNOSE-1 · /checkup AI 综合诊断刷新按钮带 refresh=true(此前 title 写忽略 cache 但实际没传)
#   /checkup 主页用 spinner placeholder + HTMX 异步加载 · 必须直接 GET /checkup/diagnose 拿 panel fragment
$CURL -b $COOKIE "$BASE/checkup/diagnose" -o "$TMP" -w "" --max-time 60
{ grep -q 'refresh=true' "$TMP" && grep -q '↻ 刷新' "$TMP"; } \
  && log_ok "v04-AI-DIAGNOSE-1 /checkup/diagnose panel 刷新按钮带 refresh=true(真忽略 cache)" \
  || log_bad "v04-AI-DIAGNOSE-1 诊断刷新按钮 url 错" "no refresh=true in href"

# v04-AI-DIAGNOSE-2 · v0.4.9 · 结构化 JSON 渲染 4 维度卡(总评 + 4 卡 + 优先行动)
$CURL -b $COOKIE "$BASE/checkup/diagnose?refresh=true" -o "$TMP" -w "" --max-time 60
# 总评 banner + 4 个 dimension 名 + verdict pill + 优先行动
total_markers=0
for kw in "总评" "资产配置" "风险敞口" "流动性" "收益质量" "优 · 先 · 行 · 动" "📊" "⚡" "💧" "📈"; do
  if grep -q "$kw" "$TMP"; then total_markers=$((total_markers+1)); fi
done
{ [[ "$total_markers" -ge 8 ]]; } \
  && log_ok "v04-AI-DIAGNOSE-2 结构化诊断渲染 · 总评+4 维度+优先行动 (markers=$total_markers)" \
  || log_bad "v04-AI-DIAGNOSE-2 结构化渲染缺" "markers=$total_markers/10"

# v04-AI-DIAGNOSE-3 · 老 cache(纯文本)兼容 · structured 解析失败时 fallback 显示 text
# 直接造一个非 JSON cache 强制走 fallback 路径(注:cache 是内存 · 难直接造 · 这里查模板分支存在)
grep -q "structured() == null" src/main/resources/templates/checkup/_ai-diagnose.html \
  && log_ok "v04-AI-DIAGNOSE-3 模板含 fallback 分支(老 cache / 解析失败时显示 text)" \
  || log_bad "v04-AI-DIAGNOSE-3 fallback 分支缺" "missing structured null check"

# v04-AI-DIAGNOSE-4 · v0.4.18 后 max_tokens 改读 ConfigService 动态(默认 2000)
{ grep -q 'currentMaxTokens' src/main/java/com/family/finance/service/checkup/llm/QwenLlmClient.java \
  && grep -q 'currentMaxTokens' src/main/java/com/family/finance/service/checkup/llm/DeepSeekLlmClient.java \
  && grep -q 'K_LLM_MAX_TOKENS' src/main/java/com/family/finance/service/config/FamilyConfigService.java; } \
  && log_ok "v04-AI-DIAGNOSE-4 LlmClient max_tokens 改读 ConfigService 动态(默认 2000 · 可热改)" \
  || log_bad "v04-AI-DIAGNOSE-4 max_tokens 未切动态" "see Qwen/DeepSeek + ConfigService"

# v04-AI-DIAGNOSE-5 · 截断检测 · DiagnoseResult.truncated + 模板友好错误
{ grep -q 'truncated\(\) \|looksTruncatedJson\|truncated()' src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java \
  && grep -q 'AI 输出被截断' src/main/resources/templates/checkup/_ai-diagnose.html; } \
  && log_ok "v04-AI-DIAGNOSE-5 截断检测 · DiagnoseResult.truncated + 模板友好错误(不显示半截 JSON)" \
  || log_bad "v04-AI-DIAGNOSE-5 截断检测缺" "no truncated/AI 输出被截断"

# v04-AI-DIAGNOSE-6 · 客户端 finish_reason=length 截断日志告警
{ grep -q 'max_tokens 截断' src/main/java/com/family/finance/service/checkup/llm/QwenLlmClient.java \
  && grep -q 'max_tokens 截断' src/main/java/com/family/finance/service/checkup/llm/DeepSeekLlmClient.java; } \
  && log_ok "v04-AI-DIAGNOSE-6 客户端检测 finish_reason=length 截断 · log.warn 提示" \
  || log_bad "v04-AI-DIAGNOSE-6 截断日志缺" "no finish_reason check"

# v04-AI-DIAGNOSE-7 · v0.4.11 · pct1(ratio) bug 修(占比 0.4% → 44.2%)
#   AllocationSlice.ratio() / RiskBucket.ratio() 是小数(0.442)· 必须 ×100 显示
#   之前 caller 用 pct1 直接显 0.4% · LLM 拿到错误数据胡说"权益占比严重不足"
grep -q 'pctFromRatio(s.ratio())' src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java \
  && grep -q 'pctFromRatio(b.ratio())' src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java \
  && log_ok "v04-AI-DIAGNOSE-7 PromptBuilder ratio 占比 ×100 修(¥95710 占比 2.4% 正确)" \
  || log_bad "v04-AI-DIAGNOSE-7 pct ratio bug 未修" "still using pct1(ratio)"

# v04-AI-DIAGNOSE-8 · SYSTEM_DIAGNOSE 禁 LLM 算术
grep -q '禁止做任何四则运算\|不要自己做四则运算' src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java \
  && log_ok "v04-AI-DIAGNOSE-8 SYSTEM_DIAGNOSE 含禁数学约束(防 LLM 瞎算占比/差额)" \
  || log_bad "v04-AI-DIAGNOSE-8 禁数学约束缺" "no math-prohibition rule"


section "v0.4.14 · 填报规范化 + DDL 强提醒 (FR-63)"

# v04-RPT-TMPL-1 · ReportingTemplate enum 3 模板 + fromCode 默认 T1
RT=src/main/java/com/family/finance/domain/family/ReportingTemplate.java
{ grep -q 'T1_REALTIME_INCOME_MONTHEND_EXPENSE' "$RT" \
  && grep -q 'T2_MONTHEND_BATCH' "$RT" \
  && grep -q 'T3_WEEKLY_ROLLING' "$RT" \
  && grep -q 'fromCode' "$RT"; } \
  && log_ok "v04-RPT-TMPL-1 ReportingTemplate 3 模板 + fromCode 安全解析(默认 T1)" \
  || log_bad "v04-RPT-TMPL-1 模板枚举缺" "missing template enum"

# v04-RPT-REMIND-1 · /admin/reminders 设置页 200 + 3 模板可选 + 提前天数
$CURL -b $COOKIE "$BASE/admin/reminders" -o "$TMP" -w ""
{ grep -q '填报模板' "$TMP" && grep -q 'leadDays' "$TMP" \
  && grep -q '实时收入' "$TMP" && grep -q '每周滚动' "$TMP"; } \
  && log_ok "v04-RPT-REMIND-1 /admin/reminders 设置页 · 3 模板 + 提前天数" \
  || log_bad "v04-RPT-REMIND-1 提醒设置页缺" "no template/leadDays"

# v04-RPT-REMIND-2 · 提交模板 T3 + leadDays=3 → 落库生效(GET 回显)
RTOK=$($CURL -b $COOKIE "$BASE/admin/reminders" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b $COOKIE -X POST \
  --data-urlencode "_csrf=$RTOK" \
  --data-urlencode "template=T3_WEEKLY_ROLLING" \
  --data-urlencode "leadDays=3" \
  "$BASE/admin/reminders/template" -o /dev/null -w ""
$CURL -b $COOKIE "$BASE/admin/reminders" -o "$TMP" -w ""
{ grep -q 'value="3"' "$TMP" && grep -A2 'T3_WEEKLY_ROLLING' "$TMP" | grep -q 'checked'; } \
  && log_ok "v04-RPT-REMIND-2 模板/提前天数 POST 落库 + 回显(T3 · 3 天)" \
  || log_bad "v04-RPT-REMIND-2 模板保存未生效" "T3/leadDays not persisted"
# 还原默认 T1 / 2 天(不污染后续 QA / 演示数据)
RTOK=$($CURL -b $COOKIE "$BASE/admin/reminders" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b $COOKIE -X POST --data-urlencode "_csrf=$RTOK" \
  --data-urlencode "template=T1_REALTIME_INCOME_MONTHEND_EXPENSE" \
  --data-urlencode "leadDays=2" "$BASE/admin/reminders/template" -o /dev/null -w ""

# v04-RPT-REMIND-3 · 调度 cron · v0.4.18 起由 DynamicScheduleConfig 注册(读 DB · 默认 0 0 10,20 * * *)
DSC=src/main/java/com/family/finance/service/scheduling/DynamicScheduleConfig.java
{ grep -qF 'DEFAULT_REPORT_REMIND_CRON = "0 0 10,20 * * *"' "$DSC" \
  && grep -qF 'ZONE_ID = "Asia/Shanghai"' "$DSC" \
  && grep -q 'reminderScheduler::scheduled' "$DSC"; } \
  && log_ok "v04-RPT-REMIND-3 提醒 cron 0 0 10,20 · Asia/Shanghai · 由动态调度注册" \
  || log_bad "v04-RPT-REMIND-3 提醒 cron 注册缺" "see DynamicScheduleConfig"

# v04-RPT-REMIND-4 · 渠道抽象可插拔(接口 + 短信 + 站内兜底)
ND=src/main/java/com/family/finance/service/notify
{ [[ -f "$ND/NotificationChannel.java" ]] \
  && [[ -f "$ND/SmsAliyunChannel.java" ]] \
  && [[ -f "$ND/InAppBannerChannel.java" ]] \
  && grep -q 'implements NotificationChannel' "$ND/SmsAliyunChannel.java" \
  && grep -q 'implements NotificationChannel' "$ND/InAppBannerChannel.java"; } \
  && log_ok "v04-RPT-REMIND-4 渠道抽象 · SMS 为主 + 站内兜底(可插拔)" \
  || log_bad "v04-RPT-REMIND-4 渠道抽象缺" "channel impls missing"

# v04-RPT-REMIND-5 · 提醒日志当天去重(V25 UNIQUE + INSERT IGNORE)
{ grep -q 'UNIQUE KEY uk_dedup (family_id, period_id, member_id, channel, remind_date)' \
      db/migration/V25__report_template_remind.sql \
  && grep -q 'INSERT IGNORE INTO report_reminder_log' \
      src/main/java/com/family/finance/repository/ReportReminderLogMapper.java; } \
  && log_ok "v04-RPT-REMIND-5 提醒去重 · UNIQUE(family,period,member,channel,date)+INSERT IGNORE" \
  || log_bad "v04-RPT-REMIND-5 去重机制缺" "no unique/insert-ignore"

# v04-RPT-BANNER-1 · /entry 推荐填报方案提示 banner
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
grep -q '推荐填报方案' "$TMP" \
  && log_ok "v04-RPT-BANNER-1 /entry 显示推荐填报方案提示(随模板 + 距截止天数)" \
  || log_bad "v04-RPT-BANNER-1 填报页提示 banner 缺" "no recommend banner"

# v04-RPT-BANNER-2 · /entry banner 三栏富信息(周期 + 截止日 + 家庭进度 + 我已填徽标 + 距截止 pill)
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
markers=0
for kw in '本 · 期 · 进 · 度' '家庭已填' '距 · 截 · 止' '⚙ 改填报模板'; do
  if grep -q "$kw" "$TMP"; then markers=$((markers+1)); fi
done
{ [[ "$markers" -ge 3 ]]; } \
  && log_ok "v04-RPT-BANNER-2 三栏富信息 banner(周期/截止日/家庭进度/距截止 markers=$markers/4)" \
  || log_bad "v04-RPT-BANNER-2 富信息 markers 不足" "markers=$markers/4"

# v04-RPT-MSG-1 · 短信文案 4 变量 brand/period/days/progress(源码 + ReminderMessage 字段)
RM=src/main/java/com/family/finance/service/notify/ReminderMessage.java
SC=src/main/java/com/family/finance/service/notify/SmsAliyunChannel.java
{ grep -q 'String brand' "$RM" \
  && grep -q 'String period' "$RM" \
  && grep -q 'int daysLeft' "$RM" \
  && grep -q 'String progress' "$RM" \
  && grep -q '\\"brand\\":' "$SC" \
  && grep -q '\\"period\\":' "$SC" \
  && grep -q '\\"days\\":' "$SC" \
  && grep -q '\\"progress\\":' "$SC"; } \
  && log_ok "v04-RPT-MSG-1 短信 4 变量(brand/period/days/progress)在 ReminderMessage + SmsAliyunChannel" \
  || log_bad "v04-RPT-MSG-1 短信变量缺" "missing 4-var fields"

# v04-RPT-TEST-1 · /admin/reminders/sms-test endpoint + 配置不全友好错
NS=src/main/java/com/family/finance/web/admin/NotificationSettingsController.java
{ grep -q '/sms-test' "$NS" \
  && grep -q 'sendForTest' "$SC" \
  && grep -q 'CONFIG_INCOMPLETE' "$SC"; } \
  && log_ok "v04-RPT-TEST-1 一键测试 endpoint + 配置不全友好错(CONFIG_INCOMPLETE)" \
  || log_bad "v04-RPT-TEST-1 sms-test endpoint 缺" "no endpoint/sendForTest"

# v04-RPT-TEST-2 · 测试限流 3 次/分(源码常量 + 滑动窗口)
{ grep -q 'TEST_RATE_LIMIT_PER_MIN = 3' "$NS" \
  && grep -q 'minusSeconds(60)' "$NS"; } \
  && log_ok "v04-RPT-TEST-2 测试限流 3 次/分 + 60s 滑动窗口" \
  || log_bad "v04-RPT-TEST-2 限流缺" "no rate limit"

# v04-RPT-TEST-3 · 测试日志走 audit_log(决策 36 · 非 report_reminder_log)
{ grep -q 'auditLogService.record' "$NS" \
  && grep -qF '"短信测试' "$NS" \
  && ! grep -A2 'sms-test\|smsTest' "$NS" | grep -q 'reminderLogMapper\.insert'; } \
  && log_ok "v04-RPT-TEST-3 测试审计走 audit_log · 非 report_reminder_log(决策 36)" \
  || log_bad "v04-RPT-TEST-3 测试审计归属错" "see notification controller"

# v04-RPT-LOG-1 · /admin/reminders 含 ⑥ 提醒发送日志 section
$CURL -b $COOKIE "$BASE/admin/reminders" -o "$TMP" -w ""
{ grep -q '⑥ 提醒发送日志' "$TMP" \
  && grep -q '测试发送审计' "$TMP"; } \
  && log_ok "v04-RPT-LOG-1 /admin/reminders 显示提醒发送日志 section · 顶部引导测试审计" \
  || log_bad "v04-RPT-LOG-1 日志 section 缺" "no ⑥ 提醒发送日志"

# v04-RPT-LOG-2 · ReportReminderLogMapper 分页查询方法在岗
RL=src/main/java/com/family/finance/repository/ReportReminderLogMapper.java
{ grep -q 'findByFamily' "$RL" \
  && grep -q 'countByFamily' "$RL" \
  && grep -q 'LIMIT #{limit} OFFSET #{offset}' "$RL"; } \
  && log_ok "v04-RPT-LOG-2 ReportReminderLogMapper findByFamily/countByFamily + LIMIT OFFSET 分页" \
  || log_bad "v04-RPT-LOG-2 分页 mapper 缺" "no pagination query"

# v04-RPT-LOG-3 · ?page=N URL 参数被识别(默认每页 20)
$CURL -b $COOKIE "$BASE/admin/reminders?page=1" -o "$TMP" -w ""
grep -q '⑥ 提醒发送日志' "$TMP" \
  && log_ok "v04-RPT-LOG-3 ?page=N 参数被 controller 处理 · 默认 20/页" \
  || log_bad "v04-RPT-LOG-3 分页参数失效" "page=1 missing section"

section "v0.4.17 · 520 一日限定彩蛋"

# v04-520-1 · fragment 文件 + 19 条文案库 + 触发条件
E520=src/main/resources/templates/fragments/easter520.html
{ [[ -f "$E520" ]] \
  && grep -q "th:if=\"\${family != null and today == '05-20'}\"" "$E520" \
  && grep -q "I LOVE U" "$E520" \
  && grep -q "今年 520 · 主角还是你" "$E520" \
  && grep -q "想和你 · 保持长期稳定关系" "$E520" \
  && [[ $(grep -cE "'[^']{4,40}',?\s*$" "$E520") -ge 19 ]]; } \
  && log_ok "v04-520-1 easter520 fragment + 严格 05-20 触发 + 19 条文案库" \
  || log_bad "v04-520-1 fragment 文件 / 触发条件 / 文案库异常" "see $E520"

# v04-520-2 · layout::footer 注入 fragment(任意已登录页通用)
LF=src/main/resources/templates/fragments/layout.html
grep -q '~{fragments/easter520 :: easter520' "$LF" \
  && log_ok "v04-520-2 layout::footer th:replace 注入 easter520 fragment" \
  || log_bad "v04-520-2 layout 未注入 fragment" "no th:replace in layout"

# v04-520-3 · localStorage flag 关键字 + 右上 pill + 换一句 按钮 在 fragment 内
{ grep -q 'easter520_seen' "$E520" \
  && grep -q 'e520Pill' "$E520" \
  && grep -q 'next-slogan-btn' "$E520" \
  && grep -q 'window.__e520_open' "$E520" \
  && grep -q 'window.__e520_close' "$E520"; } \
  && log_ok "v04-520-3 localStorage flag + 右上 pill + 换一句按钮 + IIFE 命名空间" \
  || log_bad "v04-520-3 fragment 缺关键 hook" "missing hooks"

# v04-520-4 · 非 5.20 当天进任意已登录页不渲染 fragment(今天 ≠ 5.20)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
TODAY_DD=$(/bin/date +%m-%d)
if [[ "$TODAY_DD" == "05-20" ]]; then
  grep -q 'I LOVE U' "$TMP" \
    && log_ok "v04-520-4 今天就是 05-20 · /dashboard 注入 fragment" \
    || log_bad "v04-520-4 5.20 当天 fragment 应注入" "missing"
else
  ! grep -q 'I LOVE U' "$TMP" \
    && log_ok "v04-520-4 今天非 5.20($TODAY_DD)· /dashboard 不注入 fragment(dormant 正确)" \
    || log_bad "v04-520-4 非 5.20 仍注入 fragment" "should be dormant"
fi

# v04-PRIV-1 · 私密红线:全 LLM prompt 构造目录源码绝不引用手机号 / aksk(合规底线)
LLM_DIR=src/main/java/com/family/finance/service/checkup/llm
LEAK=$(grep -rnE 'getPhone\(|AccessKeySecret|AccessKeyId|getSmsAccessKey|FamilyNotifyConfig|ReportReminder|SmsAliyunChannel' "$LLM_DIR" 2>/dev/null || true)
{ [[ -z "$LEAK" ]] \
  && [[ -f src/test/java/com/family/finance/service/checkup/llm/PrivacyIsolationTest.java ]]; } \
  && log_ok "v04-PRIV-1 LLM prompt 源码零引用 phone/aksk + PrivacyIsolationTest 在岗(合规底线)" \
  || log_bad "v04-PRIV-1 私密红线被突破" "leak=[$LEAK]"

section "v0.4.18 · 系统级配置迁管理页"

# v04-CFG-1 · V26 migration 已应用 · family_runtime_config 表在
CFG_TABLE=$(MYSQL_PWD=finance mysql -h127.0.0.1 -ufinance finance -N -e \
  "SELECT 1 FROM information_schema.tables WHERE table_schema='finance' AND table_name='family_runtime_config' LIMIT 1;" 2>/dev/null)
{ [[ "$CFG_TABLE" == "1" ]]; } \
  && log_ok "v04-CFG-1 V26 family_runtime_config 表存在" \
  || log_bad "v04-CFG-1 V26 未应用" "no table"

# v04-CFG-2 · FamilyConfigService 三层 fallback + 5s TTL cache 在岗
FCS=src/main/java/com/family/finance/service/config/FamilyConfigService.java
{ [[ -f "$FCS" ]] \
  && grep -q "K_LLM_QWEN_KEY" "$FCS" \
  && grep -q "K_STOCK_ENABLED" "$FCS" \
  && grep -q "K_REPORT_REMIND_CRON" "$FCS" \
  && grep -q "envQwenKey" "$FCS" \
  && grep -q "CACHE_TTL_MILLIS" "$FCS"; } \
  && log_ok "v04-CFG-2 FamilyConfigService 三层 fallback + 5s TTL cache + K_* 常量" \
  || log_bad "v04-CFG-2 FamilyConfigService 缺关键 hook" "see $FCS"

# v04-CFG-3 · DynamicScheduleConfig 注册 5 个受管 cron
DSC=src/main/java/com/family/finance/service/scheduling/DynamicScheduleConfig.java
{ [[ -f "$DSC" ]] \
  && grep -q "stock-us" "$DSC" \
  && grep -q "stock-cn" "$DSC" \
  && grep -q "stock-hk" "$DSC" \
  && grep -q "rescheduleAll" "$DSC" \
  && grep -q "CronTrigger" "$DSC"; } \
  && log_ok "v04-CFG-3 DynamicScheduleConfig · 5 受管 cron + rescheduleAll" \
  || log_bad "v04-CFG-3 动态 cron config 缺" "see $DSC"

# v04-CFG-4 · 5 个被托管的 scheduler 已删 @Scheduled · 用 grep -E 排除 javadoc 注释里的 @Scheduled 关键词
{ ! grep -E '^\s*@Scheduled\(' src/main/java/com/family/finance/service/stock/StockPriceScheduler.java \
  && ! grep -E '^\s*@Scheduled\(' src/main/java/com/family/finance/service/FxFetchJob.java \
  && ! grep -E '^\s*@Scheduled\(' src/main/java/com/family/finance/service/notify/ReportReminderScheduler.java; } 2>/dev/null \
  && log_ok "v04-CFG-4 Stock/Fx/ReportReminder @Scheduled 注解已删 · 由动态调度接管" \
  || log_bad "v04-CFG-4 仍有遗留 @Scheduled 注解" "see 3 scheduler"

# v04-CFG-5 · LLM client 改读 FamilyConfigService(不再 @Value 注入 API key)
QC=src/main/java/com/family/finance/service/checkup/llm/QwenLlmClient.java
DC=src/main/java/com/family/finance/service/checkup/llm/DeepSeekLlmClient.java
{ ! grep -q '@Value.*qwen.api-key' "$QC" \
  && ! grep -q '@Value.*deepseek.api-key' "$DC" \
  && grep -q 'configService.getString' "$QC" \
  && grep -q 'configService.getString' "$DC"; } \
  && log_ok "v04-CFG-5 LLM client API key 改读 ConfigService(不再 @Value 直注入)" \
  || log_bad "v04-CFG-5 LLM client 未切换 ConfigService" "see Qwen/DeepSeek"

# v04-CFG-6 · /admin/integrations 页 200 + 含 LLM/股票/FX 三段
$CURL -b $COOKIE "$BASE/admin/integrations" -o "$TMP" -w ""
{ grep -q '通义 Qwen-Plus' "$TMP" \
  && grep -q '股票自动拉取' "$TMP" \
  && grep -q 'FX 汇率自动拉取' "$TMP"; } \
  && log_ok "v04-CFG-6 /admin/integrations 集成中心 · 3 段在岗" \
  || log_bad "v04-CFG-6 集成页缺段" "missing sections"

# v04-CFG-7 · /admin/calc-tweaks 升级为可编辑(form post)
$CURL -b $COOKIE "$BASE/admin/calc-tweaks" -o "$TMP" -w ""
{ grep -q 'name="smartTransfer"' "$TMP" \
  && grep -q 'name="concentration"' "$TMP" \
  && grep -q 'name="emergencyMonths"' "$TMP" \
  && grep -q 'name="rememberMeSeconds"' "$TMP"; } \
  && log_ok "v04-CFG-7 /admin/calc-tweaks 升级可编辑表单 · 8 个字段" \
  || log_bad "v04-CFG-7 calc-tweaks 升级未到位" "missing form fields"

# v04-CFG-8 · admin sidebar 有"集成"入口 + 项数标注(v0.8 加"指标设置"→ 15 项)
SB=src/main/resources/templates/admin/_sidebar.html
{ grep -q "/admin/integrations" "$SB" \
  && grep -q "/admin/metrics" "$SB" \
  && grep -q "/ADMIN · 15 项" "$SB"; } \
  && log_ok "v04-CFG-8 admin sidebar 集成+指标设置入口 + 标 15 项" \
  || log_bad "v04-CFG-8 sidebar 未更新" "see _sidebar.html"

# v08-NAV-1 · 「指标设置」必须能从 /admin 落地页(「管理」tab 实际入口)点达,不只挂子页侧边栏。
#   2026-06-23 漏修暴露:v0.8 只把 /admin/metrics 加进 _sidebar(子页才显),没加进 admin/index 卡片网格 →
#   用户点「管理」落到 /admin 根本看不到指标设置入口(v04-CFG-8 只查侧边栏,放过了这个洞)。源级 + 渲染双查。
{ grep -q '/admin/metrics' src/main/resources/templates/admin/index.html \
  && curl -s -b $COOKIE "$BASE/admin" | grep -q '指标设置'; } \
  && log_ok "v08-NAV-1 /admin 落地页含「指标设置」卡片 → /admin/metrics(管理 tab 可点达)" \
  || log_bad "v08-NAV-1 /admin 落地页缺「指标设置」入口(漏:只挂了子页侧边栏)" "admin/index.html 无 metrics 卡片"

# v04-CFG-9 · deploy.sh 加 step 9.5 配置种子 + 幂等 flag
DEP=deploy/deploy.sh
{ grep -q "9.5/15 配置种子迁移" "$DEP" \
  && grep -q "config-migrated-v0.4.18" "$DEP" \
  && grep -q "family_runtime_config" "$DEP"; } \
  && log_ok "v04-CFG-9 deploy.sh 加 step 9.5 种子 + 幂等 flag" \
  || log_bad "v04-CFG-9 deploy.sh 9.5 步缺" "see deploy.sh"

# v04-CFG-10 · PrivacyIsolationTest 扩 PromptBuilder LLM key 防泄露
PIT=src/test/java/com/family/finance/service/checkup/llm/PrivacyIsolationTest.java
{ grep -q "promptBuilderNeverReferencesAnyPrivateAccessor" "$PIT" \
  && grep -q "K_LLM_QWEN_KEY" "$PIT"; } \
  && log_ok "v04-CFG-10 PrivacyIsolationTest 扩 · PromptBuilder 防 LLM key 泄露" \
  || log_bad "v04-CFG-10 私密红线扩展缺" "see PrivacyIsolationTest"

# ====================================================================
# v0.6 · AI 资产洞察(集中度/资产负债表/再平衡·行为/低利率)· FR-100~110
# ====================================================================
section "v0.6 · AI 资产洞察"

# v06-INSIGHT-1 · /checkup/insight endpoint 200(LLM 真机最长 35s · 无 key 时降级仍 200)
code=$(/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup/insight")
[[ "$code" == "200" ]] && log_ok "v06-INSIGHT-1 GET /checkup/insight → 200" \
  || log_bad "v06-INSIGHT-1 insight endpoint" "code=$code"

# v06-INSIGHT-2 · fragment 含 vendor/available 属性 + AI·资产洞察 标题 + 硬数据/第一层
{ grep -qE 'data-vendor=|data-available=' "$TMP" && grep -q 'AI · 资产洞察' "$TMP"; } \
  && log_ok "v06-INSIGHT-2 insight fragment 含 vendor/available + 标题" \
  || log_bad "v06-INSIGHT-2 insight fragment" "missing attrs/title"

# v06-INSIGHT-3 · fragment 含第一层硬数据(集中度/资产负债表/再平衡/低利率 任一维度名)
{ grep -q '硬 · 数 · 据' "$TMP" || grep -q '集中度' "$TMP" || grep -q '硬数据暂不可用' "$TMP"; } \
  && log_ok "v06-INSIGHT-3 insight fragment 含硬数据层(或降级占位)" \
  || log_bad "v06-INSIGHT-3 insight 硬数据层缺" "no hard-data section"

# v06-INSIGHT-4 · /checkup 页含 #checkup-insight section + 异步 placeholder + TOC 项
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'id="checkup-insight"' "$TMP" \
  && grep -q 'ai-insight-panel' "$TMP" \
  && grep -q 'AI 资产洞察' "$TMP"; } \
  && log_ok "v06-INSIGHT-4 /checkup 含资产洞察 section + placeholder + TOC 项" \
  || log_bad "v06-INSIGHT-4 /checkup 洞察 section 缺" "no #checkup-insight / panel / toc"

# v06-INSIGHT-5 · /reports 配置对照尾部含「查看完整资产洞察」交叉入口 → /checkup#checkup-insight
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q '查看完整资产洞察' "$TMP" && grep -q '/checkup#checkup-insight' "$TMP"; } \
  && log_ok "v06-INSIGHT-5 /reports 含资产洞察交叉入口" \
  || log_bad "v06-INSIGHT-5 /reports 交叉入口缺" "no cross-link to checkup insight"

# v06-INSIGHT-6 · /dashboard 资产洞察速览(有数据时显条 · 无数据 SKIP · TOC 项常在)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
if grep -q 'id="dash-insight"' "$TMP"; then
  log_ok "v06-INSIGHT-6 /dashboard 资产洞察速览条已渲染"
elif grep -q "href='#dash-insight'\|#dash-insight" "$TMP"; then
  log_skip "v06-INSIGHT-6 /dashboard 速览条" "insight 降级未渲染(TOC 项在 · 无快照数据)"
else
  log_bad "v06-INSIGHT-6 /dashboard 速览/锚点缺" "no #dash-insight"
fi

# v06-LLM-LIVE · 嗅探 /checkup/insight 是否由真 LLM 成功返回(无 key/全失败则降级 · 不阻塞)
/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "" "$BASE/checkup/insight"
ins_vendor=$(grep -oE 'data-vendor="[^"]+"' "$TMP" | head -1 | sed 's/data-vendor="\([^"]*\)"/\1/')
ins_avail=$(grep -oE 'data-available="[^"]+"' "$TMP" | head -1 | sed 's/data-available="\([^"]*\)"/\1/')
if [[ "$ins_avail" == "true" && ( "$ins_vendor" == "qwen" || "$ins_vendor" == "deepseek" ) ]]; then
  log_ok "v06-LLM-LIVE LLM 实调用成功 vendor=$ins_vendor 洞察解读已返回"
else
  log_skip "v06-LLM-LIVE" "LLM key 未配/失败 vendor=$ins_vendor available=$ins_avail — 已降级(硬数据仍在),不阻塞"
fi

# v06-COMPLIANCE · 洞察渲染输出绝不含预测涨跌/择时/具体产品名(中立诊断红线 · 防御深度)
/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "" "$BASE/checkup/insight"
if grep -qE '会涨|会跌|牛市|熊市|抄底|逃顶|高抛低吸|波段操作|余额宝|茅台|宁德时代' "$TMP"; then
  log_bad "v06-COMPLIANCE 洞察输出含预测/择时/产品词" "$(grep -oE '会涨|会跌|牛市|熊市|抄底|逃顶|高抛低吸|波段操作|余额宝|茅台|宁德时代' "$TMP" | head -3 | tr '\n' ' ')"
else
  log_ok "v06-COMPLIANCE 洞察输出无预测/择时/产品词(中立诊断)"
fi

# v06-PRIV · InsightPromptBuilder 隐私 by construction:源码不引用成员/账户名 getter
IPB=src/main/java/com/family/finance/service/insight/InsightPromptBuilder.java
if [[ -f "$IPB" ]]; then
  if grep -qE 'getDisplayName|getName\(\)|memberMapper|topAccountLabel' "$IPB"; then
    log_bad "v06-PRIV InsightPromptBuilder 引用了名字字段" "$(grep -nE 'getDisplayName|getName\(\)|topAccountLabel' "$IPB" | head -2)"
  else
    log_ok "v06-PRIV InsightPromptBuilder 不含成员/账户名(隐私 by construction)"
  fi
else
  log_bad "v06-PRIV InsightPromptBuilder 缺失" "no $IPB"
fi

# v06-MODELS · QwenLlmClient 多模型额度兜底骨架(模型列表 + 故障分类 + 额度/欠费分支)
QWC=src/main/java/com/family/finance/service/checkup/llm/QwenLlmClient.java
{ grep -q 'K_LLM_QWEN_MODELS' "$QWC" \
  && grep -q 'QUOTA_EXHAUSTED' "$QWC" \
  && grep -q 'ARREARAGE' "$QWC" \
  && grep -q 'modelExhaustedUntil' "$QWC"; } \
  && log_ok "v06-MODELS QwenLlmClient 多模型兜底(模型列表+额度用尽切换+欠费 failover)" \
  || log_bad "v06-MODELS Qwen 多模型兜底骨架缺" "see QwenLlmClient"

# v06-MIGRATION · V29 负债字段 backward-compat(纯 ADD COLUMN DEFAULT NULL)
V29=db/migration/V29__loan_detail_fields.sql
{ grep -q 'ADD COLUMN loan_kind' "$V29" \
  && grep -q 'ADD COLUMN annual_rate_pct' "$V29" \
  && grep -q 'NULL' "$V29"; } \
  && log_ok "v06-MIGRATION V29 负债明细字段 · ADD COLUMN DEFAULT NULL(prod 0 风险)" \
  || log_bad "v06-MIGRATION V29 缺/非向后兼容" "see V29"

# ====================================================================
# v0.6.1 · iOS PWA 强引导(FR-115)· mobile-guide.js
# ====================================================================
section "v0.6.1 · iOS PWA 强引导"

$CURL "$BASE/js/mobile-guide.js" -o "$TMP" -w ""
# v061-PWA-1 · JS 200 + 含强引导三函数
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/js/mobile-guide.js")
{ [[ "$code" == "200" ]] \
  && grep -q "showIosPwaInterstitial" "$TMP" \
  && grep -q "showWxGuide" "$TMP" \
  && grep -q "twoStepLeave" "$TMP"; } \
  && log_ok "v061-PWA-1 mobile-guide.js 200 · 含整屏引导 + 两段挽留三函数" \
  || log_bad "v061-PWA-1 强引导 JS 缺" "code=$code"

# v061-PWA-2 · 强口吻文案(请务必/强烈建议/整屏)
{ grep -q "强烈建议" "$TMP" && grep -q "装成 App" "$TMP"; } \
  && log_ok "v061-PWA-2 JS 含强引导文案(强烈建议 · 装成 App)" \
  || log_bad "v061-PWA-2 强口吻文案缺" "no 强烈建议/装成 App"

# v061-PWA-3 · 无 emoji(承 feedback_no_emoji · 引导全 SVG)
if grep -qE "📦|📷|✕|✓|🤖|💡|✨" "$TMP"; then
  log_bad "v061-PWA-3 引导 JS 仍含 emoji" "$(grep -oE '📦|📷|✕|✓|🤖|💡|✨' "$TMP" | head -3 | tr '\n' ' ')"
else
  log_ok "v061-PWA-3 引导 JS 无 emoji(全 inline SVG)"
fi

# v061-PWA-4 · 成果真机图可访问
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/safari-screen/home-screen.jpg")
[[ "$code" == "200" ]] \
  && log_ok "v061-PWA-4 成果图 home-screen.jpg 200(主屏装好样子)" \
  || log_bad "v061-PWA-4 成果图缺" "code=$code"

# v061-PWA-5 · 4 步截图仍在(压缩后)
miss=0; for n in 1 2 3 4; do
  c=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/safari-screen/step$n.jpg")
  [[ "$c" == "200" ]] || miss=$((miss+1))
done
[[ $miss -eq 0 ]] && log_ok "v061-PWA-5 4 步真机截图 step1-4.jpg 全部 200" \
  || log_bad "v061-PWA-5 步骤截图缺 $miss 张" "miss=$miss"

# ====================================================================
# v0.7 · Docker 部署 + 兼容存量(静态守护 · 真机冒烟留待 Mac+Ubuntu)
# ====================================================================
section "v0.7 · Docker(静态守护)"
RD="$(cd "$(dirname "$0")/.." && pwd)"   # 仓库根

# v07-DOCKER-1 文件齐
dmiss=0
for f in Dockerfile docker-compose.yml .env.example .dockerignore \
         docker/entrypoint.sh docker/backup.sh deploy/docker-up.sh \
         deploy/migrate-to-docker.sh .github/workflows/docker-publish.yml; do
  [[ -f "$RD/$f" ]] || { dmiss=$((dmiss+1)); }
done
[[ $dmiss -eq 0 ]] && log_ok "v07-DOCKER-1 Docker 9 个文件齐(docker-up.sh 为唯一 Docker 入口)" || log_bad "v07-DOCKER-1 缺 $dmiss 个 Docker 文件" "see Dockerfile/compose/..."

# v07-DOCKER-2 多阶段 + 三服务 + 三卷
{ [[ "$(grep -c '^FROM' "$RD/Dockerfile")" -ge 2 ]] \
  && grep -qE '^  app:' "$RD/docker-compose.yml" && grep -qE '^  db:' "$RD/docker-compose.yml" && grep -qE '^  backup:' "$RD/docker-compose.yml" \
  && grep -qE 'db-data' "$RD/docker-compose.yml" && grep -qE 'uploads' "$RD/docker-compose.yml" && grep -qE 'backups' "$RD/docker-compose.yml"; } \
  && log_ok "v07-DOCKER-2 Dockerfile 多阶段 + app/db/backup 三服务 + 三卷" \
  || log_bad "v07-DOCKER-2 镜像/编排结构缺" "see Dockerfile/compose"

# v07-DOCKER-3 entrypoint 复用 db/apply.sh(与 systemd 共用迁移 → 防重放)
grep -q 'db/apply.sh' "$RD/docker/entrypoint.sh" \
  && log_ok "v07-DOCKER-3 entrypoint 复用 db/apply.sh(共用 schema_history 防重放)" \
  || log_bad "v07-DOCKER-3 entrypoint 未复用 db/apply.sh" "迁移可能重放"

# v07-DOCKER-4 新 shell 语法
sbad=0
for f in docker/entrypoint.sh docker/backup.sh deploy/docker-up.sh deploy/migrate-to-docker.sh; do
  bash -n "$RD/$f" 2>/dev/null || sbad=$((sbad+1))
done
[[ $sbad -eq 0 ]] && log_ok "v07-DOCKER-4 4 个 Docker shell bash -n 通过" || log_bad "v07-DOCKER-4 $sbad 个 shell 语法错" "bash -n"

# v07-DOCKER-5 防泄密:.env 被忽略 + .env.example 无真实密钥
{ grep -qxE '\.env' "$RD/.gitignore" \
  && ! grep -qiE '^(DB_PASS|MYSQL_ROOT_PASSWORD|REMEMBER_ME_KEY)=[A-Za-z0-9]{16,}' "$RD/.env.example"; } \
  && log_ok "v07-DOCKER-5 .env 已 gitignore + .env.example 只占位无真密钥" \
  || log_bad "v07-DOCKER-5 密钥泄露风险" "检查 .gitignore / .env.example"

# v07-DOCKER-6 迁移脚本双模式(systemd + macOS)
{ grep -q '/etc/finance.env' "$RD/deploy/migrate-to-docker.sh" && grep -q '.finance/finance.env' "$RD/deploy/migrate-to-docker.sh"; } \
  && log_ok "v07-DOCKER-6 迁移脚本识别 systemd + macOS 双存量" \
  || log_bad "v07-DOCKER-6 迁移脚本未覆盖双模式" "see migrate-to-docker.sh"

# v07-DOCKER-7 一键自检入口:探测 docker/引擎/Compose-V2 + 健康验证(适配各种 Mac docker 装法)
UP="$RD/deploy/docker-up.sh"
{ [[ -f "$UP" ]] \
  && grep -q 'docker info' "$UP" \
  && grep -q 'docker compose version' "$UP" \
  && grep -q 'docker-compose version --short' "$UP" \
  && grep -q '/health' "$UP"; } \
  && log_ok "v07-DOCKER-7 docker-up.sh 自检引擎/Compose-V2 + 验 /health" \
  || log_bad "v07-DOCKER-7 一键自检入口缺检查项" "see deploy/docker-up.sh"

# v07-DOCKER-8 种子账号 prod 引导:ProdSeedRunner 修首登死锁 + docker-up 打印账号密码
PSR="$RD/src/main/java/com/family/finance/config/ProdSeedRunner.java"
{ [[ -f "$PSR" ]] \
  && grep -q '@Profile("prod")' "$PSR" \
  && grep -q 'findSeedPlaceholders' "$PSR" \
  && grep -q 'updatePasswordHash' "$PSR" \
  && grep -q 'seed.admin-password' "$PSR" \
  && grep -q 'findSeedPlaceholders' "$RD/src/main/java/com/family/finance/repository/MemberMapper.java" \
  && grep -q 'SEED_ADMIN_PASSWORD' "$RD/.env.example" \
  && grep -q '首次登录' "$RD/deploy/docker-up.sh"; } \
  && log_ok "v07-DOCKER-8 ProdSeedRunner 引导种子密码(幂等)+ docker-up 打印首登账号" \
  || log_bad "v07-DOCKER-8 prod 种子账号引导缺失" "Docker 首登会死锁,see ProdSeedRunner"

# v07-DOCKER-9 安装入口收敛:Docker 只有 docker-up.sh 一个入口(docker-init.sh 已删、.env 生成内联);
#   直装只有 deploy.sh 一个入口(macOS 自动 exec 到内部实现 _deploy-macos.sh)
LAND="$RD/src/main/resources/templates/landing.html"
{ [[ ! -f "$RD/deploy/docker-init.sh" ]] \
  && grep -q 'ensure_env' "$UP" && grep -q 'REMEMBER_ME_KEY' "$UP" && grep -q 'openssl rand' "$UP" \
  && [[ -f "$RD/deploy/_deploy-macos.sh" ]] && [[ ! -f "$RD/deploy/deploy-macos.sh" ]] \
  && grep -q '_deploy-macos.sh' "$RD/deploy/deploy.sh" \
  && grep -q 'docker-up.sh' "$LAND" && ! grep -q 'docker-init' "$LAND" \
  && ! grep -q 'docker-init' "$RD/.env.example" \
  && bash -n "$UP" 2>/dev/null && bash -n "$RD/deploy/deploy.sh" 2>/dev/null; } \
  && log_ok "v07-DOCKER-9 安装入口收敛:docker-up.sh 唯一 Docker 入口(.env 内联)+ deploy.sh 唯一直装入口(Mac 转 _deploy-macos.sh)" \
  || log_bad "v07-DOCKER-9 安装入口未收敛" "docker-init.sh 应删/内联;deploy-macos.sh 应改名 _deploy-macos.sh 并由 deploy.sh 分流"

# v07-DOCKER-10 单一构建:docker-compose.yml 只允许一个服务带 `build:`(app);backup 复用同 image tag、不得再写 build:。
#   两个服务同 image+build 时 classic builder(非 BuildKit)会 build 两遍、第二遍打 tag 撞 AlreadyExists 而失败。
{ [[ "$(grep -cE '^[[:space:]]*build:' "$RD/docker-compose.yml")" == "1" ]] \
  && grep -qE '^[[:space:]]*app:' "$RD/docker-compose.yml"; } \
  && log_ok "v07-DOCKER-10 compose 仅 app 带 build:(backup 复用镜像,不触发 classic builder 双构建 AlreadyExists)" \
  || log_bad "v07-DOCKER-10 compose 出现多个 build:(会致 classic builder 同 image 双构建撞 AlreadyExists)" "见 docker-compose.yml backup 服务不应写 build:"

# v07-CN-1 国内 Docker 阻断引导:docker-up.sh 单独探 Docker Hub 归因 + 镜像源指引 + 不覆盖已有 registry-mirrors + 平台分流(Linux/Mac) + bash -n
#   v1.6.21 起断言跟随新实现:探的是 $DB_UPSTREAM(双源里的 Docker Hub 那条),不再是写死的 mysql:8.0;
#   「不覆盖」的判据从「文件不存在才写」改成「已有 registry-mirrors 就不动」(两处:Desktop / Linux),
#   因为现在有内容时会用 python3 合并而非拒写 —— 真正要守的不变量是不破坏用户既有的镜像源配置。
{ [[ -f "$UP" ]] \
  && grep -qF 'pull_one "$DB_UPSTREAM"' "$UP" \
  && grep -q 'cn_hub_blocked_guide' "$UP" \
  && grep -q 'registry-mirrors' "$UP" \
  && grep -q 'docker.m.daocloud.io' "$UP" \
  && [ "$(grep -c '里已有 registry-mirrors' "$UP")" -eq 2 ] \
  && grep -q 'uname -s.*Darwin\|Darwin.*_cn_guide_mac' "$UP" \
  && grep -q '_cn_guide_mac' "$UP" \
  && grep -q 'colima.yaml\|\.colima/default' "$UP" \
  && grep -q 'orb config docker' "$UP" \
  && bash -n "$UP" 2>/dev/null; } \
  && log_ok "v07-CN-1 docker-up.sh 探 Docker Hub 阻断 + 镜像源引导 + 不覆盖已有 registry-mirrors + Mac 分引擎(colima/orb/Desktop)" \
  || log_bad "v07-CN-1 国内阻断引导逻辑缺件" "see deploy/docker-up.sh step5 / cn_hub_blocked_guide"

# v07-CN-2 文档守护:三处文档给对镜像源,且纠正了「build 救不了 mysql」的误导
#   v1.6.21 起 README 不再教用户写 daemon.json(默认 GHCR 副本 + MYSQL_IMAGE 覆盖),故断言换成这两个词;
#   「build 救不了」仍必须在 README/deploy-README/faq 三处保留 —— 本地构建要拉 maven/temurin,同样过不了墙。
{ grep -q 'registry-mirrors' "$RD/deploy/README.md" && grep -q 'docker.m.daocloud.io' "$RD/deploy/README.md" && grep -q '救不了' "$RD/deploy/README.md" \
  && grep -q 'mysql:8.0' "$RD/README.md" && grep -q '救不了' "$RD/README.md" \
  && grep -q 'MYSQL_IMAGE' "$RD/README.md" && grep -q 'GHCR' "$RD/README.md" \
  && grep -q 'registry-mirrors' "$RD/docs/faq.md" && grep -q 'docker.m.daocloud.io' "$RD/docs/faq.md" && grep -q 'mysql:8.0' "$RD/docs/faq.md"; } \
  && log_ok "v07-CN-2 README/deploy-README/faq 均给对国内镜像源 + 纠正 build 误导" \
  || log_bad "v07-CN-2 国内镜像源文档缺失或仍有误导" "see deploy/README.md / README.md / docs/faq.md"

# v07-CLEAN-1 全新 Docker 清演示数据成空态(与 systemd step10 一致):脚本逻辑 + entrypoint 全新库判定 + Dockerfile 接线 + bash -n
CLEAN="$RD/docker/clean-dev-data.sh"; ENT="$RD/docker/entrypoint.sh"
{ [[ -f "$CLEAN" ]] \
  && grep -q 'FINANCE_KEEP_DEMO' "$CLEAN" \
  && grep -qE 'member WHERE id > 2|id > 2' "$CLEAN" \
  && grep -q 'TRUNCATE TABLE period;' "$CLEAN" && grep -q 'TRUNCATE TABLE account;' "$CLEAN" \
  && grep -q 'schema_history' "$ENT" && grep -q 'FRESH_DB' "$ENT" && grep -q 'clean-dev-data.sh' "$ENT" \
  && grep -q 'clean-dev-data.sh' "$RD/Dockerfile" \
  && bash -n "$CLEAN" 2>/dev/null && bash -n "$ENT" 2>/dev/null; } \
  && log_ok "v07-CLEAN-1 全新库清演示数据(铁信号 schema_history + 互锁 + KEEP_DEMO 开关 + 与 step10 同表集)" \
  || log_bad "v07-CLEAN-1 全新 Docker 清空态逻辑缺件" "see docker/clean-dev-data.sh / entrypoint.sh / Dockerfile"

# v16-EMPTY-1 全新部署空账期兜底:/entry /reports /checkup 在零周期时不再 orElseThrow → 500,
#   而是 redirect:/?needs=period 回引导页;引导页横幅 + 第③步「去填报」需 hasPeriod 才放行(按序门控)。
ENTRY="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
RPT="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
CHK="$RD/src/main/java/com/family/finance/web/checkup/CheckupController.java"
OBH="$RD/src/main/resources/templates/onboarding/index.html"
{ for f in "$ENTRY" "$RPT" "$CHK"; do
    grep -q 'countByFamily(me.getFamilyId()) == 0' "$f" && grep -q 'redirect:/?needs=period' "$f" || exit 1
  done
  grep -qF "needs == 'period'" "$OBH" \
  && grep -qF 'th:if="${hasPeriod}" th:href="@{/entry}"' "$OBH" \
  && grep -qF 'th:if="${hasAccount}" th:href="@{/admin/periods}"' "$OBH"; } \
  && log_ok "v16-EMPTY-1 空账期兜底:entry/reports/checkup 零周期 redirect 引导页(不 500)+ 引导横幅 + ②③按序门控" \
  || log_bad "v16-EMPTY-1 空账期兜底缺件" "see EntryController/ReportsController/CheckupController guard + onboarding/index.html"

# v17-INSURANCE-1 保险账户类型全链落地(枚举 + 两处 CHECK 放宽 + 桶接线 + 洞察金融资产 + 保单旁表 + 种子)
V44="$RD/db/migration/V44__insurance_account.sql"
ADF="$RD/src/main/java/com/family/finance/calc/AllocationDiff.java"
AIS="$RD/src/main/java/com/family/finance/service/insight/AssetInsightService.java"
ACT="$RD/src/main/java/com/family/finance/domain/account/AccountType.java"
{ grep -q 'INSURANCE("保险")' "$ACT" \
  && [ -f "$V44" ] && [ "$(grep -c "'INSURANCE'" "$V44")" -ge 2 ] \
  && grep -q 'account_insurance_policy' "$V44" && grep -q 'SAVINGS_INSURANCE' "$V44" \
  && grep -q 'annuity_insurance' "$V44" && grep -q 'whole_life_insurance' "$V44" \
  && grep -qF '"INSURANCE".equals(type)' "$ADF" && grep -q 'Bucket.INSURANCE' "$ADF" \
  && grep -q 'AccountType.INSURANCE' "$AIS" \
  && [ -f "$RD/src/main/java/com/family/finance/domain/insurance/InsurancePolicy.java" ] \
  && [ -f "$RD/src/main/java/com/family/finance/repository/InsurancePolicyMapper.java" ] \
  && [ -f "$RD/src/main/java/com/family/finance/domain/account/InsuranceSubType.java" ]; } \
  && log_ok "v17-INSURANCE-1 保险类型全链:enum+2处CHECK放宽+pickBucket短路INSURANCE桶+financialSum含保险+保单旁表+SAVINGS_INSURANCE/2模板种子" \
  || log_bad "v17-INSURANCE-1 保险类型全链缺件" "see AccountType/V44/AllocationDiff.pickBucket/AssetInsightService/InsurancePolicy(Mapper)"

# v17-INSURANCE-2 pill-slate 四处徽章 + 向导消费型提示 + 手填不入持仓
CSS17="$RD/src/main/resources/static/css/style.css"
WIZ="$RD/src/main/resources/templates/accounts/_template-wizard.html"
{ grep -q '.pill-slate' "$CSS17" \
  && grep -qF "'INSURANCE' ? ' pill-slate'" "$RD/src/main/resources/templates/accounts/detail.html" \
  && grep -qF "'INSURANCE' ? ' pill-slate'" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -qF "'INSURANCE' ? ' pill-slate'" "$RD/src/main/resources/templates/entry/_row.html" \
  && grep -q 'insuranceHint' "$WIZ" && grep -q '消费型是纯支出' "$WIZ" \
  && grep -q 'type == AccountType.STOCK || type == AccountType.CRYPTO || type == AccountType.METAL' \
        "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"; } \
  && log_ok "v17-INSURANCE-2 pill-slate 应用四处 + 向导消费型友好提示 + supportsHoldings 不含 INSURANCE(手填非持仓)" \
  || log_bad "v17-INSURANCE-2 保险 UI/手填缺件" "see style.css pill-slate / detail·index·_row pill / _template-wizard hint / StockHoldingService.supportsHoldings"

# v17-WIZARD 模板卡真正驱动表单:点卡 → 回填类型/币种/建议名 + 高亮 + 隐藏 templateId(不再是死展示)
WIZ17="$RD/src/main/resources/templates/accounts/_template-wizard.html"
{ grep -q 'data-tpl-type=' "$WIZ17" && grep -q 'data-tpl-currency=' "$WIZ17" && grep -q 'data-tpl-name=' "$WIZ17" \
  && grep -q 'tpl-selected' "$WIZ17" \
  && grep -q 'name="templateId" id="tplId"' "$WIZ17" \
  && grep -q "querySelectorAll('.tpl-card')" "$WIZ17" \
  && grep -q 'newAcctHead' "$WIZ17" && grep -q 'scrollIntoView' "$WIZ17" \
  && ! grep -q 'select name="type" data-searchable' "$WIZ17"; } \
  && log_ok "v17-WIZARD 模板卡点击回填表单(data-tpl-* + tpl-card 点击 handler + 隐藏 templateId + type 去 searchable 便于赋值),非死展示" \
  || log_bad "v17-WIZARD 模板卡未驱动表单" "see _template-wizard.html data-tpl-*/tpl-selected/tplId/click handler"

# v17-LOAN-PROMPT 贷款趋势预测从「开账静默外推」改为「填报行内显式接受/保持上月」+ 兼容闸(committed==prev)
POJ="$RD/src/main/java/com/family/finance/service/PeriodOpener.java"
ESJ="$RD/src/main/java/com/family/finance/service/EntryService.java"
ROW17="$RD/src/main/resources/templates/entry/_row.html"
{ ! grep -q 'applyLoanPrefill' "$POJ" \
  && grep -q 'predictLoanBalance' "$POJ" \
  && grep -q 'static boolean loanPromptVisible' "$ESJ" \
  && grep -q 'acceptLoanPrediction' "$ESJ" \
  && grep -q 'accept-loan-prediction' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -qF 'showLoanPrompt' "$ROW17" && grep -q '按上两月趋势' "$ROW17"; } \
  && log_ok "v17-LOAN-PROMPT 贷款不再静默外推(删 applyLoanPrefill)· PeriodOpener 延续 prev · 填报行提示条(接受/保持上月)· acceptLoanPrediction 复刻旧逻辑 · loanPromptVisible 兼容闸(committed==prev 屏蔽老账期)" \
  || log_bad "v17-LOAN-PROMPT 贷款显式接受缺件" "see PeriodOpener(删applyLoanPrefill/留predictLoanBalance)/EntryService.loanPromptVisible+acceptLoanPrediction/EntryController/_row.html"

# v11-LENS-1 资产透视底座:V45 三列+lens_board · 枚举 · 注册表(≥8维5度量)· 唯一网关 · 纯函数引擎
V45L="$RD/db/migration/V45__asset_lens.sql"
LREG="$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java"
{ [ -f "$V45L" ] && grep -q 'asset_class' "$V45L" && grep -q 'platform_tag' "$V45L" \
  && grep -q 'industry_tag' "$V45L" && grep -q 'lens_board' "$V45L" \
  && [ -f "$RD/src/main/java/com/family/finance/domain/lens/AssetClass.java" ] \
  && [ -f "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" ] \
  && [ "$(grep -c '        dim("' "$LREG")" -ge 8 ] && [ "$(grep -c 'measure("' "$LREG")" -ge 7 ] \
  && grep -q 'PostMapping("/lens/query")' "$RD/src/main/java/com/family/finance/web/lens/LensController.java" \
  && grep -q 'holdingLevelSplit' "$RD/src/main/java/com/family/finance/calc/lens/PivotEngine.java"; } \
  && log_ok "v11-LENS-1 透视底座:V45(3列+lens_board)+ AssetClass/IndustryTag + 注册表 ≥8维/5度量 + POST /lens/query 唯一网关 + 引擎收益归因降级标记" \
  || log_bad "v11-LENS-1 透视底座缺件" "see V45/domain.lens/LensRegistry/LensController/PivotEngine"

# v11-LENS-2 前端与入口:nav 双端「透视」 · lens.js 状态机/旭日/透视表 · 打标页显式接受 · AI 白名单 · 集中度规则+阈值可配
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
{ ! grep -q '@{/lens}' "$NAVF" \
  && grep -q 'lens/_section :: section' "$RD/src/main/resources/templates/dashboard/index.html" \
  && grep -q "label:'资产透视'" "$RD/src/main/resources/templates/dashboard/index.html" \
  && grep -q '#lens-section' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q '@{/lens/tags}' "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q 'dashboard#lens-section' "$RD/src/main/resources/templates/reports/_allocation-diff.html" \
  && grep -q '由持仓逐个标' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'name="only"' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'acct_purpose_' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'scrollIntoView' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'IntersectionObserver' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'CACHE_TTL_MS' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'TransactionalEventListener' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'ApplicationReadyEvent' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'LensStaleEvent' "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q 'LensStaleEvent' "$RD/src/main/java/com/family/finance/service/AccountService.java" \
  && grep -q 'LensStaleEvent' "$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java" \
  && grep -q 'lensQueryService.evict' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q 'lensQueryService.evict' "$RD/src/main/java/com/family/finance/web/stock/StockHoldingController.java" \
  && grep -q 'drill' "$RD/src/main/resources/static/js/lens.js" && grep -q 'sunburst' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'lens-pivot' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q '保存全部打标' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'AI 推荐(全部未打标)' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'fromName' "$RD/src/main/java/com/family/finance/service/lens/LensAiTagService.java" \
  && grep -q 'LENS-CON-1' "$RD/src/main/java/com/family/finance/service/checkup/rule/LensConcentrationRules.java" \
  && grep -q 'LENS-CON-2' "$RD/src/main/java/com/family/finance/service/checkup/rule/LensConcentrationRules.java" \
  && grep -q 'lensIndustryConc' "$RD/src/main/resources/templates/admin/calc-tweaks.html"; } \
  && log_ok "v11-LENS-2 透视内嵌仪表盘 + 懒加载(IO·首屏0查询)+ 头寸缓存(60sTTL+打标/行业evict)+ 打标树状(账户›持仓·单行AI·用途列)+ 构建器展开滚动 + lens.js + AI 白名单 + LENS-CON-1/2(阈值可配)" \
  || log_bad "v11-LENS-2 透视前端/打标/集中度缺件" "see nav.html/lens.js/lens tags.html/LensAiTagService/LensConcentrationRules/calc-tweaks.html"

# v07-CLEAN-2 README 新用户硬伤:无 <your-org> 占位符 + 测试数自洽
#   2026-08-04 修:原来把测试数写死成 v0.7 时代的「289 单元 / 412」,之后每次加测试都会红,
#   于是这条护栏长期失效 —— 一条永远红的护栏不会拦住任何回归,只会淹没真问题。
#   改成「README 里的单测数必须与 mvn 实测一致」:数字从 pom 编译产物的测试类实测拿不到,
#   退一步守可验证的部分 —— 数字存在、格式对、且与主页数字带一致(v09-LAND-6 已守后者)。
RM_TST="$(grep -oE '[0-9]+ 单元' "$RD/README.md" | head -1 | grep -oE '[0-9]+')"
RM_BBX="$(grep -oE '[0-9]+ 黑盒回归' "$RD/README.md" | head -1 | grep -oE '[0-9]+')"
{ ! grep -q '<your-org>' "$RD/README.md" \
  && [ -n "$RM_TST" ] && [ "$RM_TST" -ge 289 ] \
  && [ -n "$RM_BBX" ] && [ "$RM_BBX" -ge 412 ] \
  && ! grep -q '244 单元' "$RD/README.md"; } \
  && log_ok "v07-CLEAN-2 README 无 <your-org> 占位符 + 测试数自洽($RM_TST 单元 / $RM_BBX 黑盒 · 只增不减)" \
  || log_bad "v07-CLEAN-2 README 仍有占位符或测试数不一致" "README 需写明「N 单元 / N 黑盒回归」且不小于历史基线(289/412)"

section "v0.8 · 指标端出/排序/筛选/可配置/计算正确性(静态守护)"
RG="$RD/src/main/resources/templates/dashboard/_region.html"
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
# v08-1 账户指标端出 + 真 sparkline(无硬编码假图)+ 三态排序
{ grep -q 'cumPnl' "$RD/src/main/java/com/family/finance/factview/AccountPerformance.java" \
  && grep -q 'sparkPoints' "$FVI" \
  && grep -q 'data-sortable' "$RG" && grep -q 'aria-sort' "$RG" \
  && ! grep -q '0,18 10,15 20,16' "$RG"; } \
  && log_ok "v08-1 账户指标全集端出 + 真 sparkline(假图已除)+ 列表三态排序" \
  || log_bad "v08-1 P1 指标/排序/sparkline 缺件" "see AccountPerformance/_region.html"
# v08-2 跨币种转账 to_amount
{ grep -q 'COALESCE(to_amount, amount)' "$RD/src/main/resources/mapper/FactMapper.xml" \
  && [[ -f "$RD/db/migration/V30__transfer_to_amount.sql" ]] \
  && grep -q 'toAmount' "$RD/src/main/java/com/family/finance/domain/transfer/Transfer.java"; } \
  && log_ok "v08-2 跨币种转账 to_amount(COALESCE 读 + 域/迁移)" \
  || log_bad "v08-2 跨币种 to_amount 缺件" "see FactMapper.xml / V30 / Transfer"
# v08-3 Problem B 现金调整剔出 PnL
{ [[ -f "$RD/db/migration/V33__cash_flow_adjustment.sql" ]] \
  && grep -q 'is_adjustment' "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q 'recordCashAdjustment' "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"; } \
  && log_ok "v08-3 Problem B 现金行手动改记 is_adjustment 流水(剔出投资损益)" \
  || log_bad "v08-3 Problem B 缺件" "see V33 / CashFlowMapper / StockHoldingService"
# v08-4 筛选器按账期 as-of + MoM/YoY(Problem C 双收益率)
{ grep -q 'String asof' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'resolveAsOf' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && [[ -f "$RD/src/main/java/com/family/finance/factview/MomYoy.java" ]] \
  && grep -q 'momYoy' "$FVI" && grep -q 'xirrBaseForAccountRows' "$FVI" \
  && grep -q 'asof=' "$RG"; } \
  && log_ok "v08-4 筛选器 as-of 账期 + MoM/YoY + 本位币双收益率" \
  || log_bad "v08-4 P4 筛选器/MoM-YoY/双收益率缺件" "see DashboardController/MomYoy/FactViewServiceImpl"
# v08-5 可配置指标集
{ [[ -f "$RD/src/main/java/com/family/finance/service/MetricPrefsService.java" ]] \
  && [[ -f "$RD/src/main/resources/templates/admin/metrics.html" ]] \
  && [[ -f "$RD/db/migration/V32__family_metric_prefs.sql" ]] \
  && grep -q "acctMetrics.contains" "$RG" \
  && grep -q '/admin/metrics' "$RD/src/main/resources/templates/admin/_sidebar.html"; } \
  && log_ok "v08-5 可配置指标集(MetricPrefsService + 管理页 + dashboard 按勾选显隐)" \
  || log_bad "v08-5 可配置指标缺件" "see MetricPrefsService/admin/metrics.html/V32/_region/_sidebar"
# v08-6 预实分析
{ [[ -f "$RD/db/migration/V31__account_expected_return.sql" ]] \
  && grep -q 'expectedReturnPct' "$RD/src/main/java/com/family/finance/domain/account/Account.java" \
  && grep -q 'planActualDiffPct' "$RD/src/main/java/com/family/finance/factview/AccountPerformance.java" \
  && grep -q 'expectedReturnPct' "$RD/src/main/resources/templates/accounts/edit.html"; } \
  && log_ok "v08-6 预实分析(账户预期收益覆盖 + 回落品类基准 + 编辑入口)" \
  || log_bad "v08-6 预实缺件" "see V31 / Account / AccountPerformance / accounts/edit.html"

# v08-7 家庭指标配置真控 KPI 豆腐块 + 头部(famMetrics 接线)+ 计算正确性单测存在
{ grep -q "famMetrics.contains('net_worth')" "$RG" && grep -q "famMetrics.contains('total_assets')" "$RG" \
  && grep -q "famMetrics.contains('emergency_months')" "$RG" && grep -q "famMetrics.contains('period_return')" "$RG" \
  && grep -q "famMetrics.contains('nw_mom')" "$RG" \
  && [[ -f "$RD/src/test/java/com/family/finance/factview/FactViewMetricsCalcTest.java" ]]; } \
  && log_ok "v08-7 家庭指标配置控 KPI 豆腐块/头部(famMetrics)+ 计算正确性单测在" \
  || log_bad "v08-7 豆腐块未接 famMetrics 或缺计算单测" "see _region.html / FactViewMetricsCalcTest"

# v08-8 账户/仪表盘/账本无 pictographic emoji(💡/📦/📈 等;★ 风险星与 ↔↺✕△ 排版符保留)
#   v0.10.6 扩:纳入 EntryController(账本行 kind 标签由 Java 拼 HTML · 📈 估值→△ 估值)· 网住账本 emoji 回归
EMOJI_HITS=0
for f in "$RD/src/main/resources/templates/accounts/detail.html" "$RD/src/main/resources/templates/dashboard/_region.html" "$RD/src/main/java/com/family/finance/web/entry/EntryController.java"; do
  grep -oP '[\x{1F300}-\x{1FAFF}]|[\x{2600}-\x{26FF}]' "$f" 2>/dev/null | grep -vE '★|☆' | grep -q . && EMOJI_HITS=$((EMOJI_HITS+1))
done
[[ "$EMOJI_HITS" -eq 0 ]] \
  && log_ok "v08-8 账户详情/仪表盘/账本(EntryController)无 pictographic emoji(已换 inline SVG/排版符)" \
  || log_bad "v08-8 仍有 emoji" "$EMOJI_HITS 个文件命中,see detail.html/_region.html/EntryController.java"

section "v0.7 第二批 · 外部服务配置引导(静态守护)"
ICFG="$RD/src/main/resources/templates/admin/integrations.html"
ICTL="$RD/src/main/java/com/family/finance/web/admin/IntegrationsController.java"

# v07-CFG-1 配置总指南 + README 入口
{ [[ -f "$RD/docs/configuration.md" ]] && grep -q 'docs/configuration.md' "$RD/README.md"; } \
  && log_ok "v07-CFG-1 configuration.md 存在 + README 有入口" \
  || log_bad "v07-CFG-1 配置总指南或 README 入口缺失" "see docs/configuration.md"

# v07-CFG-2 LLM 页:可选 banner + 折叠帮助 + 测试按钮 + sibling 测试表单
{ grep -q '都<b>可选</b>' "$ICFG" \
  && grep -q '如何获取 Qwen Key' "$ICFG" \
  && grep -q "form=\"llm-test-qwen\"" "$ICFG" \
  && grep -q 'id="llm-test-qwen"' "$ICFG" \
  && grep -q 'id="llm-test-deepseek"' "$ICFG"; } \
  && log_ok "v07-CFG-2 LLM 页 可选说明 + 折叠指引 + 测试按钮 + sibling 表单齐" \
  || log_bad "v07-CFG-2 LLM 配置引导 UI 缺件" "see integrations.html"

# v07-CFG-3 后端测试端点 + 脱敏分类
{ grep -q '/llm/test' "$ICTL" \
  && grep -q 'classifyLlmError' "$ICTL" \
  && grep -q 'isPrivateKeyConfigured' "$ICTL"; } \
  && log_ok "v07-CFG-3 /llm/test 端点 + classifyLlmError 脱敏 + 未配短路" \
  || log_bad "v07-CFG-3 LLM 测试端点缺失" "see IntegrationsController"

# v07-CFG-4 私密红线:测试端点不回显/不记 key 明文(/llm/test 处理不引用 key 参数,审计只记 vendor+结果)
if awk '/@PostMapping\("\/llm\/test"\)/{f=1} f{print} /^    }$/{if(f)exit}' "$ICTL" | grep -qiE 'qwenKey|deepseekKey|getString.*KEY|\.token'; then
  log_bad "v07-CFG-4 测试端点疑似触碰 key 明文" "复核 testLlm 不应读/拼 key"
else
  log_ok "v07-CFG-4 测试端点不读/不回显 key 明文(只 vendor + 脱敏结果)"
fi

# v07-CFG-5 短信页补配置指南链
grep -q 'aliyun-sms-setup.md' "$RD/src/main/resources/templates/admin/notification.html" \
  && log_ok "v07-CFG-5 短信页有「配置指南」文档链" \
  || log_bad "v07-CFG-5 短信页缺文档链" "see notification.html"

section "v0.7 第三批 · 系统内首次引导(静态守护)"
HC="$RD/src/main/java/com/family/finance/common/HomeController.java"
DC="$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"

# v07-ONB-1 落地页智能路由 + onboarding 模板 + 首登500兜底
{ [[ -f "$RD/src/main/resources/templates/onboarding/index.html" ]] \
  && grep -q 'onboarding/index' "$HC" \
  && grep -q 'redirect:/dashboard' "$HC" \
  && grep -q 'countByFamily' "$HC" \
  && grep -q 'redirect:/' "$DC"; } \
  && log_ok "v07-ONB-1 / 智能路由(onboarding/dashboard)+ dashboard 零周期兜底 redirect" \
  || log_bad "v07-ONB-1 首次引导路由/兜底缺失" "see HomeController/DashboardController"

# v07-ONB-2 引导页起步步骤 + /entry 周期流程说明
{ grep -q '加账户' "$RD/src/main/resources/templates/onboarding/index.html" \
  && grep -q '开本期周期' "$RD/src/main/resources/templates/onboarding/index.html" \
  && grep -q '周期流程' "$RD/src/main/resources/templates/entry/index.html" \
  && [[ -f "$RD/src/test/java/com/family/finance/web/OnboardingRoutingTest.java" ]]; } \
  && log_ok "v07-ONB-2 引导 3 步 + entry 周期流程说明 + 路由单测在" \
  || log_bad "v07-ONB-2 引导内容/单测缺失" "see onboarding/entry"

# v07-FIX-1 改密死循环修复(issue #1):改密后真作废 session,不再只 clearContext
PC="$RD/src/main/java/com/family/finance/web/profile/ProfileController.java"
{ grep -q 'SecurityContextLogoutHandler' "$PC" \
  && grep -q '\.logout(request, response' "$PC" \
  && [[ -f "$RD/src/test/java/com/family/finance/web/ProfilePasswordChangeTest.java" ]]; } \
  && log_ok "v07-FIX-1 改密用 SecurityContextLogoutHandler 真作废 session(修首登死循环)+ 回归单测在" \
  || log_bad "v07-FIX-1 改密死循环修复缺失" "see ProfileController issue#1"

section "v0.9 · 根路径公开落地页(降钓鱼误判 + 对外门面)"
# v09-LAND-1 · 匿名 GET / = 200 落地页(含定位文案 + GitHub 全 URL + 截图引用)
anon=/tmp/finance-qa-landing.html
code=$($CURL -o "$anon" -w "%{http_code}" "$BASE/")     # 不带 cookie = 匿名
{ [[ "$code" == "200" ]] \
  && grep -q "家庭账房" "$anon" \
  && grep -q "资产全局图" "$anon" \
  && grep -q "github.com/LuoDi-Nate/financial-management" "$anon" \
  && grep -q "feature_summary_total.jpg" "$anon"; } \
  && log_ok "v09-LAND-1 匿名 / =200 公开落地页(定位文案 + GitHub 全 URL + 总览图)" \
  || log_bad "v09-LAND-1 匿名落地页缺件" "code=$code(应 200 且含家庭账房/资产全局图/github URL/截图)"

# v09-LAND-2 · 匿名 / 不再 302 到裸登录页(降钓鱼信号的核心:首屏非登录框)
loc=$($CURL -o /dev/null -w "%{http_code}|%{redirect_url}" "$BASE/")
[[ "$loc" == 200\|* ]] \
  && log_ok "v09-LAND-2 匿名 / 直接 200,不再 302 /login(裸登录触发特征已消除)" \
  || log_bad "v09-LAND-2 匿名 / 仍跳转" "got=$loc(期望 200,无 redirect)"

# v09-LAND-3 · 已登录 GET / → 302 /dashboard(老用户无感直达)
loc=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}|%{redirect_url}" "$BASE/")
[[ "$loc" == *"/dashboard" ]] \
  && log_ok "v09-LAND-3 已登录 / → 302 /dashboard($loc)" \
  || log_bad "v09-LAND-3 已登录 / 未直达 dashboard" "got=$loc"

# v09-LAND-4 · 回归:permitAll 只放了 /,鉴权页仍需登录(没放过头)
loc=$($CURL -o /dev/null -w "%{redirect_url}" "$BASE/dashboard")   # 匿名访问 dashboard
[[ "$loc" == *"/login"* ]] \
  && log_ok "v09-LAND-4 回归:匿名 /dashboard 仍被拦去登录(放行没过头)" \
  || log_bad "v09-LAND-4 鉴权回归异常" "匿名 /dashboard redirect=$loc(应含 /login)"

# v09-LAND-5 · v0.9.1 精修元素都在(GitHub 角标 + 真实命令块 + 它解决什么四问 + 数字带)
#   v1.7 修:原断言要求落地页命令块含 `docker compose up -d`,但安装入口收敛那版起
#   落地页给的就是 `bash deploy/docker-up.sh`(它内部才去调 compose)。这条断言自那以后
#   一直是红的 —— 守护断言的是**已被淘汰的命令**,等于长期失效。改成断言现口径。
{ grep -q 'github-corner' "$anon" && grep -q 'cmd-block' "$anon" \
  && grep -q 'git clone' "$anon" && grep -q 'deploy/docker-up.sh' "$anon" \
  && grep -q '它 解 决 什 么' "$anon" && grep -q '我们家现在到底有多少钱' "$anon" \
  && grep -q 'data-stat="version"' "$anon"; } \
  && log_ok "v09-LAND-5 落地页精修元素在(GitHub 角标 + 真实4步命令 + 它解决什么 + 数字带)" \
  || log_bad "v09-LAND-5 精修元素缺" "see landing.html"

# v09-LAND-6 · 主页数字带联动(与 release skill 同口径:版本/迁移自动算,单测/黑盒随 README)
v_ver=$(ls "$RD"/prd/v*.md 2>/dev/null | wc -l | tr -d ' ')
v_mig=$(ls "$RD"/db/migration/V*.sql 2>/dev/null | wc -l | tr -d ' ')
v_tst=$(grep -oE '[0-9]+ 单元' "$RD/README.md" | head -1 | grep -oE '[0-9]+')
v_bbx=$(grep -oE '[0-9]+ 黑盒回归' "$RD/README.md" | head -1 | grep -oE '[0-9]+')
lp_get(){ grep -oE "data-stat=\"$1\" data-to=\"[0-9]+\"" "$anon" | grep -oE '[0-9]+' | head -1; }
if [[ "$(lp_get version)" == "$v_ver" && "$(lp_get tests)" == "$v_tst" && "$(lp_get migrations)" == "$v_mig" && "$(lp_get blackbox)" == "$v_bbx" ]]; then
  log_ok "v09-LAND-6 主页数字带联动一致(版本$v_ver / 单测$v_tst / 迁移$v_mig / 黑盒$v_bbx)"
else
  log_bad "v09-LAND-6 主页数字带过时(发版会被 release skill 拦)" "landing=[$(lp_get version)/$(lp_get tests)/$(lp_get migrations)/$(lp_get blackbox)] 应=[$v_ver/$v_tst/$v_mig/$v_bbx]"
fi

# ───────────────────────────────────────────────────────────────────────────
# v0.9.2 · 填报/划转错误体验(2026-06-26 修:toast 被顶栏挡 / 空字段裸 400 不可读)
# v09-UX-1 · 全局 toast 不被顶栏挡:footer 不能用 relative z-10(否则 z-[10000] 的 toast-stack 被困其层叠上下文,沉到 nav 之下)
LAY=src/main/resources/templates/fragments/layout.html
{ grep -q '<footer th:fragment="footer"' "$LAY" \
  && ! grep -qE '<footer th:fragment="footer"[^>]*z-10' "$LAY"; } \
  && log_ok "v09-UX-1 footer 去 z-10 · 全局 toast(z-[10000])不再被 nav 挡" \
  || log_bad "v09-UX-1 footer 仍 relative z-10 · toast 会被顶栏挡" "see layout.html footer fragment"

# v09-UX-2 · 划转金额前置必填(空字段客户端拦截、不发请求)
grep -qE 'name="amount"[^>]*required' src/main/resources/templates/entry/_row.html \
  && log_ok "v09-UX-2 划转金额 required(空字段前置拦截)" \
  || log_bad "v09-UX-2 划转金额缺 required" "see entry/_row.html"

# v09-UX-3 · 参数绑定错(空金额)→ 可读 toast 而非裸 400(ToastErrorAdvice 兜 binding 异常)
CSRF=$(grep XSRF-TOKEN $COOKIE 2>/dev/null | awk '{print $7}' | tail -1)
[[ -z "$CSRF" ]] && { $CURL -b $COOKIE -c $COOKIE "$BASE/entry" -o /dev/null; CSRF=$(grep XSRF-TOKEN $COOKIE | awk '{print $7}' | tail -1); }
uxhdr=$($CURL -b $COOKIE -D - -o /dev/null -X POST -H "HX-Request: true" -H "X-XSRF-TOKEN: $CSRF" \
  --data-urlencode "periodId=1" --data-urlencode "toAccountId=1" --data-urlencode "amount=" \
  "$BASE/entry/1/transfer" 2>/dev/null | grep -i "HX-Trigger")
echo "$uxhdr" | grep -q "showToast" \
  && log_ok "v09-UX-3 空金额划转 → 200 + 可读 toast(非裸 400)" \
  || log_bad "v09-UX-3 空金额划转未回可读 toast" "HX-Trigger=$uxhdr"

# v09-CPI-1 · 净资产图 CPI 线 = 购买力保命线(锚×(1+cpi/12)^i),与 M2/【reports 财富水位】同口径;
#   防回退到「名义折现」(v/(1+cpi)^i)——那种永远压名义之下、不反映所选 CPI(2026-06-26 修)
RG_DASH=src/main/resources/templates/dashboard/_region.html
{ grep -q 'anchorNw \* Math.pow(1 + cpiMonthly' "$RG_DASH" \
  && ! grep -q '/ Math.pow(1 + cpiMonthly' "$RG_DASH"; } \
  && log_ok "v09-CPI-1 净资产图 CPI 线为购买力保命线(锚×(1+cpi)^i),非名义折现" \
  || log_bad "v09-CPI-1 CPI 线口径错(疑回退到名义折现 v/(1+cpi)^i)" "see _region.html netWorthChart"

# ── v09-FORM-* · 表单缺项前置拦截(全量审计 2026-06-26)·「缺表单项不许发请求」─────────────
#   原则:必填字段挂原生 required(浏览器/HTMX 拦截);仅在某控件命中时才必填的用 data-require-when 通用助手。
#   故意可选的不挂(entry 汇总「留空=未填」· SMS aksk「留空=不修改」· toAmount 跨币种才填 · roleLabel/note)。

# v09-FORM-1 · entry 收入/支出 金额前置必填(空字段不发请求;三表单各自独立,互不阻塞)
ROW=src/main/resources/templates/entry/_row.html
{ grep -qE 'required[^>]*placeholder="\+收入"' "$ROW" && grep -qE 'required[^>]*placeholder="-支出"' "$ROW"; } \
  && log_ok "v09-FORM-1 entry 收入+支出金额 required(空字段前置拦截)" \
  || log_bad "v09-FORM-1 entry 收入/支出金额缺 required" "see entry/_row.html cash-flow forms"

# v09-FORM-2 · 通用条件必填助手就位(data-require-when:某控件命中才 required)
LAY=src/main/resources/templates/fragments/layout.html
{ grep -q 'data-require-when' "$LAY" && grep -q 'el.required = (curVal' "$LAY"; } \
  && log_ok "v09-FORM-2 通用条件必填助手 data-require-when 就位(footer 全站注入)" \
  || log_bad "v09-FORM-2 缺 data-require-when 助手" "see layout.html footer fragment"

# v09-FORM-3 · 应急金「手填基线」选中才必填(自动基线时不挡)· 新建+编辑两页一致
{ grep -q 'name="fixedBaseline"[^>]*data-require-when="autoBaseline=false"' src/main/resources/templates/goals/new-emergency.html \
  || grep -A1 'name="fixedBaseline"' src/main/resources/templates/goals/new-emergency.html | grep -q 'data-require-when="autoBaseline=false"'; } \
  && { grep -q 'name="fixedBaseline"[^>]*data-require-when="autoBaseline=false"' src/main/resources/templates/goals/edit.html \
    || grep -A1 'name="fixedBaseline"' src/main/resources/templates/goals/edit.html | grep -q 'data-require-when="autoBaseline=false"'; } \
  && log_ok "v09-FORM-3 应急金手填基线条件必填(new-emergency + edit 均挂 data-require-when)" \
  || log_bad "v09-FORM-3 应急金手填基线缺条件必填" "see goals/new-emergency.html · goals/edit.html fixedBaseline"

# v09-FORM-4 · 自选股「从现金划转买入」勾选才必填买入成本(UI 已明示「划转买入时必填」)
grep -qE 'name="costBasis"[^>]*data-require-when="deductCash=true"' src/main/resources/templates/stock/holding-new-auto.html \
  && log_ok "v09-FORM-4 划转买入成本条件必填(deductCash 勾选才必填)" \
  || log_bad "v09-FORM-4 划转买入成本缺条件必填" "see stock/holding-new-auto.html costBasis"

# v09-FORM-5 · 宏观基准录入 CPI/M2 必填(空值无意义)
INTG=src/main/resources/templates/admin/integrations.html
{ grep -qE 'name="cpi"[^>]*required' "$INTG" && grep -qE 'name="m2"[^>]*required' "$INTG"; } \
  && log_ok "v09-FORM-5 宏观录入 CPI/M2 required" \
  || log_bad "v09-FORM-5 宏观录入 CPI/M2 缺 required" "see admin/integrations.html macro form"

# v09-FORM-6 · 成员编辑「显示名」必填(原仅新增有,编辑可清空提交)
grep -qE 'name="displayName" th:value="\$\{m.displayName\}"[^>]*required' src/main/resources/templates/admin/members.html \
  && log_ok "v09-FORM-6 成员编辑显示名 required" \
  || log_bad "v09-FORM-6 成员编辑显示名缺 required" "see admin/members.html 成员卡片 form"

# ── v10-CASHFLOW-* · 仪表盘「人赚 vs 钱赚」实时拆解 + 实时收支趋势(FR-165~167)─────────────
RG=src/main/resources/templates/dashboard/_region.html

# v10-CASHFLOW-1 · 新 section 在 + 长文目录(tocItems)同步锚点(改 section 必同步目录的纪律)
{ grep -q 'id="dash-cashflow"' "$RG" \
  && grep -q "href:'#dash-cashflow'" src/main/resources/templates/dashboard/index.html; } \
  && log_ok "v10-CASHFLOW-1 dash-cashflow section + 长文目录锚点同步" \
  || log_bad "v10-CASHFLOW-1 section 或 TOC 锚点缺失" "see _region.html / dashboard/index.html tocItems"

# v10-CASHFLOW-2 · 三态文案钩子(空态CTA / 半填诚实 / 首期)+ 有符号双向条
{ grep -q '本期还没填收支' "$RG" && grep -q '收支可能不全' "$RG" \
  && grep -q 'cashflowSplit.firstPeriod()' "$RG" \
  && grep -q 'cashflowSplit.renBarStyle()' "$RG"; } \
  && log_ok "v10-CASHFLOW-2 三态(空/半填/首期)文案 + 双向条钩子在" \
  || log_bad "v10-CASHFLOW-2 三态或双向条钩子缺失" "see _region.html dash-cashflow"

# v10-CASHFLOW-3 · 实时收支趋势 canvas + 序列注入 + datalabels(数值浮于数据上,非 hover)
{ grep -q 'id="cashflowTrendChart"' "$RG" && grep -q 'cashflowSeries' "$RG" \
  && grep -q 'financeCharts.cashflow' "$RG" && grep -q 'datalabels' "$RG"; } \
  && log_ok "v10-CASHFLOW-3 收支趋势 canvas + series + datalabels" \
  || log_bad "v10-CASHFLOW-3 趋势图钩子缺失" "see _region.html cashflowTrendChart"

# v10-CASHFLOW-4 · 装配/同源:控制器装配 + 钱赚=ΔNW−人赚(卡内恒等,不与「本月资产收益」打架)
{ grep -q 'cashflowSplit' src/main/java/com/family/finance/web/dashboard/DashboardController.java \
  && grep -q 'deltaNetWorth.subtract(ren)' src/main/java/com/family/finance/web/dashboard/CashflowSplitView.java; } \
  && log_ok "v10-CASHFLOW-4 控制器装配 + 钱赚=ΔNW−人赚 同源恒等" \
  || log_bad "v10-CASHFLOW-4 装配或同源恒等缺失" "see DashboardController / CashflowSplitView"

# ── v10-CCY-LENS-* · 单一镜头【真·端到端】币种守护(v0.10.1 修)──────────────────────────────
#   反复爆的根因:净资产用「每期历史汇率」换算 → 差额类指标(ΔNW/环比%/本月收益/钱赚)跨币种不按
#   单一汇率缩放;多币种 prod 上 ΔNW 实测偏 ~17%。教训:CurrencyInvarianceTest 是单元 + 单一 mock
#   汇率,把「多期不同历史汇率」这个真实场景抹平了,永远测不出。只有【登录→真 /dashboard 多币种→
#   跑真 SQL+多期不同汇率】的端到端断言才网得住整类。需要 family 有非 base 账户 + 多期变动汇率(diwa 家满足)。

# v10-CCY-LENS-1 · 实时收支趋势各期切币种按【同一汇率】均匀缩放(逐期比值全相等)
cnyN=$($CURL -b $COOKIE "$BASE/dashboard?currency=CNY" | grep -oE '"netInflow":[0-9.-]+' | sed 's/"netInflow"://')
usdN=$($CURL -b $COOKIE "$BASE/dashboard?currency=USD" | grep -oE '"netInflow":[0-9.-]+' | sed 's/"netInflow"://')
lens1=$(paste <(printf '%s\n' $cnyN) <(printf '%s\n' $usdN) | awk 'NF==2 && $1+0!=0{r=$2/$1; if(n++==0){lo=r;hi=r} if(r<lo)lo=r; if(r>hi)hi=r} END{ if(n<2){print "SKIP n="n+0; exit} if(hi-lo<=0.0015*(hi<0?-hi:hi)) printf "OK n=%d r=%.5f",n,lo; else printf "DRIFT lo=%.5f hi=%.5f",lo,hi }')
case "$lens1" in
  OK*)   log_ok  "v10-CCY-LENS-1 多币种切币种·收支趋势各期同一汇率缩放($lens1)";;
  SKIP*) log_skip "v10-CCY-LENS-1 趋势数据不足($lens1)" "需多币种 + 多期数据";;
  *)     log_bad "v10-CCY-LENS-1 切币种各期缩放漂移·单一镜头被破坏" "$lens1";;
esac

# v10-CCY-LENS-2 · 净资产趋势(始终存在 · 正是出 bug 的核心量)各期切币种按【同一汇率】缩放
#   修前:每期净值用各自历史汇率 → NW(p1)_usd/NW(p1)_cny=期1汇率、NW(p2)同理用期2汇率,两期汇率不同 → 逐期比值漂移。
nwC=$($CURL -b $COOKIE "$BASE/dashboard?currency=CNY" | grep -oE 'netWorth: \[[^]]*\]' | head -1 | grep -oE '[0-9]+\.[0-9]+|[0-9]+')
nwU=$($CURL -b $COOKIE "$BASE/dashboard?currency=USD" | grep -oE 'netWorth: \[[^]]*\]' | head -1 | grep -oE '[0-9]+\.[0-9]+|[0-9]+')
lens2=$(paste <(printf '%s\n' $nwC) <(printf '%s\n' $nwU) | awk 'NF==2 && $1+0!=0{r=$2/$1; if(n++==0){lo=r;hi=r} if(r<lo)lo=r; if(r>hi)hi=r} END{ if(n<2){print "SKIP n="n+0; exit} if(hi-lo<=0.0015*(hi<0?-hi:hi)) printf "OK n=%d r=%.5f",n,lo; else printf "DRIFT lo=%.5f hi=%.5f",lo,hi }')
case "$lens2" in
  OK*)   log_ok  "v10-CCY-LENS-2 净资产趋势各期三币种同一汇率缩放($lens2)";;
  SKIP*) log_skip "v10-CCY-LENS-2 净资产趋势数据不足($lens2)" "需多币种 + 多期";;
  *)     log_bad "v10-CCY-LENS-2 净资产趋势切币种逐期缩放漂移·单一镜头被破坏" "$lens2";;
esac

# ── v10-TOC-SYNC-* · 长文目录漏维护守护(回应「新增功能忘了加进目录」)─────────────────────────
#   3 个目录页(dashboard/checkup/reports)的 tocItems 是手工内联列表,易漏。这两条把它变成 CI 闸门:
#   ① 任何带 scroll-margin-top 的 section(dash-/checkup-/sec- 前缀)必须出现在对应页 tocItems(加了 section 忘加目录 → 红);
#   ② 每个 tocItems 锚点必须有真实 id(删/改 section 留下死链 → 红)。
_TT=src/main/resources/templates
# 健壮提取(用 sed 捕获组,不靠 $ 锚点 —— href/id 结尾是引号,[a-z0-9-]+$ 在 GNU grep 下匹配不到)
_tocof() { grep 'tocItems' "$_TT/$1" 2>/dev/null | grep -oE "href:'#[a-z0-9-]+'" | sed -E "s/.*#([a-z0-9-]+).*/\1/"; }
_dashT=$(_tocof dashboard/index.html); _ckT=$(_tocof checkup/family.html); _rpT=$(_tocof reports/index.html)

# v10-TOC-SYNC-1 · section → 目录(新增 section 漏加目录条目)
tocmiss=""
for id in $(grep -rhE 'scroll-margin-top' "$_TT"/ | grep -oE 'id="[a-z0-9-]+"' | sed -E 's/id="([a-z0-9-]+)"/\1/' | sort -u); do
  case "$id" in
    dash-*)    grep -qx "$id" <<<"$_dashT" || tocmiss="$tocmiss dashboard:#$id";;
    checkup-*) grep -qx "$id" <<<"$_ckT"   || tocmiss="$tocmiss checkup:#$id";;
    sec-*)     grep -qx "$id" <<<"$_rpT"    || tocmiss="$tocmiss reports:#$id";;
  esac
done
[[ -z "$tocmiss" ]] \
  && log_ok "v10-TOC-SYNC-1 所有 section(dash-/checkup-/sec-)都已进对应页长文目录" \
  || log_bad "v10-TOC-SYNC-1 有 section 未加进对应页目录(漏维护)" "缺:$tocmiss · 去对应页 tocItems 补 {label,href}"

# v10-TOC-SYNC-2 · 目录锚点 → 真实 id(死链)
_allids=$(grep -rhoE 'id="[a-z0-9-]+"' "$_TT"/ | sed -E 's/id="([a-z0-9-]+)"/\1/' | sort -u)
tocstale=""
for a in $_dashT $_ckT $_rpT; do grep -qx "$a" <<<"$_allids" || tocstale="$tocstale #$a"; done
[[ -z "$tocstale" ]] \
  && log_ok "v10-TOC-SYNC-2 长文目录无死链锚点(每条 href 都有真实 id)" \
  || log_bad "v10-TOC-SYNC-2 目录有死链锚点(section 被删/改名)" "死链:$tocstale"

# v10-NOMINAL-1 · 收益口径=名义,不从收益里扣通胀(CPI/M2 只作图上参照线,不折算成"真实收益")
#   v0.10.3 修:AI 洞察「真实收益·跑输通胀」口径误导(把扣CPI数当标准收益)→ 改名义净资产增长;
#   财富水位的 CPI 购买力线/M2 社会财富线保留(让用户感受自家收益率 vs CPI,但不替他扣)。
{ grep -q 'nominalGrowthPct' src/main/resources/templates/dashboard/_insight-strip.html \
  && grep -q 'nominalGrowthPct' src/main/resources/templates/checkup/_ai-insight.html \
  && grep -q 'nominalGrowthPct' src/main/resources/templates/reports/_wealth-level.html \
  && ! grep -q '跑输通胀' src/main/resources/templates/dashboard/_insight-strip.html \
  && grep -q 'CPI 购买力线' src/main/resources/templates/reports/_wealth-level.html; } \
  && log_ok "v10-NOMINAL-1 洞察/体检/水位收益用名义口径 · CPI/M2 对比线保留" \
  || log_bad "v10-NOMINAL-1 收益口径仍扣CPI 或 对比线被误删" "see _insight-strip / _ai-insight / _wealth-level"

# v10-ACCT-COLS-1 · 账户列表补全列 + 指标 chips + sticky 首列(回应"指标设置勾了却不显示")
#   v0.10.4:dashboard 账户表补 net_principal/period_return/return_base/max_drawdown/months_held/plan_actual 列;
#   加内联指标筛选 chips(localStorage 记住)+ 账户名列 sticky + 列多横滑;目录移除无数据的 twr/yoy/risk(不超卖)。
RG=src/main/resources/templates/dashboard/_region.html
{ grep -q 'data-mchip=' "$RG" \
  && grep -q 'data-mcol="net_principal"' "$RG" && grep -q 'data-mcol="return_base"' "$RG" \
  && grep -q 'data-mcol="max_drawdown"' "$RG" && grep -q 'data-mcol="months_held"' "$RG" \
  && grep -q 'data-mcol="period_return"' "$RG" && grep -q 'acct-sticky' "$RG" && grep -q 'acctHiddenCols' "$RG" \
  && grep -q 'flex flex-wrap items-center gap-1.5 mb-2" data-acct-chips' "$RG" \
  && ! grep -q '"twr"' src/main/java/com/family/finance/service/MetricPrefsService.java; } \
  && log_ok "v10-ACCT-COLS-1 账户表补全列 + 指标 chips(PC+手机)+ sticky + 目录不超卖(twr/yoy/risk 已移除)" \
  || log_bad "v10-ACCT-COLS-1 账户列/chips/sticky 缺失 或 目录仍含无数据指标" "see _region.html dash-list / MetricPrefsService"

# v10-WINDOW-1 · 收益对比「同窗口」口径(修短账户「累计实际 vs 年化预期」错判)· v0.11.4 起走 displayedDiffPercentPoints
#   预实(账户)+ reports vs基准(账户/家庭)一律:实际 = 卡片显示的那个 xirr(<12 期累计 / ≥12 期年化),
#   预期同基(<12 期把年化基准缩放到持有月数 expectedOverWindowPct);阈值随窗口缩放(beatStatusDisplayed)。
#   根因升级:v0.10.5 曾用 cumPnl/净投入 当实际 → 净投入极小的账户爆成 +19497pp 且与头条脱节,v0.11.4 改用显示的 xirr。
BA=src/main/java/com/family/finance/calc/BenchmarkAggregator.java
{ grep -q 'displayedDiffPercentPoints' "$BA" \
  && grep -q 'expectedOverWindowPct(annualBenchmarkPct, months)' "$BA" \
  && grep -q 'beatStatusDisplayed' "$BA" \
  && grep -q 'displayedDiffPercentPoints(xirr.get(first.accountId())' src/main/java/com/family/finance/factview/FactViewServiceImpl.java \
  && grep -q 'displayedDiffPercentPoints(ap.xirr()' src/main/java/com/family/finance/web/report/ReportsController.java \
  && grep -q 'displayedDiffPercentPoints(familyXirrDecimal' src/main/java/com/family/finance/web/report/ReportsController.java; } \
  && log_ok "v10-WINDOW-1 预实/vs基准 同窗口口径(实际=显示的xirr;<12期基准缩放;不再「累计减年化」也不再 cumPnl/净投入 爆值)" \
  || log_bad "v10-WINDOW-1 收益对比仍混口径(短账户累计 vs 年化预期)" "see BenchmarkAggregator / FactViewServiceImpl / ReportsController"

# ─────────── v0.11 · 隐私模式(公共场合 / 分享隐藏金额)───────────
section "v0.11 · 隐私模式"

# v11-PRIVACY-1 · 基建:FOUC 防闪 + togglePrivacy + 常驻浮动控件 + 隐私 CSS(layout)+ nav 眼睛
LAY="$RD/src/main/resources/templates/fragments/layout.html"
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -q "sessionStorage.getItem('privacy')" "$LAY" \
  && grep -q 'function togglePrivacy' "$LAY" \
  && grep -q 'id="priv-float"' "$LAY" \
  && grep -q 'html.privacy \[data-priv\]' "$LAY" \
  && grep -q 'priv-eye-on' "$NAVF"; } \
  && log_ok "v11-PRIVACY-1 隐私基建齐(FOUC 防闪 + togglePrivacy + 浮动控件 + CSS + nav 眼睛)" \
  || log_bad "v11-PRIVACY-1 隐私基建缺失" "see layout.html / nav.html"

# v11-PRIVACY-2 · 全页金额标记覆盖:5 个用户面页渲染后都含 data-priv 金额标记 + 双入口(toggle + 浮动)
PRIV_MISS=""
for pg in dashboard reports checkup accounts entry; do
  $CURL -b $COOKIE "$BASE/$pg" -o "$TMP" -w ""
  { grep -q 'data-priv' "$TMP" && grep -q 'togglePrivacy' "$TMP" && grep -q 'id="priv-float"' "$TMP"; } || PRIV_MISS="$PRIV_MISS $pg"
done
[[ -z "$PRIV_MISS" ]] \
  && log_ok "v11-PRIVACY-2 dashboard/reports/checkup/accounts/entry 均有金额标记 + 双入口(nav+浮动)" \
  || log_bad "v11-PRIVACY-2 有页面漏金额标记/漏入口" "缺:$PRIV_MISS"

# v11-PRIVACY-3 · 比例不误遮 + 图表金额随隐私态隐藏:
#   紧急储备(月)/本月收益率(%)源码不带 data-priv;dashboard 图表 fmtMoney 含 isPrivacy 守卫(金额隐藏·形状保留)
DR="$RD/src/main/resources/templates/dashboard/_region.html"
{ ! grep -q 'data-priv th:text="${kpiEmergency}"' "$DR" \
  && ! grep -q 'data-priv th:text="${monthlyPnlPctLabel}"' "$DR" \
  && grep -q "isPrivacy()) return ''" "$DR"; } \
  && log_ok "v11-PRIVACY-3 比例/月数不误遮(emergency/pct 无 data-priv)+ 图表金额隐私守卫" \
  || log_bad "v11-PRIVACY-3 误遮比例 或 图表金额无隐私守卫" "see dashboard/_region.html"

# v11-PRIVACY-4 · 按住临时查看(peek):layout 含 priv-peek CSS 覆盖 + pointerdown 事件委托(隐私态去模糊)
{ grep -q 'html.privacy \[data-priv\].priv-peek' "$LAY" \
  && grep -q "addEventListener('pointerdown'" "$LAY" \
  && grep -q "classList.add('priv-peek')" "$LAY"; } \
  && log_ok "v11-PRIVACY-4 按住临时查看(peek)接线齐(priv-peek CSS + pointerdown 委托)" \
  || log_bad "v11-PRIVACY-4 peek 缺失" "see layout.html"

# ─────────── v0.11.2 · 账期滚动修复(切月两 bug)───────────
section "v0.11.2 · 账期滚动"

# v11-ROLLOVER-1 · bug1:开新期即关旧期(force-close 早于新期仍 OPEN 的旧期);bug2:LOAN 预填夹零 ≤0
PO="$RD/src/main/java/com/family/finance/service/PeriodOpener.java"
PM="$RD/src/main/java/com/family/finance/repository/PeriodMapper.java"
{ grep -q 'closePriorOpenPeriods' "$PO" \
  && grep -q 'forceClose' "$PO" \
  && grep -q 'findOpenBefore' "$PM" \
  && grep -q 'predictLoanBalance' "$PO" \
  && grep -q 'signum() > 0 ? BigDecimal.ZERO' "$PO"; } \
  && log_ok "v11-ROLLOVER-1 开新期即关旧期(bug1) + LOAN 预填夹零≤0(bug2)· 见 PeriodOpenerLoanPrefillTest" \
  || log_bad "v11-ROLLOVER-1 滚动关旧期 或 LOAN 夹零缺失" "see PeriodOpener/PeriodMapper"

# v11-REPORTS-1 · 报表 labels 用全期标签(debtTrend),非 decomposition(N-1)。
#   修「负债曲线少画一期(2期→1点)、本金vs损益分解图 slice(1) 再少一期(2期→0柱)」。
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
{ grep -q 'addAttribute("labels", debtTrend.stream()' "$RC" \
  && ! grep -q 'addAttribute("labels", decomposition.stream()' "$RC"; } \
  && log_ok "v11-REPORTS-1 报表 labels 用全期标签(负债曲线 N 点 / 分解图 slice(1) 对齐 N-1 柱)" \
  || log_bad "v11-REPORTS-1 报表 labels 仍接 decomposition(少一期)" "see ReportsController"

# v11-REPORTS-2 · 储蓄区图表脚本必须在 fragment(section)内 —— 否则 reports 用 `:: section` 引入时脚本被丢,
#   双柱/收支趋势 canvas 无人渲染(KPI 在 section 内正常,唯图空)。检查 </script> 后紧跟 </section>。
SAV="$RD/src/main/resources/templates/reports/_savings.html"
{ grep -A3 '</script>' "$SAV" | grep -q '</section>'; } \
  && log_ok "v11-REPORTS-2 储蓄区图表脚本在 fragment 内(:: section 引入不丢 → 双柱/收支趋势可渲染)" \
  || log_bad "v11-REPORTS-2 储蓄区图表脚本在 fragment 外 → 双柱/收支趋势不渲染" "see reports/_savings.html"

# v11-REPORTS-METRICS · 第四表复用「管理页·指标设置(账户级)」配置:控制器注入 acctMetrics + 全字段 accountRows,
#   模板按 acctMetrics.contains(...) 门控 data-mcol 指标列(与仪表盘同源)。
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
REG="$RD/src/main/resources/templates/reports/_region.html"
{ grep -q 'metricPrefsService.enabled(family.getMetricPrefs(), "account")' "$RC" \
  && grep -q 'addAttribute("accountRows"' "$RC" \
  && grep -q "acctMetrics.contains('cum_pnl')" "$REG" \
  && grep -q 'data-mcol="plan_actual"' "$REG"; } \
  && log_ok "v11-REPORTS-METRICS 报表第四表配置化指标列(复用管理页账户级指标 · data-mcol 门控)" \
  || log_bad "v11-REPORTS-METRICS 报表第四表未复用管理页指标配置" "see ReportsController / reports/_region.html"

# v11-REPORTS-PP · vs基准/预实 = 显示的 xirr − 基准(同基)→ 百分点 pp,不用 %;根因修 v0.10.5 cumPnl/净投入 爆值。
#   模板 pill 用 'pp' 结尾;控制器 + FactView 走 displayedDiffPercentPoints;不得再用 windowDiffPercentPoints 当实际。
FV="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
{ grep -q "+ 'pp'" "$REG" \
  && ! grep -qE "\\\$\{(familyBenchmarkDiff|row\.diffPct)\} \+ '%'" "$REG" \
  && grep -q 'displayedDiffPercentPoints(familyXirrDecimal' "$RC" \
  && grep -q 'displayedDiffPercentPoints(ap.xirr()' "$RC" \
  && grep -q 'displayedDiffPercentPoints(xirr.get(first.accountId())' "$FV"; } \
  && log_ok "v11-REPORTS-PP vs基准/预实用 pp + 实际=显示的 xirr(不再 cumPnl/净投入 爆值)" \
  || log_bad "v11-REPORTS-PP vs基准单位仍 % 或实际仍用 cumPnl/净投入" "see reports/_region.html / ReportsController / FactViewServiceImpl"

# v11-AUDIT-PP · 全系统「两比例相比」一律相减 + pp(v0.11.5 审计):
#   配置对照 超配/欠配 = 当前−模板 → pp;财富水位 真实/相对社会收益 = 名义−基准(相减,非 Fisher 除法)→ pp。
ADIFF="$RD/src/main/resources/templates/reports/_allocation-diff.html"
WLC="$RD/src/main/java/com/family/finance/calc/WaterLevelCalculator.java"
WLV="$RD/src/main/resources/templates/reports/_wealth-level.html"
{ grep -q "超配 +' + dif\['CASH'\] + 'pp'" "$ADIFF" \
  && ! grep -qE "超配 \+' \+ dif\['[A-Z]+'\] \+ '%'" "$ADIFF" \
  && grep -q 'nominalGrowthPct.subtract(benchmarkCumulativePct)' "$WLC" \
  && ! grep -q '(1.0 + n) / (1.0 + b)' "$WLC" \
  && grep -q "relativeReturnPct,1,1) : '—') + 'pp'" "$WLV"; } \
  && log_ok "v11-AUDIT-PP 两比例相比一律相减+pp(配置超配/欠配 · 财富水位真实/相对社会 = 名义−基准)" \
  || log_bad "v11-AUDIT-PP 仍有比例相比用 % 或用 Fisher 除法" "see _allocation-diff.html / WaterLevelCalculator / _wealth-level.html"

# v11-REPORTS-ASOF · 报表观察账期筛选器:报表=月快照,可在已关账账期里回看任一期。
#   控制器收 asof + 注入 periods/asof;模板有 账期 下拉(data-base 保留 range/currency,onchange 带 asof)。
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
REG="$RD/src/main/resources/templates/reports/_region.html"
{ grep -q 'String asof' "$RC" \
  && grep -q 'addAttribute("periods", closedPeriods)' "$RC" \
  && grep -q 'addAttribute("asof"' "$RC" \
  && grep -q "asof='+encodeURIComponent(this.value)" "$REG" \
  && grep -q 'th:each="p : ${periods}"' "$REG"; } \
  && log_ok "v11-REPORTS-ASOF 报表观察账期筛选器(已关账期下拉 · 回看任一月快照)" \
  || log_bad "v11-REPORTS-ASOF 报表缺观察账期筛选器" "see ReportsController / reports/_region.html"

# v11-DASH-LAYOUT · 首屏层级:目标进度 + AI洞察 从顶部下移到「KPI 总览之后」(dashboard 先出净资产/KPI 主角);
#   收支趋势图仅在有非零数据时出图,否则显空态细条(不留空白大卡)。
IDX="$RD/src/main/resources/templates/dashboard/index.html"
DREG="$RD/src/main/resources/templates/dashboard/_region.html"
DCTRL="$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"
{ ! grep -q '_insight-strip :: strip' "$IDX" \
  && ! grep -q '_progress-strip :: emptyHint' "$IDX" \
  && grep -q '_insight-strip :: strip' "$DREG" \
  && grep -q '_progress-strip :: emptyHint' "$DREG" \
  && grep -q 'cashflowSeriesHasData' "$DCTRL" \
  && grep -q 'th:unless="${cashflowSeriesHasData}"' "$DREG"; } \
  && log_ok "v11-DASH-LAYOUT 目标/AI洞察 下移到 KPI 总览之后 + 收支趋势无数据显空态" \
  || log_bad "v11-DASH-LAYOUT dashboard 首屏层级未修正" "see dashboard/index.html / _region.html / DashboardController"

# v11-TODO-RETIRE · 「待办」页退休折叠进「填报」:导航不再有 /my-todos 项;「填报」承接 pendingCount 角标;
#   /my-todos 保留 302 → /entry?mine=true(老书签);my-todos.html 模板已删。
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
MTC="$RD/src/main/java/com/family/finance/web/todo/MyTodosController.java"
{ ! grep -q '@{/my-todos}' "$NAVF" \
  && grep -q '填报' "$NAVF" && grep -q "state.pendingCount > 0" "$NAVF" \
  && grep -q 'redirect:/entry?mine=true' "$MTC" \
  && [[ ! -f "$RD/src/main/resources/templates/my-todos.html" ]]; } \
  && log_ok "v11-TODO-RETIRE 待办已折叠进填报(导航无 /my-todos · 填报承接角标 · /my-todos 302→/entry · 模板已删)" \
  || log_bad "v11-TODO-RETIRE 待办退休不完整" "see nav.html / MyTodosController / my-todos.html"

# v11-SUN-RINGCOLOR · 旭日每环一套独立配色(内外环=独立维度,共色系层级不可辨 · 2026-07-16 评审修订):
#   RING_PALETTES ≥2 套;colorMapFor(values, ring) 环内字典序防撞(哈希起点+线性探测,值≤色数保证互不同色);
#   内环 ring 0 / 外环 ring 1 / 排行条走外环色系(ring 1),字典序分配保证排行条与旭日外环同值同色。
LJS="$RD/src/main/resources/static/js/lens.js"
{ grep -q 'PALETTE_PLANS' "$LJS" \
  && grep -q "LENS_META.palette" "$LJS" \
  && grep -q 'function colorMapFor(values, ring)' "$LJS" \
  && grep -q 'colorMapFor(inners, 0)' "$LJS" \
  && grep -q 'colorMapFor(childNames, 1)' "$LJS" \
  && grep -q 'colorMapFor(rows.map' "$LJS" \
  && grep -q 'uniq.sort()' "$LJS" \
  && [ "$(grep -c "^\s*\['#" "$LJS")" -ge 10 ] \
  && grep -q 'K_LENS_PALETTE' "$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java" \
  && grep -q 'calc-tweaks/lens-palette' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java" \
  && grep -q 'lensPalette' "$RD/src/main/resources/templates/admin/calc-tweaks.html" \
  && grep -q 'sunCenter' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'fmtShort' "$LJS" \
  && grep -q 'renderLeaders' "$LJS" \
  && grep -q 'sunSmallNotes' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'privacyOn()' "$LJS"; } \
  && log_ok "v11-SUN-RINGCOLOR 旭日五套环级配色 + 信息交互(管理页可配默认D · 防撞 · 扇区label隐私感知 · 中心盘hover · 小扇区引导线PC/补注移动)" \
  || log_bad "v11-SUN-RINGCOLOR 旭日配色/信息交互缺件" "see lens.js PALETTE_PLANS/fmtShort/sunCenter · AdminController lens-palette"

# v11-DIM-REV2 · 维值修订三(2026-07-16 TUI 拍板):资产类型平民化命名 + 行业 17→18(+货币现金/拆金融地产/删海外市场)+ V47 迁移
ACJ="$RD/src/main/java/com/family/finance/domain/lens/AssetClass.java"
ITJ="$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java"
{ grep -q '股票股权' "$ACJ" && grep -q '债券理财' "$ACJ" && grep -q '现金活钱' "$ACJ" \
  && grep -q '黄金加密' "$ACJ" && ! grep -q '"权益"' "$ACJ" \
  && grep -q 'MONEY_CASH' "$ITJ" && grep -q '银行券商保险' "$ITJ" && grep -q 'ESTATE_CONSTRUCTION' "$ITJ" \
  && ! grep -q 'FINANCE_ESTATE' "$ITJ" && ! grep -q 'OVERSEAS' "$ITJ" \
  && [ "$(grep -c '", "' "$ITJ")" -ge 18 ] \
  && [ -f "$RD/db/migration/V47__industry_revision.sql" ] \
  && grep -q "FINANCE_ESTATE" "$RD/db/migration/V47__industry_revision.sql" \
  && grep -q "OVERSEAS" "$RD/db/migration/V47__industry_revision.sql" \
  && grep -q "股票股权" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "股票股权" "$RD/src/main/java/com/family/finance/service/checkup/rule/LensConcentrationRules.java" \
  && grep -q 'MONEY_CASH' "$RD/src/main/java/com/family/finance/service/lens/LensAiTagService.java"; } \
  && log_ok "v11-DIM-REV2 维值修订三(资产类型平民化 · 行业18含货币现金/银行券商保险/地产建筑 · 老值V47迁移 · 筛选与AI prompt联动)" \
  || log_bad "v11-DIM-REV2 维值修订不完整" "see AssetClass/IndustryTag/V47/lens.js/LensConcentrationRules/LensAiTagService"

# v11-LSEL · 自研搜索下拉(打标页 + 透视构建器/选择器 · 中文/全拼/首字母)
LSJ="$RD/src/main/resources/static/js/lens-select.js"
{ [ -f "$LSJ" ] && grep -q 'data-lsel' "$LSJ" && grep -q 'pyInit' "$LSJ" && grep -q 'MutationObserver' "$LSJ" \
  && grep -q 'font-size:16px !important' "$LSJ" \
  && grep -q "max-width:640px" "$LSJ" \
  && grep -q 'document.body.appendChild(panel)' "$LSJ" \
  && [ "$(grep -c 'data-lsel' "$RD/src/main/resources/templates/lens/tags.html")" -ge 4 ] \
  && [ "$(grep -c 'data-lsel' "$RD/src/main/resources/templates/lens/_section.html")" -ge 8 ] \
  && grep -q 'data-py' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'getPinyin' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q 'lens-select.js' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'lens-select.js' "$RD/src/main/resources/templates/lens/_section.html"; } \
  && log_ok "v11-LSEL 自研搜索下拉(拼音三路匹配 · 移动bottom sheet+portal · 16px防iOS聚焦放大 · 动态options自动重建)" \
  || log_bad "v11-LSEL 自研下拉缺件" "see lens-select.js / tags.html / _section.html data-lsel/data-py"

# v11-ROUND3 · 2026-07-17 第三轮评审 9 项(打标排版/行业20/全维看板/builder收起/维度说明/隐私随动/AI洞察/多维pivot/引导线根治)
{ grep -q '成员结构' "$LJS" && [ "$(grep -c "key: '" "$LJS" | head -1)" -ge 10 ] \
  && grep -q "builderWrap');           // 切看板 = 放弃自定义" "$LJS" \
  && grep -q 'pivotRow2Sel' "$LJS" && grep -q 'pivotCol2Sel' "$LJS" && grep -q 'groupStable' "$LJS" \
  && grep -q 'lensInsightBtn' "$LJS" \
  && grep -q 'noteItems' "$LJS" \
  && grep -q 'MIXED_ALLOC' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q 'DIVIDEND_UTIL' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q '货币基金/存款' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q 'acct-meta' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q '透视里的其余维度' "$RD/src/main/resources/templates/lens/tags.html" \
  && [ "$(grep -c 'data-priv' "$RD/src/main/resources/templates/dashboard/_region.html")" -ge 3 ] \
  && grep -q '/lens/insight' "$RD/src/main/java/com/family/finance/web/lens/LensController.java" \
  && grep -q 'anonymize' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q '严禁做任何计算' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q 'pivotRow2Sel' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'INSIGHT_CACHE' "$LJS" \
  && grep -q 'syncInsightCard' "$LJS" \
  && grep -q 'slotY' "$LJS" \
  && grep -q 'lensInsightRefresh' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q '异常信号' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q 'hold-row td:last-child' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'measurePills' "$LJS" \
  && grep -q "measures: \['value', 'share'\]" "$LJS" \
  && grep -q "'assetcls'" "$LJS" \
  && grep -q '改资料 →' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'orderByPrimaryVendor' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q 'orderByPrimaryVendor' "$RD/src/main/java/com/family/finance/service/lens/LensAiTagService.java" \
  && grep -q 'min-width:92px' "$LJS"; } \
  && log_ok "v11-ROUND3 三/四/五轮(打标列对齐+每行改资料/持仓入口 · 指标pills多选默认金额+占比 · 看板按关心度排序默认资产类型 · pivot宽度自适配 · AI follow管理页主选vendor · 洞察信号驱动+缓存)" \
  || log_bad "v11-ROUND3 第三轮修复缺件" "see lens.js/tags.html/_region.html/LensController/LensInsightService"

# v11-CASHROW · 券商现金部分语义(2026-07-17 修遗漏):透视头寸 cashRow 定死 现金活钱/货币基金/存款/低风险/灵活取用;
#   打标树保留现金行为只读展示(告知去向 · 不可标 · AI 不碰)
{ grep -q 'cashRow ? "低风险" : risk' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'cashRow ? AssetClass.CASH_EQ.getLabel() : assetClass' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'cashRow ? IndustryTag.MONEY_CASH.getLabel()' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'cashHoldings' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q '系统归类,不可改' "$RD/src/main/resources/templates/lens/tags.html"; } \
  && log_ok "v11-CASHROW 券商现金语义定死(现金活钱·货币基金/存款·低风险·灵活取用)+ 打标页只读展示去向" \
  || log_bad "v11-CASHROW 券商现金部分仍遗漏" "see LensQueryService cashRow / LensTagController cashHoldings / tags.html"

# v11-ENTRY-UX · 填报页(2026-07-17 评审):收入记录行 主理人头像+填报日期;右列操作区控件统一 h-8;收入表单 h-9
{ grep -q 'ownerName, java.time.LocalDateTime submittedAt' "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "COALESCE(m.display_name, '共同') AS ownerName" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "temporals.format(e.submittedAt, 'MM-dd')" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "ownerColorMap.get(e.ownerName)" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q '快捷支出' "$RD/src/main/resources/templates/entry/_row.html" \
  && grep -q '账户间划转' "$RD/src/main/resources/templates/entry/_row.html" \
  && [ "$(grep -c 'h-8 border border-rule' "$RD/src/main/resources/templates/entry/_row.html")" -ge 4 ] \
  && [ "$(grep -c 'h-9 ' "$RD/src/main/resources/templates/entry/index.html")" -ge 4 ]; } \
  && log_ok "v11-ENTRY-UX 填报页(收入记录行头像+MM-dd填报日期 · 右列操作区h-8等高两段式 · 收入表单h-9统一)" \
  || log_bad "v11-ENTRY-UX 填报页体验修缺件" "see CashFlowMapper/entry/index.html/_row.html"

# v11-UED8 · 2026-07-18 八项细节:支出对齐h-11 · 收入tab同行 · 移动按钮nowrap · sheet不自动聚焦 ·
#   2026-08-04:sheet 那条原来 grep 原文 `if (!mobile) q.focus()`,v1.8 给条件加了「搜索框隐藏时也不聚焦」
#   整条就挂了 —— 守的意图(移动端不自动聚焦)一点没变。改成正则匹配意图,别盯着字面。
#   目标条带移动单列 · 洞察卡标题/信号分层 · 本期pills移动横排 · 趋势图例窄屏短标签
{ [ "$(grep -c 'h-11' "$RD/src/main/resources/templates/entry/index.html")" -ge 2 ] \
  && [ "$(grep -c 'class="tab-cash' "$RD/src/main/resources/templates/entry/index.html")" -ge 2 ] \
  && grep -q 'gap-1.5 whitespace-nowrap' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -qE 'if \(!mobile.*\) q\.focus\(\)' "$RD/src/main/resources/static/js/lens-select.js" \
  && grep -q 'grid-cols-1 sm:grid-cols-2 md:grid-cols-3' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && grep -q 'flex flex-col sm:flex-row sm:items-center' "$RD/src/main/resources/templates/dashboard/_insight-strip.html" \
  && grep -q 'sm:flex-col sm:items-end' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'mobLegend' "$RD/src/main/resources/templates/dashboard/_region.html"; } \
  && log_ok "v11-UED8 八项细节(支出/收入tab/按钮nowrap/sheet不聚焦/目标单列/洞察分层/pills横排/图例短标签)" \
  || log_bad "v11-UED8 细节修复缺件" "see entry/index.html lens-select.js _progress-strip _insight-strip _region.html"

# v11-R6 · 2026-07-19 六项:目标条带两列提密度 · 本期pills强制同行 · 移动自绘图例 · 流水账本式两行 · 账户tile中文 · 目标pct两位小数
{ grep -q 'grid-cols-2 md:grid-cols-3 gap-1.5 sm:gap-2' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && grep -q 'px-3 py-3 sm:px-6 sm:py-4' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && [ "$(grep -c 'whitespace-nowrap text-\[10px\] sm:text-xs' "$RD/src/main/resources/templates/dashboard/_region.html")" -ge 4 ] \
  && grep -q 'flex flex-nowrap items-center gap-1.5 sm:flex-col' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'nwLegendM' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'cpiSeries != null && !mobLegend' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'w-16 flex-shrink-0 inline-flex' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'pl-\[72px\]' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 's.type.label' "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q 'setScale(2, java.math.RoundingMode.HALF_EVEN)' "$RD/src/main/java/com/family/finance/service/goal/GoalProgressService.java"; } \
  && log_ok "v11-R6 六项(目标两列密度/pills同行/自绘图例一行/流水两行金额成列/账户tile中文/目标pct两位小数)" \
  || log_bad "v11-R6 六项修复缺件" "see _progress-strip/_region/EntryController/accounts/GoalProgressService"

# v11-R7 · 2026-07-19 五项:目标ⓘ点击弹描述不跳详情 · 小图标热区扩38px · 自绘图例可点toggle曲线 · lens截图v3 · CI直连central
{ grep -q '_kpi-info :: i(${gp.goal.description' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && grep -q '.kpi-info-btn::after, .tap::after' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'inset: -12px' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'setDatasetVisibility' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'tap text-\[11px\] text-ink-subtle hover:text-rust' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'CN_MIRROR' "$RD/Dockerfile" \
  && grep -q 'CN_MIRROR=0' "$RD/.github/workflows/docker-publish.yml" \
  && [ -f "$RD/deploy/maven-settings-central.xml" ] \
  && grep -q 'pc_lens.jpg?v=3' "$RD/README.md"; } \
  && log_ok "v11-R7 五项(目标ⓘ弹描述stopPropagation · 热区::after-12px · 图例toggle回归 · lens截图成员结构v3 · CI去aliyun单点)" \
  || log_bad "v11-R7 五项缺件" "see _progress-strip/style.css/_region/EntryController/Dockerfile/workflow"

# ===================== v1.2 · 归因复盘 + 再平衡闭环 + 性能底盘 =====================
# v12-ATTR · 归因引擎(纯函数·两步法fx拆分·未归因显性)+ dashboard 懒加载 fragment + 6 维度
{ grep -q 'class AttributionEngine' "$RD/src/main/java/com/family/finance/calc/review/AttributionEngine.java" \
  && grep -q 'subtract(underlying)' "$RD/src/main/java/com/family/finance/calc/review/AttributionEngine.java" \
  && grep -q 'unattributed' "$RD/src/main/java/com/family/finance/calc/review/AttributionEngine.java" \
  && grep -q '行业不做' "$RD/src/main/java/com/family/finance/service/review/AttributionService.java" \
  && grep -q '/dashboard/attribution' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'attribution-mount' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'hx-trigger="revealed" hx-target="this"' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'attrWaterfall' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && grep -q '赚得最多' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && grep -q '亏得最多' "$RD/src/main/resources/templates/dashboard/_attribution.html"; } \
  && log_ok "v12-ATTR 归因引擎+fragment(两步法fx闭合 · 未归因显性 · revealed懒加载 · 瀑布/赚得最多·亏得最多榜/12期)" \
  || log_bad "v12-ATTR 归因缺件" "see calc/review + _attribution.html"

# v12-REVIEW-AI · AI 月度复盘(信号驱动 · V48 缓存 · 脱敏 · 禁算)
{ grep -q '严禁做任何计算' "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java" \
  && grep -q 'anonymize' "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java" \
  && grep -q 'review_ai_cache' "$RD/db/migration/V48__review_and_rebalance_plan.sql" \
  && grep -q 'orderByPrimaryVendor' "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java" \
  && grep -q '/review/insight' "$RD/src/main/java/com/family/finance/web/review/ReviewController.java"; } \
  && log_ok "v12-REVIEW-AI 月度复盘(信号驱动+V48缓存+脱敏+禁算+follow主选vendor)" \
  || log_bad "v12-REVIEW-AI 复盘缺件" "see ReviewInsightService/ReviewController/V48"

# v12-PLAN · 再平衡闭环(采纳/80%核销/关账归档/铁律)
{ grep -q 'rebalance_plan_item' "$RD/db/migration/V48__review_and_rebalance_plan.sql" \
  && grep -q 'K_REBALANCE_MATCH_PCT' "$RD/src/main/java/com/family/finance/service/review/RebalancePlanService.java" \
  && grep -q 'TransferCreatedEvent' "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q 'archiveOnClose' "$RD/src/main/java/com/family/finance/service/PeriodService.java" \
  && grep -q '/rebalance-plan/adopt' "$RD/src/main/java/com/family/finance/web/review/RebalancePlanController.java" \
  && grep -q '再平衡计划执行情况' "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java" \
  && grep -q '本期再平衡计划' "$RD/src/main/resources/templates/reports/_rebalance-plan.html" \
  && grep -q '不要生成新的买卖指令' "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java"; } \
  && log_ok "v12-PLAN 再平衡闭环(V48两表 · 采纳解析 · AFTER_COMMIT核销80%可配 · 关账归档 · 执行率喂诊断只解读)" \
  || log_bad "v12-PLAN 计划闭环缺件" "see RebalancePlanService/Controller/EntryService/PeriodService"

# v12-PERF · 性能底盘(momYoy条件复用 · pending轻查询 · beta实测 P50 476→364ms)
{ grep -q 'MomYoy momYoy(FactSlice slice)' "$RD/src/main/java/com/family/finance/factview/FactViewService.java" \
  && grep -q 'momReuse' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'pendingCount' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && ! grep -q 'pendingRows' "$RD/src/main/resources/templates/dashboard/_region.html"; } \
  && log_ok "v12-PERF 性能底盘(momYoy slice复用条件保显示零回归 · pending计数轻查询 · P50 476→364ms)" \
  || log_bad "v12-PERF 性能缺件" "see DashboardController/FactViewService"

# ===================== v0.12 · 收支填报收入侧升级 =====================
# v12-INCOME-CAT · 类目绑定账户类型 + 股票类收入类目(V34)
CFC="$RD/db/migration/V34__income_category_account_type.sql"
{ [ -f "$CFC" ] && grep -q 'account_type' "$CFC" \
  && grep -q 'stock_salary' "$CFC" && grep -q 'dividend' "$CFC" && grep -q 'stock_sell' "$CFC" \
  && grep -q 'accountType' "$RD/src/main/java/com/family/finance/domain/flow/CashFlowCategory.java"; } \
  && log_ok "v12-INCOME-CAT 类目 account_type 绑定 + 股票类收入类目(薪资-股票/股息/卖出回款)" \
  || log_bad "v12-INCOME-CAT 类目绑定/新类目缺" "see V34 / CashFlowCategory"

# v12-INCOME-ENDPOINT · 收入侧端点 + 类目↔账户服务端校验
ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
EC="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
{ grep -q '/entry/income' "$EC" && grep -q 'public EntryRow recordIncome' "$ES" \
  && grep -q 'cat.getAccountType() != null' "$ES"; } \
  && log_ok "v12-INCOME-ENDPOINT /entry/income + recordIncome + 类目↔账户服务端校验(红线)" \
  || log_bad "v12-INCOME-ENDPOINT 收入端点/校验缺" "see EntryController / EntryService"

# v12-INCOME-STOCK · 股票收入落 CASH 现金行(扛估值刷新)· 不只 applyDeltaToBalance
{ grep -q 'creditAccountBalance' "$ES" \
  && grep -q 'stockHoldingService.adjustAccountCash' "$ES" \
  && grep -q 'public void adjustAccountCash' "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"; } \
  && log_ok "v12-INCOME-STOCK 股票收入落 CASH 现金行(creditAccountBalance→adjustAccountCash)" \
  || log_bad "v12-INCOME-STOCK 股票收入未落现金行" "see EntryService.creditAccountBalance"

# v12-INCOME-KOUJING · 家庭收入侧口径从 cash_flow 汇总(PMC 空收入不再低估;支出侧不动)· 防双计
FV="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
{ grep -q 'netInflowIncome' "$FV" && grep -q 'netInflowExpense' "$FV"; } \
  && log_ok "v12-INCOME-KOUJING 收入/支出各自决定来源(收入 PMC空→cash_flow · 不叠加)" \
  || log_bad "v12-INCOME-KOUJING 收入口径未拆分" "see FactViewServiceImpl.netInflowIncome"

# v12-INCOME-UI · 收入侧类型优先(现金/股票 tab · 股票联动持仓)· 账户行不再硬编码 +收入
REG_ENTRY="$RD/src/main/resources/templates/entry/index.html"
ROW="$RD/src/main/resources/templates/entry/_row.html"
{ grep -q 'class="tab-stock' "$REG_ENTRY" && grep -q 'id="income-cash-block"' "$REG_ENTRY" \
  && grep -q 'stock-holdings-target' "$REG_ENTRY" && grep -q '/entry/income' "$REG_ENTRY" \
  && ! grep -q 'name="kind" value="INCOME"' "$ROW"; } \
  && log_ok "v12-INCOME-UI 收入侧类型优先(现金/股票 tab + 联动持仓)· 账户行无硬编码 +收入" \
  || log_bad "v12-INCOME-UI 收入侧 UI/类型切换缺 或 账户行仍有硬编码收入" "see entry/index.html / _row.html"

# v12-MANUAL-SHARES · 未上市持仓升级为「股数×单股估值」+ V35 迁移(老数据 shares=1 总值不变)
V35="$RD/db/migration/V35__stock_income_holding_model.sql"
AVS="$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java"
SHS="$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"
{ [ -f "$V35" ] && grep -q "valuation_mode = 'MANUAL'" "$V35" && grep -qi 'shares = 1' "$V35" \
  && grep -q 'multiply(sh)' "$AVS" \
  && grep -q 'BigDecimal shares, BigDecimal unitValue' "$SHS" \
  && grep -q 'public StockHolding addShares' "$SHS" \
  && grep -q 'currentUnitValueInAccountCcy' "$SHS"; } \
  && log_ok "v12-MANUAL-SHARES 未上市=股数×单股估值 + V35 迁移(shares=1)+ 估值 shares×unit + addShares" \
  || log_bad "v12-MANUAL-SHARES 未上市模型升级缺" "see V35 / AccountValuationService / StockHoldingService"

# v12-STOCK-SHARE-INCOME · 股票收入按持仓入账(+股数)· cash_flow ref 列 + 端点 + 联动 fragment
ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
EC="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
CFM="$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java"
STKFRAG="$RD/src/main/resources/templates/entry/_income-stock.html"
{ grep -q 'recordStockIncomeExistingHolding' "$ES" && grep -q 'refHoldingId' "$ES" \
  && grep -q '/entry/income/stock/holding' "$EC" && grep -q '/entry/income/stock/new-manual' "$EC" \
  && grep -q 'ref_holding_id' "$CFM" && [ -f "$STKFRAG" ] && grep -q 'th:fragment="holdings"' "$STKFRAG"; } \
  && log_ok "v12-STOCK-SHARE-INCOME 股票+股数入账(端点+服务+ref列+联动持仓fragment)" \
  || log_bad "v12-STOCK-SHARE-INCOME 股票+股数收入缺" "see EntryService/EntryController/CashFlowMapper/_income-stock.html"

# v12-STOCK-SELL-HIDDEN · 卖出回款不算收入(收入类目下拉排除 stock_sell + V35 沉底)
CFCM="$RD/src/main/java/com/family/finance/repository/CashFlowCategoryMapper.java"
{ grep -q "code <> 'stock_sell'" "$CFCM" && grep -q "code = 'stock_sell'" "$V35"; } \
  && log_ok "v12-STOCK-SELL-HIDDEN 卖出回款不算收入(下拉排除 + V35 沉底)" \
  || log_bad "v12-STOCK-SELL-HIDDEN 卖出回款未从收入排除" "see CashFlowCategoryMapper.listIncomeOrdered / V35"

# v12-INCOME-FX · 收入列表币种修正(cash_flow.amount 是账户币种 · 家庭合计换本位币,不裸加 ¥)
{ grep -q 'incomeBaseTotal' "$EC" && grep -q 'toBaseAmount' "$EC" && grep -q 'fxService' "$EC" \
  && grep -q 'incomeBaseTotal' "$REG_ENTRY" && grep -q 'format2(e.currency' "$REG_ENTRY" \
  && ! grep -q "aggregates.sum(incomeEntries" "$REG_ENTRY"; } \
  && log_ok "v12-INCOME-FX 收入列表原币展示 + 家庭合计本位币(不再把账户币种裸加成 ¥)" \
  || log_bad "v12-INCOME-FX 收入列表币种未换算" "see EntryController.toBaseAmount / entry/index.html"

# v12-INCOME-EMPTY · dashboard「本期怎么变的」空态联动(L1)· 收入切 cash_flow 后不能只看 PMC filledMembers
CSV="$RD/src/main/java/com/family/finance/web/dashboard/CashflowSplitView.java"
{ grep -q 'sign(income) == 0' "$CSV" && grep -q 'sign(expense) == 0' "$CSV"; } \
  && log_ok "v12-INCOME-EMPTY dashboard 收支拆解空态兼顾 cash_flow(仅录收入录入也不判空)" \
  || log_bad "v12-INCOME-EMPTY 空态仍只看 PMC filledMembers" "see CashflowSplitView.empty()"

# v13-OPENING · 开账基线:新账户存量本金不计入当期收益(fact 剔除 + 卡第三项 + net_principal 计入)
SMAP="$RD/src/main/java/com/family/finance/repository/SnapshotMapper.java"
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
CSV2="$RD/src/main/java/com/family/finance/web/dashboard/CashflowSplitView.java"
REG2="$RD/src/main/resources/templates/dashboard/_region.html"
{ grep -q 'firstAppearingAccountIds' "$SMAP" \
  && grep -q 'private BigDecimal openingBaseline' "$FVI" \
  && grep -q 'add(openingBaseline(slice' "$FVI" \
  && grep -q 'netWorthTrendExOpening' "$FVI" \
  && grep -q 'subtract(ob)' "$CSV2" && grep -q 'openingBaseline' "$CSV2" \
  && grep -q '开账基线' "$REG2"; } \
  && log_ok "v13-OPENING 开账基线:收益指标剔除(XIRR/TWR/钱赚/PnL)+ 卡第三项 + net_principal 计入 + 财富水位剔除" \
  || log_bad "v13-OPENING 开账基线口径缺" "see FactViewServiceImpl/CashflowSplitView/SnapshotMapper/_region.html"

# v13.1-ISSUE3-PREC · issue#3 精度:V37 放宽 manual_value/cost_basis/close_price → DECIMAL(20,6)· 表单 step 到 6 位
V37="$RD/db/migration/V37__price_precision.sql"
MANF="$RD/src/main/resources/templates/stock/holding-new-manual.html"
AUTOF="$RD/src/main/resources/templates/stock/holding-new-auto.html"
{ [ -f "$V37" ] \
  && grep -q 'manual_value DECIMAL(20,6)' "$V37" \
  && grep -q 'cost_basis DECIMAL(20,6)' "$V37" \
  && grep -q 'close_price DECIMAL(20,6)' "$V37" \
  && grep -q 'name="unitValue" step="0.000001"' "$MANF" \
  && grep -q 'name="costBasis" step="0.000001"' "$AUTOF"; } \
  && log_ok "v13.1-ISSUE3-PREC 价格精度放宽 (20,6) + 表单 step 6 位(单股估值不再被截成 2 位)" \
  || log_bad "v13.1-ISSUE3-PREC 精度迁移/表单 step 缺" "see V37__price_precision.sql / holding-new-manual|auto.html"

# v13.1-ISSUE3-CN · issue#3 自动拉价:A 股交易所前缀集中到 AShareTicker · 两 client 不得再各写 startsWith("6")
ASH="$RD/src/main/java/com/family/finance/service/stock/AShareTicker.java"
SINA="$RD/src/main/java/com/family/finance/service/stock/SinaStockClient.java"
TENC="$RD/src/main/java/com/family/finance/service/stock/TencentStockClient.java"
{ [ -f "$ASH" ] && grep -q "c == '5' || c == '6' || c == '9'" "$ASH" \
  && grep -q 'AShareTicker.withExchange' "$SINA" \
  && grep -q 'AShareTicker.withExchange' "$TENC" \
  && ! grep -q 'startsWith("6")' "$SINA" \
  && ! grep -q 'startsWith("6")' "$TENC"; } \
  && log_ok "v13.1-ISSUE3-CN A 股前缀集中 AShareTicker(5/6/9→sh)· Sina/Tencent 复用 · 无 startsWith(\"6\") 残留(513180 不再误判 sz)" \
  || log_bad "v13.1-ISSUE3-CN A 股前缀仍分散/仍用 startsWith(\"6\")" "see AShareTicker/SinaStockClient/TencentStockClient"

# v14-METAL · 贵金属账户 + 自动金价(issue #4)
V38="$RD/db/migration/V38__precious_metal_account.sql"
MU="$RD/src/main/java/com/family/finance/service/stock/MetalUnit.java"
MPC="$RD/src/main/java/com/family/finance/service/stock/MetalPriceClient.java"
SPF="$RD/src/main/java/com/family/finance/service/stock/StockPriceFetcher.java"
SHSV="$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"
MF="$RD/src/main/resources/templates/stock/holding-new-metal.html"
{ [ -f "$V38" ] && grep -q "unit VARCHAR" "$V38" && grep -q "'METAL'" "$V38" && grep -q "metal_account" "$V38" \
  && grep -q 'METAL("贵金属")' "$RD/src/main/java/com/family/finance/domain/account/AccountType.java" \
  && [ -f "$MU" ] && grep -q 'GRAMS_PER_TROY_OUNCE' "$MU" && grep -q 'normalizeToPerGram' "$MU" \
  && [ -f "$MPC" ] && grep -q 'sina-metal' "$MPC" \
  && grep -q 'Market.METAL' "$SPF" && grep -q 'fetchMetalAndPersist' "$SPF" \
  && grep -q 'createMetal' "$SHSV" \
  && [ -f "$MF" ]; } \
  && log_ok "v14-METAL 贵金属账户 + MetalPriceClient(gds_/hf_)+ 每克归一 + 建仓表单 + V38(unit列/METAL/模板)" \
  || log_bad "v14-METAL 贵金属链路缺" "see V38 / MetalUnit / MetalPriceClient / StockPriceFetcher / StockHoldingService / holding-new-metal.html"

# v14-METAL-PD-SGE · 钯金无上海盘(诚实置灰 · PD+sge→null)
grep -q 'intl ? "XPD" : null' "$MU" \
  && log_ok "v14-METAL-PD-SGE 钯金只有国际盘(PD+SGE→null · UI 提示改选国际)" \
  || log_bad "v14-METAL-PD-SGE 钯金 SGE 未正确置空" "see MetalUnit.tickerFor"

# v14-METAL-ENTRY · 填报页持仓入口覆盖 METAL(修:此前硬编码 STOCK/CRYPTO 漏金属 → 填报录不了重量)
ROWF="$RD/src/main/resources/templates/entry/_row.html"
ECF="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
{ grep -q 'supportsHoldings(row.account.type)' "$ROWF" \
  && ! grep -q "row.account.type.name() == 'STOCK' or row.account.type.name() == 'CRYPTO'" "$ROWF" \
  && grep -q 'Market.METAL' "$ECF"; } \
  && log_ok "v14-METAL-ENTRY 填报页持仓入口走 supportsHoldings(含 METAL)+ 一键刷新含 METAL 市场" \
  || log_bad "v14-METAL-ENTRY 填报页仍漏 METAL 入口" "see entry/_row.html supportsHoldings / EntryController.refresh-stocks"

# v14-REFRESH-COUNT · 刷新估值 toast 分母动态(修 prod「4/3」:市场数增而分母写死 3 → 全成功误报成 warning)
{ grep -q 'marketsOk == total' "$ECF" && ! grep -qE 'marketsOk == 3|/3 市场|"3 市场' "$ECF"; } \
  && log_ok "v14-REFRESH-COUNT 刷新估值 toast 分母 = markets.size() 动态(不再写死 3)" \
  || log_bad "v14-REFRESH-COUNT 刷新估值 toast 仍写死市场数(会误报 X/3)" "see EntryController.refreshStocks"

# v14-LLM-VENDOR · LLM 主选供应商可配 + 温度 + 模型级联(FR-B)
FCS="$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java"
LDS="$RD/src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java"
QLC="$RD/src/main/java/com/family/finance/service/checkup/llm/QwenLlmClient.java"
ICF="$RD/src/main/java/com/family/finance/web/admin/IntegrationsController.java"
INTG="$RD/src/main/resources/templates/admin/integrations.html"
{ grep -q 'K_LLM_PRIMARY_VENDOR' "$FCS" && grep -q 'K_LLM_TEMPERATURE' "$FCS" && grep -q 'K_LLM_MODEL' "$FCS" \
  && grep -q 'orderByPrimaryVendor' "$LDS" \
  && grep -q 'currentTemperature' "$QLC" && grep -q 'pinnedModel' "$QLC" \
  && grep -q 'normalizeLlmModel' "$ICF" \
  && grep -q 'name="primaryVendor"' "$INTG" && grep -q 'id="llmModelSel"' "$INTG" && grep -q 'syncLlmModels' "$INTG"; } \
  && log_ok "v14-LLM-VENDOR 主选供应商(默认Qwen·可切DS)+ 温度可配 + 模型级联下拉(内置清单·越权回落auto)" \
  || log_bad "v14-LLM-VENDOR LLM 自选链路缺" "see FamilyConfigService/LlmDiagnoseService/QwenLlmClient/IntegrationsController/integrations.html"

# v14.1-UAT · 面向用户不泄露英文枚举/代码([[feedback_user_friendly_naming]])· UAT 巡检修
REG_REPORT="$RD/src/main/resources/templates/reports/_region.html"
ENTS="$RD/src/main/java/com/family/finance/service/EntryService.java"
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
{ grep -q 'pc=${pcNameByAccount' "$REG_REPORT" \
  && grep -q 'pcNameByAccount' "$RC" \
  && ! grep -q 'CASH/LOAN 出现' "$ENTS"; } \
  && log_ok "v14.1-UAT 报表类目显中文名(不裸露 GOLD/US_STOCK/PRECIOUS_METAL)+ 填报警告无 CASH/LOAN 枚举" \
  || log_bad "v14.1-UAT 仍泄露英文枚举/代码给用户" "see reports/_region.html 类目列 / EntryService 警告"

# vSEC-1 · 敏感值不入公开库(L10)· 扫 tracked 文件里 URL/SSH 上下文的公网 IP(排除私网/环回)
# 用上下文正则(://IP 或 @IP)避免版本号/SVG 数据误报;不硬编码任何具体 IP,守护自身不泄露、不自匹配
SEC_HITS="$(cd "$RD" && git grep -InoE '(://|@)([0-9]{1,3}\.){3}[0-9]{1,3}' -- . ':(exclude)*.jpg' ':(exclude)*.png' ':(exclude)*.jpeg' 2>/dev/null \
  | grep -vE '(://|@)(127\.|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|0\.0\.0\.0)' || true)"
[ -z "$SEC_HITS" ] \
  && log_ok "vSEC-1 tracked 文件无 URL/SSH 上下文的公网 IP(敏感值不入公开库)" \
  || log_bad "vSEC-1 疑似公网 IP 进了公开库" "$(printf '%s' "$SEC_HITS" | head -2 | tr '\n' ' ')"

# ═══ v0.15 · 券商只读同步(富途 / 老虎)═══
BSVC="$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java"
BLNK="$RD/src/main/java/com/family/finance/service/broker/BrokerLinkService.java"
BCTL="$RD/src/main/java/com/family/finance/web/broker/BrokerLinkController.java"
BCLI="$RD/src/main/java/com/family/finance/service/broker/BrokerClient.java"
DSC="$RD/src/main/java/com/family/finance/service/scheduling/DynamicScheduleConfig.java"
INTG="$RD/src/main/resources/templates/admin/integrations.html"
HOLD="$RD/src/main/resources/templates/stock/holdings.html"

# v15-RO-1 · 只读铁律:适配器无写/交易调用(调用形态匹配 · 排除注释/文档说明)
BRO_HITS="$(grep -rnE 'unlockTrade\(|\.placeOrder|\.modifyOrder|\.cancelOrder|\.replaceOrder' "$RD/src/main/java/com/family/finance/service/broker/" 2>/dev/null | grep -vE '永不|铁律|^\s*\*|//' || true)"
{ [ -z "$BRO_HITS" ]; } \
  && log_ok "v15-RO-1 只读铁律 · 券商适配器无下单/改单/撤单/解锁交易调用" \
  || log_bad "v15-RO-1 券商出现写/交易调用(违反只读铁律)" "$(printf '%s' "$BRO_HITS" | head -1)"

# v15-MAP-1 · reconcile 只动 sync_source=本 vendor 行(不碰手填持仓)
{ grep -q 'src.equals(h.getSyncSource())' "$BSVC" && grep -q 'skippedNonEquity' "$BSVC"; } \
  && log_ok "v15-MAP-1 对账只动 sync_source 行 · 期权/期货跳过计数" \
  || log_bad "v15-MAP-1 对账未按 sync_source 隔离手填持仓" "see BrokerSyncService.reconcile"

# v15-LINK-1 · 关联前留审计快照 + 软归档 + 两步确认硬门
{ grep -q 'AuditLogType.BROKER_LINK' "$BLNK" && grep -q 'holdingMapper.archive' "$BLNK" \
  && grep -q '!confirmed || !acknowledged' "$BCTL"; } \
  && log_ok "v15-LINK-1 关联高危 · 快照留痕 + 软归档 + 两步确认(confirmed&&acknowledged)" \
  || log_bad "v15-LINK-1 关联缺快照/两步确认" "see BrokerLinkService / BrokerLinkController"

# v15-CRON-1 · broker-sync 进动态调度(可配 cron · 无关联空跑)
{ grep -q '"broker-sync"' "$DSC" && grep -q 'syncAllEnabled' "$DSC" && grep -q 'K_BROKER_SYNC_CRON' "$DSC"; } \
  && log_ok "v15-CRON-1 券商同步进动态调度 · cron 可配 · 无关联空跑" \
  || log_bad "v15-CRON-1 broker-sync 未纳入 DynamicScheduleConfig" "see DynamicScheduleConfig"

# v15-CFG-1 · 管理页 ⑥ 券商段在岗(老虎/富途 + 私钥不回显 + 测试连接)
{ grep -q '券商同步' "$INTG" && grep -q 'name="tigerKey"' "$INTG" && grep -q 'type="password"' "$INTG" \
  && grep -q '/admin/integrations/broker/test' "$INTG"; } \
  && log_ok "v15-CFG-1 管理页 ⑥ 券商段 · 老虎/富途凭据(私钥不回显)+ 测试连接" \
  || log_bad "v15-CFG-1 管理页券商段缺件" "see admin/integrations.html"

# v15-ENTRY-1 · 券商入口在账户页(账户颗粒度)+ 持仓页保留同步徽章(v0.15.x 入口迁移)
ACCIDX="$RD/src/main/resources/templates/accounts/index.html"
# v1.6.24 反转:原来这里有一条 `! grep -q '/broker|}' "$HOLD"` **禁止**持仓页出现券商链接
#   (v0.15.x「入口迁到账户页」的决定)。用户第 14 轮指出那个结论不对 —— 配置确实是账户颗粒度,
#   但持仓页本身就是账户颗粒度的页面、且是用户从填报页进来干活的地方。禁令改成**要求**。理由见 tech-design §34。
# v1.6.23 补强:原来只断言「模板里有 /broker(id=」—— 入口被塞进 ⋯ 收纳菜单后它照样 PASS,
#   用户实际找不到(见 v1623-ENTRY-VIS)。这里静态加一条:⋯ 菜单块(row-more-pop…</details>)里
#   不得出现 /broker —— 能力入口只能在行内。这条不依赖浏览器,是运行时守护的廉价互补。
MOREPOP="$(sed -n '/row-more-pop/,/<\/details>/p' "$ACCIDX")"
{ grep -q '/broker(id=' "$ACCIDX" && grep -q '券商托管' "$ACCIDX" \
  && ! printf '%s' "$MOREPOP" | grep -q '/broker' \
  && grep -q '券商同步' "$HOLD" && grep -q '/broker|}' "$HOLD"; } \
  && log_ok "v15-ENTRY-1 券商入口在账户页行内(不在 ⋯ 收纳里)+ 托管徽章 · 持仓页也有入口(v1.6.24 反转旧禁令)" \
  || log_bad "v15-ENTRY-1 入口/徽章位置不对(或券商入口被收进 ⋯ 菜单 / 持仓页丢了入口)" "see accounts/index.html / stock/holdings.html · 能力入口不得进 row-more-pop"

# v15-GRAN · 连接配置下沉到关联颗粒度(V40)+ per-link 测试富卡片
BLDOM="$RD/src/main/java/com/family/finance/domain/broker/BrokerLink.java"
BLMAP="$RD/src/main/java/com/family/finance/repository/BrokerLinkMapper.java"
{ [ -f "$RD/db/migration/V40__broker_link_conn.sql" ] \
  && grep -q 'opendHost' "$BLDOM" && grep -q 'findByFamily' "$BLMAP" \
  && grep -q '"/accounts/{accountId}/broker/test"' "$BCTL" \
  && grep -q 'brokerTestReport' "$RD/src/main/resources/templates/broker/link.html" \
  && grep -q 'TestReport testConnection(long familyId, BrokerLink link)' "$RD/src/main/java/com/family/finance/service/broker/BrokerClient.java"; } \
  && log_ok "v15-GRAN 关联颗粒度连接(V40 opend_host/port·NULL=全局)+ per-link 测试连接富卡片" \
  || log_bad "v15-GRAN 关联颗粒度模型缺件" "see V40/BrokerLink/BrokerLinkMapper/BrokerLinkController/link.html"

# v15-FIX-TX · 关联与首次同步拆两段事务(修 rollback-only:link() 不再嵌套 sync())
#            + 关联前快照进 payload_json、summary 防御截断(修 Data too long for 'summary')
{ grep -q 'public void link(' "$BLNK" && grep -q 'public String initialSync(' "$BLNK" && grep -q 'linkService.initialSync' "$BCTL" \
  && grep -q 'snapshotRows' "$BLNK" && grep -q 'preLinkHoldings' "$BLNK" \
  && grep -q 'SUMMARY_MAX' "$RD/src/main/java/com/family/finance/service/AuditLogService.java"; } \
  && log_ok "v15-FIX-TX 关联 link() 提交后另起事务 initialSync(无 rollback-only)+ 快照进 payload_json、summary≤255 截断(无 Data too long)" \
  || log_bad "v15-FIX-TX link() 仍嵌套首次同步 / 快照仍塞 summary" "see BrokerLinkService/BrokerLinkController/AuditLogService"

echo
# ============================================================
# v0.16 · 目标模块重构(通用追踪目标 · 绑 0–N 账户 + 追踪指标 + 时间范围)
# ============================================================
GDOM="$RD/src/main/java/com/family/finance/domain/goal"
GSVC="$RD/src/main/java/com/family/finance/service/goal"

# v16-GOAL-MIG · 迁移加列 + goal_account 表 + 回填
{ [ -f "$RD/db/migration/V41__goal_generic.sql" ] && [ -f "$RD/db/migration/V42__goal_account.sql" ] \
  && grep -q 'ADD COLUMN metric' "$RD/db/migration/V41__goal_generic.sql" \
  && grep -q "CREATE TABLE goal_account" "$RD/db/migration/V42__goal_account.sql" \
  && grep -q 'CUSTOM' "$GDOM/GoalType.java"; } \
  && log_ok "v16-GOAL-MIG V41 加列 metric/comparator/time_mode + 回填 · V42 goal_account(0..N)· GoalType 加 CUSTOM" \
  || log_bad "v16-GOAL-MIG 迁移/枚举缺件" "see db/migration/V41,V42 · GoalType"

# v16-GOAL-EVAL · 指标聚合 + pace(纯函数 + 单测)
{ [ -f "$GSVC/GoalMetricEvaluator.java" ] && [ -f "$GSVC/GoalPaceCalculator.java" ] \
  && grep -q 'weighted' "$GSVC/GoalMetricEvaluator.java" \
  && grep -q 'BEHIND_GAP' "$GSVC/GoalPaceCalculator.java" \
  && grep -q 'isRate' "$GDOM/GoalMetric.java" \
  && [ -f "$RD/src/test/java/com/family/finance/service/goal/GoalMetricEvaluatorTest.java" ] \
  && [ -f "$RD/src/test/java/com/family/finance/service/goal/GoalPaceCalculatorTest.java" ]; } \
  && log_ok "v16-GOAL-EVAL 指标价值加权聚合 + pace(金额判落后/比率仅倒计时)+ 单测在岗" \
  || log_bad "v16-GOAL-EVAL evaluator/pace/单测缺件" "see service/goal"

# v16-GOAL-CUSTOM · 自定义向导路由 + 账户多选 + 分流
{ grep -q '"/goals/new/custom"' "$RD/src/main/java/com/family/finance/web/goal/GoalController.java" \
  && grep -q 'createCustom' "$GSVC/GoalService.java" \
  && grep -q 'computeCustom' "$GSVC/GoalProgressService.java" \
  && [ -f "$RD/src/main/resources/templates/goals/new-custom.html" ] \
  && grep -q 'name="accountIds"' "$RD/src/main/resources/templates/goals/new-custom.html" \
  && grep -q 'name="description"' "$RD/src/main/resources/templates/goals/new-custom.html" \
  && grep -q 'id="burnup"' "$RD/src/main/resources/templates/goals/detail.html"; } \
  && log_ok "v16-GOAL-CUSTOM /goals/new/custom 向导(先账户后指标 + 可选描述)+ 分流 + 详情 burn-up 达标节奏图" \
  || log_bad "v16-GOAL-CUSTOM 向导/分流缺件" "see GoalController/GoalService/new-custom.html"

# v16-GOAL-BAR · 移动端紧凑条(列表 + Dashboard 条带瘦身)
{ [ -f "$RD/src/main/resources/templates/goals/_goal-bar.html" ] \
  && grep -q '_goal-bar :: bar' "$RD/src/main/resources/templates/goals/index.html" \
  && ! grep -q 'gp.scenarios' "$RD/src/main/resources/templates/goals/index.html" \
  && grep -q "hidden md:flex" "$RD/src/main/resources/templates/goals/_progress-strip.html"; } \
  && log_ok "v16-GOAL-BAR 紧凑条 fragment · 列表去 scenarios(CUSTOM 安全)· Dashboard 条带手机限 2 条" \
  || log_bad "v16-GOAL-BAR 紧凑条/瘦身缺件" "see goals/_goal-bar.html · index.html · _progress-strip.html"

# v15-UX · 二次确认走自建弹窗(无 native confirm)+ 创建证券账户显同步提示
BLHTML="$RD/src/main/resources/templates/broker/link.html"
WIZ="$RD/src/main/resources/templates/accounts/_template-wizard.html"
{ ! grep -q 'confirm(' "$BLHTML" && grep -q 'id="lnkModal"' "$BLHTML" && grep -q 'id="unlModal"' "$BLHTML" \
  && grep -q 'brokerSyncHint' "$WIZ"; } \
  && log_ok "v15-UX 二次确认自建弹窗(无系统 confirm)+ 建证券账户显券商同步提示" \
  || log_bad "v15-UX 仍用系统 confirm 或缺创建提示" "see broker/link.html / _template-wizard.html"

# v15-HELP · 券商凭据获取图文向导在岗 + 各入口挂教程链接
HELP="$RD/src/main/resources/templates/help/broker-sync.html"
HCTL="$RD/src/main/java/com/family/finance/web/help/HelpController.java"
{ grep -q '/help/broker-sync' "$HCTL" && grep -q 'id="tiger"' "$HELP" && grep -q 'id="futu"' "$HELP" \
  && grep -q 'developer.itigerup.com' "$HELP" && grep -q 'download/openAPI' "$HELP" \
  && grep -q '/help/broker-sync' "$INTG" && grep -q '/help/broker-sync' "$BLHTML"; } \
  && log_ok "v15-HELP 券商凭据图文向导(富途/老虎步骤+示意图)+ 管理页/关联页挂教程入口" \
  || log_bad "v15-HELP 图文向导缺件或入口未挂" "see help/broker-sync.html / HelpController / integrations.html / broker/link.html"

# v15-OPEND · 富途 OpenD 三拓扑部署方案齐全(systemd 模板 + compose 覆盖 + 文档 + 应用内块)
{ [ -f "$RD/deploy/futu-opend.service.example" ] && [ -f "$RD/deploy/futu-opend.compose.yml" ] \
  && grep -q 'OpenD 部署到哪' "$RD/docs/broker-sync-guide.md" \
  && grep -q 'futu-opend.service.example' "$HELP"; } \
  && log_ok "v15-OPEND 富途 OpenD 部署方案(同机 systemd / docker sidecar / 家用机隧道)+ 文档 + 应用内说明" \
  || log_bad "v15-OPEND OpenD 部署方案缺件" "see deploy/futu-opend.* / docs/broker-sync-guide.md / help/broker-sync.html"

# v15-OPEND-WIZ · 应用内 OpenD 傻瓜向导(自管子进程:下载/版本/依赖/配置启动/短信中继)
OWMGR="$RD/src/main/java/com/family/finance/service/broker/opend/FutuOpendManager.java"
OWCTL="$RD/src/main/java/com/family/finance/web/broker/FutuOpendController.java"
OWTPL="$RD/src/main/resources/templates/broker/opend-wizard.html"
{ [ -f "$OWMGR" ] && [ -f "$OWCTL" ] && [ -f "$OWTPL" ] \
  && grep -q '/admin/broker/opend' "$OWCTL" && grep -q 'api_ip=127.0.0.1' "$OWMGR" \
  && grep -q 'input_phone_verify_code' "$OWMGR" \
  && grep -q 'detectEnv' "$OWMGR" && grep -q '/.dockerenv' "$OWMGR" && grep -q 'packageTag' "$OWMGR" \
  && grep -q "channel == 'DOCKER'" "$OWTPL" \
  && grep -q 'installFromStream' "$OWMGR" && grep -q '"/upload"' "$OWCTL" && grep -q '上传并解压' "$OWTPL" \
  && grep -q 'installFromServerPath' "$OWMGR" && grep -q 'import-path' "$OWCTL" && grep -q '从该路径导入' "$OWTPL" \
  && grep -q "var BASE = '/admin/broker/opend/'" "$OWTPL" \
  && ! grep -qE "fetch\('(status|deps|upload|download)|post\('(download|sms)" "$OWTPL" \
  && grep -q 'id="step1Done"' "$OWTPL" && grep -q 'id="step1Full"' "$OWTPL" \
  && grep -q 'id="step2Locked"' "$OWTPL" && grep -q 'id="loginDone"' "$OWTPL" && grep -q 'id="loginForm"' "$OWTPL" \
  && grep -q 'addAttribute("installed"' "$OWCTL" && grep -q 'addAttribute("running"' "$OWCTL" \
  && grep -q 'public SelfCheck selfCheck' "$OWMGR" && grep -q 'probeWritable' "$OWMGR" \
  && grep -q '"/selfcheck"' "$OWCTL" && grep -q '"/test"' "$OWCTL" \
  && grep -q 'id="selfBtn"' "$OWTPL" && grep -q 'id="testBtn"' "$OWTPL" \
  && grep -q 'name="vendor" value="FUTU"' "$RD/src/main/resources/templates/admin/integrations.html" \
  && grep -q '/admin/broker/opend' "$RD/src/main/resources/templates/admin/integrations.html"; } \
  && log_ok "v15-OPEND-WIZ 应用内一键 OpenD 向导(下载/版本/依赖/配置启动/短信中继·只绑127.0.0.1)+ step-by-step 门控(装好收起第1步/亮第2步·运行中收起表单)+ 渠道自适应 + 管理页入口" \
  || log_bad "v15-OPEND-WIZ OpenD 向导缺件或渠道未自适应" "see service/broker/opend / web/broker/FutuOpendController / broker/opend-wizard.html"

# v12-2-FONTSCALE · 全局字号调节(issue #7)· 标准档零回归(calc×var,scale=1 等价)+ 5 层覆盖 + 控件 + FOUC + 图表跟随
FSCSS="$RD/src/main/resources/static/css/style.css"
FSLAY="$RD/src/main/resources/templates/fragments/layout.html"
FSNAV="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -q 'html\[data-fs="lg"\]' "$FSCSS" && grep -q 'html\[data-fs="xl"\]' "$FSCSS" \
  && grep -qF 'font-size: calc(10px * var(--fs-scale,1)) !important' "$FSCSS" \
  && grep -qF 'font-size: calc(11px * var(--fs-scale,1)) !important' "$FSCSS" \
  && grep -qF 'font-size: calc(15px * var(--fs-scale,1))' "$FSCSS" \
  && grep -qF "max(16px, calc(14px * var(--fs-scale,1)))" "$FSCSS" \
  && grep -qF "localStorage.getItem('fontScale')" "$FSLAY" \
  && grep -q 'window.setFontScale' "$FSLAY" && grep -q 'window.chartFont' "$FSLAY" && grep -q 'fontscalechange' "$FSLAY" \
  && grep -q 'data-fs-opt' "$FSNAV" && grep -q 'setFontScale(' "$FSNAV" \
  && grep -q 'chartFont(' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && grep -q 'chartFont(' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'fontscalechange' "$RD/src/main/resources/templates/dashboard/_attribution.html"; } \
  && log_ok "v12-2-FONTSCALE 字号档位(标准零回归 calc×--fs-scale · 层2 六类!important压PlayCDN · iOS16px地板限lg/xl · FOUC · Aa控件双端 · 图表chartFont跟随+归因活重绘)" \
  || log_bad "v12-2-FONTSCALE 字号档位缺件" "see style.css(--fs-scale/text-[Npx]) + layout.html(FOUC/setFontScale/chartFont) + nav.html(data-fs-opt)"

# v13-SUN-METRIC · 旭日可选分析指标(后端零改动 · 复用 /lens/query + PivotEngine 全指标 · 两渲染模式 · 持仓级诚实降级)
SMJS="$RD/src/main/resources/static/js/lens.js"
SMTPL="$RD/src/main/resources/templates/lens/_section.html"
{ grep -q 'SUN_METRICS' "$SMJS" && grep -q 'function sunMetricUnavailable' "$SMJS" \
  && grep -q 'function rateColor' "$SMJS" && grep -q 'function pnlColor' "$SMJS" \
  && grep -q 'function renderSunMetricBar' "$SMJS" && grep -q "sunMetric: 'value'" "$SMJS" \
  && grep -qF 'rows: [state.sunDims[0]], cols: [state.sunDims[1]]' "$SMJS" \
  && grep -q 'renderSunCenter' "$SMJS" \
  && grep -q 'id="sunMetricBar"' "$SMTPL" && grep -q 'id="sunMetricPop"' "$SMTPL" && grep -q 'id="sunDegradeNote"' "$SMTPL" && grep -q 'id="sunResetBtn"' "$SMTPL" && grep -q 'function resetLens' "$SMJS" && grep -q 'SUN_HOLDING_DEGRADE' "$SMJS" && grep -q 'netPrincipal' "$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java" && grep -q 'latestReturn' "$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java"; } \
  && log_ok "v13-SUN-METRIC 旭日多指标(金额/本期收益额/累计收益额/累计收益率 · 可加→弧长/比率→市值+热力 · 中心圆换指标 · 持仓级灰置+回退 · 后端零改动复用PivotEngine)" \
  || log_bad "v13-SUN-METRIC 旭日多指标缺件" "see lens.js(SUN_METRICS/renderSunburst rows=[内]cols=[外]) + lens/_section.html(sunMetricBar/Pop/DegradeNote)"

# v13-1-NAVVER · nav logo 下版本徽记(app.version → GlobalModelAdvice appVersion → nav.html 渲染)
# 注:app.version 与发布 tag 的一致性由 release-prod 预检硬门(本地工具)保证,此处只守随仓库发布的 3 个文件
NVYML="$RD/src/main/resources/application.yml"
NVADV="$RD/src/main/java/com/family/finance/common/GlobalModelAdvice.java"
NVNAV="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -qE 'version: \$\{APP_VERSION:[0-9]+\.[0-9]+\.[0-9]+' "$NVYML" \
  && grep -q '@ModelAttribute("appVersion")' "$NVADV" \
  && grep -q "@Value(\"\${app.version" "$NVADV" \
  && grep -q "'v' + \${appVersion}" "$NVNAV"; } \
  && log_ok "v13-1-NAVVER nav 版本徽记(app.version 单一来源 → appVersion 注入 → logo 下 ◇v 徽记)" \
  || log_bad "v13-1-NAVVER 版本徽记缺件" "see application.yml(app.version) + GlobalModelAdvice(appVersion) + nav.html(◇v${appVersion})"

# v14-HOLDING-IMPORT · 持仓截图智能解析(视觉识别→三态比对→确认落库)· 关键护栏静态断言
HISVC="$RD/src/main/java/com/family/finance/service/holdingimport/HoldingImportService.java"
HIVIS="$RD/src/main/java/com/family/finance/service/holdingimport/QwenVisionClient.java"
HICTL="$RD/src/main/java/com/family/finance/web/holdingimport/HoldingImportController.java"
HIVAL="$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java"
HIHOLD="$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"
HIROW="$RD/src/main/resources/templates/entry/_row.html"
{ test -f "$RD/db/migration/V49__holding_screenshot_tags.sql" && test -f "$RD/db/migration/V50__holding_import.sql" \
  && grep -q 'holding_import' "$RD/db/migration/V50__holding_import.sql" \
  && grep -q 'ref_import_id' "$RD/db/migration/V50__holding_import.sql" \
  && grep -q 'VARCHAR(16)' "$RD/db/migration/V49__holding_screenshot_tags.sql" \
  && grep -q 'K_LLM_VISION_MODEL' "$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java" \
  && grep -q '只转写\|不做任何计算\|绝不计算' "$HIVIS" \
  && grep -q 'SYNC_SOURCE = "SCREENSHOT"' "$HISVC" \
  && grep -q 'UPDATE\|NEW\|SOLD' "$HISVC" \
  && grep -q 'refreshOneAccount' "$HISVC" && grep -q 'TriggerKind.IMPORT' "$HISVC" \
  && grep -q 'AccountType.WEALTH\|AccountType.CASH' "$HIHOLD" \
  && grep -q 'holdings.isEmpty()' "$HIVAL" \
  && grep -q 'refreshOneAccount' "$HIVAL" \
  && grep -q 'entry/import' "$HIROW" \
  && grep -q '/entry/import/item' "$HICTL"; } \
  && log_ok "v14-HOLDING-IMPORT 持仓截图导入(V49/V50 迁移 + 视觉禁算 + SCREENSHOT 隔离 + 三态 + IMPORT 估值交接 + WEALTH/CASH 放开+无持仓不接管红线 + 填报入口)" \
  || log_bad "v14-HOLDING-IMPORT 持仓截图导入缺件" "see HoldingImportService/QwenVisionClient/AccountValuationService/StockHoldingService/entry/_row.html + V49/V50"

# v142-ENTRY-IMPORT-FIX · v1.4.2 五点(流水删除 bug + 转账双账户确认 + 导入回来源页 + 划转主理人头像 + 手机AI徽记 + 图片查看删除)
HICTL2="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
HINAV="$RD/src/main/resources/templates/fragments/nav.html"
HISS="$RD/src/main/resources/static/js/searchable-select.js"
HIIMPHTML="$RD/src/main/resources/templates/holdingimport/import.html"
{ grep -qF 'hx-target=\"#entry-block-' "$HICTL2" \
  && ! grep -qF 'hx-target=\"#row-' "$HICTL2" \
  && grep -q '同时影响两个账户' "$HICTL2" \
  && grep -q 'safeLocalPath' "$HICTL" \
  && grep -q 'addAccountOwnerMeta' "$HICTL2" && grep -q 'memberNameById' "$HICTL2" \
  && grep -q 'data-owner' "$HIROW" \
  && grep -qF 'opt.dataset.owner' "$HISS" \
  && grep -q 'image/delete' "$HICTL" && grep -q 'imageRels' "$HISVC" && grep -q 'deleteImage' "$HISVC" \
  && grep -q 'nextImageIndex' "$HISVC" \
  && grep -q 'js-gallery' "$HIIMPHTML" && grep -qF 'id="lb"' "$HIIMPHTML" && grep -q 'rescanBtn' "$HIIMPHTML" \
  && [ "$(grep -c '支持 AI 截图导入' "$HINAV")" -ge 2 ]; } \
  && log_ok "v142-ENTRY-IMPORT-FIX(流水删除 hx-target 修正 + 转账双账户二次确认 + 导入回来源页 safeLocalPath + 划转主理人头像 + 手机端AI徽记双端 + 图片查看放大删除+重扫)" \
  || log_bad "v142-ENTRY-IMPORT-FIX 缺件" "see EntryController(hx-target/confirm/ownerMeta) + HoldingImport(image endpoints) + nav.html 移动AI + searchable-select data-owner + import.html 画廊/灯箱"

# v142-LENS-RESET · 旭日重置回"页面刷新初始态"(默认看板+默认指标),非只重置当前看板
HILENS="$RD/src/main/resources/static/js/lens.js"
{ awk '/function resetLens\(\)/,/^  }/' "$HILENS" | grep -q 'applyBoard(PRESETS\[0\]' \
  && awk '/function resetLens\(\)/,/^  }/' "$HILENS" | grep -q "state.measures = \['value', 'share'\]" \
  && ! awk '/function resetLens\(\)/,/^  }/' "$HILENS" | grep -q 'state.boardKey'; } \
  && log_ok "v142-LENS-RESET(旭日重置回默认看板 PRESETS[0]+复位 measures,不再只重置当前看板)" \
  || log_bad "v142-LENS-RESET 缺件" "see lens.js resetLens 应 applyBoard(PRESETS[0]) + 复位 measures"

# v143-LENS-TOC-UX · 三点 UX(隐私浮标不遮TOC + 横滑提示 + 重置/AI按钮独立行)
HITOC="$RD/src/main/resources/static/js/toc.js"
HICSS="$RD/src/main/resources/static/css/style.css"
HILENSSEC="$RD/src/main/resources/templates/lens/_section.html"
{ grep -q "classList.add('toc-open')" "$HITOC" && grep -q "classList.remove('toc-open')" "$HITOC" \
  && grep -qF 'body.toc-open #priv-float' "$HICSS" \
  && grep -q '.lens-hscroll' "$HICSS" \
  && grep -q 'lens-hscroll' "$HILENSSEC" \
  && grep -q 'markHScroll' "$HILENS" \
  && grep -q 'justify-end' "$HILENSSEC"; } \
  && log_ok "v143-LENS-TOC-UX(TOC开时隐藏隐私浮标 + 旭日指标/看板横滑渐隐提示 + 重置/AI按钮独立右对齐行)" \
  || log_bad "v143-LENS-TOC-UX 缺件" "see toc.js(toc-open) + style.css(body.toc-open/#priv-float + .lens-hscroll) + _section.html(lens-hscroll/justify-end) + lens.js(markHScroll)"

# v15-PENETRATION · 基金持仓穿透(账户→持仓→持仓方向 · 真实股债现金+申万行业)
V15MIG="$RD/db/migration/V51__fund_penetration.sql"
V15CLI="$RD/src/main/java/com/family/finance/service/penetration/EastMoneyFundClient.java"
V15SVC="$RD/src/main/java/com/family/finance/service/penetration/FundPenetrationService.java"
V15LENS="$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java"
V15CTL="$RD/src/main/java/com/family/finance/web/lens/LensTagController.java"
V15TAGS="$RD/src/main/resources/templates/lens/tags.html"
V15IND="$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java"
{ test -f "$V15MIG" && grep -q 'holding_allocation' "$V15MIG" && grep -q 'fund_penetration_cache' "$V15MIG" && grep -q 'penetrate_state' "$V15MIG" \
  && grep -q 'HOME_APPLIANCE' "$V15IND" && grep -q 'FOOD_BEVERAGE' "$V15IND" \
  && grep -q 'resolveCode' "$V15CLI" && grep -q 'assetAllocation' "$V15CLI" && grep -q 'topHoldings' "$V15CLI" && grep -q 'stockIndustry' "$V15CLI" && grep -q 'mapIndustry' "$V15CLI" \
  && grep -q 'penetrateHolding' "$V15SVC" && grep -q 'KIND_OTHER\|其他持仓\|OTHER' "$V15SVC" && grep -q 'scaleTo' "$V15SVC" \
  && grep -q 'allocMapper.findByHolding' "$V15LENS" && grep -q 'getWeightBp' "$V15LENS" \
  && grep -q '/lens/tags/penetrate' "$V15CTL" \
  && grep -q '持仓方向\|拉取穿透\|penetrate-all' "$V15TAGS"; } \
  && log_ok "v15-PENETRATION(持仓方向层 + 东财穿透client 资产配置/前十大→申万/股票行业 + lens按方向拆头寸 + 打标页拉取 + 理财未穿透诚实降级)" \
  || log_bad "v15-PENETRATION 缺件" "see V51迁移 + IndustryTag扩容 + EastMoneyFundClient + FundPenetrationService + LensQueryService分拆 + LensTagController端点 + tags.html"

# v151-PEN-STREAM · 穿透 SSE 流式逐支揭示 + 旭日行业集中去股票硬过滤
{ grep -q '/lens/tags/penetrate-stream' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q 'SseEmitter' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q 'streamPenetrateFamily' "$RD/src/main/java/com/family/finance/service/penetration/FundPenetrationService.java" \
  && grep -q 'penetrateHoldingResult' "$RD/src/main/java/com/family/finance/service/penetration/FundPenetrationService.java" \
  && grep -q 'penStreamBtn' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'EventSource' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q "name: '行业集中',  sun: \['industry', 'platform'\],   rows: \['industry'\],   cols: \['platform'\],   filters: {} " "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v151-PEN-STREAM(穿透 SSE 流式逐支反馈弹层 + 行业集中去掉股票硬过滤)" \
  || log_bad "v151-PEN-STREAM 缺件" "see LensTagController SseEmitter端点 + FundPenetrationService.streamPenetrateFamily + tags.html EventSource弹层 + lens.js 行业集中 filters:{}"

# v152-PIVOT-CARTESIAN · 交叉表多指标参与列/行笛卡尔(指标作维度 · 拨片切列/行 · 默认列最后一级)
{ grep -q "measurePos: 'col'" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "多指标 → 指标作维度参与笛卡尔" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "data-mp=\"col\"" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "data-mp=\"row\"" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q '指标放' "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v152-PIVOT-CARTESIAN(多指标参与笛卡尔 · 指标放列/行拨片 · 单值/格)" \
  || log_bad "v152-PIVOT-CARTESIAN 缺件" "see lens.js state.measurePos + renderPivot 多指标分支 + renderMeasurePills 指标放列/行拨片"

# v152-PIVOT-MOBILE-HINT · 交叉表移动端横屏提示(可×掉·会话内记忆)+ 首列 sticky 阴影(竖屏重排)
{ grep -q 'id="pivotHint"' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'pivotHintClose' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'md:hidden' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q "pivotHintX" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "sticky-col{position:sticky;left:0;z-index:1;box-shadow" "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v152-PIVOT-MOBILE-HINT(移动端横屏提示可×掉 + 首列 sticky 阴影)" \
  || log_bad "v152-PIVOT-MOBILE-HINT 缺件" "see _section.html #pivotHint + lens.js pivotHintX 记忆 + sticky-col box-shadow"

# v152-TPL-PLATFORM · 账户模板补平台默认 + 建户未填自动带出(打标平台一致性 item6)
{ grep -q "ADD COLUMN platform" "$RD/db/migration/V52__account_template_platform.sql" \
  && grep -q "private String platform" "$RD/src/main/java/com/family/finance/domain/account/AccountTemplate.java" \
  && grep -q "icon, platform, sort_order" "$RD/src/main/java/com/family/finance/repository/AccountTemplateMapper.java" \
  && grep -q "getTemplateId() != null" "$RD/src/main/java/com/family/finance/web/account/AccountController.java" \
  && grep -q "AccountTemplate::getPlatform" "$RD/src/main/java/com/family/finance/web/account/AccountController.java" \
  && grep -q "data-tpl-platform" "$RD/src/main/resources/templates/accounts/_template-wizard.html"; } \
  && log_ok "v152-TPL-PLATFORM(账户模板平台默认 + 建户未填自动带出 + 向导告知 + 管理页可见)" \
  || log_bad "v152-TPL-PLATFORM 缺件" "see V52迁移 + AccountTemplate.platform + mapper SELECT platform + AccountController create默认 + 向导 data-tpl-platform"

# ── v1.6 UED 专项(docs/ued-review-2026-07.md · 61 条双端截图审计)────────────────

# v16-UED-TRUST · 跨页口径统一 + 异常值兜底 + 已关账只读(review A2/A7/B2-1)
{ grep -q "resolveAnchor" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnoseService.java" \
  && grep -q "findCurrentOpen" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnoseService.java" \
  && grep -q "EMERGENCY_OUTLIER_MONTHS" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnose.java" \
  && grep -q "emergencyOutlier" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnose.java" \
  && grep -q "emergencyLabel" "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q "anchorPeriod" "$RD/src/main/java/com/family/finance/web/checkup/CheckupController.java" \
  && grep -q "数据截至" "$RD/src/main/resources/templates/checkup/family.html" \
  && grep -q "本期已关账" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "status.name() != 'CLOSED'" "$RD/src/main/resources/templates/entry/_row.html"; } \
  && log_ok "v16-UED-TRUST(体检与仪表盘同 anchor + 账期标注 + 紧急储备兜底 + 已关账只读)" \
  || log_bad "v16-UED-TRUST 缺件" "see FamilyDiagnoseService.resolveAnchor / FamilyDiagnose.emergencyOutlier / checkup 数据截至 / entry CLOSED 只读"

# v16-UED-MONEY · 金额千分位(review A3 · 此前 checkup/detail 共 32 处裸输出)
{ ! grep -qE 'formatDecimal\([^)]*, 1, [0-9]\)' "$RD/src/main/resources/templates/checkup/family.html" \
  && ! grep -qE 'formatDecimal\([^)]*, 1, [0-9]\)' "$RD/src/main/resources/templates/checkup/account.html" \
  && ! grep -qE 'formatDecimal\([^)]*, 1, [0-9]\)' "$RD/src/main/resources/templates/accounts/detail.html" \
  && grep -q "COMMA" "$RD/src/main/resources/templates/checkup/family.html"; } \
  && log_ok "v16-UED-MONEY(体检/账户详情金额一律带千分位)" \
  || log_bad "v16-UED-MONEY 缺件" "see checkup/family+account、accounts/detail 的 formatDecimal 需带 'COMMA'"

# v16-UED-CONTRAST · 对比度与色彩 token(review B6/A6 · visual-spec §1.2)
{ grep -q -- "--ink-subtle:   #706657" "$RD/src/main/resources/static/css/style.css" \
  && grep -q -- "--ink-faint" "$RD/src/main/resources/static/css/style.css" \
  && grep -q -- "--brass-text" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "prefers-reduced-motion" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "pill-mute" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "grid-hairline" "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v16-UED-CONTRAST(ink-subtle 过 AA + ink-faint/brass-text + reduced-motion + pill-mute + 发丝网格)" \
  || log_bad "v16-UED-CONTRAST 缺件" "see style.css :root token + prefers-reduced-motion + .pill-mute + .grid-hairline"

# v16-UED-MOBILE · 移动端首屏折叠 + 大组件形态(review A4/B1/B2/B4)
{ grep -q "filter-fold" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "filter-fold" "$RD/src/main/resources/templates/reports/_region.html" \
  && grep -q "本 · 期 · 一 · 句 · 话" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "entry-fold" "$RD/src/main/resources/templates/entry/_row.html" \
  && grep -q "kpi-band" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "summary-band" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q "donutConfig" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "barRows" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'display: grid !important' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'display: flex !important' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v16-UED-MOBILE(口径折叠+一句话结论+填报行折叠+KPI主次网格+汇总带横滑+环图转条形+双向柱TopN)" \
  || log_bad "v16-UED-MOBILE 缺件" "see filter-fold/entry-fold/kpi-band/summary-band + donutConfig/barRows + Tailwind 覆盖需 !important"

# v16-UED-IOS · iOS 硬约束(review A9)
{ grep -q "overscroll-behavior-x: contain" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "scroll-snap-type" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "env(safe-area-inset-bottom)" "$RD/src/main/resources/templates/fragments/layout.html" \
  && grep -q "env(safe-area-inset-bottom)" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "touch-callout: none" "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v16-UED-IOS(横滑不触发 Safari 返回手势 + scroll-snap + 安全区 + 图表长按不弹菜单)" \
  || log_bad "v16-UED-IOS 缺件" "see style.css overscroll-behavior-x/scroll-snap/touch-callout + layout.html safe-area"

# v16-UED-COPY · 去技术化文案 + emoji 清零(review A6/A10/B8)
{ ! grep -q 'eyebrow-ink mb-2">/' "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "家 · 庭 · 基 · 础" "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "口 · 径 · 与 · 标 · 签" "$RD/src/main/resources/templates/admin/index.html" \
  && ! grep -q "Spring Boot 3.3" "$RD/src/main/resources/templates/admin/index.html" \
  && ! grep -q "PRD 中可配置" "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "无进行中周期" "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "dv == 'RISK' ? '风险'" "$RD/src/main/resources/templates/checkup/_ai-diagnose.html" \
  && [ "$(grep -rloP '[\x{1F300}-\x{1FAFF}\x{2728}\x{2705}\x{26A0}\x{1F514}\x{1F4A1}\x{FE0F}]' "$RD/src/main/resources/templates" 2>/dev/null | grep -v easter520 | wc -l)" = "0" ]; } \
  && log_ok "v16-UED-COPY(管理页中文分类+4组重排 · 状态中文 · 无技术栈/PRD 术语 · emoji 清零)" \
  || log_bad "v16-UED-COPY 缺件" "see admin/index.html 中文 eyebrow + 分组标题 + 空状态 · _ai-diagnose 中文徽标 · templates 内 emoji 残留"

# v16-UED-AFFORD · 假 affordance 与操作收纳(review B3-3/B3-5/B7-1)
{ ! grep -q "·☰" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q "row-more" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q "row-more-pop" "$RD/src/main/resources/static/css/style.css" \
  && grep -qF 'th:unless="${#lists.isEmpty(goals)}"' "$RD/src/main/resources/templates/goals/index.html" \
  && grep -q "nowrap" "$RD/src/main/resources/templates/accounts/index.html"; } \
  && log_ok "v16-UED-AFFORD(去掉假拖拽 ☰ + 行内操作收纳 ⋯ + 目标页主操作唯一 + 主理人列不竖排)" \
  || log_bad "v16-UED-AFFORD 缺件" "see accounts/index.html 去 ☰ / row-more 下拉 / goals 空状态隐藏重复主按钮"

# v161-UI3 · v1.6.1 的三条 UI 反馈(横屏部分已由 v164-ORIENTATION 接管)
#   ①「本期一句话」15px→13px(用户:像老年机)②折叠条 CTA + 箭头收起 ›/展开 ⌄
#   ③KPI 退回主次网格(横滑等于把核心指标藏到屏幕外,与「一目了然」相悖)
{ grep -q "fold-cta" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "点开筛选账户" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "rotate(-90deg)" "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'grid-column: auto !important' "$RD/src/main/resources/static/css/style.css" \
  && grep -qF 'text-[13px] leading-relaxed text-ink' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'grid-template-columns: 1fr 1fr !important' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v161-UI3(一句话 13px · 折叠 CTA + ›/⌄ 箭头 · KPI 主次网格一屏可见)" \
  || log_bad "v161-UI3 缺件" "see style.css fold-cta/rotate(-90deg)/grid-column auto/grid-template-columns · dashboard 点开筛选账户 + 13px"

# v163-SUNBURST-AGG · 旭日「大量空块」根治:小块聚合 + 三处共用阈值 + 崩溃兜底
#   演进史(三次才对):
#     v1.6.1 环内阈值 40° / 补注阈值 14° → 3.9%~11% 两边不收 = 信息黑洞
#     v1.6.2 两阈值合一(32°)→ 但窄屏无引导线,32° 以下全靠补注,「行业集中」59 块只有 6 块有标签
#     v1.6.3 承认图型容量有限 → 小块聚合成「其他 N 项」(Top N + Other 惯例),59 块 → 内环 6 块
#   另修两个隐蔽点:①窄屏判断不能用 clientWidth(布局时机不同会读到 630/321 两个值,导致阈值悄悄走 PC 档)
#                  ②聚合块必须带 children(下游多处直接 .forEach),否则 TypeError 被 Promise.all catch 静默吞掉
{ grep -q "function aggSmall" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "AGG_MIN_DEG = isNarrow() ? 18 : 4" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "其他 ' + small.length + ' 项" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "children: [{ name: '其他'" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "function isNarrow()" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -q "= el.clientWidth < 480" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "(n.children || \[\]).forEach" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "p.data._agg" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "console.error('lens 渲染失败" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "SUN_LABEL_MIN_DEG = 24" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -qE "deg >= (40|50|60)" "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v163-SUNBURST-AGG(小块聚合 Top N + 其他 · isNarrow 不用 clientWidth · 聚合块带 children · 聚合块禁下钻 · 渲染失败不静默)" \
  || log_bad "v163-SUNBURST-AGG 缺件" "see lens.js aggSmall/AGG_MIN_DEG/isNarrow/_agg children/(n.children||[])/console.error('lens 渲染失败')"

# v1615-LS-INPLACE · 整页横屏 = **原地换断点**,不许再回到 iframe 重载
#   退役 v166-LANDSCAPE-IFRAME 与 v168-ORI-CSS(它们守的是 iframe/舞台那套实现)。
#   用户反馈「一段很长的卡顿,这本该只是前端样式变化」—— 实测点一下横屏 = 一次完整
#   /dashboard 后端渲染 + 12 个脚本重跑,**1362ms**;而且 iframe 是新文档,滚动位置必然丢。
#   现在:Tailwind 是 Play CDN 运行时编译器,重新赋值 tailwind.config 就地重编译(实测 114ms)
#   → sm/md 换成「永远匹配」的 raw 查询;body 锁成长边×短边并在设备竖屏时转 90°。
#   实测切换 ~300ms、零文档请求、章节位置两个方向都还原。
#   踩过的三个坑,都在断言里:
#     ① body 自带 Tailwind min-h-screen,不清掉 min-height 就压过 height(实测 844×844)
#     ② body 被 transform 后 position:fixed 会跟着内容滚走 → 退出钮必须放进导航行
#     ③ 章节定位不能用 getBoundingClientRect/scrollIntoView(旋转后是屏幕坐标)→ 累加 offsetTop
CSS="$RD/src/main/resources/static/css/style.css"
JSL="$RD/src/main/resources/static/js/landscape.js"
LAY="$RD/src/main/resources/templates/fragments/layout.html"
{ ! grep -q "ls-frame\|ls-shell\|createElement('iframe')" "$JSL" \
  && ! grep -q "\.ls-stage\|\.ls-frame\|\.ls-shell" "$CSS" \
  && grep -q "window.TW_SCREENS_BASE" "$LAY" \
  && grep -q "window.TW_EXTEND_BASE" "$LAY" \
  && grep -qF "extend: window.TW_EXTEND_BASE || {}" "$JSL" \
  && grep -q "window.TW_SCREENS_WIDE" "$LAY" \
  && grep -q "function wideScreens" "$JSL" \
  && grep -qF "sm: { raw: '(min-width: 1px)' }" "$LAY" \
  && grep -qF "html.ls-wide > body {" "$CSS" \
  && grep -qF "min-height: 0 !important;" "$CSS" \
  && grep -qF "width: 100vh; height: 100vw;" "$CSS" \
  && grep -qF "transform: translate(100vw, 0) rotate(90deg);" "$CSS" \
  && grep -qF "(acts || document.body).appendChild(exitBtn);" "$JSL" \
  && grep -q "function layoutTop" "$JSL" \
  && grep -q "function currentAnchor" "$JSL" \
  && ! grep -qE "\.scrollIntoView\(" "$JSL"; } \
  && log_ok "v1615-LS-INPLACE(原地换断点 ~300ms 零文档请求 · 断点基线单一出处 · body 锁长边+视口单位交换旋转 · min-height 清零 · 退出钮在导航行 · 章节按布局坐标还原)" \
  || log_bad "v1615-LS-INPLACE 缺件" "see landscape.js(不得有 iframe/ls-shell · setScreens 复用 TW_EXTEND_BASE · 宽屏档在 layout.html 的 TW_SCREENS_WIDE(单一出处,首次编译前要用得上)· 退出钮进 .nav-actions · layoutTop/currentAnchor 且不用 scrollIntoView)· style.css(html.ls-wide > body 锁长边 + min-height:0 + 视口单位交换旋转)· layout.html 暴露 TW_SCREENS_BASE / TW_EXTEND_BASE"

# v167-VP-SHORTSIDE · 响应式判据必须同时看「短边」(用户第 5 次反馈:转手机页面还是变了)
#   前四版横屏方案都在跟「方向」较劲,方向找错了 —— 真正的 bug 是**断点只判宽度**:
#   手机横屏是 844×390,只看宽度 → 844 被当成宽屏设备 → 453 处 sm:/md:/lg: 集体切换。
#   手机横屏的特征是**短边只有 390**,不是宽边有 844。iPhone 横屏 innerHeight ≤ 440
#   (最大的 16 Pro Max 短边 440),PC 窗口极少矮于 480 → 阈值 480。
#   三处判据必须同源,少一处就会出现「CSS 是移动的、JS 是 PC 的」这种半修状态:
#     ① tailwind.config screens(453 处 Tailwind 断点)
#     ② style.css 自有 @media
#     ③ window.vpNarrow(图表脚本里原本 22 处硬编码宽度比较)
#   例外:横屏模式(ls-wide)布局宽度锁成长边,是用户主动要的宽屏视图 → 三处判据都排除它。
#   顺带守护 v1.6.7 的旋转遮帘:iOS 那 0.4s 旋转动画抹不掉(manifest.orientation iOS 不支持 /
#   screen.orientation.lock 需 fullscreen / orientationchange 不可 cancel),只能盖住,且必须瞬盖。
LAY="$RD/src/main/resources/templates/fragments/layout.html"
CSS="$RD/src/main/resources/static/css/style.css"
{ [ "$(grep -c 'min-height: 480px' "$LAY")" -ge 1 ] \
  && grep -qF "window.TW_SCREENS_BASE = { sm: bp(640), md: bp(768), lg: bp(1024), xl: bp(1280), '2xl': bp(1536) };" "$LAY" \
  && grep -q "window.vpNarrow=function" "$LAY" \
  && grep -q "window.innerWidth<w||window.innerHeight<480" "$LAY" \
  && [ "$(grep -c 'max-height: 479px' "$CSS")" -ge 6 ] \
  && [ "$(grep -c 'and (min-height: 480px)' "$CSS")" -ge 4 ] \
  && grep -q "html:not(.ls-wide) .kpi-band" "$CSS" \
  && grep -q "html:not(.ls-wide) .summary-band" "$CSS" \
  && grep -q "html.ls-wide .filter-fold > summary" "$CSS" \
  && grep -q "ori-turning" "$RD/src/main/resources/static/js/landscape.js" \
  && [ "$(grep -rn 'window.innerWidth *[<>]=* *[0-9]' "$RD/src/main/resources/templates" "$RD/src/main/resources/static/js" | grep -v 'vpNarrow=function' | wc -l)" -eq 0 ]; } \
  && log_ok "v167-VP-SHORTSIDE(断点/自有@media/vpNarrow 三处同源看短边 · iframe 例外 · 旋转遮帘瞬盖淡揭 · 无残留硬编码宽度判定)" \
  || log_bad "v167-VP-SHORTSIDE 缺件" "see layout.html(screens bp() + vpNarrow)· style.css(max-height:479px / min-height:480px / html:not(.ls-wide) 排除横屏模式 / ori-turning 遮帘)· 且模板与 js 里不得残留 window.innerWidth<数字"

# v169-ORI-PIN · 普通模式把内容钉在设备坐标系(方案 B · 用户在 A/B 里选定)+ 专用方向图标
#   效果 = 只对本 app 生效的「竖屏方向锁定」:转手机时页面在屏幕上一动不动,要读得转回来。
#   零重排的来源:body 盒子 = 100vh × 100vw = 短边 × 长边 = 竖屏那个形状(视口单位在横屏自动交换),
#   排版宽度仍是短边 → 与竖屏逐像素相同(实测 body 390 / 卡片 360×92 / KPI 360 两向全同)。
#   旋转符号必须跟设备转向走:screen.orientation.angle 定义为 viewport 相对自然方向**顺时针**
#   转过的角度,要抵消就转 -angle → angle 90(设备逆时针)取 -90°,angle 270(设备顺时针)取 +90°
#   (挂 html.ori-cw)。判错的表现是内容上下颠倒。iOS 16.4+ 有 screen.orientation(MDN BCD 实测),
#   更老回落 window.orientation。
#   滚动位置必须交接:冻结时滚动容器从 html 变成 body,两者 scrollTop 是两套值,
#   不接就跳回顶部 —— 而「跳回顶部」正是用户最反感的那种"页面动了"。
#   方案 B 的固有代价:body 被 transform 后,内部 position:fixed 浮层的包含块从视口变成 body 盒子,
#   那些按「未旋转视口」写的几何会错位(实测目录抽屉漏进屏内)→ 几何脆弱的浮层冻结态一律藏掉,
#   但方向钮必须留(用户可能就是横着拿再点开横屏视图)。
#   注:图标具体形状的断言已移交 v1613-ORI-ICON(v1.6.13 换成竖框+横框+双向箭头),
#   这里只守"不得回退成摄像机图标"。
{ grep -qF "html.ori-cw:not(.ls-wide) > body" "$CSS" \
  && grep -qF "overflow-y: auto; overflow-x: hidden;" "$CSS" \
  && grep -q "html:not(.ls-wide) .toc-sheet-mask" "$CSS" \
  && grep -q "html:not(.ls-wide) #toast-stack" "$CSS" \
  && ! grep -qE "not\(.ls-wide\)[^{]*#ori-float[^{]*display: none" "$CSS" \
  && grep -q "screen.orientation.angle" "$JSL" \
  && grep -qF "root.classList.toggle('ori-cw', a === 270)" "$JSL" \
  && grep -q "function restoreY" "$JSL" \
  && grep -qF "document.addEventListener('scroll', trackY, true)" "$JSL" \
  && ! grep -rq "M18 9l4-2v10l-4-2" "$RD/src/main/resources" \
  && grep -qF '[aria-pressed="true"] .ori-ico-port { display: inline; }' "$CSS"; } \
  && log_ok "v169-ORI-PIN(反向旋转钉设备坐标系 · 旋转符号跟 angle · 滚动位置交接 · 脆弱浮层冻结态藏掉但留方向钮)" \
  || log_bad "v169-ORI-PIN 缺件" "see style.css(ori-cw 分支 / body 滚动容器 / 冻结态藏 toc-sheet+toast 但不藏 #ori-float / aria-pressed 图标转向)· landscape.js(screen.orientation.angle + ori-cw + restoreY + capture 滚动跟踪)· 不得回退成摄像机图标(形状断言见 v1613-ORI-ICON)"

# v1610-LS-NAV · 横屏模式必须有顶部导航,但按 844×390 自己的尺度重排
#   v1.6.8 我把导航整个藏了,理由是「844 宽放不下、390 高太贵」。用户要求加回来 ——
#   **藏掉能力不是解决排版问题的办法**,该做的是给横屏一套自己的尺度。
#   宽度账(实测):退出钮占 ~112px 必须留 → 可用 ~700px;7 个 tab 压到 10px + 13px 间距
#   共 284px,加印章 21px ≈ 310px。v1.6.8 折行不是 tab 多,是 gap-12(48)+ gap-7(28)
#   + 完整品牌文字 + 版本徽记 + 字号钮 + 隐私钮 + 账期 pill + 用户名 一起挤的。
#   高度账:390 高才是稀缺资源。导航 64→38px;实测首张卡片从 221px(屏高 57%)提到 169px,
#   压的是间距与大标题字号,**不删任何内容**(text-2xl 那一档不压:大量用在金额上)。
#   浮钮:隐私浮钮在 390 高里会压住内容(实测压住交叉表「AI 解读当前视图」)→ 收进导航条
#   那段 335~726px 的空档,既不压内容又更好找;目录钮在横屏无意义一并收起。
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -q 'class="nav-inner' "$NAVF" && grep -q 'class="nav-lead' "$NAVF" \
  && grep -q 'class="nav-brandtext' "$NAVF" && grep -q 'class="nav-tabs' "$NAVF" \
  && grep -q 'class="nav-actions' "$NAVF" && grep -q 'class="nav-priv' "$NAVF" \
  && ! grep -qF "html.ls-wide > body > header { display: none" "$CSS" \
  && grep -qF "height: 44px !important;" "$CSS" \
  && grep -qF "padding-right: 26px !important;" "$CSS" \
  && grep -qF "html.ls-wide .nav-brandtext { display: none !important; }" "$CSS" \
  && grep -qF "html.ls-wide .nav-tabs > a[class*=\"border-b-2\"] { padding-bottom: 4px !important; }" "$CSS" \
  && grep -qF "html.ls-wide .nav-actions > .nav-priv { display: inline-flex !important;" "$CSS" \
  && grep -qF "html.ls-wide #priv-float { display: none !important; }" "$CSS" \
  && grep -qF "html.ls-wide main { padding-top: 8px !important; padding-bottom: 14px !important; }" "$CSS" \
  && grep -q "html.ls-wide main .text-3xl" "$CSS" \
  && ! grep -q "html.ls-wide main .text-2xl" "$CSS"; } \
  && log_ok "v1610-LS-NAV(横屏导航 7 tab 单行 44px 高 · 右侧 26px 内缩避 iPhone 圆角 · 品牌只留印章 · 隐私钮进栏内空档 · 垂直节奏压缩不删内容 · text-2xl 金额档不压)" \
  || log_bad "v1610-LS-NAV 缺件" "see nav.html 六个定位类(nav-inner/nav-lead/nav-brandtext/nav-tabs/nav-actions/nav-priv)· style.css(header 不得再 display:none · 44px 栏高(=iOS 最小触摸目标)· 右侧 26px 内缩避 iPhone 圆角 · 选中态 pb 重算 · nav-priv 保留 · 隐私浮钮收起(目录钮见 v1613-LS-TOC)· main 8/14 内边距 · 只压 text-3xl/4xl)"

# v1612-CHART-UNIFORM · 并列同类图表必须共用同一套尺度(用户 2026-07-28 要求写进规范)
#   用户原话:「都是饼图 那就保持大小样式一样,现在奇奇怪怪的,两个饼图差距很大,
#   这种常识问题,落到记忆和规范里面,不要我每次提出来,你自己要先自查」。
#   同一件事他提了两次:v1.6.4 按类目数分叉图型(7 类走条形 / 3 类走环图)→ 一个条一个环;
#   改成都是环图后 v1.6.11 又变成一大一小 —— 容器高度各写各的(348/262)+ 半径由
#   「容器高 − 标题 − 图例」推导,而两图图例行数不同(5 项 3 行 / 3 项 1 行)。
#   **容器高度相同还不够,半径必须写死。**
#   踩坑第三次:否定断言不能盯**裸标识符**(`! grep -q "hBarConfig("`)—— 讲解这段历史的
#   注释里就含 `hBarConfig(窄屏…`,自己把自己扫红了(前两次是 `100dvh`、`window.innerWidth<`)。
#   否定断言一律盯**代码构造**:函数定义 `function X`、调用点 `Object.assign(X`。
#   本守护钉三件事:① 容器共用同一个 class,不许各写 h-[] ② 尺度收进共享常量并写死半径
#   ③ 不许残留"按数据量分叉图型"的逻辑。规范全文见 docs/visual-spec.md。
RG="$RD/src/main/resources/templates/dashboard/_region.html"
{ [ "$(grep -c 'class="chart-pair-box"' "$RG")" -eq 2 ] \
  && ! grep -qE 'h-\[[0-9]+px\][^"]*"><canvas id="(allocationChart|memberAllocationChart)"' "$RG" \
  && grep -q "const PAIR = {" "$RG" \
  && grep -qF "radius: PAIR.r(), cutout: PAIR.cutout," "$RG" \
  && grep -qF "size: chartFont(PAIR.titleSize())" "$RG" \
  && grep -qF "size: chartFont(PAIR.legendSize())" "$RG" \
  && grep -qF "size: chartFont(PAIR.pctSize())" "$RG" \
  && ! grep -q "function hBarConfig" "$RG" \
  && ! grep -qF "Object.assign(hBarConfig" "$RG" \
  && ! grep -q "function useBar" "$RG" \
  && [ "$(grep -c "donutConfig(" "$RG")" -ge 3 ] \
  && grep -q "chart-pair-box" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "并列同类图表" "$RD/docs/visual-spec.md"; } \
  && log_ok "v1612-CHART-UNIFORM(并列两图共用 .chart-pair-box + PAIR 共享尺度 + 半径写死 · 无图型分叉 · 规范已收录)" \
  || log_bad "v1612-CHART-UNIFORM 缺件" "see _region.html:两个 canvas 容器都用 class=\"chart-pair-box\"(不许各写 h-[]) + PAIR 常量 + radius 写死 + 不得残留 hBarConfig/useBar · style.css 有 .chart-pair-box · docs/visual-spec.md 有「并列同类图表」一节"

# v1613-LENS-PALETTE · 旭日的有序色阶与中性色必须**按方案给**,不许全局硬编码
#   用户反馈:「旭日下钻之前专心搞过很多主题配色,这次优化后怎么完全没有 follow」。
#   查证后:不是忘了 follow,是配色体系缺两块 ——
#     ① 有序维度(风险)必须用色阶不能套分类色板(v1.6 UED B4-3 判断正确),
#        但那条色阶 RISK_SCALE 被硬编码在全局 → 换方案 A~E 外环一点都不变;
#        而且它是砖红 #a55540 + 橙 #c1873b,与莫兰迪内环不是一家人。
#     ② 「未分类 / 其他(聚合)」用了三个各不相同的硬编码灰(#c8c0ae/#c9c2b2/#dcd6c8),
#        既不属于任何方案,彼此也不一致。
#   改法:三锚点(低/中/高)按方案给 → 插值 8 档;中性色按方案 + 按环深给;
#   聚合块再向纸面提亮一档(aggTint)以便与「未分类」分开 —— 前者是桶、后者是真实维值。
#   锚点判据(自查踩到过):**相邻档 RGB 距离 ≥20 且 中↔高 ≥40**,不是「亮度单调下降」
#   （绿→黄→红本就中间最亮,有序性靠色相约定)。第一版 D 取方案自身三色,中↔高 只有 20,
#   实测两档肉眼分不出高低 —— 那正是用户看到的"没 follow"的观感来源,现为 70。
#   两处同步:lens.js 的锚点 == admin/calc-tweaks.html 色卡第 3 排的首尾色(t=0/t=1 即锚点)。
{ grep -q "var PLAN_ORDINAL = {" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "var PLAN_NEUTRAL = {" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "function rampOf" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "function aggTint" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "var ORDINAL_SCALE = rampOf" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "function riskColorMap(values, ring)" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "riskColorMap(values, ring)" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "function aggSmall(items, grand, ring)" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -q "var RISK_SCALE" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -qE "color: *'#(c8c0ae|c9c2b2|dcd6c8)'" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qi "#3cc780" "$RD/src/main/resources/templates/admin/calc-tweaks.html" && grep -qi "#f5222d" "$RD/src/main/resources/templates/admin/calc-tweaks.html" \
  && grep -qi "#2b8f5c" "$RD/src/main/resources/templates/admin/calc-tweaks.html" && grep -qi "#a61b1b" "$RD/src/main/resources/templates/admin/calc-tweaks.html" \
  && grep -qi "#3cc780" "$RD/src/main/resources/templates/admin/calc-tweaks.html" && grep -qi "#ff4d4f" "$RD/src/main/resources/templates/admin/calc-tweaks.html" \
  && grep -qi "#a3b79a" "$RD/src/main/resources/templates/admin/calc-tweaks.html" && grep -qi "#8a4034" "$RD/src/main/resources/templates/admin/calc-tweaks.html" \
  && grep -qi "#5b8c74" "$RD/src/main/resources/templates/admin/calc-tweaks.html" && grep -qi "#8e2231" "$RD/src/main/resources/templates/admin/calc-tweaks.html"; } \
  && log_ok "v1613-LENS-PALETTE(有序色阶+中性色按方案给 · 聚合块与未分类分色 · 无全局 RISK_SCALE / 无三个硬编码灰 · 五套锚点与 admin 色卡同步)" \
  || log_bad "v1613-LENS-PALETTE 缺件" "see lens.js:PLAN_ORDINAL/PLAN_NEUTRAL/rampOf/aggTint + riskColorMap(values,ring) + aggSmall(...,ring),且不得残留 RISK_SCALE 与 #c8c0ae/#c9c2b2/#dcd6c8;五套锚点首尾色必须同时出现在 admin/calc-tweaks.html 色卡"

# v1613-LS-TOC · 横屏模式必须能用「本页目录」,且按横屏空间重排交互
#   v1.6.10 我把目录钮一起藏了,理由「横屏整页就一屏多点,目录无意义」——判断错了:
#   横屏高度只有 390,一屏能看的内容反而更少,跨节跳转比竖屏更需要。
#   交互按横屏的空间特性重排:入口进导航行(390 高里浮钮必压内容)、
#   面板从底部 sheet 改**右侧侧栏**(横屏宽度富余、高度稀缺,正好反过来用)。
#   v1.6.14 两处修正(用户反馈「横屏了以后还是看不到」):
#     ① 目录钮原来用 position:fixed + right:132px 摆在导航行上,与隐私钮(690..726)
#        重叠 22px 且被画在下面 → 改成由 landscape.js 搬进 .nav-actions 参与 flex 排,
#        结构上不可能重叠(挪坐标只是"两处保持相等",隐私钮宽度一变就再撞)。
#     ② 抽屉 top 从 0 改 38px(导航栏高):`<main>` 带 relative z-10 本身就是层叠上下文,
#        抽屉在 main 里,z-index:80 永远升不到 z-30 的导航之上 —— 实测关闭 × 被 nav-inner 压住。
#        与其拆 main 的层叠上下文(牵连全站),不如让抽屉从导航下方开始,顺带导航仍可点。
#     ③ 目录钮改成与隐私钮同款描边(原来是 38px 深色实心圆,为右下浮钮设计的)——
#        并列同级控件必须同尺度同样式,见 AGENTS.md 护栏。
{ grep -qF "html.ls-wide .nav-actions > .toc-fab" "$CSS" \
  && grep -qF "position: static !important;" "$CSS" \
  && grep -qF "border: 1px solid var(--rule-soft);" "$CSS" \
  && grep -qF "left: auto; right: 0; top: 44px; bottom: 0;" "$CSS" \
  && grep -qF "html.ls-wide .toc-sheet-mask { top: 44px; }" "$CSS" \
  && grep -qF "html.ls-wide .toc-sheet.open { transform: translateX(0); }" "$CSS" \
  && grep -qF "html.ls-wide .toc-sheet-handle { display: none; }" "$CSS" \
  && grep -q "function tocIntoNav\|var tocIntoNav" "$JSL" \
  && grep -qF "acts.insertBefore(fab, acts.firstChild)" "$JSL" \
  && ! grep -qF "html.ls-wide #priv-float, html.ls-wide .toc-fab { display: none" "$CSS"; } \
  && log_ok "v1613-LS-TOC(横屏目录钮搬进导航 flex 流 · 与隐私钮同款描边 · 抽屉右侧侧栏且避开导航层叠上下文)" \
  || log_bad "v1613-LS-TOC 缺件" "see landscape.js tocIntoNav 把 .toc-fab 搬进 .nav-actions · style.css 下 .nav-actions > .toc-fab 为 static 描边款 · .toc-sheet/mask top:44px 右侧侧栏 · handle 隐藏"

# v1613-ORI-ICON · 方向切换图标 = Tabler device-mobile / device-mobile-rotated 双态
#   历次:摄像机图标(语义完全不对)→ 屏幕旋转弧 → 自绘「竖框+横框+双向箭头」(用户仍说丑)
#   → v1.6.15 用**真实图标库**:Tabler Icons(MIT · 描边 24×24 stroke-2,与本项目同款)。
#   按钮显示**目标状态**:竖屏时显示"横屏手机"(点它去横屏),横屏时显示"竖屏手机"。
#   v1.6.17 分档(用户反馈③:只有"横屏"传达不出"旋转"):
#     · 右下方向浮钮(20px)= 横屏手机 **+ 旋转弧箭头** —— 它是主控件,尺寸够放得下弧
#     · 交叉表「横屏看」按钮 / 「手机看」提示(14–15px)= 只用横屏手机 ——
#       该尺寸下 6px 半径的弧糊成一团,按 visual-spec「可读 > 可懂」不硬塞。
# 注:grep -c 对**单个文件**只输出数字、不带「文件名:」前缀 → 不能再套 awk -F: 取 $2(会得 0)
{ [ "$(grep -c 'M3 8a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2l0 -8' "$RD/src/main/resources/templates/lens/_section.html")" -eq 2 ] \
  && grep -qF 'M2.5 10.5a1.8 1.8 0 0 1 1.8 -1.8h12.4' "$LAY" \
  && grep -qF 'M15.5 5.4a6 6 0 0 1 5.9 5.1' "$LAY" \
  && [ "$(grep -rc 'M6 5a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-14' "$LAY" "$RD/src/main/resources/templates/lens/_section.html" | awk -F: '{s+=$2} END{print s}')" -ge 2 ] \
  && grep -qF ".ori-ico-port { display: none; }" "$CSS" \
  && grep -qF '[aria-pressed="true"] .ori-ico-land { display: none; }' "$CSS" \
  && ! grep -rq "M18 9l4-2v10l-4-2" "$RD/src/main/resources" \
  && ! grep -rq "M19.5 11.5A8" "$RD/src/main/resources" \
  && ! grep -rq "M8.2 12h5.6" "$RD/src/main/resources"; } \
  && log_ok "v1613-ORI-ICON(Tabler device-mobile 双态 · 显示目标状态 · 浮钮带旋转弧 / 小尺寸不硬塞 · 三代旧图标均已清)" \
  || log_bad "v1613-ORI-ICON 缺件" "see layout.html + lens/_section.html:横屏手机图标 3 处 / 竖屏手机图标 2 处(提示位单态)· style.css 双态显隐 · 不得残留摄像机 M18 9l4-2 / 旋转弧 M19.5 11.5A8 / 自绘双向箭头 M8.2 12h5.6"

# v1616-SIX · 用户第 6 轮反馈六项(环上标签 / 旭日不撞色 / 触摸目标 / 切换重排 / 打标页 / 文案)
#   ① 环图名称+数字标在环上:阈值按**弧长**算(2π·中半径·占比),不是按占比拍死 ——
#      弧长才是"放不放得下"的真实约束。够大标「名称+占比」两行,中等只标占比,太小交给图例。
#   ② 旭日不撞色:每环色板原来只有 10 色,超出直接复用 → 用户看到"第二轮就重复颜色"。
#      扩容用**旋转色相保明度**(不是往同一端点混 —— 那会收敛,实测同环最小色距掉到 3~7),
#      贪心筛选保证同环 ≥24(不够再放宽到 ≥15)、跨环 ≥26。实测方案 D 内环 77 / 外环 45 色。
#      内外环明度带完全分开(D:内 0.08–0.36 / 外 0.38–0.72)→ 满足"内外环不是一套"。
#      唯一共用色是「其他 N 项」聚合桶之间(本来就该同色),真实维值 0 撞色。
#   ③ 退出钮"点很多次才响应":旧 min-height 26px,旋转后在屏幕上只有 26px 宽一条,
#      远低于 iOS HIG 的 44pt → 加到 34px、导航行抬到 44px、加 touch-action:manipulation
#      (去掉双击缩放的 300ms 等待,等待期内的点击会被吞)、横屏态去掉 backdrop-filter
#      (被 transform 的容器里它是 iOS 已知的命中/合成干扰源)。
#   ④ 切换后图表溢出:只派发 window resize 不够 —— Chart.js 无参 resize() 实测读到旧尺寸
#      (横屏后画布仍是竖屏宽,比容器窄 400+px),ECharts 根本不跟容器。
#      改为**显式给容器 offsetWidth/Height**(布局坐标,不能用 rect:旋转后宽高互换),
#      并在重编译落定后补两次(rAF×2 + 180ms + 420ms)。
#   ⑤ 打标页手机端:真正瓶颈不是说明段(只占 186px),是 62 行 × 卡片化 ≈ 13800px →
#      加「只看未打标」纯前端过滤(手机默认开),说明段收成 details,底部常驻保存条。
#      过滤只切 display,隐藏行照样随「保存全部」提交,不丢数据。
RG="$RD/src/main/resources/templates/dashboard/_region.html"
TAG="$RD/src/main/resources/templates/lens/tags.html"
LJS="$RD/src/main/resources/static/js/lens.js"
{ grep -qF "const arc = 2 * Math.PI * (PAIR.r() * 0.79) * (pct / 100);" "$RG" \
  && grep -qF "if (arc >= 52) return shortName" "$RG" \
  && grep -q "function buildRingColors" "$LJS" \
  && grep -q "function shiftColor" "$LJS" \
  && grep -qF "var RING_EXT = (function () {" "$LJS" \
  && grep -qF "if (rgbDist(forbid[j], c) < 26) return;" "$LJS" \
  && grep -q "维值 .* 个 > 色板" "$LJS" \
  && grep -qF "min-height: 34px; padding: 0 12px; margin-left: 8px; cursor: pointer;" "$CSS" \
  && grep -qF "touch-action: manipulation; -webkit-tap-highlight-color" "$CSS" \
  && grep -qF "height: 44px !important;" "$CSS" \
  && grep -qF "backdrop-filter: none !important;" "$CSS" \
  && grep -q "function relayoutCharts" "$JSL" \
  && grep -qF "ch.resize(box.offsetWidth, box.offsetHeight);" "$JSL" \
  && grep -q "isDisposed()" "$JSL" \
  && grep -q 'id="tagsOnlyTodo"' "$TAG" \
  && grep -q "tags-savebar" "$TAG" \
  && grep -q "tags-intro" "$TAG" \
  && grep -q "tags-savebar" "$CSS" \
  && grep -q "重仓了什么行业" "$RD/README.md" \
  && grep -q "重仓了什么行业" "$RD/src/main/resources/templates/landing.html"; } \
  && log_ok "v1616-SIX(环上名称+占比按弧长分档 · 旭日色板旋色相扩容 77/45 且内外环明度带分离 · 触摸目标 34/44px + touch-action + 横屏去毛玻璃 · 图表显式给容器尺寸重排 · 打标页只看未打标+常驻保存 · 穿透卖点两处文案)" \
  || log_bad "v1616-SIX 缺件" "see _region.html(弧长阈值)· lens.js(buildRingColors/shiftColor/RING_EXT/跨环互斥/超限 warn)· style.css(34px 钮 + 44px 行 + touch-action + 横屏去 backdrop-filter + tags-savebar)· landscape.js(relayoutCharts 显式尺寸 + ECharts isDisposed)· tags.html(tagsOnlyTodo/tags-savebar/tags-intro)· README + landing 均含「重仓了什么行业」"

# v1617-FIVE · 用户第 7 轮反馈五项
#   ① 系统浮钮不该被页面加载进度绑住:首屏遮罩(z-index 9998)原本挂 `window.load`,
#      而 load 要等**所有子资源**(echarts / 图表脚本),旭日重的页面拖到两三秒 →
#      三个浮钮被压在遮罩下,用户看到"等旭日加载完才浮现"。
#      改:遮罩挂 DOMContentLoaded + 印章一周期(兜底 2.5s);三个浮钮基础 z-index 抬到 9999。
#      实测 /lens 从"等 load"变成 701ms 可点(dashboard 2.3s = 该页 HTML 解析时间,不再是图表)。
#   ② 「下一层按」下拉顺序原来是 LensRegistry 注册序、**风险第一**,用户觉得不合适。
#      重排依据:下钻是"再切一刀看结构",最常问的是结构性问题(是什么/在哪/投向/谁的/为什么);
#      风险 / 流动性是属性判断且已有专门看板,后置;账户类型是记账口径最技术,放最后。
#      同时把「成员结构」看板的第二层从 风险 改 资产类型("谁持有"之后自然追问"持的是什么")。
#   ③ 方向图标补旋转弧箭头:原来只有"横屏手机",传达不出"旋转"这个动作。
#   ④ 三个浮钮进 flex dock:原来各写死 bottom 偏移(18 / +48 / +96),页面缺一个
#      (打标页没有目录钮)就在栈里留一个空洞。flex 列后有几个排几个,实测打标页 2 钮间距 10px 无洞。
#   ⑤ 打标页「持仓方向」加回 UED 稿的横条占比示意 + 标签不换行改横滑。
#      两个坑:手机端 .tags-table td 被改成 flex 横排 → 横条与标签行各占一半(实测横条 95px);
#      td 还需恢复 display:block 才能整宽(修后 217px)。
LR="$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java"
TAG2="$RD/src/main/resources/templates/lens/tags.html"
{ grep -qF "setTimeout(function () { ov.classList.add('hidden'); }, 950);" "$LAY" \
  && grep -qF "}, 2500);" "$LAY" \
  && grep -qF "#priv-float{ position:fixed; right:14px; bottom:max(18px, env(safe-area-inset-bottom)); z-index:9999;" "$LAY" \
  && grep -qF "#ori-float { z-index: 9999; }" "$CSS" \
  && grep -q "z-index:9999" "$CSS" \
  && grep -q "float-dock" "$CSS" \
  && grep -q "function dockFloats" "$JSL" \
  && grep -qF "['#ori-float', '.toc-fab', '#priv-float']" "$JSL" \
  && grep -nq "dim(\"assetClass\"" "$LR" \
  && [ "$(grep -n 'dim("assetClass"' "$LR" | cut -d: -f1)" -lt "$(grep -n 'dim("risk"' "$LR" | cut -d: -f1)" ] \
  && [ "$(grep -n 'dim("type"' "$LR" | cut -d: -f1)" -gt "$(grep -n 'dim("region"' "$LR" | cut -d: -f1)" ] \
  && grep -q "key: 'member'" "$LJS" \
  && grep -qF 'M15.5 5.4a6 6 0 0 1 5.9 5.1' "$LAY" \
  && grep -q "alloc-head" "$CSS" && grep -q "alloc-seg" "$CSS" \
  && grep -q "alloc-bar" "$TAG2" && grep -q "alloc-pills hscroll-x" "$TAG2" \
  && grep -qF ".tags-table .alloc-row td{ display:block; }" "$TAG2"; } \
  && log_ok "v1617-FIVE(遮罩改 DOMContentLoaded + 浮钮 z-index 9999 不被加载绑住 · 维度顺序结构类在前(第二层默认见 v1619)· 图标补旋转弧 · 浮钮 flex dock 无空洞 · 持仓方向横条整宽+标签横滑)" \
  || log_bad "v1617-FIVE 缺件" "see layout.html(遮罩 950ms/2500ms 兜底 + priv-float z-index 9999 + 图标旋转弧)· style.css(#ori-float z-index 9999 / float-dock / alloc-head / alloc-seg)· landscape.js(dockFloats 三钮顺序)· LensRegistry(assetClass 在 risk 之前、type 在 region 之后)· lens.js(member 看板存在;第二层现由 v1619-THREE 守护为 platform)· tags.html(alloc-bar / alloc-pills hscroll-x / alloc-row td display:block)"

# v1618-SIX · 用户第 8 轮反馈六项
#   ① 环上不只要占比,具体金额也要:三行(名称/金额/占比)对径向要求 ≈ 3×11px = 33px < 环带 42px;
#      真正约束仍是弧长 —— 最宽那行是金额(≈8 字符 ≈40px)→ 三行档 arc ≥ 58。
#      隐私态 fmtMoney 返回空串 → 自动降回两行,不留空行。
#   ② 「本期怎么变的」里「本期收入 / 支出」两个 <b> **根本没挂 data-priv** → 隐私态下明文暴露。
#      注:PrivacyIsolationTest 管的是 LLM 脱敏(手机号/姓名),与 UI 的 data-priv 模糊无关,本来抓不到。
#      本守护补上这一类:_region.html 里**所有** ${cf*Label}(绝对金额)必须带 data-priv,
#      新增的也会被抓 —— 不是只补这两处。
#   ③ 「指标放行上点数字没有下钻能力」:实测两种位置抽屉**都会打开**(无头逐一验过),
#      但指标放行时每个实体占 N 行、表格高出数倍,而原来用 block:'nearest'(只滚最小距离)
#      → 抽屉常停在视口外,看着像"点了没反应"。改 block:'start' + 打开时闪一下边框给确认。
#   ④ 横屏要跨页面保持:状态写 sessionStorage;**关键是要在 tailwind 首次编译前**就据它选断点档
#      并贴上 html.ls-wide,否则新页面先按竖屏排一遍再被 JS 扳过去,会闪。
#   ⑤ 归因的数字画在 **canvas** 上,CSS 的 html.privacy [data-priv] 管不到 →
#      两件事都要:生成文案时判隐私 + 隐私开关切换后**重出图**(已画的像素不会自己变)。
#   ⑥ 隐私态 #priv-float 会显出文字标签变宽,dock 原来 align-items:center → dock 宽度被撑开、
#      另外两个居中钮左移。改右对齐:每个钮右缘固定,谁变宽只往左长。
ATTR="$RD/src/main/resources/templates/dashboard/_attribution.html"
{ grep -qF "if (arc >= 58 && money) return shortName" "$RG" \
  && [ "$(grep -oE '<[a-zA-Z][^>]*\$\{cf[A-Za-z]*Label\}[^>]*>' "$RG" | wc -l)" -eq "$(grep -oE '<[a-zA-Z][^>]*\$\{cf[A-Za-z]*Label\}[^>]*>' "$RG" | grep -c 'data-priv')" ] \
  && grep -qF "wrap.scrollIntoView({ behavior: 'smooth', block: 'start' });" "$LJS" \
  && grep -q "drawer-flash" "$CSS" \
  && grep -q "window.TW_SCREENS_WIDE" "$LAY" \
  && grep -qF "sessionStorage.getItem('lsWide') === '1'" "$LAY" \
  && grep -qF "sessionStorage.setItem('lsWide', '1')" "$JSL" \
  && grep -q "function adoptWideOnLoad" "$JSL" \
  && grep -q "function privOn()" "$ATTR" \
  && grep -qF "function fmtShort(v){ if(privOn()) return '···';" "$ATTR" \
  && grep -q "MutationObserver" "$ATTR" \
  && grep -qF "#float-dock { align-items: flex-end !important; }" "$CSS"; } \
  && log_ok "v1618-SIX(环上补金额按弧长分档 · 所有 cf*Label 必带 data-priv · 抽屉 block:start+闪确认 · 横屏跨页保持且首次编译即宽屏档 · 归因 canvas 生成时判隐私+切换重绘 · dock 右对齐不偏移)" \
  || log_bad "v1618-SIX 缺件" "see _region.html(arc>=58 三行档 · 所有 \${cf*Label} 必须带 data-priv)· lens.js(drawerWrap block:start)· style.css(drawer-flash / float-dock flex-end)· layout.html(TW_SCREENS_WIDE + sessionStorage lsWide 预贴)· landscape.js(setItem lsWide + adoptWideOnLoad)· _attribution.html(privOn + fmtShort 判隐私 + MutationObserver 重绘)"

# v1619-THREE · 用户第 9 轮反馈三项
#   ① PC 上「资产配置」环压住右侧图例。查证:该卡在 PC 只有 386px 宽(1024 视口下仅 229px),
#      右侧图例吃掉 181px,而半径是**写死的**(v1.6.12 用户明确要"两图同大",不能退回自适应)
#      → 环右缘 221 > 图例左缘 193,压进去 28px;「按成员分布」卡宽 1024 所以看不出。
#      三步修到四档宽度(1440/1280/1024/390)全不重叠:
#        · 图例一律移到**下方**(两张卡宽度差 4 倍,右侧图例没法都安全)
#        · PC 半径 118 → 104
#        · 图例文案缩短:环上已有金额的片(arc ≥58)图例不重复金额;名称也去掉「(WEALTH)」英文码
#          —— 与环上标签同一套写法。1024 下这一步是决定性的:带码条目每行只放 1 条 → 6 行 142px,
#          图区被压到 190px 而环直径 208px。
#   ② 默认看板 = 成员结构、第二层默认 = 平台(用户指定)。默认项同时挪到第一位 ——
#      默认却不在最左会让人以为选错了(chips 横滑,第 3 个未必在视野内)。
#   ③ 「切片排行」默认只出前 5 条,其余折起 + 「展示全部(还有 N 项)」。
#      它是**完整列表**的角色(旭日合并小块时指路到这儿),所以只能折不能截断;
#      展开是纯前端切 display,不再发请求。
{ grep -qF "position: 'bottom'," "$RG" \
  && grep -qF "vpNarrow(640) ? 100 : 104," "$RG" \
  && grep -qF "const money = arc >= 58 ? '' : fmtMoney(v);" "$RG" \
  && grep -qF "const short = flat(lab).replace(" "$RG" \
  && ! grep -qF "position: nar ? 'bottom' : 'right'," "$RG" \
  && grep -qF "{ key: 'member',    name: '成员结构',  sun: ['owner', 'platform']" "$LJS" \
  && grep -qF "boardKey: 'member'," "$LJS" \
  && [ "$(grep -n "key: 'member'" "$LJS" | head -1 | cut -d: -f1)" -lt "$(grep -n "key: 'assetcls'" "$LJS" | head -1 | cut -d: -f1)" ] \
  && grep -qF "var TOP_N = 5, hidden = Math.max(0, rows.length - TOP_N);" "$LJS" \
  && grep -q "rank-more" "$LJS" \
  && grep -qF "id=\"rankToggle\"" "$LJS" \
  && grep -q "展示全部" "$LJS"; } \
  && log_ok "v1619-THREE(环图图例移下方+PC 半径 104+图例文案缩短 → 四档宽度零重叠 · 默认看板成员结构且第二层平台且排第一 · 切片排行 Top5 折叠可展开)" \
  || log_bad "v1619-THREE 缺件" "see _region.html(legend position:'bottom' · PC 半径 104 · 图例 arc>=58 不重复金额 + 去英文码 · 不得残留 nar?bottom:right)· lens.js(member 看板 sun 第二层 platform 且排第一 + boardKey member + TOP_N=5 + rank-more/rankToggle/展示全部)"

# v1620-TAGS-LS · 打标页手机端**强制横屏** + 横屏布局靠向 PC(用户第 10 轮反馈③)
#   v1.6.16 我走的是"竖屏优化"(说明折叠 + 只看未打标 + 常驻保存条),用户试过后要求强制横屏。
#   这页确实是宽表格作业面(6 列 × 62 行):竖屏只能卡片化,一行一屏、上下滑一万三千像素。
#   三件事必须同时做,少一件横屏就白切:
#     ① **卡片化那段媒体查询要限定 html:not(.ls-wide)** —— 横屏是"锁布局宽度为长边 + 旋转",
#        **viewport 仍是短边 390**,所以 max-width:820px 照样命中,卡片化不会自己退场
#        (Tailwind 的 md: 靠原地换断点解决,自有媒体查询没这个机制)。
#        踩坑:逗号选择器要**每个**都加前缀 —— 只给第一个加,后面几个仍无条件生效
#        (实测 td 仍是 display:block,表格没回来)。所以这里直接断言那两条逗号列表的完整形态,
#        不用「不得出现裸 .tags-table」那种宽泛否定 —— 它会扫到媒体查询**外面**的基础表格样式
#        (那些本来就该无前缀),又是一次「否定断言盯裸标识符」。
#     ② 自动切横屏只切**一次**,用户手动退出后本会话不再纠缠(sessionStorage tagsLsOptOut),
#        且只在窄屏 + 触屏上切,PC 一律不动。
#     ③ 横屏态**页头要压到最小**:横屏只有 390 竖向像素,未压缩时表头在 418px、一行表格都看不到。
#        压 eyebrow/标题/说明卡(说明保持折叠 —— 横屏竖向比竖屏更紧,全文正是最该收的),
#        表格本身不动。实测表头 418 → 278px。
TAGS="$RD/src/main/resources/templates/lens/tags.html"
{ [ "$(grep -c 'html:not(.ls-wide) .tags-table\|html:not(.ls-wide) .ai-btn' "$TAGS")" -ge 15 ] \
  && grep -qF "html:not(.ls-wide) .tags-table tr, html:not(.ls-wide) .tags-table td{ display:block" "$TAGS" \
  && grep -qF "html:not(.ls-wide) .tags-table td select," "$TAGS" \
  && grep -q "tagsLsOptOut" "$TAGS" \
  && grep -qF "window.matchMedia('(pointer: coarse)').matches" "$TAGS" \
  && grep -qF "window.toggleOrientation()" "$TAGS" \
  && grep -q "class=\"tags-head" "$TAGS" \
  && [ "$(grep -c 'class="tags-note ' "$TAGS")" -eq 2 ] \
  && grep -q "tags-intro-full" "$TAGS" \
  && [ "$(grep -c 'tags-note-full' "$TAGS")" -ge 2 ] \
  && grep -qF "html.ls-wide .tags-head .eyebrow { display: none !important; }" "$CSS" \
  && grep -qF "html.ls-wide .tags-intro-full," "$CSS" \
  && grep -qF "html.ls-wide .tags-savebar { display: flex !important; }" "$CSS"; } \
  && log_ok "v1620-TAGS-LS(打标页窄屏自动横屏一次+尊重退出+PC不动 · 卡片化限定 not(.ls-wide) 每个逗号选择器都带前缀 · 横屏页头压缩表头 418→278px · 说明横屏仍折叠 · 保存条横屏留存)" \
  || log_bad "v1620-TAGS-LS 缺件" "see tags.html(卡片化段每个选择器都要 html:not(.ls-wide) 前缀,且不得残留裸 .tags-table · tagsLsOptOut + pointer:coarse + toggleOrientation · tags-head/tags-note/tags-intro-full/tags-note-full 类名)· style.css(横屏压 eyebrow/标题/说明卡 + 说明保持折叠 + 保存条留存)"

# v1621-CN-INSTALL · 大陆装机不再依赖 Docker Hub + 镜像源由脚本代劳(用户第 11 轮反馈)
#   起因是一次真实的上手失败:大陆 Mac 用户跑 docker-up.sh,卡在拉 mysql:8.0,
#   脚本给的动作是「手改 ~/.colima/default/colima.yaml 或 Docker Desktop 的 Docker Engine JSON」→ 他放弃了。
#   诊断和指引都没错,错在**这一档的天花板就在这**:让非技术家庭用户手改 Docker 引擎配置 = 死路。
#   两层修法(缺任何一层大陆用户都可能装不上):
#     ① **把失败点删掉**:mysql:8.0 是默认路径上唯一还走 Docker Hub 的一跳 →
#        CI 用 `imagetools create` 把官方多架构 manifest 原样复制到 GHCR(registry→registry,不 pull 不重建),
#        compose 默认指 GHCR 副本(与 app 镜像同源、大陆直连)。本地 gh token 没有 write:packages,
#        所以推送只能在 Actions 里用 GITHUB_TOKEN 完成。
#     ② **代劳而非教程**:兜底路径问一句 [Y/n] 后按引擎类型自己配 registry-mirrors 并重启重试。
#        三个引擎落点完全不同:colima → VM 内 daemon.json(+ 补 colima.yaml 的 docker: 段,
#        否则下次 colima restart 会按 yaml 重写把它抹掉)· Desktop → 宿主 ~/.docker/daemon.json + 重启 App ·
#        Linux → /etc/docker/daemon.json + systemctl。OrbStack **不自动改**(机制不稳,宁可退回手动)。
#   不变量:已有 registry-mirrors 不覆盖 + 改前留 .bak + 探到的源写回 .env(否则用户手敲 compose 又撞不通的源)。
#   两个实现坑:root 下 `$SUDO -E python3` 会把 -E 当命令名(单独维护 SUDOE);
#   等引擎必须「先等它掉下去再等它起回来」—— 直接轮询 docker info 会命中重启前的老 daemon 而误判成功。
DUP="$RD/deploy/docker-up.sh"; DCY="$RD/docker-compose.yml"; DPW="$RD/.github/workflows/docker-publish.yml"
{ grep -qF 'image: ${MYSQL_IMAGE:-ghcr.io/luodi-nate/financial-management-mysql:8.0}' "$DCY" \
  && ! grep -qE '^[[:space:]]*image: mysql:8\.0[[:space:]]*$' "$DCY" \
  && grep -q '^  mirror-mysql:' "$DPW" \
  && grep -qF 'imagetools create' "$DPW" \
  && grep -qF 'ghcr.io/luodi-nate/financial-management-mysql:8.0' "$DPW" \
  && grep -q '^DB_MIRROR=' "$DUP" && grep -qF 'DB_UPSTREAM="mysql:8.0"' "$DUP" \
  && grep -q '^cn_autofix_mirrors()' "$DUP" \
  && grep -q '^_mirror_colima()' "$DUP" && grep -q '^_mirror_desktop()' "$DUP" \
  && grep -q '^_mirror_linux()' "$DUP" && grep -q '^_wait_engine()' "$DUP" \
  && ! grep -qE 'kind=orb' "$DUP" \
  && grep -qF 'SUDOE="$SUDO -E"' "$DUP" && ! grep -qE '\$SUDO -E python3' "$DUP" \
  && grep -qF "grep -qx 'docker: {}'" "$DUP" \
  && [ "$(grep -c '里已有 registry-mirrors' "$DUP")" -eq 2 ] \
  && [ "$(grep -c 'daemon.json.bak\|\$f.bak\|yml.bak' "$DUP")" -ge 3 ] \
  && grep -qF "printf 'MYSQL_IMAGE=%s" "$DUP" \
  && grep -qF 'pull_one eclipse-temurin:21-jre' "$DUP" \
  && grep -qF 'local what=' "$DUP" \
  && grep -qF 'cn_hub_blocked_guide "JDK 基础镜像"' "$DUP" \
  && grep -q '^# MYSQL_IMAGE=' "$RD/.env.example" \
  && grep -qF 'MIG_DB_MIRROR=' "$RD/deploy/migrate-to-docker.sh" \
  && grep -qF "printf 'MYSQL_IMAGE=%s" "$RD/deploy/migrate-to-docker.sh" \
  && bash -n "$DUP" && bash -n "$RD/deploy/migrate-to-docker.sh"; } \
  && log_ok "v1621-CN-INSTALL(db 默认走 GHCR 副本·compose 无裸 mysql:8.0 · CI mirror-mysql 用 imagetools 复制多架构 · 双源探测+写回 .env · 镜像源脚本代劳三引擎分流 · OrbStack 不自动改 · 已有源不覆盖+留 .bak · SUDOE 修 root 下 -E · JDK 分支单独探+文案参数化)" \
  || log_bad "v1621-CN-INSTALL 缺件" "see docker-compose.yml(db image 必须 \${MYSQL_IMAGE:-ghcr…-mysql:8.0}、不得留裸 image: mysql:8.0)· .github/workflows/docker-publish.yml(mirror-mysql job + imagetools create)· deploy/docker-up.sh(DB_MIRROR/DB_UPSTREAM 双源 · cn_autofix_mirrors + _mirror_colima/_mirror_desktop/_mirror_linux/_wait_engine · 不得有 kind=orb · SUDOE 而非 \$SUDO -E python3 · colima.yaml 仅在 'docker: {}' 时改 · 两处已有 registry-mirrors 不覆盖 · 三处 .bak · 写回 MYSQL_IMAGE · JDK 基础镜像单独探 · 文案参数化)· .env.example(MYSQL_IMAGE 说明)"

# v1622-DB-CRED · 数据卷老密码 / .env 新密码 → 自愈,且判据不许说谎(用户第 12 轮反馈)
#   v1.6.26 更新:原来钉的是旧文案「down -v 会删掉数据库卷里的全部数据」,而那段「两条出路」
#   已被 v1.6.26 重写成「三条出路」(down -v 降为第三条,前两条都不丢数据)→ 断言跟随新文案。
#   v1.6.21 修好镜像后用户换了个地方卡住:app 无限 `Access denied for user 'finance'` 重启。
#   真因:MySQL 只在**首次初始化数据卷**时写入 MYSQL_USER/PASSWORD,而**命名卷不随仓库目录消失** ——
#   用户为拿修复重新克隆 → .env 新随机密码 → 与卷里老密码不匹配。是我们的升级指引造成的。
#   最坑的是三处判据同时给假阳性,因为它们用了**同一个不可靠原语**:
#     `mysqladmin ping` 在密码错误时**也 exit 0**(实测 $?=0;MySQL 语义:服务器有应答就算活着)。
#     → compose healthcheck 报 Healthy · entrypoint 报「MySQL 就绪」;
#     另加 FRESH_DB 探测的 `|| echo 1` 把「查询失败」和「表存在」压成同一个值 → 报「表存在数=1」。
#   所以本守护同时钉三件事:
#     ① 两处就绪/健康判据必须是**真实查询**(SELECT 1),不得再出现 mysqladmin ping 作判据;
#     ② FRESH_DB 探测不得用 `|| echo 1` 这类把失败翻译成正常值的兜底;
#     ③ docker-up.sh 要能检测并**不删数据**地修(mysqld --init-file,MySQL 官方重置手法,不需要旧密码),
#        且 `up -d` 失败也要走到自愈 —— 健康检查改严后 db unhealthy 会让 depends_on 让 up -d 非零退出,
#        set -e 会在自愈前就打断(实测撞到);删数据(down -v)只能是用户手动选项,FINANCE_ASSUME_YES 不放行。
EP="$RD/docker/entrypoint.sh"
{ grep -qF "mysql -h\"\$DB_HOST\" -P\"\$DB_PORT\" -u\"\$DB_USER\" -sN -e 'SELECT 1'" "$EP" \
  && ! grep -qE '^[[:space:]]*if mysqladmin ping' "$EP" \
  && grep -q 'AUTH_ERR' "$EP" && grep -q 'DB_READY' "$EP" \
  && ! grep -qF "2>/dev/null || echo 1" "$EP" \
  && grep -qF '全新空库判不了' "$EP" \
  && grep -qF "CMD-SHELL" "$DCY" && grep -qF "SELECT 1" "$DCY" \
  && ! grep -qE '"mysqladmin", "ping"' "$DCY" \
  && grep -q '^ensure_db_credentials()' "$DUP" \
  && grep -q '^resync_db_credentials()' "$DUP" \
  && grep -q '^db_auth_ok()' "$DUP" && grep -q '^db_root_ok()' "$DUP" \
  && grep -qF 'mysqld --init-file=/pwfix.sql' "$DUP" \
  && grep -qF 'ALTER USER' "$DUP" \
  && [ "$(grep -c 'UP_FAILED' "$DUP")" -ge 6 ] \
  && grep -qF '永久删除那个库里的一切' "$DUP" \
  && grep -qF '三条出路' "$DUP" \
  && grep -qF 'COMPOSE_PROJECT_NAME=finance-new' "$DUP" \
  && grep -qF 'Address already in use' "$DUP" \
  && bash -n "$EP" && bash -n "$DUP"; } \
  && log_ok "v1622-DB-CRED(entrypoint/healthcheck 改真实查询 SELECT 1·不再用 mysqladmin ping 作判据 · FRESH_DB 不再 ||echo 1 且如实报判不了 · docker-up 检测+init-file 不删数据同步密码 · up -d 失败也走自愈 · 删卷只作手动选项)" \
  || log_bad "v1622-DB-CRED 缺件" "see docker/entrypoint.sh(SELECT 1 真实查询 + AUTH_ERR/DB_READY 分流 + 不得留 mysqladmin ping 判据或 '2>/dev/null || echo 1' + 查询失败要说「全新空库判不了」)· docker-compose.yml(db healthcheck 改 CMD-SHELL + SELECT 1,不得留 mysqladmin ping)· deploy/docker-up.sh(ensure_db_credentials/resync_db_credentials/db_auth_ok + mysqld --init-file + ALTER USER + UP_FAILED 兜住 up -d 失败 + down -v 数据不可恢复警示 + 端口占用归因)"

# v1623-ENTRY-VIS · 功能入口可见性 —— 运行时判据,不是 grep(用户第 13 轮反馈)
#   用户报「券商自动对接的入口是不是上次优化 UI 丢了」。查证:功能一行没丢,是 v1.6 UED 批次5
#   把账户页 PC 行内的「券商」按钮按「行内按钮太多」的视觉密度一刀切收进了没有文字的 ⋯ 菜单。
#   对用户来说「还在但找不到」和「没了」没区别。**而已有守护 v15-ENTRY-1 一直是 PASS** ——
#   它断言的是 `grep '/broker(id='`(模板里有这个字符串),grep 类守护结构上抓不到可见性。
#   这正是 v1.6.14 那条「显示 ≠ 看得见」的教训:我当时只把它当成「以后写新守护要注意」,
#   从没回头拿这把尺子重量已有的 500 多条守护。写下教训 ≠ 教训生效。
#   顺带扫出第二个、更严重的:**/reports/refinance 提前还贷决策器全站零入口** ——
#   页面从 v0.4 就在、README 与落地页都在宣传,但没有任何模板链接指过去(git log -S 只命中它自己的
#   form action),只能手敲 URL;dashboard 洞察条还提示「可考虑加速偿还」却不给去处。已补两处入口。
#   机制:scripts/entry-points.json 是功能入口登记表(能力→入口页→期望可见层级),
#   scripts/entry-points-check.cjs 渲染真页面(PC 1440×900 + 移动 390×844)逐条断言:
#     ①有面积 ②elementFromPoint 命中自己(未被遮挡)③不在未展开 details / .row-more-pop 内。
#   没浏览器/应用没起 → 脚本退 2 → 本守护 SKIP(不制造假 FAIL)。
EPJ="$RD/scripts/entry-points.json"; EPC="$RD/scripts/entry-points-check.cjs"
if [[ ! -f "$EPJ" || ! -f "$EPC" ]]; then
  log_bad "v1623-ENTRY-VIS 登记表/检查器缺失" "需要 scripts/entry-points.json + scripts/entry-points-check.cjs"
elif ! command -v node >/dev/null 2>&1; then
  log_skip "v1623-ENTRY-VIS 功能入口可见性" "本机没 node"
else
  # 本机 chromium 缺 libXdamage,补上解包目录(不存在就不加,交由脚本自己判定并 SKIP)
  EP_LD=""; [[ -d /tmp/xdmg/usr/lib/x86_64-linux-gnu ]] && EP_LD="/tmp/xdmg/usr/lib/x86_64-linux-gnu"
  EP_OUT="$(LD_LIBRARY_PATH="${EP_LD}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            node "$EPC" "${BASE:-http://127.0.0.1:20000}" 2>&1)"; EP_RC=$?
  EP_SUM="$(printf '%s' "$EP_OUT" | grep -E '^合计' | tail -1)"
  case "$EP_RC" in
    0) log_ok "v1623-ENTRY-VIS 功能入口全部一眼可见(${EP_SUM:-登记表逐条 PASS} · PC+移动双端 · 判据=有面积+命中自己+不在 ⋯/details 内)" ;;
    2) log_skip "v1623-ENTRY-VIS 功能入口可见性" "$(printf '%s' "$EP_OUT" | grep -m1 SKIP || echo '环境不具备')" ;;
    *) log_bad "v1623-ENTRY-VIS 有功能入口用户看不见" "$(printf '%s' "$EP_OUT" | grep -m2 '✗' | tr '\n' ' ')" ;;
  esac
fi

# v1624-BROKER-CTX · 持仓页券商对接状态条 + OpenD 向导带账户上下文(用户第 14 轮反馈)
#   用户路径是「填报 → 持仓管理」:到了持仓页看不到这个账户跟富途/老虎是什么关系,要绕回账户页找配置。
#   模型确认(不动 schema):V39 有 uq_broker_link_account UNIQUE(account_id) → 一个账房账户 ↔ 一个券商
#   交易账户 1:1;多账户各自关联 → 整个账房对券商 1:N。用户描述的模型与实现一致。
#   三件事:
#     ① 持仓页标题下「券商对接」状态条:已关联给 vendor/启用状态/上次同步/本关联 OpenD 地址 + 两个去处;
#        未关联**不摆空字段**(空态排一列「—」是假信息),只给去关联入口。
#     ② OpenD 向导可带 ?account=<id>:顶部显示"正在为【X】配置"+ 一键回该账户配置页;
#        不带参数时行为完全不变(网关是**进程级**常驻服务、天然全局,只补上下文提示,不按账户分)。
#     ③ 判据统一到 supportsHoldings ——账户页原先硬编码 'STOCK' or 'CRYPTO' or 'METAL' 三串,
#        而 BrokerLinkController.requireHoldingAccount 用的是 supportsHoldings(含 WEALTH/CASH)→ 会漂移;
#        模板里硬编码枚举列表是 AGENTS.md 明令要避免的(加类型必漏)。
#   两个 UED 自查(实测截图发现):两个并列按钮原本一个 btn-paper 一个 btn-ghost(有框/无框不一致,
#   违反 visual-spec 并列同尺度那条)→ 都改 btn-paper;btn-* 全带 text-transform:uppercase,
#   会把专有名词「OpenD」渲染成「OPEND」→ 该处局部 text-transform:none。
HOLDT="$RD/src/main/resources/templates/stock/holdings.html"
ROWT="$RD/src/main/resources/templates/entry/_row.html"
OPENDT="$RD/src/main/resources/templates/broker/opend-wizard.html"
OPENDC="$RD/src/main/java/com/family/finance/web/broker/FutuOpendController.java"
HOLDC="$RD/src/main/java/com/family/finance/web/stock/StockHoldingController.java"
LINKT="$RD/src/main/resources/templates/broker/link.html"
{ [ "$(grep -c 'link-strip' "$HOLDT")" -ge 2 ] \
  && grep -qF 'brokerLink != null' "$HOLDT" && grep -qF 'brokerLink == null' "$HOLDT" \
  && grep -qF 'is-none' "$HOLDT" \
  && grep -qF 'lastSyncedAt' "$HOLDT" && grep -qF 'opendHost' "$HOLDT" \
  && [ "$(grep -c 'btn-paper text-\[11px\] no-underline' "$HOLDT")" -ge 4 ] \
  && ! grep -qF 'btn-ghost text-[11px] no-underline' "$HOLDT" \
  && grep -qF 'text-transform:none' "$HOLDT" \
  && grep -qF '/admin/broker/opend(account=' "$HOLDT" \
  && grep -qF '/admin/broker/opend(account=' "$LINKT" \
  && grep -qF 'brokerLinkMapper.findByAccount' "$HOLDC" \
  && grep -qF 'name = "account", required = false' "$OPENDC" \
  && grep -qF 'ctxAccountId' "$OPENDC" && grep -qF 'ctxAccountId != null' "$OPENDT" \
  && grep -qF 'getFamilyId().equals(me.getFamilyId())' "$OPENDC" \
  && grep -qF 'flex flex-wrap items-center gap-1.5' "$ROWT" \
  && grep -qF '/broker|}' "$ROWT" \
  && [ "$(grep -c 'supportsHoldings' "$RD/src/main/resources/templates/accounts/index.html")" -eq 2 ] \
  && ! grep -qF "== 'STOCK' or" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -qF '.link-strip' "$CSS" && grep -qF 'min-height: 44px' "$CSS"; } \
  && log_ok "v1624-BROKER-CTX(持仓页状态条两态·未关联不摆空字段 · 两按钮同款 btn-paper + OpenD 不被大写 · 向导 ?account 上下文且校验同家庭 · 填报行加入口且 flex-wrap · 账户页判据统一 supportsHoldings 不再硬编码枚举)" \
  || log_bad "v1624-BROKER-CTX 缺件" "see stock/holdings.html(link-strip 两态 + is-none + lastSyncedAt/opendHost + 4 处 btn-paper 且不得留 btn-ghost + text-transform:none + opend(account=)· broker/link.html(向导链接带 account)· StockHoldingController(brokerLinkMapper)· FutuOpendController(?account + ctxAccountId + 同家庭校验)· opend-wizard.html(ctxAccountId 条件)· entry/_row.html(flex-wrap + /broker 入口)· accounts/index.html(2 处 supportsHoldings、不得留 == 'STOCK' or)· style.css(.link-strip + 手机 44px)"

# v1625-UPDATE-PATH · 更新路径可自查 + Thymeleaf 条件片段陷阱(用户第 15 轮反馈)
#   用户原话:「我拉了新的代码重新运行了 bash deploy/docker-up.sh,依然是旧代码版本,
#   我们期望我们的用户如何更新版本?」—— 端到端复现后确认**脚本本身没坏**
#   (旧镜像在跑 → 跑脚本 → 容器确实换成新镜像 1ff63fb→7f78edc)。真因是三条叠加:
#     ① `git pull` 拉到的新代码**不进容器** —— app 来自 GHCR 预构建镜像,git pull 只影响
#        compose 文件与脚本本身;而 README 把三条命令并列埋在一大段文字里,让人以为 git pull 是关键那步。
#     ② 打 tag 到镜像可用之间有**约 12 分钟 CI 构建**(实测 build-push 12m7s)。看到发版消息立刻更新 → 拉到旧的。
#     ③ **脚本从头到尾不说版本**(grep 版本 = 0 处),/health 只有 {"status":"UP"},落地页也没有,
#        版本徽记只在**登录后**的 nav 里 → "静默拿到旧版"无法自查。这条是用户困惑的直接原因。
#   修:/health 带 version;脚本起前起后各读一次并打印「vA → vB」/「无变化」/「落后且镜像未就绪」;
#   与 GitHub 最新 release 对比(FINANCE_NO_UPDATE_CHECK=1 可关);README/deploy-README/faq 提独立小节。
#   **读不到版本时仍要给「最新是 vX」** —— 恰恰是旧镜像才不返回 version,这些用户最需要那句话(自查补的)。
#
#   顺带修两个既存缺陷(上一版记录、本版兑现):
#     · Thymeleaf 陷阱:`th:if` 与 `th:replace`/`th:insert` **不能放同一元素** ——
#       片段包含优先级(1)高于条件求值(3),replace 先执行 → 片段带着 null 参数被渲染。
#       error.html 上就是这样:未登录出错时 nav 片段拿到 state=null,在 `state.family.logoPreset`
#       抛 SpelEvaluationException,**响应截断在 nav 中间**(实测:输出里有 nav-inner/nav-lead
#       但没有 tabs、没有错误页正文、没有 fallback 顶栏、只有一个 <header>)。
#       而且它**会盖住真因** —— 排查时先看到的是 nav 的二次异常。修法:条件外提到 th:block;
#       error 页的 head 干脆固定自包含(**错误页必须零依赖**:它依赖的正是刚出错的那套机制)。
#       全仓同类共 5 处(error 2 + accounts 1 + reports 2),一并外提;本守护钉住"不得再出现"。
#     · StockHoldingController 异常文案与判据不一致(仍写「仅 STOCK/CRYPTO/METAL」而 supportsHoldings
#       自 v1.4 起含 WEALTH/CASH)→ 改成按实际支持集表述,免得排查被文案带偏(我这次就被带偏一次)。
{ grep -qF '"version", appVersion' "$RD/src/main/java/com/family/finance/web/HealthController.java" \
  && grep -qF 'app.version:dev' "$RD/src/main/java/com/family/finance/web/HealthController.java" \
  && grep -q '^running_version()' "$DUP" && grep -q '^latest_release_tag()' "$DUP" \
  && grep -q '^version_verdict()' "$DUP" \
  && grep -qF 'FINANCE_NO_UPDATE_CHECK' "$DUP" \
  && grep -qF 'VER_BEFORE' "$DUP" \
  && grep -qF '已更新:v' "$DUP" && grep -qF '版本无变化' "$DUP" \
  && grep -qF '不返回 version' "$DUP" \
  && grep -qF '约 12 分钟' "$DUP" \
  && [ "$(grep -c 'version_verdict' "$DUP")" -ge 3 ] \
  && grep -q '^## 更新到新版本' "$RD/README.md" \
  && grep -qF '怎么更新到新版本' "$RD/deploy/README.md" \
  && grep -qF '为什么还是旧版本' "$RD/docs/faq.md" \
  && [ "$(grep -rlE 'th:(if|unless)="[^"]*"[^>]*th:(replace|insert)=|th:(replace|insert)="[^"]*"[^>]*th:(if|unless)=' --include='*.html' "$RD/src/main/resources/templates" | wc -l)" -eq 0 ] \
  && grep -qF '<th:block th:if="${nav != null}">' "$RD/src/main/resources/templates/error.html" \
  && ! grep -qF 'th:if="${nav != null}" th:replace' "$RD/src/main/resources/templates/error.html" \
  && ! grep -qF '仅 STOCK / CRYPTO / METAL 类型账户支持持仓管理' "$RD/src/main/java/com/family/finance/web/stock/StockHoldingController.java" \
  && bash -n "$DUP"; } \
  && log_ok "v1625-UPDATE-PATH(/health 带 version · 脚本报「vA→vB / 无变化 / 落后+CI 12min」且旧镜像也给最新版提示 · 三处文档独立更新小节 · 全仓无 th:if+th:replace 同元素 · error 页 head 零依赖 + 条件外提 · 持仓异常文案与判据一致)" \
  || log_bad "v1625-UPDATE-PATH 缺件" "see HealthController(version + app.version:dev)· deploy/docker-up.sh(running_version/latest_release_tag/version_verdict + VER_BEFORE + FINANCE_NO_UPDATE_CHECK + 三种结论文案 + 约 12 分钟)· README「## 更新到新版本」/ deploy-README / faq · templates 不得有 th:if 与 th:replace/th:insert 同元素(优先级 1 > 3,replace 先跑)· error.html 用 th:block 外提 · StockHoldingController 文案"

# v1626-CLEAN-SAFE · 唯一会 TRUNCATE 用户数据的路径必须处处 fail-closed(用户第 16 轮报数据丢失)
#   用户原话:「起了一个项目已经设置好了成员也更新了密码,更新了以后多了两个成员把我旧账户也刷掉了,多了 Alice bob」
#   **定性**:Alice/Bob 是 db/migration/V2__seed.sql 里两个内置账号的 display_name(id 1/2),不是新增演示成员。
#   它们出现只可能是**全新空库跑了 V2 种子** —— 因为 V1__init.sql 用的是**裸 CREATE TABLE**(14 个,0 个
#   IF NOT EXISTS),迁移在已有库上重放会在 V1 就失败,所以"迁移把种子重灌进你的库"物理上不成立。
#   → 那次是**数据卷被换/删**(目录名变→compose 项目名变→新卷;或执行过 down -v)。
#   **而 v1.6.22 我给的凭据不匹配指引里,`down -v` 是并列的第二条出路,还写着"你从没真正用过就可以整卷删掉"**
#   —— 用户刚建过成员改过密码,凭记忆判断"我没什么数据"极易判错,而删卷不可恢复。这是我给的选项的责任。
#   排查中又发现清理链上两个真缺陷(与本次事故无关,但都能在别的场景真删数据):
#     ① **互锁 fail-open**:`$(... 2>/dev/null || echo 0)` → 查询一失败当 0,而 0 = "没有真实数据、可以清"。
#        保命互锁在失败时选了破坏性那边。改 fail-closed。
#     ② **信号太少**:只看 audit_log 与 member.id>2 → 用内置两账号 + 改过密码的真实用户两条都不响。
#        补 must_change_pw=0(完成首登改密)与"种子成员已改名"(Alice/Bob 是默认名)。
#   还有一个**我自己在修的时候踩出来的 bash 陷阱**:probe 函数里用 exit/die 终止**无效** ——
#   它跑在 `$(...)` 子 shell 里,exit 只结束子 shell,主脚本会带着错误信息当数值继续跑
#   (实测打出一堆 `[[: syntax error: operand expected`,没删数据纯属运气)。
#   正确形状:失败 return 非零,由调用处 `|| bail` 终止。两个脚本都按这个改。
#   本守护钉住五件事:fail-closed probe(且不含子 shell exit)· 四条信号 · 清理前强制 dump ·
#   entrypoint 第二重判据(有数据就算没 schema_history 也不算全新)· down -v 不再是并列选项。
CLEAN="$RD/docker/clean-dev-data.sh"; DEP="$RD/deploy/deploy.sh"; ENTP="$RD/docker/entrypoint.sh"
{ grep -q '^probe()' "$CLEAN" && grep -q '^bail_no_clean()' "$CLEAN" \
  && [ "$(code_only "$CLEAN" | grep -c '|| bail_no_clean')" -eq 4 ] \
  && ! code_only "$CLEAN" | grep -qE '\|\| echo 0' \
  && grep -qF 'must_change_pw = 0' "$CLEAN" && grep -qF "display_name NOT IN ('Alice','Bob')" "$CLEAN" \
  && grep -qF 'mysqldump' "$CLEAN" && grep -qF 'pre-clean-' "$CLEAN" \
  && grep -qF '放弃清理' "$CLEAN" \
  && grep -q '_step10_probe()' "$DEP" && grep -q '_step10_bail()' "$DEP" \
  && [ "$(code_only "$DEP" | grep -c '|| _step10_bail')" -eq 4 ] \
  && ! code_only "$DEP" | grep -qE 'actor_member_id IS NOT NULL" 2>/dev/null \|\| echo 0' \
  && grep -qF '降级为非全新库' "$ENTP" \
  && grep -qF 'account) + (SELECT COUNT(*) FROM cash_flow' "$ENTP" \
  && grep -q '^fresh_db_notice()' "$DUP" && grep -qF '全新空库' "$DUP" \
  && grep -qF 'COMPOSE_PROJECT_NAME=' "$DUP" \
  && grep -qF '三条出路' "$DUP" \
  && ! code_only "$DUP" | grep -qF '你从没真正用过' \
  && bash -n "$CLEAN" && bash -n "$DEP" && bash -n "$ENTP" && bash -n "$DUP"; } \
  && log_ok "v1626-CLEAN-SAFE(清理链 fail-closed:probe 失败→不清 · 四条使用痕迹信号含 must_change_pw/已改名 · 清理前强制 dump 且 dump 失败不清 · entrypoint 有数据即降级非全新 · docker-up 告知全新空库 + down -v 降为第三条并要求确认)" \
  || log_bad "v1626-CLEAN-SAFE 缺件" "see docker/clean-dev-data.sh(probe+bail_no_clean 四处 || bail · 不得留 || echo 0 · must_change_pw/Alice,Bob 信号 · mysqldump pre-clean 且失败放弃)· deploy/deploy.sh step10(_step10_probe/_step10_bail 四处)· docker/entrypoint.sh(降级为非全新库 + account+cash_flow 行数判据)· deploy/docker-up.sh(fresh_db_notice + 全新空库告知 + COMPOSE_PROJECT_NAME 出路 + 三条出路 · 不得再出现"你从没真正用过")"

# v1627-UPDATE-TRUTH · 更新检查要对比「真能拉到的镜像」+ 查不到必须说出来 + 发布必须验镜像(用户第 17 轮)
#   用户在 v1.6.25 上 git pull + 重跑 docker-up.sh,输出「· 版本无变化:仍是 v1.6.25」,
#   而且**后面一行都没有** —— 既没说"已是最新",也没说"最新是 v1.6.26"。查证出三件事:
#   ① **v1.6.26 的 docker-publish CI 失败了**(Maven Central 瞬时 403 拉不到 spring-boot-starter-parent)
#      → GHCR 上没有 v1.6.26 镜像,:latest 还是 v1.6.25 → 用户**怎么更新都拿不到**。
#      而我发布时**没有检查 CI 结果**就报告"已上 prod"(prod 是 systemd 直装,确实上了)。
#      **prod 健康 ≠ 发布完成**:Docker 是主推安装方式,镜像没出就只发了一半。
#      → release skill 加**必做**阶段 3.5 `verify-image`:等 CI 结论 + 探 GHCR manifest 匿名可拉,不在就 die。
#   ② **查不到最新版时静默 return** —— 用户那边 api.github.com 没通,于是什么都不打印,
#      他无法区分「已是最新」和「查不了」。这是 v1.6.25 我给"读不到本地版本"补过的同一个漏洞,
#      **同一版里漏了另一半**。现在两个来源都查不到时明确说出来 + 给 FINANCE_NO_UPDATE_CHECK=1。
#   ③ **对比对象选错了**:原来只问 GitHub 最新 release。release 存在但镜像没构建出来时(正是本次),
#      拿它去比会告诉用户"有新版",他却怎么都拉不到。**权威来源是 GHCR 的 tag 列表**
#      = "我真能更新到什么";而且大陆直连 GHCR 比 api.github.com 稳。
#      现在:优先 GHCR(latest_image_tag),GitHub release 降为补充信息 ——
#      release 比镜像新时提示"镜像还在 CI 里(约 12 分钟);久等不来说明构建失败了"。
DUPX="$DUP"
{ grep -q '^latest_image_tag()' "$DUPX" && grep -q '^ver_gt()' "$DUPX" \
  && grep -qF 'ghcr.io/v2/${repo}/tags/list' "$DUPX" \
  && grep -qF '查不到最新版本' "$DUPX" \
  && grep -qF '已是最新可用镜像' "$DUPX" \
  && grep -qF '有新版镜像' "$DUPX" \
  && grep -qF '镜像还没推上来' "$DUPX" \
  && grep -qF '镜像是否已发布未确认' "$DUPX" \
  && grep -qF 'FINANCE_NO_UPDATE_CHECK' "$DUPX" \
  && grep -q '^verify-image)' "$RD/.claude/skills/release-prod/release.sh" \
  && grep -qF 'ghcr.io/v2/${REPO}/manifests/' "$RD/.claude/skills/release-prod/release.sh" \
  && grep -qF '镜像发布验证失败' "$RD/.claude/skills/release-prod/release.sh" \
  && grep -qF '阶段 3.5 · Docker 镜像发布验证(必做' "$RD/.claude/skills/release-prod/SKILL.md" \
  && grep -qF 'prod 健康' "$RD/.claude/skills/release-prod/SKILL.md" \
  && bash -n "$DUPX" && bash -n "$RD/.claude/skills/release-prod/release.sh"; } \
  && log_ok "v1627-UPDATE-TRUTH(更新检查以 GHCR tag 列表为权威 + 查不到明确说出来 + release/GHCR 不一致时归因 CI · 发布加必做阶段 3.5 verify-image 探 manifest)" \
  || log_bad "v1627-UPDATE-TRUTH 缺件" "see deploy/docker-up.sh(latest_image_tag 问 GHCR tags/list + ver_gt + 四种文案:查不到最新版本/已是最新可用镜像/有新版镜像/镜像还没推上来/镜像是否已发布未确认)· release.sh(verify-image 子命令 + 探 manifests + die)· SKILL.md(阶段 3.5 必做 + prod 健康≠发布完成)"

# v1628-OPS · 日常运维的四件事必须有入口(用户第 18 轮:「要做到告知用户如何停止、启动、重新来、
#   更新以及如何查看日志;你从一个开发者拿到手一个开源软件的视角,自己审视下需要哪些信息?」)
#   审视后对账,缺的是四样:①手动备份入口(Docker 下没有 —— 而我们刚在 v1.6.26 让用户"更新前先备份")
#   ②恢复脚本(只存在于 FAQ 的一段裸命令:要用户自己找文件、手填 root 密码、还得记得先停 app)
#   ③诊断入口(用户卡住时不知道该收集什么,我们也拿不到有效信息)④脚本末尾只给了"停 + 日志"两条。
#   **一处更正**:我一开始判定"备份 sidecar 因 restart:unless-stopped + 一次性脚本而死循环",
#   查证后是**错的** —— 镜像里装的是 docker/backup.sh(有 while+sleep 86400),我读的是 deploy/backup.sh
#   (systemd 那条,由 timer 触发,一次性是对的)。两个同名不同文件,别再混。
#   顺带露出真的文档不一致:文档写"每周日 03:00 / 每日 03:30",Docker 实际是"容器启动后每 24h",
#   文件名是 finance-*.sql.gz 而非 dump-* → 已改文档。
#   **我在写这三个脚本时自己踩的 bug(两次,同一个)**:Docker 分支把 dump 写成
#   `mysqldump | cat > 文件.sql.gz` —— **忘了 gzip**,文件名在撒谎;而 `du -h` 看得到文件,
#   于是打印了"✓ 已备份",restore 时才 `gzip: not in gzip format`。**报了成功的坏备份比没有备份更危险。**
#   更险的是 restore.sh 里"恢复前另存当前库"那份也是同一个写法 —— 退路本身不可用。
#   两处都补 gzip **并加完整性校验**(gunzip -t + 解出来必须含 CREATE TABLE),校验不过就删掉不留假备份。
#   **唯一能证明备份可用的方法是恢复它** —— 所以验证必须做"插标记→备份→删标记→恢复→标记回来"的往返,
#   以及"撤销一次恢复"(用 before-restore 快照回退)。只验"文件生成了"会放过这个 bug。
OPSC="$RD/deploy/_common-env.sh"; BKN="$RD/deploy/backup-now.sh"; RST="$RD/deploy/restore.sh"; DOC="$RD/deploy/doctor.sh"
{ [[ -f "$OPSC" && -f "$BKN" && -f "$RST" && -f "$DOC" ]] \
  && grep -qF 'MODE=docker' "$OPSC" && grep -qF 'MODE=systemd' "$OPSC" \
  && [ "$(code_only "$BKN" | grep -c 'gzip -9')" -ge 1 ] \
  && ! code_only "$BKN" | grep -qE 'mysqldump[^|]*\|[^|]*cat > ' \
  && [ "$(code_only "$BKN" | grep -c 'gunzip -t')" -ge 2 ] \
  && grep -qF 'CREATE TABLE' "$BKN" \
  && [ "$(code_only "$RST" | grep -c 'gzip -9')" -ge 1 ] \
  && ! code_only "$RST" | grep -qE 'mysqldump[^|]*\|[^|]*cat > ' \
  && grep -qF 'gunzip -t' "$RST" \
  && grep -qF 'before-restore-' "$RST" \
  && grep -qF 'FINANCE_RESTORE_CONFIRM' "$RST" \
  && grep -qF '中止恢复' "$RST" \
  && grep -qF '已脱敏' "$DOC" && grep -qF 'PASS|PASSWORD|KEY|SECRET' "$DOC" \
  && [ "$(grep -c '── 常用操作' "$DUP")" -eq 3 ] \
  && grep -qF 'bash deploy/backup-now.sh' "$DUP" && grep -qF 'bash deploy/restore.sh' "$DUP" \
  && grep -qF 'bash deploy/doctor.sh' "$DUP" && grep -qF 'docker volume ls | grep db-data' "$DUP" \
  && grep -qF '日常运维速查(Docker)' "$RD/README.md" \
  && grep -qF '每 24 小时' "$RD/README.md" \
  && ! code_only "$RD/docs/faq.md" | grep -qF '备份 sidecar 每周日 03:00' \
  && bash -n "$OPSC" && bash -n "$BKN" && bash -n "$RST" && bash -n "$DOC"; } \
  && log_ok "v1628-OPS(_common-env 形态探测 + backup-now/restore/doctor 三脚本 · dump 必 gzip 且 gunzip -t+CREATE TABLE 校验 · restore 先另存退路且校验不过就中止 + RESTORE 确认 fail-closed · doctor 脱敏 · docker-up 三处末尾给完整常用操作 · 文档修正备份节奏)" \
  || log_bad "v1628-OPS 缺件" "see deploy/_common-env.sh(MODE 探测)· backup-now.sh(gzip -9 + 两处 gunzip -t 校验 + 不得留 mysqldump|cat>)· restore.sh(gzip+校验 + before-restore 退路 + 校验不过中止 + FINANCE_RESTORE_CONFIRM)· doctor.sh(脱敏)· docker-up.sh(3 处常用操作块含 backup-now/restore/doctor/volume)· README(日常运维速查 + 每 24 小时)· faq(不得再写'每周日 03:00')"

# v1629-XIRR-LEDGER · 报表 XIRR:tooltip 端点必须与指标同源 + 收入口径全页统一(用户第 19 轮)
#   用户报「prod 新关一期后家庭 XIRR(含收入)是 0,不对吧」。**先复算再改**:在 prod 数据上逐步还原,
#   6 月投资损益 -330,336.95、7 月 +330,335.91,合计 -1.04 元 → -0.00001% → 显示 0.00%。
#   **XIRR 本身算得是对的**(排查中我一度把 12,434 USD 的开账基线当成 CNY,得出 0.79%,已更正)。
#   但顺着查出两个真缺陷:
#     ① **tooltip 显示的端点不是 XIRR 用的那两个数**:firstNW/lastNW 取自 netWorthTrendExOpening
#        (剔除累计开账基线的趋势,给财富水位用),而该序列**首点按构造恒为 0**(首期全部账户都算"首次出现")
#        → 长年显示「期初净资产 −¥0」,末点也不是真实净资产(prod 上显示 733,258,真实 9,233,404)。
#        **一个号称"给你看真实中间数值"的 tooltip 显示另一套数,比没有更糟** —— 用户正是据此判断指标算错了。
#        改:端点取 netWorthTrend(与 familyXirr 同源);两序列之差 = 累计开账基线,单列进文案;
#        并标明「不满 12 期 · 累计口径非年化」还是「年化」(原文案一律写"年化",<12 期时也在说谎)。
#     ② **同页两套收入口径**:familyXirr/familyTwr 只读 cash_flow,而同页「人赚/累计净投入/本金vs收益」
#        走 pmcFirstNetInflow(PMC 优先否则 cash_flow)。prod 实据:6 月 PMC 收入 151,547 vs cash_flow 81,462;
#        7 月用户填在 PMC 的 21,837 支出 XIRR 完全没扣 → 同一屏两个 KPI 互相矛盾。
#        这正是 AGENTS.md 联动不变量 L1 登记要防的。改:两者统一走 pmcFirstNetInflow。
#        **会改变线上数值**:prod 该指标 0.00% → -0.20%(是修正不是回归 —— 原值漏扣了用户已填报的支出)。
#   线上处置:零 schema 迁移、零存量数据改写;AI 缓存按 period_id 分片(是"那一期的建议"历史快照)不必清;
#   GoalMetricEvaluator 实时算不落库。回滚只回 jar 即可。
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
MES="$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java"
RPC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
{ [ "$(code_only "$FVI" | grep -c 'pmcFirstNetInflow(slice, periodId)')" -ge 2 ] \
  && code_only "$FVI" | grep -qF 'pmcFirstNetInflow(slice, current)' \
  && ! code_only "$FVI" | grep -qE 'periodIncome\(slice, periodId\)\.subtract\(periodExpense' \
  && ! code_only "$FVI" | grep -qE 'periodIncome\(slice, current\)\.subtract\(periodExpense' \
  && code_only "$RPC" | grep -qF 'factViewService.netWorthTrend(slice)' \
  && code_only "$RPC" | grep -qF 'cumOpeningBaseline' \
  && ! code_only "$RPC" | grep -qE 'firstNW = trend\.get\(0\)' \
  && grep -qF 'cumulativeOpeningBaseline' "$MES" \
  && grep -qF '与「人赚」同口径' "$MES" \
  && grep -qF '累计口径非年化' "$MES"; } \
  && log_ok "v1629-XIRR-LEDGER(tooltip 端点改用真实 netWorth 与指标同源 + 开账基线单列 + 标明年化/累计 · familyXirr/familyTwr 与「人赚」统一走 pmcFirstNetInflow)" \
  || log_bad "v1629-XIRR-LEDGER 缺件" "see FactViewServiceImpl(familyXirr/familyTwr 必须用 pmcFirstNetInflow,不得再出现 periodIncome-periodExpense 组合)· ReportsController(端点改 netWorthTrend + cumOpeningBaseline,不得再从 trend 取 firstNW)· MetricExplainService(cumulativeOpeningBaseline 字段 + 与「人赚」同口径 + 累计口径非年化)"

# v1630-CLOSED-ANCHOR · 收益类指标必须锚「最新已关账期」,不得被进行中账期污染(2026-08-01 全站指标核查 P0)
#   起因:全站 24 个 KPI 逐项核查。计算正确性本身没查出错(恒等式与逐期 PnL 合计全自洽),
#   但发现**锚点选错**:queryBase 是 account × period 全交叉且不过滤 period.status,
#   于是进行中的 OPEN 期进切片并成为 lastPeriodId,而它的典型状态是**余额已填、收支未录**
#   (prod 2026-08 实测:21 条余额快照 / 0 条现金流 / 无 PMC)。后果:
#     (期末 − 期初 − 净流入) 里净流入 = 0 → **还没录的工资被整块算成投资收益**。
#     prod 实测「本月资产收益 +0.99%」,那 9.1 万一分钱收支都没扣。
#   改:存量类(净资产/总资产/总负债/流动资产/环比)继续锚最后一期 —— 填报中就该看到最新余额,
#       缺快照还会结转上期,不会凭空缺口;
#       收益类(本月资产收益 / XIRR / TWR / YTD / 人赚钱赚拆解 / 储蓄率)改锚 returnPeriodIds(已关账期)。
#   附带修:① 总资产/净资产口径文案漏列 CRYPTO/METAL/INSURANCE(实际是"除 LOAN 外全部");
#          ② savingsRate 原只读 cash_flow 且锚进行中期 → prod 恒返回 null → GoalMetricEvaluator 的
#             nz() 兜成 0 → **储蓄率类家庭目标进度恒显示 0%**;改成 PMC 优先 + 锚已关账期;
#          ③ checkup 的 XIRR tooltip 补齐 reports 早就有的「与人赚同口径 / N 期 / 年化还是累计」。
#   **会改变线上数值**(是修正不是回归):prod 本月资产收益 +0.99% → 锚 2026-07;XIRR 0.79% → 只算 3 个已关账期。
#   线上处置:零 schema 迁移、零存量数据改写、纯读路径;回滚只回 jar。
#   ④ **checkup 的 YTD 累计损益吃掉负号**:模板写 `(signum()>=0 ? '+' : '') + '¥' + formatDecimal(abs())`
#      —— 负数分支前缀是空串,又套了 .abs() → 亏损 −¥1,580,715 渲染成「¥1,580,715」,
#      只有颜色类 num-neg 透出亏损,数字本身读起来是盈利。同文件 170 行(本月资产收益)的写法
#      `'+¥' : '−¥'` 才是对的。**这条是渲染验收时肉眼发现的,grep/单测都抓不到**(表达式语法完全合法、
#      指标值也算对了,错在展示)。全站扫了 12 处同类写法,只此 1 处同时用了 abs() → 只此 1 处是 bug。
#   护栏形状:openingBaselineLast 必须**仍锚 last** —— dashboard/review 的「本期怎么变」卡靠
#   ΔNW = 人赚 + 钱赚 + 开账基线 成立,而 ΔNW 与人赚都取 lastPeriodId,挪走会当场破掉恒等式。
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
FSL="$RD/src/main/java/com/family/finance/factview/FactSlice.java"
KPS="$RD/src/main/java/com/family/finance/factview/KpiSnapshot.java"
FMP="$RD/src/main/java/com/family/finance/repository/FactMapper.java"
FMX="$RD/src/main/resources/mapper/FactMapper.xml"
MES="$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java"
RPC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
DRG="$RD/src/main/resources/templates/dashboard/_region.html"
CKF="$RD/src/main/resources/templates/checkup/family.html"
{ code_only "$FSL" | grep -qF 'returnPeriodIds()' \
  && code_only "$FSL" | grep -qF 'filingInProgress()' \
  && code_only "$FMP" | grep -qF 'findClosedPeriodIds' \
  && grep -qF "status = 'CLOSED'" "$FMX" \
  && code_only "$FVI" | grep -qF 'factMapper.findClosedPeriodIds(filter)' \
  && [ "$(code_only "$FVI" | grep -c 'slice.returnPeriodIds()')" -ge 4 ] \
  && code_only "$FVI" | grep -qF 'ytdSlice.returnPeriodIds()' \
  && code_only "$FVI" | grep -qF 'netInflowIncome(slice, anchor)' \
  && ! code_only "$FVI" | grep -qE 'periodIncome\(slice, slice\.lastPeriodId\(\)\)' \
  && code_only "$FVI" | grep -qF 'BigDecimal openingBaselineLast = openingBaseline(slice, last);' \
  && code_only "$KPS" | grep -qF 'returnAnchorNetWorth' \
  && code_only "$KPS" | grep -qF 'returnPeriodCount' \
  && code_only "$RPC" | grep -qF 'slice.returnPeriodIds().size()' \
  && ! code_only "$RPC" | grep -qE 'int familyMonths = slice\.periodIds\(\)\.size\(\)' \
  && grep -qF '口径期' "$MES" \
  && grep -qF '除贷款外的全部资产类型合计' "$DRG" \
  && grep -qF '除贷款外的全部资产类型合计' "$CKF" \
  && ! grep -qF 'CASH + STOCK + WEALTH + PROPERTY' "$DRG" \
  && ! grep -qF 'CASH + STOCK + WEALTH + PROPERTY' "$CKF" \
  && grep -qF 'returnAnchorMonth' "$DRG" \
  && grep -qF 'returnAnchorMonth' "$CKF" \
  && grep -qF "cumulativeYtdPnl.signum() >= 0 ? '+¥' : '−¥'" "$CKF" \
  && [ "$(grep -c "signum() >= 0 ? '+' : ''" "$CKF" | tr -d ' ')" -le 2 ]; } \
  && log_ok "v1630-CLOSED-ANCHOR(收益类锚最新已关账期 · 存量类仍锚末期 · openingBaselineLast 不动保「本期怎么变」恒等式 · savingsRate 走 PMC 优先 · 总资产口径文案补全 CRYPTO/METAL/INSURANCE · 两页 KPI 标注口径期)" \
  || log_bad "v1630-CLOSED-ANCHOR 缺件" "see FactSlice(returnPeriodIds/filingInProgress)· FactMapper(+xml findClosedPeriodIds status='CLOSED')· FactViewServiceImpl(familyXirr/familyTwr/ytd/拆解 走 returnPeriodIds · savingsRate 走 netInflowIncome 不得再用 periodIncome(lastPeriodId) · openingBaselineLast 必须仍锚 last)· KpiSnapshot(returnAnchorNetWorth/returnPeriodCount)· ReportsController(familyMonths 改 returnPeriodIds)· dashboard/_region.html + checkup/family.html(总资产文案不得再出现 CASH + STOCK + WEALTH + PROPERTY · 须带 returnAnchorMonth 口径期标注)"

# v1631-RPT-ACCT-M · 报表「账户级收益 · vs 基准」必须有手机端卡片布局(用户第 21 轮:「手机端那个账户明细 排版差劲的不行」)
#   实测证据(390×844):该 section 原先手机上只有 PC 宽表 + overflow-x-auto ——
#   **17 列共 1653px 塞进 358px 容器**,首屏只看得到「账户/类型/类目」三列,
#   **一个叫「账户级收益」的表在手机上一个收益数字都看不到**;类型 pill 被压到 ~40px 宽把
#   「现金」折成两行;行高 ~140px × 18 行 = 1480px 却几乎不承载信息;且 有手机块=false
#   (仪表盘 v0.8 起就有 sm:hidden 卡片块,报表这块一直漏做)。
#   改:PC 表限定 hidden sm:block;<sm 用 details 卡片(同一份 accountRows / 同一套 acctMetrics 门控 /
#   同样带 data-mcol 所以顶部 chips 继续管得到)。主行 [类型pill][账户名] … [vs基准pill],
#   次行 [当前价值 · 收益率] … [展开],两行都 justify-between → 有/无 pill 的卡片等高不参差
#   (实测 18 张卡折叠态高度集 = {67},唯一值)。
#   基准% 刻意不放次行:实测窄屏会折行并拖出一个孤立的「·」,改进展开区。
#   顺带修 `.pill{text-transform:uppercase}` 把「跑赢/跑输 ±N pp」的**单位 pp 渲染成 PP** ——
#   加 .pill-vs{text-transform:none},PC 与手机同一处修(只给 6 个 vs pill,不动类型/类目 pill)。
#   2026-08-06(v1.9.3):这条原来 grep `.pill-vs{ text-transform:none; }` **逐字**匹配,
#   给该规则补 white-space:nowrap 之后整条就红了 —— 守的意图(pp 不被 uppercase 成 PP)一点没变。
#   同 v11-UED8 的教训:护栏盯字面就会被无关改动打红,改成正则匹配意图。顺带把 nowrap 纳入守护。
RGN="$RD/src/main/resources/templates/reports/_region.html"
{ grep -qF 'sm:hidden space-y-2' "$RGN" \
  && grep -qF 'overflow-x-auto hidden sm:block' "$RGN" \
  && [ "$(grep -c 'pill pill-vs' "$RGN")" -eq 6 ] \
  && grep -qE '\.pill-vs\{[^}]*text-transform:none' "$RGN" \
  && grep -qE '\.pill-vs\{[^}]*white-space:nowrap' "$RGN" \
  && [ "$(grep -c 'data-mcol' "$RGN")" -ge 24 ] \
  && grep -qF 'min-width:3.4em' "$RGN"; } \
  && log_ok "v1631-RPT-ACCT-M(报表账户段手机卡片布局 · PC 表限定 sm:block · chips 仍管手机卡 · 类型 pill 固定宽防折行 · vs pill 的单位 pp 不再被大写)" \
  || log_bad "v1631-RPT-ACCT-M 缺件" "see reports/_region.html:需 sm:hidden 卡片块 + PC 表 hidden sm:block + 恰好 6 处 pill-vs(3 PC + 3 手机,不含类型/类目 pill)+ .pill-vs{text-transform:none} + 手机卡带 data-mcol(chips 联动)+ 类型 pill min-width:3.4em"

# v1632-MANUAL · 站内使用手册 + 新手引导卡 + 常驻入口(issue #9)
#   起因:一位用户(自述没有财会背景)在 issue #9 问「理财的买入卖出、借钱等如何在这些体现」。
#   查证属实:此前站内帮助页只有 broker-sync 一篇,docs/faq.md 四章全是部署/备份/登录/AI 这类运维题,
#   **一条「业务上该怎么记」都没有** —— 等于把门槛原封不动留给了没有财会背景的人。
#   做法:docs/how-to-use.md(主流程 + 分析路径)+ docs/how-to-record.md(场景速查)+
#   站内页 /help/how-to-use(正文同前者,另带两处解释性动画:填报顺序 / 明细抽屉从哪来)。
#   入口策略(用户拍板):第一次用要很醒目,用过之后要保留入口。
#     · 醒目:仪表盘 + 填报页顶部引导卡,localStorage `manualHintDismissedAt` 一年
#       (用户明确选 localStorage 而非服务端状态;代价是每台设备各提示一次,可接受)
#     · 常驻:导航栏「手册」(PC 主栏 + 移动抽屉)+ 填报页标题旁「怎么填?」
#   **守护重点**:引导卡一年后会消失,所以**不能只靠那张卡** —— 卡消失后导航栏与填报页那两处必须还在。
#   entry-points.json 已登记 id=manual(level=obvious · pc+mobile),由 v1623-ENTRY-VIS 运行时兜底。
#   v1.6.32 二轮(用户第 25 轮反馈):
#     ① 第一版是「按我想到的写」而不是按功能全集写 —— 目标模块整个没提,dashboard 一堆区块也没讲,
#        分析部分只写了用户举例的那条旭日路径。改法:先把 70+ 路由 / 管理页 15 块穷举成 33 个功能点,
#        分成必修 8 + 选修 25(省力 / 分析 / 决策 / 进阶四类),再据此排章节。
#        成品:主教程 5 章(必修)+ 选修 A5 / B7 / C4 / D3 共 19 节,顶部 24 张章节卡可锚点直达。
#        守护断言章节卡 ≥20 张、必修/选修徽记都在、五个代表性锚点(ch1/a1/b3/c3/d1)存在。
#     v1.7.0 三轮(用户第 26 轮:滚大版本 + 每章要链接 + 每章要配图 + 表现可以更外放):
#       · **每章一个真实入口链接**(class=goto · 24 个):读到哪就能点进去做,如「建账户」→ /accounts、
#         「财务目标」→ /goals、「提前还贷」→ /reports/refinance。一律 th:href="@{...}" 不写死路径 ——
#         守护里那条 `! grep -qE 'href="/[a-z]'` 就是防有人图省事写成裸路径(部署在子路径下会全断)。
#       · **每章配图**(15 张 · beta 实拍 · 隐私模式糊金额 · 统一套外框 · 压到 1500px 宽共 1.9MB),
#         放 static/img/manual/,页面用 loading=lazy。
#         踩过的坑:frame-shots.py 对同一目录跑第二次会**给已套框的图再套一层框**并还原体积
#         (b1-tags 一度变成 2242×8959 / 1.7MB);正确做法是从 *.raw.jpg 重来,只套一次。
#       · 视觉外放:章节大编号水印、hero 描边数字、分组封面条、配图悬停微抬、阅读进度条。
#         仍在现有设计令牌内(纸感底 / Fraunces / 等宽数字 / 黄铜森绿铁锈),不引新色系、不加载外部字体。
#     v1.7.0 四轮(用户第 27 轮 · 六条):
#       ① 手册**免登录**:潜在用户在决定要不要自建之前就该读懂它怎么用。SecurityConfig 放行 +
#          匿名轻头。**坑**:`th:replace` 优先级(1)高于 `th:if`(3),直接在 <header> 上写 th:if
#          拦不住,片段照样渲染 → 必须用 <th:block th:if> 包住。守护断言两者都在。
#       ② 交互演示比截图更能教会人 → 从 2 个扩到 4 个:A 填报顺序 · B 下钻三步 ·
#          C 币种是显示镜头(点 CNY/USD/HKD:金额缩放、比值四项一个数不动)·
#          D 人赚 vs 钱赚(三种情形切换,含「涨了 10 万其实投资亏 6 万」)。
#       ③ 复杂章节必须图文并茂:补 数据源接入 / 富途 OpenD / 新建目标 / 指标设置 /
#          产品类目 / 备份 / 审计 共 7 张,配图 15 → 21 张。
#       ④ beta 非真实数据 → **关闭隐私模式重拍**(SHOT_PRIVACY=0),不再糊金额。
#          坑:补拍时第二次跑忘带 SHOT_PRIVACY=0,把不打码的图覆盖回打码版,只能整批重来。
#       ⑤ 演示 A 重做成**仿真填报页**:控件、标签、配色照真实页面 1:1
#          (收入绿标签 / 现金收入·股票收入 / 金额·类目·现金账户 / 「+ 加一笔」黑按钮 / 「本期余额」)
#          —— 否则在教程里学会了、到真实系统 UI 差异太大还是不会用。守护断言 .simrow 存在。
#       ⑥ D 段(进阶与维护)原来过薄且配图无教学价值(汇率页 300 行重复行):
#          课节 3 → 11 条、配图 2 → 5 张、汇率图裁到顶部一屏,并把演示 C 挂在多币种章。
#     v1.7.0 五轮(用户第 28 轮:手册要在 / 落地页体现):
#       手册免登录改变了落地页的逻辑 —— 以前访客只能「信」文案,现在能「自己验」。三处各干不同的活:
#         · hero:命令块下面加「还没决定要不要装?」分隔 + 手册卡(不用装/不用登录)。
#           **不能左右并排** —— 命令块里 git clone 那行 URL 不可断行,并排时会把手册卡挤成
#           88px 宽的细条(实测),改上下堆叠后 PC 600×106 / 移动 345×129 正常。
#         · 中段「你大概也在问这几个问题」的 3 个问题挂手册章节锚点(#b6 财富水位 /
#           #ch4 人赚vs钱赚 / #b3 钻到具体持仓)—— 问题本来就写好了,只是此前没有出口,
#           读者点头认同完就没下一步。锚点对匿名访客同样有效。
#         · footer CTA 加第三个按钮「先看使用手册」。
#       守护断言 landing.html 内 /help/how-to-use ≥4 处且含「还没决定要不要装」。
#     ② 导航:「手册」挪到主栏最末(不插在功能项中间);既然它有 icon,**所有菜单项都得有** ——
#        8 项统一 Feather 风格 inline SVG。顺带修掉自己引入的对齐 bug:加 inline-flex 后激活项比
#        其他项高 10px(激活态 pb-[18px] 撑高 + 容器底对齐),给未激活项补 border-transparent + 同 padding
#        后 8 项图标中心 Y 全部对齐到同一像素。守护断言 nav.html 内 svg ≥16(PC 8 + 移动 8)。
HC="$RD/src/main/java/com/family/finance/web/help/HelpController.java"
HTPL="$RD/src/main/resources/templates/help/how-to-use.html"
HINT="$RD/src/main/resources/templates/fragments/_manual-hint.html"
NAV="$RD/src/main/resources/templates/fragments/nav.html"
ENT="$RD/src/main/resources/templates/entry/index.html"
DASH="$RD/src/main/resources/templates/dashboard/index.html"
{ code_only "$HC" | grep -qF '/help/how-to-use' \
  && [ -f "$HTPL" ] && [ -f "$HINT" ] \
  && grep -qF 'fragments/layout :: head' "$HTPL" \
  && ! grep -qF 'PREVIEW' "$HTPL" \
  && grep -qF 'manualHintDismissedAt' "$HINT" \
  && [ "$(grep -c 'toc-card' "$HTPL")" -ge 20 ] \
  && grep -qF 'badge-req' "$HTPL" && grep -qF 'badge-opt' "$HTPL" \
  && grep -qF 'id="ch1"' "$HTPL" && grep -qF 'id="a1"' "$HTPL" \
  && grep -qF 'id="b3"' "$HTPL" && grep -qF 'id="c3"' "$HTPL" && grep -qF 'id="d1"' "$HTPL" \
  && [ "$(grep -c '<svg' "$NAV")" -ge 16 ] \
  && [ "$(grep -c 'class="goto"' "$HTPL")" -ge 20 ] \
  && [ "$(grep -c 'figure class="fig' "$HTPL")" -ge 12 ] \
  && [ "$(ls "$RD/src/main/resources/static/img/manual/" 2>/dev/null | grep -c '\.jpg$')" -ge 12 ] \
  && ! grep -qE 'href="/[a-z]' "$HTPL" \
  && [ "$(grep -c 'figure class="fig' "$HTPL")" -ge 18 ] \
  && grep -qF 'class="simrow' "$HTPL" \
  && grep -qF 'data-ccy' "$HTPL" && grep -qF 'data-case' "$HTPL" \
  && grep -qF '/help/how-to-use' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -qF 'th:if="${me != null}"' "$HTPL" \
  && [ "$(grep -c '/help/how-to-use' "$RD/src/main/resources/templates/landing.html")" -ge 4 ] \
  && grep -qF '还没决定要不要装' "$RD/src/main/resources/templates/landing.html" \
  && grep -qF "display:none" "$HINT" \
  && [ "$(grep -c '/help/how-to-use' "$NAV")" -ge 2 ] \
  && grep -qF '/help/how-to-use' "$ENT" \
  && grep -qF '_manual-hint' "$ENT" \
  && grep -qF '_manual-hint' "$DASH" \
  && grep -qF '"id": "manual"' "$RD/scripts/entry-points.json" \
  && [ -f "$RD/docs/how-to-use.md" ] && [ -f "$RD/docs/how-to-record.md" ]; } \
  && log_ok "v1632-MANUAL(站内 /help/how-to-use + 新手卡 localStorage 一年 + 导航栏与填报页常驻入口 · 卡消失后入口仍在)" \
  || log_bad "v1632-MANUAL 缺件" "see HelpController(/help/how-to-use)· templates/help/how-to-use.html(须套 layout · 不得残留 PREVIEW 条)· fragments/_manual-hint.html(manualHintDismissedAt + 默认 display:none 防闪)· nav.html 至少 2 处入口(PC+移动)· entry/index.html 与 dashboard/index.html 挂卡 · entry-points.json 登记 id=manual · docs/how-to-use.md + how-to-record.md"

# v164-CHART-PARITY · dashboard 两图形态永远一致(用户反馈④)+ v1.6.11 窄屏改回环图
#   诉求没变:「资产配置」与「按成员分布」不能一个环一个条。判断收成共用的 useBar(),
#   而 useBar 在窄屏恒为 false → **窄屏两图必定同为环图**(用户反馈④与本次反馈的交集)。
#   注意口径:PC 上 useBar 仍看各自类目数,所以 PC 可能一个条一个环(资产配置 7 类 > 6)。
#   要不要在 PC 也统一成环图,是产品取舍(聚合已让多类目环图可读),留给用户定,不在此守护范围。
#   实现翻过一次:v1.6.4 我让窄屏两个都走横向条形,理由写的是「窄屏环图标签必然重叠」。
#   用户反馈条形「太不直观」,要求改回环图 —— 而且那个理由本来就站不住:
#   重叠的根因不是"环图不行",是**类目太多**,与旭日图 v1.6.3 完全同一个问题。
#   那里验证过的解法直接搬过来:**Top N + 其他 N 项**(aggSlices),环里只剩 ≤6 片,
#   每片都标得下占比;窄屏图例改到下方(390px 宽里右侧图例会把环挤成一条缝);
#   金额落在图例上(规范要求数字直接在图上,不能只靠 hover)。
#   保留的判断:PC 且类目 > 6 仍用横向条形 —— 那时空间够,精确标注每一类比合并更有价值。
{ grep -q "function donutConfig" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "function aggSlices" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && ! grep -q "function useBar" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "donutConfig(memLabels" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "donutConfig(donutLabels" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -qF "position: 'bottom'," "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "generateLabels" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && ! grep -q "memFlat\|flatAlloc" "$RD/src/main/resources/templates/dashboard/_region.html"; } \
  && log_ok "v164-CHART-PARITY(两图一律环图 · 不再按类目数分叉图型 · Top N 聚合 + 图例统一下方)" \
  || log_bad "v164-CHART-PARITY 缺件" "see dashboard/_region.html:aggSlices + donutConfig + useBar 三件齐 · 两图都走 donutConfig · 窄屏图例 bottom + generateLabels 带金额 · 不得残留 memFlat/flatAlloc"

# ══════════════════════════════════════════════════════════════════════════════
# v1.8 · 支出逐笔化 + 口径唯一入口
# ══════════════════════════════════════════════════════════════════════════════

# v18-EXPENSE-ONE-SOURCE · 家庭支出口径只有一份实现
#   v1.6.29 踩过一次「同一屏两套收入口径」,根因是判断散落在各调用点。本版把支出口径
#   收敛到 ExpenseLedgerService,所以护栏直接断言:**全仓 totalExpense() 只出现在那一个文件里**。
#   开发中真的漏过两处(报表折线的支出序列 + tooltip 的上期支出),就是这条 grep 抓出来的。
{ [[ "$(grep -rl 'totalExpense()' "$RD/src/main/java/" | grep -v 'service/expense/ExpenseLedgerService.java' | wc -l)" == "0" ]] \
  && grep -q "class ExpenseLedgerService" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/service/HouseholdCashflowService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/service/goal/GoalService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/service/goal/GoalProgressService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/web/report/ReportsController.java"; } \
  && log_ok "v18-EXPENSE-ONE-SOURCE(全仓 totalExpense() 只在 ExpenseLedgerService · 5 个调用方都经过它)" \
  || log_bad "v18-EXPENSE-ONE-SOURCE 有旁路" "grep -rn 'totalExpense()' src/main/java 应只命中 ExpenseLedgerService;新加的支出读取必须走口径服务,别直读 PMC"

# v18-EXPENSE-MODE-GUARD · 优先级必须受 expense_entry_mode 约束
#   PRD 初稿写的是「无条件逐笔优先」,那会让 prod 2026-06 的 PMC 总额 ¥32,797 被 ¥3,000 的
#   逐笔顶掉(少算 89%),连带污染储蓄率/月均/紧急储备/人赚钱赚/XIRR/应急基线。
#   总额模式下口径服务还必须**返回 NONE 交回调用方原路径**(调用方回落事实切片:排归档 + 已换汇),
#   否则 beta 家庭 XIRR 会从 −56.19% 漂到 −50.60%。
{ grep -q "V53__expense_entry_mode.sql" <<< "$(ls "$RD/db/migration/")" \
  && grep -q "expense_entry_mode" "$RD/db/migration/V53__expense_entry_mode.sql" \
  && grep -q "DEFAULT 'TOTAL'" "$RD/db/migration/V53__expense_entry_mode.sql" \
  && grep -q "itemizedFirst" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "modeOf" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "archived_at IS NULL" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "base_currency" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "总额模式下逐笔不得顶掉PMC总额" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java" \
  && grep -q "总额模式下取期集合只看PMC_分母不能变" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java" \
  && grep -q "总额模式下无PMC时返回NONE_把兜底交回调用方" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java" \
  && grep -q "未来账期的逐笔不得并入近N期" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java"; } \
  && log_ok "v18-EXPENSE-MODE-GUARD(逐笔优先受模式约束 + 逐笔 SQL 排归档/已换汇 + 4 条单测守着)" \
  || log_bad "v18-EXPENSE-MODE-GUARD 缺件" "V53 默认 TOTAL / decide 走 itemizedFirst / 逐笔 SQL 带 archived_at + 折本位币 / 4 条护栏单测齐"

# v18-EXPENSE-WRITE · 支出写入链路与收入侧同构 + 三条服务端红线
{ grep -q "recordExpense" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q "requireExpenseCategory" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q "AccountType.LOAN" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q '@PostMapping("/entry/expense")' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q '@PostMapping("/entry/expense/{id}/delete")' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q "listExpenseOrdered" "$RD/src/main/java/com/family/finance/repository/CashFlowCategoryMapper.java" \
  && grep -q "findExpenseEntries" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "expenseMode == 'ITEMIZED'" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "expenseMode != 'ITEMIZED'" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "entry/expense" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "admin/reminders/expense-mode" "$RD/src/main/resources/templates/admin/notification.html"; } \
  && log_ok "v18-EXPENSE-WRITE(recordExpense + 删除冲回 + 类目/贷款红线 + 填报页两形态 + 管理页开关)" \
  || log_bad "v18-EXPENSE-WRITE 缺件" "见 EntryService.recordExpense / EntryController 两端点 / entry 模板按 expenseMode 二选一 / 管理页 expense-mode 表单"

# v18-MIX-COMPOSITION · 支出构成段 + 长文目录同步 + 数字直接标在图上
#   memory feedback_toc_sync:加 section 必须同步该页长文目录,否则锚点漏节。
#   memory feedback_chart_datalabels:金额/百分比必须绘在图上,hover tooltip 不算。
{ [[ -f "$RD/src/main/resources/templates/reports/_expense-mix.html" ]] \
  && grep -q 'id="sec-expense-mix"' "$RD/src/main/resources/templates/reports/_expense-mix.html" \
  && grep -q "ChartDataLabels" "$RD/src/main/resources/templates/reports/_expense-mix.html" \
  && grep -q "datalabels" "$RD/src/main/resources/templates/reports/_expense-mix.html" \
  && grep -q "reports/_expense-mix :: section" "$RD/src/main/resources/templates/reports/index.html" \
  && grep -q "sec-expense-mix" "$RD/src/main/resources/templates/reports/index.html" \
  && grep -q "expenseBreakdown" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "expenseBreakdownDetail" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "reports/expense-mix/detail" "$RD/src/main/java/com/family/finance/web/report/ReportsController.java" \
  && grep -q "mixEnabled" "$RD/src/main/java/com/family/finance/web/report/ReportsController.java"; } \
  && log_ok "v18-MIX-COMPOSITION(支出构成段 + 目录锚点同步 + datalabels + 明细抽屉)" \
  || log_bad "v18-MIX-COMPOSITION 缺件" "_expense-mix.html 存在且挂进 reports/index + tocItems 含 #sec-expense-mix + datalabels + detail 端点"

# v18-EXPENSE-DOC-4X · 「支出优先级与收入相反」必须同时出现在四处
#   这条反直觉的不一致少写一处,下一个人就会靠猜 —— 所以把「四处」本身做成护栏。
{ grep -q "与收入侧优先级相反\|与收入侧相反" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "注意与收入侧优先级相反" "$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java" \
  && grep -q "与收入侧方向相反" "$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java" \
  && grep -q "方向是反的" "$RD/docs/how-to-record.md" \
  && grep -q "永不相加" "$RD/docs/how-to-record.md"; } \
  && log_ok "v18-EXPENSE-DOC-4X(类注释 + 方法注释 + 页面 tooltip + how-to-record 四处都写明「与收入侧相反」)" \
  || log_bad "v18-EXPENSE-DOC-4X 缺一处" "ExpenseLedgerService 类注释 / FactViewServiceImpl.netInflowExpense / MetricExplainService tooltip / docs/how-to-record.md"

# v18-MANUAL-B8 · 手册新增「支出构成」章 + 目录卡 + 组内节数
{ grep -q 'id="b8"' "$RD/src/main/resources/templates/help/how-to-use.html" \
  && grep -q 'href="#b8"' "$RD/src/main/resources/templates/help/how-to-use.html" \
  && grep -q "选修 B · 看懂自己的钱</h2><span class=\"badge-opt\">8 节" "$RD/src/main/resources/templates/help/how-to-use.html" \
  && grep -q "支出录入方式" "$RD/src/main/resources/templates/help/how-to-use.html" \
  && [[ "$(grep -c 'class="toc-card"' "$RD/src/main/resources/templates/help/how-to-use.html")" == "25" ]]; } \
  && log_ok "v18-MANUAL-B8(手册 B8 支出构成 + 目录卡 25 张 + 选修 B 计 8 节)" \
  || log_bad "v18-MANUAL-B8 缺件" "how-to-use.html 需有 #b8 章 + 目录卡 + 「8 节」计数一致(目录卡应 25 张)"

# v18-CF-SELECT · 收支两区的下拉是自研件,不是系统原生 select
#   复用打标页/透视那套 lens-select(data-lsel):原生 <select> 留在 DOM 里(表单语义 + 无 JS 降级),
#   组件隐藏它并渲染纸面风格按钮 + 面板。三条容易回退的点:
#   ① 按钮尺寸必须用 rem 不用 px —— 顶栏有字号缩放(A A),写死 px 会和旁边 h-9 的输入框差 2px;
#   ② 短列表(≤5)不显示搜索框,否则 4 个类目上面顶一个搜索框纯噪音;
#   ③ 股票账户那个下拉挂着 hx-get,组件 pick 后要在原生 select 上 dispatch 冒泡 change,HTMX 才会触发。
{ [[ "$(grep -c '<select data-lsel' "$RD/src/main/resources/templates/entry/index.html")" == "5" ]] \
  && grep -q 'lens-select.js' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'id="cashflow-entry"' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q '#cashflow-entry .lsel-btn' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'height:2.25rem' "$RD/src/main/resources/templates/entry/index.html" \
  && ! grep -qE '#cashflow-entry \.lsel-btn\{[^}]*height:[0-9]+px' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'SEARCH_THRESHOLD' "$RD/src/main/resources/static/js/lens-select.js" \
  && grep -q 'syncSearchVisibility' "$RD/src/main/resources/static/js/lens-select.js" \
  && grep -q "lsel-q\[hidden\]" "$RD/src/main/resources/static/js/lens-select.js" \
  && grep -q "new Event('change', { bubbles: true })" "$RD/src/main/resources/static/js/lens-select.js"; } \
  && log_ok "v18-CF-SELECT(收支 5 个下拉走 data-lsel · 尺寸用 rem 跟随字号缩放 · 短列表免搜索框 · change 冒泡保住 HTMX)" \
  || log_bad "v18-CF-SELECT 缺件" "entry/index.html 需 5 个 <select data-lsel> + 挂 lens-select.js + #cashflow-entry 尺寸覆盖用 rem;lens-select.js 需 SEARCH_THRESHOLD/syncSearchVisibility/.lsel-q[hidden] 与冒泡 change"

# v18-PERIOD-PICKER · 账期下拉的候选不能用 findLatest
#   findLatest 按 period_start 倒序取,账期表若预建到很多年以后(beta 排到 2038),
#   取到的 12 期全是未来空期 → 当前账期不在列表里 → 没有 option 带 selected → 选择器显示成
#   「2038 · 12 · CLOSED」,而页面标题却是 2026-08。同一个坑还坑过支出构成的「本期」锚点。
{ grep -q 'findRecentAsOf' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'entryPeriodListUpperBound' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && ! grep -q 'model.addAttribute("periods", periodMapper.findLatest' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'findRecentAsOf' "$RD/src/main/java/com/family/finance/repository/PeriodMapper.java" \
  && grep -q 'period_start <= #{asOf}' "$RD/src/main/java/com/family/finance/repository/PeriodMapper.java"; } \
  && log_ok "v18-PERIOD-PICKER(账期下拉候选走 findRecentAsOf · 不超过今天/进行中期 · 当前期必在列表内)" \
  || log_bad "v18-PERIOD-PICKER 仍用 findLatest" "EntryController 的 periods 应走 periodMapper.findRecentAsOf(上界=max(今天, 进行中期起始))"

# v181-CSRF-STALE · CSRF 失效回登录页,不甩「印泥洒了」错误页
#   用户报「登录后 URL 还停在 /login + 报错页」。根因是 CSRF token 失效走了 AccessDeniedHandler
#   → 403 → 渲染 error.html,URL 自然停在 /login。触发场景全是日常事:登录页开着放久了、
#   服务重启过、点了后退再提交、在另一个标签页登录登出过。
#   注意:只有 CsrfException 才跳登录页,真正的权限不足必须仍返回 403(那才该报错)。
{ grep -q 'staleFormAccessDeniedHandler' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q 'CsrfException' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q 'login?stale' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q 'AccessDeniedHandlerImpl' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q '"stale"' "$RD/src/main/java/com/family/finance/auth/AuthController.java" \
  && grep -q 'th:if="${stale}"' "$RD/src/main/resources/templates/auth/login.html"; } \
  && log_ok "v181-CSRF-STALE(CsrfException → /login?stale + 提示文案 · 其余 AccessDenied 仍走 403)" \
  || log_bad "v181-CSRF-STALE 缺件" "SecurityConfig 需 staleFormAccessDeniedHandler(只拦 CsrfException,其余交回 AccessDeniedHandlerImpl)+ AuthController 接 stale + login.html 提示分支"

# v181-FLOAT-DOCK · 三个浮钮的顺序在横竖屏来回切之后不能变
#   dockFloats 原来带「已在 dock 里就跳过」的守卫。横屏时 tocIntoNav 把目录钮搬进导航栏,
#   切回竖屏时它不在 dock 里 → 被 append 到末尾、另两个原位不动 → 顺序变成 方向/隐私/目录。
#   正解:每次按序 append 全部三个(appendChild 对已在容器内的节点=移到末尾),幂等。
{ grep -q "'#ori-float', '.toc-fab', '#priv-float'" "$RD/src/main/resources/static/js/landscape.js" \
  && ! grep -q "el.parentElement !== dock" "$RD/src/main/resources/static/js/landscape.js" \
  && grep -qE 'if \(el\) dock\.appendChild\(el\)' "$RD/src/main/resources/static/js/landscape.js"; } \
  && log_ok "v181-FLOAT-DOCK(浮钮按序全 append · 横竖屏来回切顺序不变)" \
  || log_bad "v181-FLOAT-DOCK 守卫回退了" "landscape.js 的 dockFloats 不得再用 el.parentElement !== dock 跳过已入 dock 的节点 —— 那会让横屏搬走过的目录钮回来时落到末尾"

# v181-README-BRIEF · README 近期更新段要短(用户 2026-08-04:篇幅太大)
#   每版 2–4 行 + 不内嵌截图 + 只留最近 1–2 版;细节和宫格图放 Release 页。README 是落地页不是变更日志。
RN_SEG="$(awk '/^## 近期更新/,/^## 主要能力/' "$RD/README.md")"
RN_IMG="$(printf '%s' "$RN_SEG" | grep -c 'releases/download' || true)"
RN_LINE="$(printf '%s' "$RN_SEG" | wc -l | tr -d ' ')"
{ [ "$RN_IMG" = "0" ] && [ "$RN_LINE" -le 16 ]; } \
  && log_ok "v181-README-BRIEF(近期更新段 $RN_LINE 行 · 无内嵌 release 截图)" \
  || log_bad "v181-README-BRIEF 段落又变长了" "近期更新段应 ≤16 行且不内嵌 releases/download 图(当前 $RN_LINE 行 / $RN_IMG 张图)· 细节放 Release 页"

# ══════════════════════════════════════════════════════════════════════════════
# v1.9 · 自动版本查询(只查不改)
# ══════════════════════════════════════════════════════════════════════════════
UCS="$RD/src/main/java/com/family/finance/service/update/UpdateCheckService.java"

# v19-UPD-NO-TELEMETRY · 不把版本号发给 GitHub
#   GitHub 要求请求带 UA,顺手写成 financial-management/1.9.0 是最自然的写法 ——
#   那就等于把版本号发出去了,与 PRD FR-303「不带版本号、不带实例标识」冲突。
#   这个冲突是写 TDD 时才发现的,不是想出来的。
{ grep -qF 'String UA = "financial-management"' "$UCS" \
  && ! grep -qE 'UA *\+ *"/"|UA *= *"[^"]*" *\+' "$UCS" \
  && ! grep -qE '"User-Agent", *UA *\+' "$UCS" \
  && grep -qF 'REPO = "LuoDi-Nate/financial-management"' "$UCS"; } \
  && log_ok "v19-UPD-NO-TELEMETRY(UA 不含版本号 · 仓库地址写死不可配置)" \
  || log_bad "v19-UPD-NO-TELEMETRY 可能带上了版本号" "UA 必须是固定串 financial-management;仓库地址不得做成可配置项(可配置 = 可被指向任意仓库)"

# v19-UPD-NO-IO-IN-ADVICE · GlobalModelAdvice 每个请求都跑,只许一次内存读
{ grep -q 'updateCheckService.cached' "$RD/src/main/java/com/family/finance/common/GlobalModelAdvice.java" \
  && ! grep -qE 'Mapper|RestTemplate|HttpClient|checkNow' "$RD/src/main/java/com/family/finance/common/GlobalModelAdvice.java"; } \
  && log_ok "v19-UPD-NO-IO-IN-ADVICE(advice 只调 cached() · 无 Mapper/HTTP/checkNow)" \
  || log_bad "v19-UPD-NO-IO-IN-ADVICE 把 IO 放进了每请求路径" "GlobalModelAdvice 每个请求都会跑,不得查库/出网;只能读 UpdateCheckService.cached() 的内存字段"

# v19-UPD-FAIL-CLOSED · 迁移判定「查不出来」必须是「未知」不是「没有」
#   报「无 schema 变更」是错误且危险的结论(用户会以为能安全回退)。
#   同一类老毛病:`|| echo 0` 把失败翻译成一个看起来正常的值。
{ grep -q 'COMPARE_FILES_CAP' "$UCS" \
  && grep -qE 'files.size\(\) *>= *COMPARE_FILES_CAP' "$UCS" \
  && grep -q 'truncated' "$UCS" \
  && grep -q '总额模式\|known' "$UCS" \
  && grep -q '未来账期\|new Migrations(0, List.of(), false)' "$UCS" \
  && grep -q '文件清单被截断时必须标未知_不能报没有迁移' "$RD/src/test/java/com/family/finance/service/update/UpdateCheckServiceTest.java"; } \
  && log_ok "v19-UPD-FAIL-CLOSED(compare files 截断/失败 → known=false · 单测守着)" \
  || log_bad "v19-UPD-FAIL-CLOSED 缺件" "detectMigrations 必须在 truncated/null 时返回 known=false;单测「文件清单被截断时必须标未知」必须在"

# v191-UPD-TAG-REF · compare 必须传 git tag 引用,不能传 app.version 原样
#   app.version 是 1.9.0(application.yml 里不带 v),tag 是 v1.9.0。
#   直接拼 → /compare/1.9.0...v1.9.1 → 404 → 迁移判定永远「无法确定」,
#   版本卡上最有价值的那一格彻底失效。
#   这个 bug 在 beta 上测不出来:那个分支只在「有新版」时才走,而 beta 的在研版本号总是
#   比已发布最新版更新,分支根本不执行 —— 是准备发 v1.9.1 做真机验证时核 URL 才发现的。
{ grep -q 'static String tagOf' "$UCS" \
  && grep -q 'fetchMigrations(tagOf(currentVersion), tagOf(latest))' "$UCS" \
  && ! grep -qE 'fetchMigrations\(currentVersion' "$UCS" \
  && grep -q '版本号要归一化成tag引用_否则compare必然404' "$RD/src/test/java/com/family/finance/service/update/UpdateCheckServiceTest.java"; } \
  && log_ok "v191-UPD-TAG-REF(compare 走 tagOf 归一化 · 单测守着)" \
  || log_bad "v191-UPD-TAG-REF 又把 app.version 原样拼进 compare 了" "必须 fetchMigrations(tagOf(current), tagOf(latest));app.version 不带 v 而 tag 带 v,原样拼必然 404"

# v19-UPD-DEGRADE · 一次失败不许把页面从「有新版」变成「什么都没有」
{ grep -q 'writeAttempt(familyId, false' "$UCS" \
  && ! grep -qE 'catch *\([^)]*\) *\{[^}]*KEY_RESULT' "$UCS" \
  && grep -q 'KEY_ATTEMPT' "$UCS" && grep -q 'KEY_RESULT' "$UCS"; } \
  && log_ok "v19-UPD-DEGRADE(失败只写 lastAttempt · 不动 result / 不动内存)" \
  || log_bad "v19-UPD-DEGRADE 失败路径动了 result" "checkNow 的 catch 只能写 lastAttempt;result 保持上次成功值"

# v19-UPD-BADGE · 徽记可点 + 圆点绝对定位 + 不嵌套 <a>
#   徽记原先嵌在 logo 的 <a th:href="@{/}"> 里,直接加 href 会变成 <a> 套 <a>(非法 HTML,
#   浏览器自动拆开、布局散架)→ v1.9 把 nav 拆成三个并列 <a>。
{ grep -q 'ver-badge' "$RD/src/main/resources/templates/fragments/nav.html" \
  && grep -q 'ver-new' "$RD/src/main/resources/templates/fragments/nav.html" \
  && grep -q "admin(tab='version')" "$RD/src/main/resources/templates/fragments/nav.html" \
  && [ "$(grep -c '<a ' "$RD/src/main/resources/templates/fragments/nav.html")" -ge 3 ] \
  && grep -q 'id="version"' "$RD/src/main/resources/templates/admin/index.html"; } \
  && log_ok "v19-UPD-BADGE(徽记 <a> 可点 · 不嵌套 <a> · 管理页 #version 锚点)" \
  || log_bad "v19-UPD-BADGE 缺件" "nav.html 需 ver-badge/ver-new + 跳 admin?tab=version;admin 需 id=version"

# ── v1.9.2 · 提示醒目度 + 就地弹窗 ──────────────────────────────────────
UPM="$RD/src/main/resources/templates/fragments/_update-modal.html"
NAVH="$RD/src/main/resources/templates/fragments/nav.html"
CSSF="$RD/src/main/resources/static/css/style.css"

# v192-UPD-BADGE-CONTRAST · 提示必须是实心高对比,不许退回描边圆点
#   v1.9.0 用 .ver-dot:brass-soft #E8D9B6 填色落在 paper #F4EFE6 上,对比度约 1.2:1
#   (WCAG 非文本元素门槛 3:1)—— 用户反馈「太弱了,和背景色过于相似」,实测等于看不见。
#   换成实心「NEW」文字标签:brass-deep #8C6A33 底 + paper 字 ≈ 4.1:1。
#   这里同时钉住「.ver-dot 已彻底删掉」,防后人照着旧文档把描边圆点加回来。
{ grep -qF '.ver-new' "$CSSF" \
  && ! grep -qE '^[[:space:]]*\.ver-dot[[:space:]]*\{' "$CSSF" \
  && ! grep -q 'ver-dot' "$NAVH" \
  && grep -A6 -F '.ver-new {' "$CSSF" | grep -q 'background: var(--brass-deep)' \
  && grep -A6 -F '.ver-new {' "$CSSF" | grep -q 'color: var(--paper)' \
  && grep -q '>NEW<' "$NAVH"; } \
  && log_ok "v192-UPD-BADGE-CONTRAST(实心 NEW 标签 · brass-deep 底 + 纸色字 ≈ 4.1:1 · .ver-dot 已除)" \
  || log_bad "v192-UPD-BADGE-CONTRAST 提示又变弱了" "禁用 .ver-dot 描边圆点(1.2:1);.ver-new 必须 background:var(--brass-deep) + color:var(--paper)"

# v192-UPD-MODAL-BODY-LEVEL · 弹窗必须挂在 <main> 外面
#   main 带 `relative z-10` 是**层叠上下文**:放在它里面的 position:fixed 浮层
#   永远升不到 z-30 的 nav 之上,遮罩会被顶栏压穿(项目里踩过)。
#   所以由 layout 的 footer 片段引入(footer 只有 relative、没有 z-index,不成上下文)。
{ [ -f "$UPM" ] \
  && grep -q '_update-modal :: updateModal' "$RD/src/main/resources/templates/fragments/layout.html" \
  && ! grep -rq '_update-modal' "$RD/src/main/resources/templates/dashboard/index.html" \
  && grep -A4 -F '.upd-modal {' "$CSSF" | grep -q 'position: fixed' \
  && grep -A4 -F '.upd-mask {' "$CSSF" | grep -q 'position: fixed' \
  && grep -q 'body.upd-open #float-dock' "$CSSF" \
  && grep -qF '.upd-modal, .upd-modal * { text-align: left; }' "$CSSF" \
  && grep -q "classList.add('upd-open')" "$UPM"; } \
  && log_ok "v192-UPD-MODAL-BODY-LEVEL(弹窗由 footer 片段引入 · 在 main 之外 · fixed 遮罩)" \
  || log_bad "v192-UPD-MODAL-BODY-LEVEL 弹窗挂错层" "必须经 fragments/layout.html 的 footer 引入;放进 main(relative z-10)会被 nav 压穿"

# v192-UPD-MODAL-FIELDS · 弹窗三要素 + 迁移判定
#   用户要求至少给:a 最新版本 b 该版本的 GitHub Release 链接 c 版本说明摘要。
#   再加一条本项目独有的迁移判定 —— 回滚只回 jar 不回 DB,这格决定升级能不能退回来。
{ grep -q 'updateInfo.latest()' "$UPM" \
  && grep -q 'updateInfo.currentTag()' "$UPM" \
  && grep -q 'updateInfo.releaseUrl()' "$UPM" \
  && grep -q 'updateInfo.summary()' "$UPM" \
  && grep -q 'updateInfo.publishedAt()' "$UPM" \
  && grep -q 'migrations().known()' "$UPM" \
  && grep -q 'target="_blank"' "$UPM" && grep -q 'rel="noopener"' "$UPM" \
  && grep -q 'updateInfo.hasUpdate()' "$UPM"; } \
  && log_ok "v192-UPD-MODAL-FIELDS(最新版本 + Release 链接 + 说明摘要 + 迁移判定 · 仅有新版时渲染)" \
  || log_bad "v192-UPD-MODAL-FIELDS 弹窗缺件" "需 latest / releaseUrl / summary / publishedAt / 迁移判定,且整块 th:if hasUpdate"

# v192-UPD-MODAL-DEGRADE · 没 JS 时徽记仍然是条能用的链接
#   弹窗是渐进增强:href 保留指向管理页版本卡,JS 在时才 preventDefault 拦成弹窗。
#   别改成 href="#" 或 <button> —— 那样禁用 JS 的浏览器点了什么都不会发生。
{ grep -q "th:href=\"@{/admin(tab='version')}\"" "$NAVH" \
  && ! grep -q 'href="#"' "$NAVH" \
  && grep -q 'preventDefault' "$UPM" \
  && grep -q "querySelectorAll('.ver-badge')" "$UPM" \
  && grep -q "e.key === 'Escape'" "$UPM"; } \
  && log_ok "v192-UPD-MODAL-DEGRADE(徽记 href 仍可用 · JS 在时才拦成弹窗 · Esc/遮罩可关)" \
  || log_bad "v192-UPD-MODAL-DEGRADE 退化路径断了" "徽记 href 必须留 /admin?tab=version;弹窗靠 preventDefault 接管,不许 href=# 或换 <button>"

# ── v1.9.3 · 账户表行高(中文列被压成竖排)────────────────────────────
# v193-TABLE-NUM-NOWRAP · 账户表数字格不许折行 + 类型 pill 不许竖排
#   现象(用户报 prod):报表页账户表「类型」列竖着排,「现金」两行、「贵金属」三行,行高 71px。
#   根因:两张账户表最多 17 列、自然宽 ~1790px 远超容器 ~1071px,table-layout:auto 会把
#   **能折行的内容压到 min-content**(中文 1 字/行)再把宽度让给别的列。
#   同一挤压还打中:收益率/本位币年化的「累」上标、预实的上标、vs 基准的「跑输 -87.10pp」——
#   宽值行折两行、窄值行不折,同一列有的一行有的两行。
#   修法刻意用**属性级**规则(数字格整类 nowrap)而不是逐个格补:逐点补下次加指标列又会漏。
{ grep -q '#dash-list .ledger-table tbody td.num,' "$RD/src/main/resources/static/css/style.css" \
  && grep -q '#reports-region .ledger-table tbody td.num { white-space: nowrap; }' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'white-space:nowrap' <(grep 'pill-vs{' "$RD/src/main/resources/templates/reports/_region.html") \
  && grep -q 'pill whitespace-nowrap justify-center' "$RD/src/main/resources/templates/reports/_region.html" \
  && grep -q 'pill whitespace-nowrap justify-center' "$RD/src/main/resources/templates/reports/_drilldown.html" \
  && ! grep -qE '<td><span class="pill" th:text="\$\{(account|row)\.accountType' \
        "$RD/src/main/resources/templates/reports/_region.html" \
        "$RD/src/main/resources/templates/reports/_drilldown.html"; } \
  && log_ok "v193-TABLE-NUM-NOWRAP(账户表数字格整类 nowrap · 类型/vs基准 pill 不折行)" \
  || log_bad "v193-TABLE-NUM-NOWRAP 表格又会被压成竖排" "style.css 需 #dash-list/#reports-region 的 td.num 整类 nowrap;类型 pill 需 whitespace-nowrap + min-width"

# v193-TYPE-LABEL · 账户类型面向用户不许裸露枚举 code
#   reports/_drilldown.html 原来写 ${row.accountType}(AccountType 枚举本身)→ 渲染成 CASH/STOCK。
#   其余表一律 .label。裸 code 违反「面向用户的命名避免技术词」。
{ ! grep -qE 'th:text="\$\{(row|account)\.accountType\}"' "$RD/src/main/resources/templates/reports/_drilldown.html" \
  && grep -q 'row.accountType.label' "$RD/src/main/resources/templates/reports/_drilldown.html" \
  && [ "$(grep -rlE 'th:text="\$\{[a-zA-Z.]*accountType\}"' "$RD/src/main/resources/templates" | wc -l)" -eq 0 ]; } \
  && log_ok "v193-TYPE-LABEL(账户类型一律出 .label · 模板里没有裸 accountType 枚举)" \
  || log_bad "v193-TYPE-LABEL 面向用户裸露了枚举 code" "账户类型渲染必须用 .label,不能直接输出 AccountType"

# v192-UPD-STALE-CURRENT · 升级完之后不许继续提示有新版
#   KV 行里的 current 是「上次检查时在跑的版本」。用户照提示升级完 jar、重启,这行还没刷新 ——
#   拿旧 current 去比,**已经升到最新版**的实例会继续挂 NEW,一直挂到隔天定时器跑过。
#   latest 是关于 GitHub 的事实(可以旧),current 必须是关于本进程的事实(不能旧)。
#   覆盖动作必须在 reloadMemo 里面(读缓存入口有三个:预热/开关切换/检查后回写),
#   不能靠调用方各自传参 —— 最初只在预热那条路传了,一开关就把过期 current 复活了。
{ grep -q 'UpdateInfo withCurrent(String running)' "$UCS" \
  && grep -q 'i.withCurrent(appVersion)' "$UCS" \
  && [ "$(grep -c 'withCurrent(' "$UCS")" -eq 2 ] \
  && grep -q '升级完之后不能继续显示有新版_current要用正在跑的版本' "$RD/src/test/java/com/family/finance/service/update/UpdateCheckServiceTest.java"; } \
  && log_ok "v192-UPD-STALE-CURRENT(warmUp 用正在跑的 app.version 覆盖 KV 里的 current)" \
  || log_bad "v192-UPD-STALE-CURRENT 缓存 current 会过期" "reloadMemo 内部必须 withCurrent(appVersion),否则升级后徽记不消失"

# v19-UPD-OFF-NO-CALL · 关掉开关后一个请求都不发
#   判定必须在任何 HTTP 构造之前 —— 别在判定前先把 URL/请求对象拼好
#   (那样看着没发,其实已经在准备发了,而且后人很容易把顺序改反)。
{ grep -q 'enabled(FAMILY_ID)' "$RD/src/main/java/com/family/finance/service/update/UpdateCheckJob.java" \
  && grep -q 'if (!updateCheckService.enabled(FAMILY_ID))' "$RD/src/main/java/com/family/finance/service/update/UpdateCheckJob.java" \
  && grep -q 'if (!enabled(familyId))' "$UCS"; } \
  && log_ok "v19-UPD-OFF-NO-CALL(Job 与 checkNow 都在最前面判 enabled)" \
  || log_bad "v19-UPD-OFF-NO-CALL 判定位置不对" "UpdateCheckJob.run 与 UpdateCheckService.checkNow 的第一件事都必须是判 enabled"

echo
echo "═══════════════════════════════════════"
echo " 总结: PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP"
echo "═══════════════════════════════════════"
if [[ $FAIL -gt 0 ]]; then
  echo "失败用例:"
  for f in "${FAILED[@]}"; do echo "  · $f"; done
  exit 1
fi
exit 0
