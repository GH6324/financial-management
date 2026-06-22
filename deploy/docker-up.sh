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

# 带超时跑命令(timeout 在 macOS 默认没有,有 gtimeout 用 gtimeout,都没有就直接跑)
_to(){ local s="$1"; shift
  if command -v timeout >/dev/null 2>&1; then timeout "$s" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then gtimeout "$s" "$@"
  else "$@"; fi; }
pull_one(){ _to 50 docker pull "$1" >/dev/null 2>&1; }

# Docker Hub 被墙(拉 mysql 超时)时的引导。按平台分流:
#   · Linux 原生 systemd 引擎 → 给 /etc/docker/daemon.json 指引,可征同意后自动写入并重启;
#   · macOS(引擎在虚拟机里,不读宿主 /etc/docker/daemon.json)→ 按 colima / OrbStack / Docker Desktop
#     各自机制给精确手动指引,不自动改 VM 配置。
# 注:registry-mirrors 只对 Docker Hub 生效,正好兜 mysql;GHCR 的 app 镜像大陆直连、不受影响。
cn_hub_blocked_guide(){
  say ""
  say "  ⚠ 拉取基础镜像 mysql 超时 —— 这是中国大陆访问 Docker Hub 被限速/阻断的典型表现。"
  say "    (我们自己的 app 镜像在 GHCR,大陆能直连;registry-mirrors 只对 Docker Hub 生效,正好兜 mysql。)"
  say ""
  if [[ "$(uname -s)" == "Darwin" ]]; then _cn_guide_mac; else _cn_guide_linux; fi
  say ""
}

# Linux:/etc/docker/daemon.json + 可选自动写入(原生 systemd 引擎)
_cn_guide_linux(){
  say "    修复:把下面这段写进 ${DAEMON_JSON}"
  say "    (已有该文件就把 registry-mirrors 这段并进去,别覆盖其它配置):"
  say ""
  say "      ${MIRRORS_JSON}"
  say ""
  say "    然后重启:sudo systemctl restart docker"
  # 仅在「systemd 引擎 + 该文件不存在 + (交互终端 或 FINANCE_ASSUME_YES) + 可提权」时提议自动写
  if command -v systemctl >/dev/null 2>&1 && [[ ! -e "$DAEMON_JSON" ]] \
     && { [[ -t 0 ]] || [[ -n "${FINANCE_ASSUME_YES:-}" ]]; }; then
    local SUDO=""; [[ $(id -u) -ne 0 ]] && SUDO="sudo"
    if [[ -z "$SUDO" ]] || command -v sudo >/dev/null 2>&1; then
      local ans
      if [[ -n "${FINANCE_ASSUME_YES:-}" ]]; then ans=y
      else printf '\n  要我现在自动写入 %s 并重启 Docker 吗?(会让本机其它容器中断几秒)[y/N] ' "$DAEMON_JSON"
           read -r ans </dev/tty || ans=""; fi
      if [[ "$ans" =~ ^[Yy] ]]; then
        $SUDO mkdir -p "$(dirname "$DAEMON_JSON")"
        printf '%s\n' "$MIRRORS_JSON" | $SUDO tee "$DAEMON_JSON" >/dev/null
        say "  ✓ 已写入 $DAEMON_JSON,重启 Docker 中…"
        if [[ -n "${FINANCE_DOCKER_RESTART:-}" ]]; then eval "$FINANCE_DOCKER_RESTART"
        else $SUDO systemctl restart docker; fi
        sleep 3
      fi
    fi
  fi
}

# macOS:引擎在虚拟机里,按装法分别配(不碰 VM 配置,只给精确步骤)
_cn_guide_mac(){
  say "    Mac 的 Docker 引擎跑在虚拟机里(不读宿主的 /etc/docker/daemon.json),按你的装法选一种配:"
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
  bash deploy/docker-init.sh >/dev/null
  say "  ✓ 已生成 .env"
fi

# ── 5. 镜像:拉预构建 → 拉不到时分清两种原因(Docker Hub 被墙 vs app 镜像未发版)──
say "· 准备镜像(优先拉预构建,首次约几分钟)…"
if $DC pull >/dev/null 2>&1; then
  $DC up -d
else
  # pull 失败:先单独探 mysql(Docker Hub)能不能拉 —— 大陆最常见是 Docker Hub 被墙。
  # 注意 `up --build` 只构建 app,db 仍要拉 mysql,所以墙必须先过、build 救不了。
  if ! pull_one mysql:8.0; then
    cn_hub_blocked_guide
    if $DC pull >/dev/null 2>&1; then
      $DC up -d
    else
      die "基础镜像仍拉不下来。按上面的镜像源指引配好 ${DAEMON_JSON} 后,重跑 bash deploy/docker-up.sh。"
    fi
  else
    # mysql 能拉,说明缺的只是预构建 app 镜像(如尚未发版)→ 本地源码构建
    say "  没有预构建 app 镜像,改为本地源码构建(首次约几分钟)…"
    $DC up -d --build
  fi
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
