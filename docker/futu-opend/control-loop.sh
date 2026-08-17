#!/usr/bin/env bash
# =========================================================
# 富途 OpenD 网关 launcher · 控制循环(v1.17)
#
# app 在【另一个容器】里,而 OpenD 的控制口(telnet 22222)没有任何鉴权 ——
# 连上就能重登、发验证码、退进程。所以那个口只绑容器内 127.0.0.1,app 连不到它。
# app 与本容器共挂一个小卷 /ctl,通过文件下指令:文件权限就是鉴权,不需要再发明一套令牌。
#
# 协议(刻意用 key=value 而不是 JSON:这个镜像里没有 jq,bash 解析 JSON 会脆):
#   app  写 /ctl/cmd/<序号>.req   →  op=login|sms|req-sms|probe  + 参数行
#   本脚本执行完写 /ctl/cmd/<序号>.done(带结果),并刷新 /ctl/status
#
# 凭据:login 请求里的密码【读完立刻删文件】,且绝不写进任何日志(只记"已发送密码")。
#      这仍然是"短暂落卷"——比长期写在 .env 里好一个量级,但不是零落盘,文档里如实说。
# =========================================================
set -uo pipefail

CTL_DIR="${FUTU_CTL_DIR:-/ctl}"
TELNET_PORT="${FUTU_TELNET_PORT:-22222}"
VERSION="${FUTU_VERSION:-}"
CMD_DIR="$CTL_DIR/cmd"
PHASE="STARTING"
MESSAGE="等待 OpenD 控制口就绪"

mkdir -p "$CMD_DIR" 2>/dev/null

# 注意写 stderr:do_login 是在 $( ) 里调用的,写 stdout 会被命令替换吞掉
# —— 那样 docker logs 反而看不到,而返回值里混进日志文本。
log(){ printf '[ctl] %s\n' "$*" >&2; }

status(){
  { printf 'phase=%s\n' "$PHASE"
    printf 'message=%s\n' "$MESSAGE"
    printf 'version=%s\n' "$VERSION"
    printf 'apiPort=%s\n' "${FUTU_API_PORT:-11111}"
    printf 'ts=%s\n' "$(date -u +%FT%TZ)"
  } > "$CTL_DIR/.status.$$" 2>/dev/null && mv -f "$CTL_DIR/.status.$$" "$CTL_DIR/status" 2>/dev/null
}

# 往控制口发一行并把回显读回来。
# 关键词判定表必须和 Java 侧 OpendTelnet.stepFromPrompt 认同一批词
# —— qa-run 的 v117-CTL-KEYWORDS 钉住这件事,别只改一边。
talk(){
  local line="$1" out=""
  # 连不上时 bash 自己会往 stderr 打两行 "Connection refused" —— OpenD 起来前每秒两行,
  # 会把 docker logs 刷满、把 OpenD 的真日志淹掉。所以整个重定向包在子 shell 里静音。
  if ! { exec 3<>"/dev/tcp/127.0.0.1/$TELNET_PORT"; } 2>/dev/null; then
    printf ''
    return 1
  fi
  printf '%s\r\n' "$line" >&3
  # 不用 `timeout N cat`:OpenD 不会主动关连接,那样每次都要耗满 N 秒,
  # 一次登录三个来回就是 3N 秒。改成"有数据就读,静默 2 秒即收工"。
  local first=4 chunk=""
  while IFS= read -r -t "$first" -u 3 chunk; do
    out="$out$chunk"$'\n'
    first=2
    [ "${#out}" -gt 2000 ] && break
  done
  exec 3<&- 2>/dev/null
  printf '%s' "$out"
}

