#!/usr/bin/env bash
# 家庭账房 · v0.7 · 一键起(自检环境 → 生成 .env → 拉/构建镜像 → up → 验 /health)
# 目标:macOS / Linux 上不管哪种 docker 装法(Docker Desktop / OrbStack / colima / 原生 engine)
#       都能一条命令跑通,卡住时给可直接复制的修复命令,而不是吐底层报错。
# 用法:在仓库根目录 `bash deploy/docker-up.sh`
set -euo pipefail
cd "$(dirname "$0")/.."   # 仓库根

say(){ printf '%s\n' "$*"; }
die(){ printf '\n✗ %s\n' "$*" >&2; exit 1; }

# 国内镜像源(免登录公共加速,实测 2026-06 可用)。注:GHCR(我们自己的 app 镜像)大陆能直连,
# 只有 Docker Hub 的 mysql 基础镜像需要走镜像源 —— 所以这里只为兜 Docker Hub 被墙。
DAEMON_JSON="${FINANCE_DAEMON_JSON:-/etc/docker/daemon.json}"   # 可用环境变量覆盖(便于测试 / Docker Desktop)
MIRRORS_JSON='{ "registry-mirrors": ["https://docker.m.daocloud.io", "https://docker.1ms.run"] }'

# 数据库镜像双源(v1.6.21)。原先只写死 mysql:8.0 → 大陆新手 100% 卡在 Docker Hub 上,
# 而修复要他手改 Docker 引擎配置,对非技术用户就是死路。现在:
#   ① 默认拉 GHCR 上我们镜像的同一份 mysql(GHCR 大陆直连,和 app 镜像同一个源)
#   ② 拉不到再退官方 Docker Hub(海外/已配镜像源的机器走这条)
#   ③ 两条都不通才去配镜像源(且由脚本代劳,见 cn_autofix_mirrors)
DB_MIRROR="${FINANCE_DB_MIRROR:-ghcr.io/luodi-nate/financial-management-mysql:8.0}"
DB_UPSTREAM="mysql:8.0"

# 带超时跑命令(timeout 在 macOS 默认没有,有 gtimeout 用 gtimeout,都没有就直接跑)
_to(){ local s="$1"; shift
  if command -v timeout >/dev/null 2>&1; then timeout "$s" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then gtimeout "$s" "$@"
  else "$@"; fi; }
pull_one(){ _to 50 docker pull "$1" >/dev/null 2>&1; }

# 生成 .env(随机 DB/root/REMEMBER_ME_KEY)· 幂等:已存在直接返回。
# v0.x 起 .env 生成内联到本脚本(原 deploy/docker-init.sh 已删),让 Docker 渠道只有这一个入口。
ensure_env(){
  [[ -f .env ]] && return 0
  [[ -f .env.example ]] || die "找不到 .env.example,确认在仓库根目录跑本脚本。"
  local dbp rootp rmk
  dbp="$(openssl rand -hex 18)"; rootp="$(openssl rand -hex 18)"; rmk="$(openssl rand -hex 32)"
  cp .env.example .env
  # 跨平台 sed(GNU/BSD 都用 -i.bak 再删)
  sed -i.bak \
    -e "s|^DB_PASS=.*|DB_PASS=${dbp}|" \
    -e "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=${rootp}|" \
    -e "s|^REMEMBER_ME_KEY=.*|REMEMBER_ME_KEY=${rmk}|" \
    .env
  rm -f .env.bak
  chmod 600 .env
}

# Docker Hub 拉不动时的处置。顺序:先「脚本代劳配好镜像源并重启引擎」(征得同意),
# 只有代劳不成才退回打印手动指引 —— 让不懂电脑的用户手改 Docker 引擎配置等于劝退(v1.6.21)。
# 注:registry-mirrors 只对 Docker Hub 生效,正好兜 mysql;GHCR 上我们的镜像大陆直连、不受影响。
cn_hub_blocked_guide(){
  local what="${1:-数据库镜像}"       # 同一处置也服务 JDK 基础镜像分支,别把文案写死成数据库
  say ""
  say "  ⚠ 镜像拉不动(${what})—— 这是中国大陆访问 Docker Hub 被限速/阻断的典型表现。"
  say "    (registry-mirrors 只对 Docker Hub 生效,正好兜它;GHCR 上我们的镜像大陆能直连。)"
  if cn_autofix_mirrors; then say "  · 镜像源已配好,重试拉取…"; return 0; fi
  say ""
  if [[ "$(uname -s)" == "Darwin" ]]; then _cn_guide_mac; else _cn_guide_linux; fi
  say ""
}

