#!/usr/bin/env bash
# ============================================================================
# metric-baseline.sh · 口径改造的「零差异基线」工具
#
#   为什么存在:改指标口径 / 换取数路径时,最难的不是改,是**证明没改坏别的**。
#   memory feedback_metric_refactor_baseline:基线必须来自**已发布 tag**,
#   否则分不清「代码差异」和「数据漂移」。
#
#   形态:把报表页 + 仪表盘在 6 个 range × 3 个币种下的渲染 HTML 原样存盘,
#   抹掉会话级易变位(csrf token / app.version),然后逐字节比。
#   页面渲染本身是确定性的(同一 DB + 同一代码 → 同一字节),实测两次请求 diff 为 0,
#   所以任何差异都真的是代码行为差异,不是噪声。
#
#   用法(登录名/密码只从环境变量来 · 不写进仓库):
#     export BL_USER=<你的登录名> BL_PASS=<你的密码>
#     bash scripts/metric-baseline.sh capture /tmp/baseline-v1.11.3   # 改造前(跑在已发布 tag 的构建上)
#     ...改代码 + 重新部署...
#     bash scripts/metric-baseline.sh capture /tmp/baseline-after
#     bash scripts/metric-baseline.sh diff /tmp/baseline-v1.11.3 /tmp/baseline-after
#
#   diff 输出「ZERO-DIFF OK」= 通过分水岭;列出差异文件 = 停下查因,不许"看起来更合理就接受"。
# ============================================================================
set -u

BASE="${BL_BASE:-http://127.0.0.1:20000}"
USER_="${BL_USER:?请设 BL_USER=<登录名>}"; PASS_="${BL_PASS:?请设 BL_PASS=<密码> · 凭据不进仓库}"
RANGES="${BL_RANGES:-1M 3M 6M YTD 1Y ALL}"
CCYS="${BL_CCYS:-CNY USD HKD}"

# 构建/会话/JVM 级易变位,与指标无关,必须抹掉否则每次都是满屏假差异:
#   ① csrf token —— 每会话一个
#   ② app.version —— 改造后必然变
#   ③ 静态资源缓存戳 `?v=0.1.0-SNAPSHOT-mssh0g0f` —— 每次构建重算,一改就是全页 38~60 行
#   ④ 仪表盘 dims/measures 那两个内联 JSON 串的**字段顺序** —— Jackson 按反射发现序输出,
#      JVM 不保证跨进程稳定。实测「同一份代码、只重启」也会变(key,holdingLevel,label ↔ key,label,holdingLevel),
#      值完全相同。属于既有的显示层非确定性,与本版无关;这里按键名排序规范化,**不动值** ——
#      所以 dims/measures 的内容真变了照样会被抓到。
# 只抹这四类 —— 抹多了会把真差异一起抹掉。
normalize(){ sed -E \
  -e 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/CSRF-UUID/g' \
  -e 's/\?v=[0-9A-Za-z.-]+/?v=ASSET/g' \
  -e 's/v[0-9]+\.[0-9]+\.[0-9]+/vX.Y.Z/g' \
  | canon_json ; }

# ④ 的实现:只处理形如 `<名>: "[{...}]",` 的内联 JSON 串行,json.loads 后按键排序回写。
# 解析失败就原样输出 —— 宁可留噪声,也不要静默吃掉一行。
canon_json(){ python3 -c '
import sys, json, re
pat = re.compile(r"^(\s*(?:dims|measures): )\"(.*)\",\s*$")
for line in sys.stdin:
    m = pat.match(line)
    if not m:
        sys.stdout.write(line); continue
    try:
        obj = json.loads(m.group(2).replace("\\\"", "\""))
        sys.stdout.write(m.group(1) + json.dumps(obj, sort_keys=True, ensure_ascii=False) + ",\n")
    except Exception:
        sys.stdout.write(line)
'; }

capture(){
  local out="$1"; mkdir -p "$out"
  local ck; ck="$(mktemp)"
  trap 'rm -f "$ck"' RETURN
  local x
  : > "$ck"
  curl -s -c "$ck" "$BASE/login" -o /dev/null
  x="$(grep XSRF-TOKEN "$ck" | awk '{print $7}' | tail -1)"
  curl -s -b "$ck" -c "$ck" -X POST "$BASE/login" -H "X-XSRF-TOKEN: $x" \
       --data-urlencode "username=$USER_" --data-urlencode "password=$PASS_" -o /dev/null
  curl -s -b "$ck" -c "$ck" "$BASE/dashboard" -o /dev/null
  if ! curl -s -b "$ck" -c "$ck" "$BASE/dashboard" | grep -q '净资产'; then
    echo "✗ 登录失败(dashboard 无净资产)· 中止"; return 1
  fi

  local n=0
  for c in $CCYS; do
    for r in $RANGES; do
      curl -s -b "$ck" -c "$ck" "$BASE/reports?range=$r&currency=$c" | normalize > "$out/reports_${c}_${r}.html"
      n=$((n+1))
    done
    curl -s -b "$ck" -c "$ck" "$BASE/dashboard?currency=$c"   | normalize > "$out/dashboard_${c}.html"
    curl -s -b "$ck" -c "$ck" "$BASE/checkup?currency=$c"     | normalize > "$out/checkup_${c}.html"
    n=$((n+2))
  done
  echo "✓ 抓取 $n 个页面 → $out"
  ls -la "$out" | awk 'NR>3 {printf "  %8d  %s\n", $5, $9}'
}

do_diff(){
  local a="$1" b="$2" bad=0 miss=0
  for f in "$a"/*.html; do
    local n; n="$(basename "$f")"
    if [ ! -f "$b/$n" ]; then echo "  ✗ 缺失: $n"; miss=$((miss+1)); continue; fi
    if ! cmp -s "$f" "$b/$n"; then
      echo "  ✗ 有差异: $n  ($(diff "$f" "$b/$n" | grep -c '^[<>]') 行)"
      bad=$((bad+1))
    fi
  done
  echo
  if [ "$bad" = 0 ] && [ "$miss" = 0 ]; then
    echo -e "\033[32mZERO-DIFF OK\033[0m · $(ls "$a"/*.html | wc -l) 个页面逐字节相同"
    return 0
  fi
  echo -e "\033[31mZERO-DIFF FAILED\033[0m · $bad 个有差异 · $miss 个缺失"
  echo "  逐个查因:diff $a/<名> $b/<名>"
  return 1
}

case "${1:-}" in
  capture) capture "${2:?用法: capture <输出目录>}" ;;
  diff)    do_diff "${2:?用法: diff <基线目录> <对照目录>}" "${3:?}" ;;
  *) echo "用法: $0 capture <outdir> | diff <dirA> <dirB>"; exit 2 ;;
esac
