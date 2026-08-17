#!/usr/bin/env bash
# =========================================================
# 富途 OpenD 网关 launcher · 入口(v1.17)
#
# 做四件事:自检 → (首次)下载+校验+解包 → 生成配置 → 起进程
# 镜像里没有富途任何文件;OpenD 是这里从官网下的,下完先比对 sha256。
#
# 设计要点(都是 2026-08-17 在 beta 上实测出来的):
#  1. 运行 uid 必须在 /etc/passwd 里,否则 OpenD 直接段错误 → 先自检并说人话
#  2. OpenD 忽略 $HOME,按 getpwuid 的家目录建 ~/.com.futunn.FutuOpenD → 卷挂 /home/futu
#  3. 没有 pty 时 OpenD 往 stdout 写 0 字节 → 日志靠后台 tail -F 它自己的 GTWLog
#  4. stdin 给 /dev/null 也能跑住 → 可以 exec 直起(PID 1 = OpenD,docker stop 的 SIGTERM 直达)
#  5. 官方模板把 telnet_ip/telnet_port 整行注释掉了(默认不启用控制口)→ 要取消注释再设值;
#     而那个口没有鉴权,所以地址按死 127.0.0.1
# =========================================================
set -uo pipefail

HOME_DIR="/home/futu"
INSTALL_DIR="$HOME_DIR/opend"
CTL_DIR="${FUTU_CTL_DIR:-/ctl}"
CATALOG="/opt/futu/releases.json"
OS_TAG="${FUTU_OS_TAG:-Ubuntu18.04}"          # 本镜像是 debian 基底,吃 Ubuntu18.04 包
API_PORT="${FUTU_API_PORT:-11111}"
TELNET_PORT="${FUTU_TELNET_PORT:-22222}"
DL_HOST="softwaredownload.futunn.com"
LATEST_ENDPOINT="https://www.futunn.com/download/fetch-lasted-link?name=opend-ubuntu"
ALLOW_UNVERIFIED="${FUTU_ALLOW_UNVERIFIED:-0}"

say(){ printf '%s\n' "$*"; }
die(){ printf '\n✗ %s\n' "$*" >&2; status "phase=ERROR" "message=$*"; exit 1; }

# 把当前状态写给 app(key=value,不用 JSON —— 这个镜像里没有 jq,bash 解析 JSON 会脆)
status(){
  mkdir -p "$CTL_DIR" 2>/dev/null
  { for kv in "$@"; do printf '%s\n' "$kv"; done; printf 'ts=%s\n' "$(date -u +%FT%TZ)"; } > "$CTL_DIR/.status.ep.$$" 2>/dev/null \
    && mv -f "$CTL_DIR/.status.ep.$$" "$CTL_DIR/status" 2>/dev/null
}

# ---------- 1. 自检:说人话,别让用户看到段错误 ----------
UID_NOW="$(id -u)"
if ! getent passwd "$UID_NOW" >/dev/null 2>&1; then
  die "富途网关不能以自定义 uid 运行(当前 uid=$UID_NOW 不在容器的 /etc/passwd 里)。
     OpenD 会用 getpwuid 找家目录,找不到就直接段错误(那个报错完全看不出原因)。
     你大概在 compose 里给这个服务加了 user: —— 去掉它就行,镜像里已经建好专用用户。"
fi
[ -w "$HOME_DIR" ] || die "数据目录 $HOME_DIR 不可写(卷的属主不对?)。
     期望属主 uid=$UID_NOW;OpenD 要往这里写 .com.futunn.FutuOpenD/(它忽略 \$HOME,只认 passwd 里的家目录)。"
mkdir -p "$CTL_DIR" || die "控制目录 $CTL_DIR 不可写 —— app 要通过它给网关下指令。"

status "phase=STARTING" "message=网关容器已启动,检查安装…"

# ---------- 2. 首次启动:下载 + 校验 + 只解命令行版 ----------
# 从清单里取该系统最新一条(约定:新版本追加在 releases 数组末尾)
read_catalog(){
  awk -v want="$OS_TAG" '
    /"os":/      { cur=$0; sub(/.*"os": *"/,"",cur); sub(/".*/,"",cur) }
    /"file":/    { f=$0;   sub(/.*"file": *"/,"",f); sub(/".*/,"",f) }
    /"bytes":/   { b=$0;   gsub(/[^0-9]/,"",b) }
    /"sha256":/  { s=$0;   sub(/.*"sha256": *"/,"",s); sub(/".*/,"",s) }
    /}/          { if (cur==want && f!="" && s!="") { file=f; sha=s; bytes=b }; f=""; s=""; b="" }
    END          { if (file!="") print file"\t"sha"\t"bytes }
  ' "$CATALOG"
}