# ── 镜像源自动配置(v1.6.21)────────────────────────────────────────
# 返回 0 = 已改配置且引擎重启完;1 = 没动用户机器(不认识的装法 / 用户拒绝 / 非交互)。
# 三种引擎的配置落点完全不同,必须分流:
#   · colima   → 引擎在 VM 里,写 VM 内的 /etc/docker/daemon.json(顺带尽力持久化到 colima.yaml)
#   · Desktop  → 读宿主 ~/.docker/daemon.json,改完要重启 Docker.app
#   · Linux 原生 → /etc/docker/daemon.json + systemctl restart docker
# OrbStack 不自动改(配置机制不稳定,宁可退回手动指引,也不要写坏用户的引擎配置)。
cn_autofix_mirrors(){
  local kind=""
  if [[ "$(uname -s)" == "Darwin" ]]; then
    if command -v colima >/dev/null 2>&1 && colima status >/dev/null 2>&1; then kind=colima
    elif [[ -d /Applications/Docker.app ]] && command -v osascript >/dev/null 2>&1; then kind=desktop
    fi
  elif command -v systemctl >/dev/null 2>&1; then
    kind=linux
  fi
  [[ -n "$kind" ]] || return 1

  # 同意闸门:改用户机器的引擎配置必须问一次。没有 tty 且没显式 FINANCE_ASSUME_YES → 不动。
  local ans
  if [[ -n "${FINANCE_ASSUME_YES:-}" ]]; then ans=y
  elif [[ -t 0 || -e /dev/tty ]]; then
    say ""
    printf '  要我现在自动配好镜像源并重启 Docker 吗?(本机其它容器会中断十几秒)[Y/n] '
    read -r ans </dev/tty || ans=""
    [[ -z "$ans" ]] && ans=y
  else
    return 1
  fi
  [[ "$ans" =~ ^[Yy] ]] || return 1

  case "$kind" in
    colima)  _mirror_colima  ;;
    desktop) _mirror_desktop ;;
    linux)   _mirror_linux   ;;
  esac
}

# 等引擎回来。先等它「掉下去」再等「起回来」—— 直接轮询 docker info 会命中重启前的老 daemon 而误判成功。
_wait_engine(){
  local budget="${1:-90}" i=0
  while [[ $i -lt 8 ]]; do docker info >/dev/null 2>&1 || break; sleep 1; i=$((i+1)); done
  i=0
  while [[ $i -lt $budget ]]; do
    docker info >/dev/null 2>&1 && { say "  ✓ 引擎已就绪"; return 0; }
    sleep 2; i=$((i+2))
  done
  say "  ✗ 等引擎回来超时(${budget}s)"; return 1
}

_mirror_colima(){
  say "  · colima:写入虚拟机内的 /etc/docker/daemon.json…"
  colima ssh -- sudo cp /etc/docker/daemon.json /etc/docker/daemon.json.bak >/dev/null 2>&1 || true
  printf '%s\n' "$MIRRORS_JSON" | colima ssh -- sudo tee /etc/docker/daemon.json >/dev/null 2>&1 \
    || { say "  ✗ 写不进 colima 虚拟机(colima ssh 失败)"; return 1; }
  # 尽力持久化:colima 会在 start 时按 colima.yaml 的 docker: 段重写 VM 里的 daemon.json,
  # 所以默认的空 `docker: {}` 要一并填上,否则下次 colima restart 会把上面的写入抹掉。
  # 只在该行「恰好是空映射」时改,形状不符就不碰(宁可少做,不要写坏用户 yaml)。
  local yml="$HOME/.colima/default/colima.yaml"
  if [[ -f "$yml" ]] && grep -qx 'docker: {}' "$yml"; then
    cp "$yml" "$yml.bak" 2>/dev/null || true
    _yml="$yml" python3 - <<'PY' 2>/dev/null || true
import os
p = os.environ['_yml']
s = open(p).read()
block = ('docker:\n'
         '  registry-mirrors:\n'
         '    - https://docker.m.daocloud.io\n'
         '    - https://docker.1ms.run\n')
open(p, 'w').write(s.replace('docker: {}\n', block, 1))
PY
  fi
  # 优先在 VM 内重启 docker(保住刚写的 daemon.json);不行才整机 colima restart
  colima ssh -- sudo systemctl restart docker >/dev/null 2>&1 \
    || colima ssh -- sudo service docker restart >/dev/null 2>&1 \
    || { say "  · VM 内重启失败,改用 colima restart(约 1 分钟)…"; colima restart >/dev/null 2>&1 || true; }
  _wait_engine 120
}

