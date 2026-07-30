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

# ── 版本可见性(v1.6.25)────────────────────────────────────────────
# 起因:用户 git pull 后重跑本脚本,拿到的仍是旧版本,而**脚本从头到尾不说跑的是哪一版**
# (版本徽记只在登录后的 nav 里),于是"静默拿到旧版"无法自查。现在:
#   ① 起之前读一次版本、起之后再读一次 → 明确打印「vA → vB 已更新」或「已是 vB,无变化」;
#   ② 和 GitHub 上最新 release 比 → 落后就说清楚,尤其区分「已经最新」和「镜像还在构建」
#      (打 tag 后 CI 约 12 分钟才推出镜像,用户一看到发版消息就来更新必然拿到旧的 —— 就是这个坑)。
# 关掉联网检查:FINANCE_NO_UPDATE_CHECK=1(脚本本来就要联网拉镜像,查一个 tag 不新增暴露,但给开关)
running_version(){   # 读 /health 的 version;读不到输出空
  local port="$1"
  command -v curl >/dev/null 2>&1 || return 0
  _to 6 curl -fsS "http://127.0.0.1:${port}/health" 2>/dev/null \
    | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}
# 最新**能真的拉到的镜像**版本 —— 直接问 GHCR。这才是"我能更新到什么"的权威来源。
# 为什么不只问 GitHub release(v1.6.26 实测踩到):release 已经发布了,但 CI 构建失败 →
# GHCR 上没有那个镜像,用户怎么拉都拉不到。拿 release 去比会告诉他"有新版",然后他更新不了。
# 顺带:大陆直连 GHCR 比 api.github.com 稳得多(用户那次就是 GitHub API 没通 → 我这条检查静默了)。
latest_image_tag(){
  [[ -z "${FINANCE_NO_UPDATE_CHECK:-}" ]] || return 0
  command -v curl >/dev/null 2>&1 || return 0
  local repo="luodi-nate/financial-management" tk
  tk="$(_to 8 curl -fsS "https://ghcr.io/token?scope=repository:${repo}:pull&service=ghcr.io" 2>/dev/null \
        | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
  [[ -n "$tk" ]] || return 0
  _to 10 curl -fsS -H "Authorization: Bearer ${tk}" "https://ghcr.io/v2/${repo}/tags/list" 2>/dev/null \
    | tr ',' '\n' | sed -n 's/.*"v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)".*/\1/p' \
    | sort -t. -k1,1n -k2,2n -k3,3n | tail -1
}

