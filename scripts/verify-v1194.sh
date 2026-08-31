#!/usr/bin/env bash
# v1.19.4 · 截图识别失败不许装成功 · 端到端验证
#
# 不是 grep 源码,是**真的制造一次上游失败**:配一把无效的视觉 key → 上传图 → 识别 →
# 看状态机走到哪、生成了什么、页面上给了什么。这正是线上那次事故的形状
# (那次是免费额度耗尽 403,这里用无效 key 触发 401,走的是同一条失败路径)。
#
# 会改配置和写数据,所以先快照后还原。
set -uo pipefail

BASE="${V_BASE:-http://127.0.0.1:20000}"
DBU=finance; DBP=finance; DBN=finance
USER_=diwa; PASS_=demo1234
ACCT="${V_ACCT:-25}"          # 账户 25 = 「测试」· WEALTH

CK="$(mktemp)"; DUMP="$(mktemp /tmp/v1194_baseline.XXXXXX.sql)"; IMG=/tmp/v1194.png
PASS=0; FAIL=0; FAILED=()
ok(){  PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad(){ FAIL=$((FAIL+1)); FAILED+=("$1"); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }

restore(){
  mysql -u"$DBU" -p"$DBP" "$DBN" < "$DUMP" 2>/dev/null \
    && echo "▸ beta 数据/配置已还原" || echo "✗ 还原失败!手动:mysql $DBN < $DUMP"
  rm -f "$CK"
}
trap restore EXIT

echo "▸ 快照 beta 基线"
mysqldump --single-transaction --skip-lock-tables -u"$DBU" -p"$DBP" "$DBN" > "$DUMP" 2>/dev/null \
  || { echo "✗ 快照失败,中止"; exit 1; }

M(){ mysql -N -B -u"$DBU" -p"$DBP" "$DBN" -e "$1" 2>/dev/null; }

# ── 配一把必然失败的视觉三元组 ──
# 平台/型号都合法(所以 available() 通过、导入页正常打开),但 key 是假的 → 调用时 401。
echo "▸ 配置无效的视觉 key(制造上游失败)"
for kv in "llm_vision_platform|dashscope" "llm_vision_family|qwen-vl" \
          "llm_vision_model_id|qwen-vl-max" "llm_vision_enabled|true" \
          "llm_qwen_api_key|sk-invalid-key-for-v1194-verification"; do
  K="${kv%%|*}"; V="${kv#*|}"
  M "INSERT INTO family_runtime_config (family_id,key_name,value_text) VALUES (1,'$K','$V')
     ON DUPLICATE KEY UPDATE value_text='$V'"
done

# ── 一张合成 PNG(内容无所谓,key 是坏的,到不了模型那一步) ──
python3 -c "
import zlib,struct
def ch(t,d):
    c=t+d; return struct.pack('>I',len(d))+c+struct.pack('>I',zlib.crc32(c))
w=h=80
GRAY=200
raw=b''.join(b'\x00'+bytes([GRAY])*3*w for _ in range(h))   # 灰度像素;别把同一个值写成逗号分隔三元组 —— 那个形状会被金额护栏当成千分位数字
png=b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+ch(b'IDAT',zlib.compress(raw))+ch(b'IEND',b'')
open('$IMG','wb').write(png)
"

# ── 登录 ──
xsrf(){ grep XSRF-TOKEN "$CK" | awk '{print $7}' | tail -1; }
: > "$CK"
curl -s -c "$CK" "$BASE/login" -o /dev/null
curl -s -b "$CK" -c "$CK" -X POST "$BASE/login" -H "X-XSRF-TOKEN: $(xsrf)" \
     --data-urlencode "username=$USER_" --data-urlencode "password=$PASS_" -o /dev/null
curl -s -b "$CK" -c "$CK" "$BASE/dashboard" -o /dev/null
GET(){ curl -s -b "$CK" -c "$CK" "$BASE$1"; }

echo
echo "═══ 1 · 开一次导入并上传 ═══"
PAGE=$(GET "/entry/import/$ACCT")
IID=$(printf '%s' "$PAGE" | grep -oE 'data-import="[0-9]+"' | head -1 | grep -oE '[0-9]+')
if [ -z "$IID" ]; then bad "拿不到 importId(导入页没开出来)"; echo "$PAGE" | grep -o '视觉[^<]*' | head -2; else ok "导入 id=$IID"; fi

if [ -n "$IID" ]; then
  UP=$(curl -s -b "$CK" -c "$CK" -X POST "$BASE/entry/import/$IID/upload" \
       -H "X-XSRF-TOKEN: $(xsrf)" -F "files=@$IMG;type=image/png")
  case "$UP" in *'"ok":true'*|*imgCount*) ok "图片已上传";; *) bad "上传失败: $(printf '%s' "$UP" | head -c 120)";; esac

  echo
  echo "═══ 2 · 识别(key 是坏的 → 必然失败) ═══"
  curl -s -b "$CK" -c "$CK" -X POST "$BASE/entry/import/$IID/scan" -H "X-XSRF-TOKEN: $(xsrf)" -o /dev/null
  ST="";
  for i in $(seq 1 40); do
    sleep 2
    ST=$(M "SELECT status FROM holding_import WHERE id=$IID")
    case "$ST" in SCAN_ERROR|REVIEW) break;; esac
  done
  echo "  终态 = $ST"

  [ "$ST" = "SCAN_ERROR" ] \
    && ok "状态是 SCAN_ERROR(不是 REVIEW —— 这正是事故的正根)" \
    || bad "状态是 $ST,应为 SCAN_ERROR"

  N=$(M "SELECT COUNT(*) FROM holding_import_item WHERE import_id=$IID")
  [ "$N" = "0" ] \
    && ok "一条比对项都没生成(没有表就没有误确认的可能)" \
    || bad "生成了 $N 条比对项 —— 全失败时不该有任何项"

  NSOLD=$(M "SELECT COUNT(*) FROM holding_import_item WHERE import_id=$IID AND match_state='SOLD'")
  [ "$NSOLD" = "0" ] \
    && ok "没有任何「卖出?」项(线上那次是 9 条和 4 条)" \
    || bad "冒出了 $NSOLD 条假的卖出建议"

  ERR=$(M "SELECT COALESCE(scan_error,'(NULL)') FROM holding_import WHERE id=$IID")
  echo "  提示文案 = $ERR"
  case "$ERR" in
    '(NULL)') bad "scan_error 是 NULL —— 用户又是一个字的线索都没有";;
    *识别失败,请重试) bad "退化成兜底文案,没说清是 key 的问题";;
    *) ok "有可操作的失败原因";;
  esac
  case "$ERR" in *key*|*额度*|*拒绝*) ok "文案指向了具体原因(key/额度/权限)";; *) bad "文案没指向具体原因: $ERR";; esac

  echo
  echo "═══ 3 · 页面:失败态不能有确认按钮 ═══"
  GET "/entry/import/$ACCT" > /tmp/v1194_page.html
  # 判据必须看**真正渲染出来的元素**,不能 grep 整页字符串:
  #   · <script> 里有 getElementById('reviewForm'),那段 JS 是无条件输出的
  #   · HTML 注释会被原样送到浏览器
  # 第一版就栽在这两处,报了两条假的 FAIL。先剥掉注释和 script 再判。
  BODY=$(python3 - <<'PYEOF'
import re
h = open('/tmp/v1194_page.html', encoding='utf-8', errors='replace').read()
h = re.sub(r'<!--.*?-->', '', h, flags=re.S)
h = re.sub(r'<script\b.*?</script>', '', h, flags=re.S | re.I)
print(h)
PYEOF
)
  case "$BODY" in *'一张都没识别出来'*) ok "页面显示了「一张都没识别出来」";; *) bad "页面没有失败态提示";; esac
  case "$BODY" in *'id="reviewForm"'*) bad "页面上仍然渲染了确认表单";; *) ok "没有确认表单(误确认物理上不可能)";; esac
  case "$BODY" in *'卖出?'*) bad "页面上出现了「卖出?」";; *) ok "页面上没有任何「卖出?」";; esac
  case "$BODY" in *'/confirm"'*) bad "页面上还有指向 confirm 的表单";; *) ok "没有任何指向 confirm 的提交点";; esac
  case "$BODY" in *rescanBtn*) ok "给了「重新识别」的出口";; *) bad "没有重新识别入口,用户走投无路";; esac

  # 顺带守一条:内部注释不许出现在给用户的 HTML 里
  RAW=$(cat /tmp/v1194_page.html)
  case "$RAW" in *'线上真发生过'*|*'用户点了确认'*) bad "内部事故复盘被原样输出到页面源码";; *) ok "内部注释没有泄到页面源码";; esac
  # 注释块被自己的内容提前闭合 → 后半段注释会当成正文渲染出来。
  # 真发生过:在解析期注释里写了闭合标记当例子,页面顶部于是多出一段乱码文字。
  # e2e 抓不到(它剥注释,而那段已经不算注释了)—— 是截图看出来的,所以补这条。
  # 判据只认闭合标记的**完整形态**。第一版写了裸的 */,而页面 <style> 里的 CSS 注释
  # 必然含 */ —— 当场误报。收窄之后再在剥掉 style/script 的正文里查一遍。
  LEAK=$(python3 - <<'PYEOF'
import re
h = open('/tmp/v1194_page.html', encoding='utf-8', errors='replace').read()
h = re.sub(r'<style\b.*?</style>', '', h, flags=re.S | re.I)
h = re.sub(r'<script\b.*?</script>', '', h, flags=re.S | re.I)
print('LEAK' if ('*/-->' in h or '*/' in re.sub(r'<!--.*?-->', '', h, flags=re.S)) else 'CLEAN')
PYEOF
)
  [ "$LEAK" = "CLEAN" ] \
    && ok "没有注释残片漏到页面正文" \
    || bad "页面上漏出了注释闭合标记 —— 注释块被自己的内容截断了"

  echo
  echo "═══ 4 · 服务端也要挡住确认 ═══"
  C=$(curl -s -o /dev/null -w '%{http_code}' -b "$CK" -c "$CK" -X POST "$BASE/entry/import/$IID/confirm" \
      -H "X-XSRF-TOKEN: $(xsrf)" --data-urlencode "from=/entry")
  ST2=$(M "SELECT status FROM holding_import WHERE id=$IID")
  [ "$ST2" = "SCAN_ERROR" ] \
    && ok "强行 POST confirm 也没能改状态(仍是 SCAN_ERROR · HTTP $C)" \
    || bad "confirm 竟然生效了,状态变成 $ST2"

  echo
  echo "═══ 5 · 持仓一条都没动 ═══"
  ARCH=$(M "SELECT COUNT(*) FROM stock_holding WHERE account_id=$ACCT AND archived_at >= NOW() - INTERVAL 10 MINUTE")
  [ "$ARCH" = "0" ] && ok "没有任何持仓被归档" || bad "有 $ARCH 条持仓被归档了"
fi

echo
echo "═══════════════════════════════════════"
echo " 总结: PASS=$PASS  FAIL=$FAIL"
echo "═══════════════════════════════════════"
if [ $FAIL -gt 0 ]; then printf '  · %s\n' "${FAILED[@]}"; exit 1; fi
exit 0