_mirror_desktop(){
  local f="${FINANCE_DESKTOP_DAEMON_JSON:-$HOME/.docker/daemon.json}"
  mkdir -p "$(dirname "$f")"
  if [[ -s "$f" ]]; then
    if grep -q 'registry-mirrors' "$f"; then
      say "  · $f 里已有 registry-mirrors —— 不覆盖(可能是别的源不通),按下面指引自己调"
      return 1
    fi
    command -v python3 >/dev/null 2>&1 \
      || { say "  · $f 已有内容且本机没 python3,不敢机器合并 JSON —— 按下面指引手动加"; return 1; }
    cp "$f" "$f.bak" 2>/dev/null || true
    _f="$f" python3 - <<'PY' || { say "  ✗ 合并 $f 失败(JSON 可能不合法),已保留原文件"; return 1; }
import json, os
p = os.environ['_f']
d = json.load(open(p))
d['registry-mirrors'] = ["https://docker.m.daocloud.io", "https://docker.1ms.run"]
json.dump(d, open(p, 'w'), indent=2, ensure_ascii=False)
PY
  else
    printf '%s\n' "$MIRRORS_JSON" > "$f"
  fi
  say "  ✓ 已写 $f,重启 Docker Desktop(约 30-60 秒)…"
  osascript -e 'quit app "Docker"' >/dev/null 2>&1 || true
  sleep 3
  open -a Docker >/dev/null 2>&1 || true
  _wait_engine 120
}

_mirror_linux(){
  local SUDO="" SUDOE=""; [[ $(id -u) -ne 0 ]] && SUDO="sudo"
  [[ -z "$SUDO" ]] || command -v sudo >/dev/null 2>&1 || { say "  · 需要 root 权限但没有 sudo"; return 1; }
  # 非 root 才带 sudo -E(-E 让 _f 环境变量穿透);root 下 SUDOE 为空,不能留个裸 -E 当命令名
  [[ -n "$SUDO" ]] && SUDOE="$SUDO -E"
  if [[ -s "$DAEMON_JSON" ]]; then
    grep -q 'registry-mirrors' "$DAEMON_JSON" \
      && { say "  · $DAEMON_JSON 里已有 registry-mirrors —— 不覆盖,按下面指引自己调"; return 1; }
    command -v python3 >/dev/null 2>&1 \
      || { say "  · $DAEMON_JSON 已有内容且本机没 python3,不敢机器合并 JSON —— 按下面指引手动加"; return 1; }
    $SUDO cp "$DAEMON_JSON" "$DAEMON_JSON.bak" 2>/dev/null || true
    _f="$DAEMON_JSON" $SUDOE python3 - <<'PY' || { say "  ✗ 合并 $DAEMON_JSON 失败(JSON 可能不合法),已保留原文件"; return 1; }
import json, os
p = os.environ['_f']
d = json.load(open(p))
d['registry-mirrors'] = ["https://docker.m.daocloud.io", "https://docker.1ms.run"]
json.dump(d, open(p, 'w'), indent=2, ensure_ascii=False)
PY
  else
    $SUDO mkdir -p "$(dirname "$DAEMON_JSON")"
    printf '%s\n' "$MIRRORS_JSON" | $SUDO tee "$DAEMON_JSON" >/dev/null
  fi
  say "  ✓ 已写 $DAEMON_JSON,重启 Docker…"
  if [[ -n "${FINANCE_DOCKER_RESTART:-}" ]]; then eval "$FINANCE_DOCKER_RESTART"
  else $SUDO systemctl restart docker >/dev/null 2>&1 || true; fi
  _wait_engine 90
}

# Linux:纯手动指引(自动路径见 _mirror_linux)
_cn_guide_linux(){
  say "    修复:把下面这段写进 ${DAEMON_JSON}"
  say "    (已有该文件就把 registry-mirrors 这段并进去,别覆盖其它配置):"
  say ""
  say "      ${MIRRORS_JSON}"
  say ""
  say "    然后重启:sudo systemctl restart docker,再重跑 bash deploy/docker-up.sh"
}