# a > b ?(语义版本数值比较)
ver_gt(){ [[ "$1" != "$2" ]] && [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)" == "$1" ]]; }

latest_release_tag(){   # GitHub 最新 release 的 tag;失败或被关闭时输出空
  [[ -z "${FINANCE_NO_UPDATE_CHECK:-}" ]] || return 0
  command -v curl >/dev/null 2>&1 || return 0
  _to 8 curl -fsS -H 'Accept: application/vnd.github+json' \
    https://api.github.com/repos/LuoDi-Nate/financial-management/releases/latest 2>/dev/null \
    | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

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

# 起之前先记下"现在跑的是哪一版"(可能没在跑 → 空,那就是首装)
PORT_PRE="$(grep -E '^SERVER_PORT=' .env 2>/dev/null | cut -d= -f2 || true)"; PORT_PRE="${PORT_PRE:-20000}"
VER_BEFORE="$(running_version "$PORT_PRE" || true)"
[[ -n "$VER_BEFORE" ]] && say "· 当前在跑 v${VER_BEFORE}(准备检查更新)"

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

# ── 数据库凭据自愈(v1.6.22)────────────────────────────────────────
# 真实上手失败:用户重新下载仓库 → .env 里生成了新随机密码,但**命名卷还是老的**。
# MySQL 只在第一次初始化数据卷时写入账号密码,之后换 .env 不会同步进去 → app 一直 1045 崩溃重启。
# 当时三处判据全给了假阳性(compose healthcheck / entrypoint 的 mysqladmin ping / FRESH_DB 探测),
# 用户只看到「90s 没就绪」+ 一屏 Access denied,完全不知道该干什么。
# 现在:主动验一次账号,不通就讲清原因,并**在不删数据的前提下**把新密码同步进已有库。
envval(){ grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2- || true; }   # 键缺失时输出空、退出 0(pipefail 下不拖累调用处)

# 从 db 容器内用真实查询验账号(不能用 mysqladmin ping —— 它在 Access denied 时也返回 0)
db_auth_ok(){ $DC exec -T db env MYSQL_PWD="$2" mysql -h127.0.0.1 -u"$1" -sN -e 'SELECT 1' >/dev/null 2>&1; }

# root 也要验:db 的健康检查用的是 root,而 app 的 depends_on 读健康检查。
# 只验 finance 会漏掉「finance 密码对、root 密码不对」——那时 db 永远 unhealthy、app 永远起不来,
# 而自愈却不触发(这是把健康检查改严之后我自己引入的缺口)。
db_root_ok(){ $DC exec -T db env MYSQL_PWD="$1" mysql -h127.0.0.1 -uroot -sN -e 'SELECT 1' >/dev/null 2>&1; }

# 用 mysqld --init-file 把 .env 里的密码写进已有数据卷 —— 这是 MySQL 官方的密码重置手法:
# init 文件由服务器在启动时以最高权限执行,**不需要旧密码**,也**不动任何业务数据**。
resync_db_credentials(){
  local dbu="$1" dbp="$2" dbn="$3" rootp="$4" sql ok="" i
  sql="$(mktemp /tmp/finance-pwfix.XXXXXX)" || return 1
  {
    [[ -n "$rootp" ]] && { printf "ALTER USER IF EXISTS 'root'@'localhost' IDENTIFIED BY '%s';\n" "$rootp"
                           printf "ALTER USER IF EXISTS 'root'@'%%' IDENTIFIED BY '%s';\n" "$rootp"; }
    printf "CREATE USER IF NOT EXISTS '%s'@'%%' IDENTIFIED BY '%s';\n" "$dbu" "$dbp"
    printf "ALTER USER '%s'@'%%' IDENTIFIED BY '%s';\n" "$dbu" "$dbp"
    printf "CREATE DATABASE IF NOT EXISTS \`%s\` CHARACTER SET utf8mb4;\n" "$dbn"
    printf "GRANT ALL PRIVILEGES ON \`%s\`.* TO '%s'@'%%';\n" "$dbn" "$dbu"
    printf "FLUSH PRIVILEGES;\n"
  } > "$sql"
  chmod 644 "$sql"
  say "  · 同步密码中(临时以恢复模式起一次数据库,不动数据)…"
  $DC stop db >/dev/null 2>&1 || true
  docker rm -f finance-pwfix >/dev/null 2>&1 || true
  $DC run -d --rm --name finance-pwfix -v "$sql":/pwfix.sql:ro db mysqld --init-file=/pwfix.sql >/dev/null 2>&1 \
    || { say "  ✗ 恢复模式起不来"; rm -f "$sql"; return 1; }
  for i in $(seq 1 45); do
    docker exec finance-pwfix env MYSQL_PWD="$dbp" mysql -h127.0.0.1 -u"$dbu" -sN -e 'SELECT 1' >/dev/null 2>&1 \
      && { ok=1; break; }
    sleep 2
  done
  docker stop finance-pwfix >/dev/null 2>&1 || true
  rm -f "$sql"
  [[ -n "$ok" ]] || { say "  ✗ 密码没同步成功"; return 1; }
  say "  ✓ 密码已同步进已有数据库(数据未动)"
  # 这里 up 失败先不判死:健康检查要几秒才翻绿,外层还会再等再验
  $DC up -d >/dev/null 2>&1 || say "  · 重新起服务时报了错,继续等健康检查"
  return 0
}

ensure_db_credentials(){
  local dbu dbp dbn rootp i ans
  dbu="$(envval DB_USER)"; dbu="${dbu:-finance}"
  dbp="$(envval DB_PASS)"; dbn="$(envval DB_NAME)"; dbn="${dbn:-finance}"
  rootp="$(envval MYSQL_ROOT_PASSWORD)"
  [[ -n "$dbp" ]] || return 0
  # 先等数据库端口活过来(这一步只问「有没有应答」,不问密码)
  for i in $(seq 1 45); do
    $DC exec -T db mysqladmin ping -h127.0.0.1 --silent >/dev/null 2>&1 && break
    sleep 2
  done
  local who=""
  db_auth_ok "$dbu" "$dbp" || who="$dbu"
  if [[ -z "$who" && -n "$rootp" ]]; then
    db_root_ok "$rootp" || who="root"      # root 不对 → db 永远 unhealthy → app 起不来
  fi
  [[ -n "$who" ]] || { say "  ✓ 数据库账号已验证(${dbu} + root)"; return 0; }

  say ""
  say "  ⚠ 数据库起来了,但 .env 里的密码进不去(Access denied for user '${who}')。"
  say "    原因:MySQL 的账号密码**只在第一次创建数据卷时**写入。你之前跑过一次(或重新下载过本仓库、"
  say "    .env 里换了新的随机密码),而数据卷不会随仓库目录一起消失 —— 卷里还是老密码。"
  say ""
  say "    我可以把新密码同步进那个已有数据库,**不会删任何数据**。"
  if [[ -n "${FINANCE_ASSUME_YES:-}" ]]; then ans=y
  elif [[ -t 0 || -e /dev/tty ]]; then
    printf '    现在就修吗?[Y/n] '; read -r ans </dev/tty || ans=""; [[ -z "$ans" ]] && ans=y
  else ans=n; fi

  if [[ "$ans" =~ ^[Yy] ]] && resync_db_credentials "$dbu" "$dbp" "$dbn" "$rootp"; then
    for i in $(seq 1 45); do
      if db_auth_ok "$dbu" "$dbp" && { [[ -z "$rootp" ]] || db_root_ok "$rootp"; }; then return 0; fi
      sleep 2
    done
    return 0
  fi

  # 同步不成(或用户不要)→ 给两条真实出路,并且**不擅自删数据**
  # v1.6.26 修:这里原来把 `down -v` 作为并列的第二条出路,还写着"你从没真正用过就可以整卷删掉"。
  # 用户凭记忆判断"我没什么数据"很容易判错(刚建过成员、改过密码就已经是数据了),而删卷不可恢复。
  # 现在:**先给两条都不丢数据的出路**,删卷降到第三条并要求明确确认。
  say ""
  say "  三条出路,按「越靠前越安全」排:"
  say "    ① 放回旧密码(最稳,数据完整保留)"
  say "       你还留着以前那份 .env,或记得旧的 DB_PASS / MYSQL_ROOT_PASSWORD → 改回旧值,重跑本脚本。"
  say "    ② 保住旧卷,换个新项目名从零开始(旧数据留着,以后随时能捞回来)"
  say "         COMPOSE_PROJECT_NAME=finance-new bash deploy/docker-up.sh"
  say "       旧卷原封不动待在那儿($DC 的卷名带项目名前缀),想回去就用回原来的项目名/目录名。"
  say "    ③ 真的确定旧库不要了,才删卷重来:"
  say "         $DC down -v && bash deploy/docker-up.sh"
  say "       ⚠ **这会永久删除那个库里的一切,包括你已经建好的成员、账户、账期、改过的密码 —— 不可恢复。**"
  say "       判断标准不是「我印象里没用过」,而是:你有没有登录进去建过成员 / 账户?建过就走 ① 或 ②。"
  die "数据库账号进不去,已停在这一步(**没有动你的数据**)。按上面三条之一处理后重跑本脚本。"
}

say "· 准备 app 镜像(优先拉预构建,首次约几分钟)…"
if $DC pull >/dev/null 2>&1; then
  # 注意 `|| UP_FAILED=1`:db 的健康检查现在是真实查询,root 密码不匹配时 db 会 unhealthy,
  # 于是 app 的 depends_on: service_healthy 不满足、`up -d` 直接非零退出 —— 而这恰恰是最需要自愈的场景。
  # 若在这里就被 set -e 打断,用户又只能看到一句 docker 的原始报错。
  UP_FAILED=""
  $DC up -d || UP_FAILED=1
  # docker 自己那句报错(如 dependency failed to start: container ... is unhealthy)对普通用户只是惊吓,
  # 先安抚一句再去查真因 —— 否则用户以为已经失败、直接关窗口了。
  [[ -z "$UP_FAILED" ]] || say "  (上面这条 docker 报错先别管,我来看看到底卡在哪 …)"
  ensure_db_credentials
  [[ -z "$UP_FAILED" ]] || $DC up -d
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
  UP_FAILED=""
  $DC up -d --build || UP_FAILED=1
  ensure_db_credentials
  [[ -z "$UP_FAILED" ]] || $DC up -d
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

# 全新空库告知(v1.6.26)· 起因:用户报「更新后多了 Alice/Bob、旧账户被刷掉」——
# 复盘是**换了/删了数据卷**导致连到一个全新库(V1 用裸 CREATE TABLE,迁移不可能在已有库上重放,
# 所以"Alice/Bob 出现"只可能是全新库跑了 V2 种子)。而全新库这件事**只写在容器日志里**
# (`[entrypoint] FRESH_DB=yes`),脚本输出一个字都没提 → 用户直到发现数据没了才知道。
# 这里在**用户看得见的地方**说出来,并给出"我的数据是不是在另一个卷里"的自查命令。
fresh_db_notice(){
  local mem acc pwdone
  mem="$($DC exec -T db env MYSQL_PWD="$1" mysql -h127.0.0.1 -u"$2" "$3" -sN \
        -e 'SELECT COUNT(*) FROM member' 2>/dev/null || true)"
  [[ "$mem" =~ ^[0-9]+$ ]] || return 0     # 读不到就别猜
  acc="$($DC exec -T db env MYSQL_PWD="$1" mysql -h127.0.0.1 -u"$2" "$3" -sN \
        -e 'SELECT COUNT(*) FROM account' 2>/dev/null || echo -1)"
  pwdone="$($DC exec -T db env MYSQL_PWD="$1" mysql -h127.0.0.1 -u"$2" "$3" -sN \
        -e 'SELECT COUNT(*) FROM member WHERE must_change_pw = 0' 2>/dev/null || echo -1)"
  # "全新" = 只有两个种子成员、零账户、且没人改过密码(= 从没被真人用过)
  [[ "$mem" == "2" && "$acc" == "0" && "$pwdone" == "0" ]] || return 0
  say ""
  say "  ⚠ 这个数据库是**全新空库**(只有 2 个内置成员、0 个账户、没人登录改过密码)。"
  say "    首次安装本该如此 —— 但**如果你之前在这台机器上已经录过数据**,那说明现在连的不是原来那个数据卷:"
  say "      docker volume ls | grep db-data      # 看看是不是存在另一个 *_db-data 卷"
  say "    常见原因:仓库目录名变了(compose 项目名跟着变 → 换了新卷)· 或者执行过 down -v。"
  say "    旧卷还在的话数据没丢:用原来的目录名、或 COMPOSE_PROJECT_NAME=<原项目名> 重跑本脚本即可回到旧数据。"
}

# 版本结论(v1.6.25)· 这段就是为了不让"静默拿到旧版"再发生
version_verdict(){
  local now img rel
  now="$(running_version "$PORT" || true)"
  img="$(latest_image_tag || true)"                      # 最新可拉镜像(权威:GHCR)
  rel="$(latest_release_tag || true)"; rel="${rel#v}"    # GitHub 最新 release(镜像可能还没出)

  # ── 本地这次跑起来的版本有没有变 ──
  if [[ -z "$now" ]]; then
    say "  · 读不到版本(/health 不返回 version → 你的镜像早于 v1.6.25)"
  elif [[ -n "$VER_BEFORE" && "$VER_BEFORE" != "$now" ]]; then
    say "  ✓ 已更新:v${VER_BEFORE} → **v${now}**"
  elif [[ -n "$VER_BEFORE" ]]; then
    say "  · 版本无变化:仍是 v${now}"
  else
    say "  · 当前版本 v${now}"
  fi

  # ── 和"外面最新的"比 ──
  # v1.6.26 修:两个来源都查不到时**必须说出来**。此前是静默 return,用户无法区分
  # 「已是最新」和「查不了」(用户第 17 轮就是撞在这:GitHub API 没通 → 什么都不打印)。
  if [[ -z "$img" && -z "$rel" ]]; then
    say "  · 查不到最新版本(ghcr.io 与 api.github.com 都没通 —— 网络问题,不影响已起好的服务)"
    say "    想关掉这项检查:FINANCE_NO_UPDATE_CHECK=1"
    return 0
  fi

  local base="${now:-}"
  if [[ -n "$img" ]]; then
    if [[ -z "$base" ]]; then
      say "  · 最新可拉镜像是 **v${img}**;更新到它之后这里就会直接显示版本号。"
    elif [[ "$img" == "$base" ]]; then
      say "  ✓ 已是最新可用镜像(v${img})"
    elif ver_gt "$img" "$base"; then
      say ""
      say "  ⚠ 有新版镜像 **v${img}**(你在跑 v${base})→ 重跑本脚本即可更新。"
    else
      say "  · 你在跑 v${base},比 GHCR 上最新镜像 v${img} 还新(本地构建过?)"
    fi
    # release 比镜像新 = 刚打完 tag、镜像还在 CI 里(或构建失败)
    if [[ -n "$rel" ]] && ver_gt "$rel" "$img"; then
      say "    (GitHub 上已发布 v${rel},但镜像还没推上来 —— CI 构建约 12 分钟;若久等不来说明构建失败了)"
    fi
  else
    # GHCR 查不到,只能拿 release 说事 —— 必须注明"镜像是否已发布未确认"
    if [[ -n "$base" && "$rel" != "$base" ]]; then
      say ""
      say "  ⚠ GitHub 上最新发布版是 **v${rel}**(你在跑 v${base});ghcr.io 没查通,**镜像是否已发布未确认**。"
      say "    刚打 tag 不久的话镜像还在 CI 里(约 12 分钟),过几分钟重跑本脚本。"
    elif [[ -n "$base" ]]; then
      say "  ✓ 与 GitHub 最新发布版一致(v${rel});ghcr.io 没查通,未复核镜像。"
    fi
  fi
  # git pull 的作用范围:这条对"拉了代码却没变新版"的困惑最有解释力,保留
  if [[ -n "$img$rel" && -n "$base" ]] && { [[ -n "$img" ]] && ver_gt "$img" "$base"; } then
    say "    (git pull 拉到的新代码不会进容器,它只影响 compose 文件与本脚本自身;"
    say "     想立刻用上仓库里的代码可本地构建:$DC up -d --build)"
  fi
}

if [[ "$ok" == "1" ]]; then
  say ""
  say "✓ 起好了 → http://127.0.0.1:${PORT}  (默认只发布到 loopback,公网请前置反代加 HTTPS)"
  version_verdict
  _fdu="$(envval DB_USER)"; _fdn="$(envval DB_NAME)"
  fresh_db_notice "$(envval DB_PASS)" "${_fdu:-finance}" "${_fdn:-finance}"
  login_hint
  say ""
  say "  ── 常用操作 ────────────────────────────────"
  say "   看日志   $DC logs -f app              (只看错误:$DC logs --tail=200 app | grep -i error)"
  say "   停       $DC stop                     (数据都在,随时能起回来)"
  say "   起       $DC start                    (或直接重跑 bash deploy/docker-up.sh)"
  say "   重启     $DC restart app"
  say "   更新     git pull && bash deploy/docker-up.sh   (会告诉你从哪一版升到哪一版)"
  say "   改配置   编辑 .env 后 $DC up -d        (运营参数如 key/阈值走管理页,改 .env 无效)"
  say "   备份     bash deploy/backup-now.sh    (立刻备一份 · 加个目录参数可同时拷到宿主机)"
  say "   恢复     bash deploy/restore.sh       (列出备份让你选 · 会先另存当前库当退路)"
  say "   出问题   bash deploy/doctor.sh        (一键收集诊断信息 · 已脱敏 · 可直接贴 issue)"
  say "   数据在哪 Docker 命名卷(不在仓库目录里):docker volume ls | grep db-data"
  say "   彻底重来 $DC down -v && bash deploy/docker-up.sh   ⚠ down -v 会删光数据库,先备份"
  say "  ────────────────────────────────────────────"
elif [[ "$ok" == "skip" ]]; then
  say ""
  say "✓ 容器已起 → http://127.0.0.1:${PORT}  · 浏览器自行确认"
  version_verdict
  login_hint
  say ""
  say "  ── 常用操作 ────────────────────────────────"
  say "   看日志   $DC logs -f app              (只看错误:$DC logs --tail=200 app | grep -i error)"
  say "   停       $DC stop                     (数据都在,随时能起回来)"
  say "   起       $DC start                    (或直接重跑 bash deploy/docker-up.sh)"
  say "   重启     $DC restart app"
  say "   更新     git pull && bash deploy/docker-up.sh   (会告诉你从哪一版升到哪一版)"
  say "   改配置   编辑 .env 后 $DC up -d        (运营参数如 key/阈值走管理页,改 .env 无效)"
  say "   备份     bash deploy/backup-now.sh    (立刻备一份 · 加个目录参数可同时拷到宿主机)"
  say "   恢复     bash deploy/restore.sh       (列出备份让你选 · 会先另存当前库当退路)"
  say "   出问题   bash deploy/doctor.sh        (一键收集诊断信息 · 已脱敏 · 可直接贴 issue)"
  say "   数据在哪 Docker 命名卷(不在仓库目录里):docker volume ls | grep db-data"
  say "   彻底重来 $DC down -v && bash deploy/docker-up.sh   ⚠ down -v 会删光数据库,先备份"
  say "  ────────────────────────────────────────────"
else
  # 别只丢一句「看日志」+ 一串猜测(v1.6.22 前写的是「DB 还在初始化 / 端口被占」,而真实原因是
  # 数据卷老密码不匹配 —— 两个猜测都不对,用户拿到一屏 Access denied 完全不知道该干什么)。
  # 这里直接读 app 日志按特征归因,能自愈的当场自愈。
  LOGS="$($DC logs --tail=120 app 2>&1 || true)"
  case "$LOGS" in
    *1045*|*"Access denied"*)
      say ""
      say "  ⚠ 定位到了:数据库拒绝了应用的密码(Access denied / ERROR 1045),不是启动慢。"
      ensure_db_credentials
      say "· 再等应用就绪(最多 ~90s)…"
      for _ in $(seq 1 45); do
        curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1 && { ok=1; break; }
        sleep 2
      done
      if [[ "$ok" == "1" ]]; then
        say ""; say "✓ 起好了 → http://127.0.0.1:${PORT}"; login_hint
        say ""
  say "  ── 常用操作 ────────────────────────────────"
  say "   看日志   $DC logs -f app              (只看错误:$DC logs --tail=200 app | grep -i error)"
  say "   停       $DC stop                     (数据都在,随时能起回来)"
  say "   起       $DC start                    (或直接重跑 bash deploy/docker-up.sh)"
  say "   重启     $DC restart app"
  say "   更新     git pull && bash deploy/docker-up.sh   (会告诉你从哪一版升到哪一版)"
  say "   改配置   编辑 .env 后 $DC up -d        (运营参数如 key/阈值走管理页,改 .env 无效)"
  say "   备份     bash deploy/backup-now.sh    (立刻备一份 · 加个目录参数可同时拷到宿主机)"
  say "   恢复     bash deploy/restore.sh       (列出备份让你选 · 会先另存当前库当退路)"
  say "   出问题   bash deploy/doctor.sh        (一键收集诊断信息 · 已脱敏 · 可直接贴 issue)"
  say "   数据在哪 Docker 命名卷(不在仓库目录里):docker volume ls | grep db-data"
  say "   彻底重来 $DC down -v && bash deploy/docker-up.sh   ⚠ down -v 会删光数据库,先备份"
  say "  ────────────────────────────────────────────"
      else
        die "密码问题已处理,但应用仍没就绪。看日志:$DC logs --tail=80 app db"
      fi
      ;;
    *"Address already in use"*|*"port is already allocated"*)
      die "端口 ${PORT} 被别的程序占了。改 .env 里的 SERVER_PORT 换一个,再重跑 bash deploy/docker-up.sh。"
      ;;
    *)
      die "应用 90s 内没就绪。把下面这条的输出发出来最容易定位:
  $DC logs --tail=80 app db"
      ;;
  esac
fi