install_opend(){
  local file sha bytes url tmp got gotbytes
  IFS=$'\t' read -r file sha bytes <<<"$(read_catalog)"

  if [ -n "${file:-}" ]; then
    url="https://$DL_HOST/$file"
    say "· 安装 OpenD:$file(已核对版本 · sha256 ${sha:0:12}…)"
  elif [ "$ALLOW_UNVERIFIED" = "1" ]; then
    # 用户显式同意装我们还没核对过的版本 —— 跟 302 拿官方当前最新
    url="$(curl -sSI "$LATEST_ENDPOINT" | tr -d '\r' | sed -n 's/^[Ll]ocation: *//p' | tail -1)"
    [ -n "$url" ] || die "问不到富途官方的最新版地址(端点没给跳转)。"
    case "$url" in https://$DL_HOST/*) : ;; *) die "官方端点给的地址不在预期域名下:$url";; esac
    file="${url##*/}"; sha=""; bytes=""
    say "· 安装 OpenD:$file(⚠ 未核对版本 · 你已通过 FUTU_ALLOW_UNVERIFIED=1 确认)"
  else
    die "清单里没有 $OS_TAG 的已核对版本($CATALOG)。
     富途官方不公布校验和,所以我们只为核对过的版本背书。
     要装官方当前最新版(我们还没核对过这一版),在 compose 里设 FUTU_ALLOW_UNVERIFIED=1 后重启本容器。"
  fi

  tmp="$HOME_DIR/download.tar.gz"
  status "phase=DOWNLOADING" "message=下载 $file"
  curl -fSL --no-progress-meter --retry 2 --retry-delay 3 -o "$tmp" "$url" || die "下载失败:$url(墙内/网络问题?可改用「上传安装包」那条路)"

  gotbytes="$(stat -c%s "$tmp")"
  if [ -n "${bytes:-}" ] && [ "$bytes" -gt 0 ] && [ "$gotbytes" != "$bytes" ]; then
    rm -f "$tmp"
    die "文件大小和我们核对过的不一样:期望 $bytes 字节,实得 $gotbytes(下载可能不完整)。"
  fi

  got="$(sha256sum "$tmp" | cut -d' ' -f1)"
  if [ -n "${sha:-}" ]; then
    if [ "$got" != "$sha" ]; then
      rm -f "$tmp"
      die "这个安装包和我们核对过的版本不一样,已停止并删除下载的文件。
       期望 sha256=$sha
       实得 sha256=$got
     可能是富途换了包,也可能下载被中间人改过。请不要绕过这个检查。
     交叉验证:curl -sI '$url' | grep -i etag   (COS 的 etag 实测等于文件 MD5)"
    fi
    say "  ✓ sha256 与仓库钉住的值一致"
  else
    say "  ⚠ 这一版没有钉住的哈希,实算 sha256=$got —— 请自己核对后再信任它"
  fi

  status "phase=DOWNLOADING" "message=解包(只取命令行版)"
  mkdir -p "$INSTALL_DIR"
  # 只解服务端需要的部分:445MB 里有 320MB 是桌面 GUI 的 AppImage,服务器上纯属浪费
  tar -xzf "$tmp" -C "$INSTALL_DIR" --strip-components=1 --exclude='*GUI*' --exclude='*.AppImage' \
    || die "解包失败(文件可能不是有效的 OpenD tar.gz)"
  rm -f "$tmp"
  printf '%s\n' "$got" > "$INSTALL_DIR/.sha256"
  printf '%s\n' "$file" > "$INSTALL_DIR/.file"
}

BIN="$(find "$INSTALL_DIR" -maxdepth 3 -type f -name FutuOpenD 2>/dev/null | head -1)"
if [ -z "$BIN" ]; then
  install_opend
  BIN="$(find "$INSTALL_DIR" -maxdepth 3 -type f -name FutuOpenD 2>/dev/null | head -1)"
  [ -n "$BIN" ] || die "解包后找不到 FutuOpenD 可执行文件。"