# macOS:引擎在虚拟机里,按装法分别配(不碰 VM 配置,只给精确步骤)
_cn_guide_mac(){
  say "    Mac 的 Docker 引擎跑在虚拟机里(不读宿主的 /etc/docker/daemon.json)。"
  say "    上面的自动配置没能用上(OrbStack / 你选了不改 / 已有别的镜像源配置),按你的装法手动配一种:"
  say ""
  if command -v colima >/dev/null 2>&1; then
    say "    · colima:编辑 ~/.colima/default/colima.yaml,在 docker: 段加 registry-mirrors —"
    say "          docker:"
    say "            registry-mirrors:"
    say "              - https://docker.m.daocloud.io"
    say "              - https://docker.1ms.run"
    say "      然后 colima restart(约 1-2 分钟),再重跑本脚本。"
    say ""
  fi
  if command -v orb >/dev/null 2>&1; then
    say "    · OrbStack:运行 orb config docker,把 registry-mirrors 加进去 —"
    say "          ${MIRRORS_JSON}"
    say "      存盘后 orb restart docker。"
    say ""
  fi
  say "    · Docker Desktop:Settings → Docker Engine,把 registry-mirrors 并进 JSON —"
  say "          ${MIRRORS_JSON}"
  say "      然后 Apply & Restart。"
  say ""
  say "    配好后重跑:bash deploy/docker-up.sh"
}

# ── 1. docker 在不在 ───────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || die "没装 docker。任选其一装好后重跑本脚本:
  · macOS:Docker Desktop  https://www.docker.com/products/docker-desktop/
          或 OrbStack(更轻)https://orbstack.dev
          或 colima(纯命令行):brew install colima docker docker-compose && colima start
  · Linux:curl -fsSL https://get.docker.com | sh  (装完 sudo usermod -aG docker \$USER 后重登)"

# ── 2. 引擎(daemon)起没起 ─────────────────────────────────────────
# Mac 上 docker 引擎跑在一个小 Linux 虚拟机里,要单独装/起;`brew install docker` 只装了命令行,没引擎。
if ! docker info >/dev/null 2>&1; then
  if command -v colima >/dev/null 2>&1; then
    die "Docker 引擎没在运行。你已装 colima,启动它再重跑就行:

    colima start                 # 第一次约 1-2 分钟
    bash deploy/docker-up.sh

  (若你用的是 Docker Desktop / OrbStack:打开那个 App,等它就绪)"
  else
    die "Docker 引擎没在运行。

  原因:你用 brew 装的是 docker「命令行」,但 Mac 上还缺一个「引擎」——
  Docker 引擎其实跑在一个小 Linux 虚拟机里,brew install docker 不含它。

  照着敲(命令行引擎 colima,最省事):

    brew install colima docker-compose
    colima start                 # 第一次约 1-2 分钟,起引擎
    bash deploy/docker-up.sh

  或者装带界面的:brew install orbstack(或 Docker Desktop),打开 App 等就绪,即可跳过上面命令。"
  fi
fi

