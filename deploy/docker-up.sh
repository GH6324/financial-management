#!/usr/bin/env bash
# 家庭账房 · v0.7 · 一键起(自检环境 → 生成 .env → 拉/构建镜像 → up → 验 /health)
# 目标:macOS / Linux 上不管哪种 docker 装法(Docker Desktop / OrbStack / colima / 原生 engine)
#       都能一条命令跑通,卡住时给可直接复制的修复命令,而不是吐底层报错。
# 用法:在仓库根目录 `bash deploy/docker-up.sh`
set -euo pipefail
cd "$(dirname "$0")/.."   # 仓库根

say(){ printf '%s\n' "$*"; }
die(){ printf '\n✗ %s\n' "$*" >&2; exit 1; }

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

# ── 5. 镜像:能拉预构建就拉,拉不到(如尚未发版)就本地源码构建 ──────
say "· 准备镜像(优先拉预构建,拉不到则本地构建,首次构建约几分钟)…"
if $DC pull >/dev/null 2>&1; then
  $DC up -d
else
  say "  没有预构建镜像,改为本地源码构建…"
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