fi
chmod +x "$BIN" 2>/dev/null
BIN_DIR="$(dirname "$BIN")"
VERSION="$(basename "$BIN_DIR" | sed -n 's/.*Futu_\?OpenD[_-]\([0-9.]*\).*/\1/p')"
say "· OpenD 就位:$BIN(版本 ${VERSION:-未知})"

# ---------- 3. 生成配置(基于包内官方模板) ----------
# api 这侧要绑 0.0.0.0 才能被同 compose 网络里的 app 容器连到(容器不对宿主 publish 端口);
# 控制口那侧按死 127.0.0.1,只在容器内可达。
OFFICIAL="$BIN_DIR/FutuOpenD.xml"
[ -f "$OFFICIAL" ] || die "包内缺少 FutuOpenD.xml(不是完整的 OpenD 安装包?)"
CFG="$HOME_DIR/FutuOpenD.generated.xml"

RSA_KEY="$CTL_DIR/opend.pem"
if [ "${FUTU_API_RSA:-1}" = "1" ] && [ ! -f "$RSA_KEY" ]; then
  # API 通道加密:同一把私钥 app 侧也要用(通过 /ctl 卷共享,600)
  openssl genrsa -out "$RSA_KEY" 2048 2>/dev/null && chmod 600 "$RSA_KEY" \
    && say "· 已生成 API 通道私钥 $RSA_KEY(app 用同一把)"
fi

# 【容器实跑才发现的坑】官方模板里 telnet_ip / telnet_port / rsa_private_key 这几个"进阶参数"
# 是【整行注释掉的示例】。只替换标签内容的话,值改进了注释里 —— OpenD 压根不启用控制口,
# 日志里连「Telnet监听地址」都不会出现,表现是交互登录连不上。所以这三行要【取消注释】再设值。
sed -e "s#<ip>[^<]*</ip>#<ip>0.0.0.0</ip>#" \
    -e "s#<api_port>[^<]*</api_port>#<api_port>$API_PORT</api_port>#" \
    -e "s#<!-- *<telnet_ip>[^<]*</telnet_ip> *-->#<telnet_ip>127.0.0.1</telnet_ip>#" \
    -e "s#<!-- *<telnet_port>[^<]*</telnet_port> *-->#<telnet_port>$TELNET_PORT</telnet_port>#" \
    -e "s#<telnet_ip>[^<]*</telnet_ip>#<telnet_ip>127.0.0.1</telnet_ip>#" \
    -e "s#<telnet_port>[^<]*</telnet_port>#<telnet_port>$TELNET_PORT</telnet_port>#" \
    -e "s#<lang>[^<]*</lang>#<lang>chs</lang>#" \
    "$OFFICIAL" > "$CFG"
if [ "${FUTU_API_RSA:-1}" = "1" ] && [ -f "$RSA_KEY" ]; then
  sed -i "s#<!-- *<rsa_private_key>[^<]*</rsa_private_key> *-->#<rsa_private_key>$RSA_KEY</rsa_private_key>#" "$CFG"
fi
grep -q '<telnet_port>' "$CFG" \
  || die "配置生成异常:控制口没有启用(官方模板结构可能变了),拒绝启动 —— 否则登录不了还看不出原因。"
grep -q '<telnet_ip>127.0.0.1</telnet_ip>' "$CFG" \
  || die "配置生成异常:控制口没有绑到 127.0.0.1,拒绝启动(那个口没有鉴权)。"

# ---------- 4. 日志转发 + 控制循环 + 起进程 ----------
# 没有 pty 时 OpenD 的 stdout 是空的(实测 0 字节),所以 docker logs 要靠 tail 它自己的日志
LOG_DIR="$HOME_DIR/.com.futunn.FutuOpenD/Log"
mkdir -p "$LOG_DIR"
( tail -F -q -n 0 "$LOG_DIR"/GTWLog_*.log 2>/dev/null | sed -u 's/^/[opend] /' ) &

FUTU_CTL_DIR="$CTL_DIR" FUTU_TELNET_PORT="$TELNET_PORT" FUTU_VERSION="${VERSION:-}" \
  /opt/futu/control-loop.sh &

status "phase=INSTALLED" "message=OpenD 启动中" "version=${VERSION:-}" "sha256=$(cat "$INSTALL_DIR/.sha256" 2>/dev/null || echo '')"

cd "$BIN_DIR"
say "· 启动 OpenD(PID 1;docker stop 的 SIGTERM 直达进程)"
exec "$BIN" -cfg_file="$CFG" < /dev/null