# ── 3. 选 compose 命令(强制 V2;本项目 compose 文件是无 version: 的 V2 写法,V1 解析不了)──
DC=""
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  ver="$(docker-compose version --short 2>/dev/null || true)"
  case "$ver" in
    2.*|v2.*) DC="docker-compose" ;;
    *) die "只找到老版 docker-compose ${ver:-(V1)} —— 已停止维护,解析不了本项目的 compose 文件。
  装 Compose V2 插件(macOS):
    brew install docker-compose
    mkdir -p ~/.docker/cli-plugins
    ln -sfn \"\$(brew --prefix)/opt/docker-compose/bin/docker-compose\" ~/.docker/cli-plugins/docker-compose
  之后 \`docker compose version\` 应显示 v2.x,再重跑本脚本。" ;;
  esac
fi
[[ -n "$DC" ]] || die "没有可用的 Compose V2 命令。
  · Docker Desktop / OrbStack 自带,确认装好且在运行(\`docker compose version\` 应有输出)
  · Homebrew 装的纯 docker CLI:\`brew install docker-compose\` 再软链到 ~/.docker/cli-plugins/(见 deploy/README.md)"

say "✓ 环境就绪 · 使用 \`$DC\`"

# ── 4. .env(没有就生成随机密钥)────────────────────────────────────
if [[ ! -f .env ]]; then
  say "· 没有 .env,生成中(随机 DB 密码 / root 密码 / REMEMBER_ME_KEY)…"
  ensure_env
  say "  ✓ 已生成 .env"
fi

# ── 5. 镜像:先定数据库镜像(双源)→ 再拉 app 预构建 → 拉不到才本地构建 ──
# 用户显式指定优先(环境变量 或 .env 里的 MYSQL_IMAGE);否则 GHCR 副本 → Docker Hub 顺序探。
if [[ -z "${MYSQL_IMAGE:-}" ]]; then
  MYSQL_IMAGE="$(grep -E '^MYSQL_IMAGE=' .env 2>/dev/null | cut -d= -f2- || true)"
fi
if [[ -n "${MYSQL_IMAGE:-}" ]]; then
  export MYSQL_IMAGE
  say "· 数据库镜像:沿用你指定的 ${MYSQL_IMAGE}"
else
  say "· 准备数据库镜像(先试 GHCR 副本,大陆直连)…"
  if pull_one "$DB_MIRROR"; then
    export MYSQL_IMAGE="$DB_MIRROR"; say "  ✓ GHCR 副本"
  elif pull_one "$DB_UPSTREAM"; then
    export MYSQL_IMAGE="$DB_UPSTREAM"; say "  ✓ Docker Hub 官方镜像"
  else
    cn_hub_blocked_guide
    if   pull_one "$DB_MIRROR";   then export MYSQL_IMAGE="$DB_MIRROR"
    elif pull_one "$DB_UPSTREAM"; then export MYSQL_IMAGE="$DB_UPSTREAM"
    else die "数据库镜像两个源都拉不下来(${DB_MIRROR} / ${DB_UPSTREAM})。
  网络恢复、或按上面指引配好镜像源后,重跑 bash deploy/docker-up.sh。"
    fi
    say "  ✓ 已拉到 ${MYSQL_IMAGE}"
  fi
  # 定下来就记进 .env —— 否则用户之后自己敲 docker compose 时会又去撞那个不通的源。
  grep -q '^MYSQL_IMAGE=' .env 2>/dev/null || printf 'MYSQL_IMAGE=%s\n' "$MYSQL_IMAGE" >> .env
fi

say "· 准备 app 镜像(优先拉预构建,首次约几分钟)…"
if $DC pull >/dev/null 2>&1; then
  $DC up -d
else
  # db 镜像上面已确认能拉 → 这里缺的只是预构建 app 镜像(如尚未发版)→ 本地源码构建。
  # 但本地构建要从 Docker Hub 拉 JDK 基础镜像,大陆同样会卡,所以先探一下再决定。
  say "  没有预构建 app 镜像,改为本地源码构建(首次约几分钟)…"
  if ! pull_one eclipse-temurin:21-jre; then
    say "  · 本地构建要用 Docker Hub 上的 JDK 基础镜像,现在拉不动 —"
    cn_hub_blocked_guide "JDK 基础镜像"
    pull_one eclipse-temurin:21-jre \
      || die "JDK 基础镜像仍拉不下来,没法本地构建。按上面指引配好镜像源后重跑 bash deploy/docker-up.sh。"
  fi
  $DC up -d --build
fi

# ── 6. 等就绪 + 验 /health ─────────────────────────────────────────
PORT="$(grep -E '^SERVER_PORT=' .env | cut -d= -f2 || true)"; PORT="${PORT:-20000}"
say "· 等应用就绪(最多 ~90s)…"
ok=""
if command -v curl >/dev/null 2>&1; then
  for _ in $(seq 1 45); do
    curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1 && { ok=1; break; }
    sleep 2
  done
else
  say "  (没装 curl,跳过自动探测)"; ok="skip"
fi

# 首次登录账号(种子用户 diwa / wangergou;临时密码可在 .env 用 SEED_ADMIN_PASSWORD 自定义)
SEEDPW="$(grep -E '^SEED_ADMIN_PASSWORD=' .env | cut -d= -f2 || true)"; SEEDPW="${SEEDPW:-demo1234}"
login_hint(){
  say ""
  say "  ── 首次登录 ──────────────────────────────"
  say "   用户名:diwa   (或 wangergou)"
  say "   密  码:${SEEDPW}   ← 首次登录后会要求你改密"
  say "  ──────────────────────────────────────────"
}

if [[ "$ok" == "1" ]]; then
  say ""
  say "✓ 起好了 → http://127.0.0.1:${PORT}  (默认只发布到 loopback,公网请前置反代加 HTTPS)"
  login_hint
  say "  停:$DC down(不删数据卷,数据还在)   日志:$DC logs -f app"
elif [[ "$ok" == "skip" ]]; then
  say ""
  say "✓ 容器已起 → http://127.0.0.1:${PORT}  · 浏览器自行确认"
  login_hint
  say "  停:$DC down   日志:$DC logs -f app"
else
  die "应用 90s 内没就绪。看日志定位(常见:DB 还在初始化 / 端口被占):
  $DC logs --tail=80 app db"
fi