step_of(){
  local s="$1"
  case "$s" in
    *验证码错误*|*密码错误*|*账号错误*|*登录失败*|*"login failed"*) echo FAILED ;;
    *登录成功*|*"login success"*|*"login succeed"*|*已登录*)        echo LOGGED_IN ;;
    *验证码*|*"verify code"*|*verifycode*)                          echo WANT_SMS ;;
    *请输入密码*|*"input password"*|*"enter password"*)              echo WANT_PASSWORD ;;
    *请输入账号*|*请输入帐号*|*"input account"*|*"enter account"*)   echo WANT_ACCOUNT ;;
    *)                                                              echo UNKNOWN ;;
  esac
}

apply_step(){
  case "$1" in
    LOGGED_IN) PHASE=RUNNING;   MESSAGE="OpenD 已登录 · 运行中" ;;
    WANT_SMS)  PHASE=NEEDS_SMS; MESSAGE="需要手机短信验证码" ;;
    FAILED)    PHASE=ERROR;     MESSAGE="登录被拒(账号/密码/验证码有误)" ;;
    WANT_ACCOUNT|WANT_PASSWORD) PHASE=STARTING; MESSAGE="OpenD 在等登录信息" ;;
  esac
}

# 取 key=value 文件里某个键(只取第一次出现;值里允许有 = 和空格)
field(){ sed -n "s/^$2=//p" "$1" | head -1; }

do_login(){
  local req="$1" account password out step
  account="$(field "$req" account)"
  password="$(field "$req" password)"
  rm -f "$req"                     # 密码读进内存后立刻删文件,别留在卷上
  [ -n "$account" ] || { echo "缺 account"; return 1; }

  # 【实测教训】不能靠"首次读到什么"决定要不要发账号:OpenD 把事件广播给所有 telnet 客户端,
  # 每次新建连接都可能先收到上一次操作的残留(比如上回失败的「账号错误」)→ 会被判成 FAILED 就不喂了。
  # 所以除了"已经登录"以外,一律先把账号喂进去,再看它要什么。
  out="$(talk "")"; step="$(step_of "$out")"
  if [ "$step" = "LOGGED_IN" ]; then apply_step "$step"; echo "$step"; return 0; fi

  out="$(talk "$account")"; log "已发送账号"; step="$(step_of "$out")"
  if [ "$step" != "LOGGED_IN" ] && [ "$step" != "WANT_SMS" ]; then
    out="$(talk "$password")"; log "已发送密码(不记录内容)"; step="$(step_of "$out")"
  fi
  unset password
  apply_step "$step"
  echo "$step"
}

log "控制循环启动 · 控制口 127.0.0.1:$TELNET_PORT · 指令目录 $CMD_DIR"
status

TICK=0
while true; do
  # 1) 处理 app 下的指令
  for req in "$CMD_DIR"/*.req; do
    [ -e "$req" ] || continue
    op="$(field "$req" op)"
    log "收到指令 op=$op"
    result="UNKNOWN"
    case "$op" in
      login)   result="$(do_login "$req")" ;;
      sms)     code="$(field "$req" code)"; rm -f "$req"
               out="$(talk "input_phone_verify_code -code=$code")"
               result="$(step_of "$out")"; apply_step "$result" ;;
      req-sms) rm -f "$req"
               talk "req_phone_verify_code" >/dev/null
               MESSAGE="已请求重发验证码 · 留意手机短信"; result="SENT" ;;
      probe)   rm -f "$req"
               out="$(talk "")"; result="$(step_of "$out")"; apply_step "$result" ;;
      *)       rm -f "$req"; result="UNSUPPORTED" ;;
    esac
    printf 'op=%s\nresult=%s\nts=%s\n' "$op" "$result" "$(date -u +%FT%TZ)" \
      > "${req%.req}.done" 2>/dev/null
    status
  done

  # 2) 每 ~10 秒自己探一次状态(免密恢复 / 掉线都靠它反映到页面上)
  TICK=$((TICK+1))
  if [ $((TICK % 10)) -eq 0 ]; then
    out="$(talk "")"
    [ -n "$out" ] && apply_step "$(step_of "$out")"
    status
  fi

  sleep 1
done
