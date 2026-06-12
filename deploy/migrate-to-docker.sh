#!/usr/bin/env bash
# =====================================================================
# 家庭账房 · v0.7 · 存量部署 → Docker 一键迁移(systemd / macOS 双模式)
#
#   自动识别:
#     · systemd(Linux): /etc/finance.env + systemctl   → sudo bash deploy/migrate-to-docker.sh
#     · macOS(launchd/前台): ~/.finance/finance.env      → bash deploy/migrate-to-docker.sh
#
#   做什么(每步失败即停 · 全程不删旧部署 · 可回滚):
#     预检 → mysqldump 备份 → 生成 .env(携带 REMEMBER_ME_KEY)→ 停旧 app 腾端口
#     → 起 db 容器灌 dump(含 schema_history,版本不重放)→ 搬 uploads → 起 app → 验 /health
#
#   回滚:docker compose down  + 重启旧 app(systemd: systemctl start finance)
# =====================================================================
set -euo pipefail

C_G='\033[32m'; C_Y='\033[33m'; C_R='\033[31m'; C_X='\033[0m'
ok(){ echo -e "${C_G}✓${C_X} $*"; }; warn(){ echo -e "${C_Y}⚠${C_X} $*"; }; die(){ echo -e "${C_R}✗ $*${C_X}" >&2; exit 1; }
say(){ echo; echo -e "${C_Y}── $* ──${C_X}"; }

cd "$(dirname "$0")/.."            # 仓库根
REPO="$(pwd)"

# docker compose v2
DC="docker compose"
$DC version >/dev/null 2>&1 || { command -v docker-compose >/dev/null 2>&1 && DC="docker-compose" || die "未装 docker / docker compose(v2)"; }
docker info >/dev/null 2>&1 || die "Docker 守护进程没跑(或当前用户无权限:加入 docker 组或用 sudo)"

# ---------- 识别模式 ----------
MODE=""; SRC_ENV=""; STOP_HINT=""
if [[ -f /etc/finance.env ]] && command -v systemctl >/dev/null 2>&1; then
  MODE="systemd"; SRC_ENV="/etc/finance.env"
elif [[ -f "$HOME/.finance/finance.env" ]]; then
  MODE="macos"; SRC_ENV="$HOME/.finance/finance.env"
else
  die "没找到存量部署(/etc/finance.env 或 ~/.finance/finance.env 都不在)。这是给已部署用户迁移用的;全新装请直接 docker compose up -d。"
fi
ok "识别到存量部署:${MODE} · 读 ${SRC_ENV}"

# ---------- 读旧配置 ----------
get(){ grep "^$1=" "$SRC_ENV" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"' || true; }
DB_HOST="$(get DB_HOST)"; DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="$(get DB_PORT)"; DB_PORT="${DB_PORT:-3306}"
DB_NAME="$(get DB_NAME)"; DB_NAME="${DB_NAME:-finance}"
DB_USER="$(get DB_USER)"; DB_USER="${DB_USER:-finance}"
DB_PASS="$(get DB_PASS)"; [[ -n "$DB_PASS" ]] || die "从 $SRC_ENV 读不到 DB_PASS"
RMK="$(get REMEMBER_ME_KEY)"
SERVER_PORT="$(get SERVER_PORT)"; SERVER_PORT="${SERVER_PORT:-20000}"
UPLOAD_SRC="$(get UPLOAD_ROOT)"
# 老 systemd 默认上传目录;macOS 默认 $HOME/finance/uploads
[[ -n "$UPLOAD_SRC" ]] || { [[ "$MODE" == "systemd" ]] && UPLOAD_SRC="/var/finance/uploads" || UPLOAD_SRC="$HOME/finance/uploads"; }

# ---------- 预检 ----------
say "预检"
MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" -e "SELECT 1" >/dev/null 2>&1 \
  || die "用 $SRC_ENV 的凭据连不上旧库 $DB_NAME@$DB_HOST:$DB_PORT"
ok "旧库可连 · 上传目录:${UPLOAD_SRC}"
[[ -n "$RMK" ]] && ok "携带原 REMEMBER_ME_KEY(登录态保留)" || warn "旧 env 无 REMEMBER_ME_KEY,将新生成(已登录会话会失效,需重新登录)"

# ---------- 1. 强制备份(安全网 + 迁移源)----------
say "1/6 备份旧库(mysqldump)"
DUMP="${REPO}/migrate-dump-$(date +%Y%m%d-%H%M%S).sql.gz"
MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
  -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" | gzip > "$DUMP"
ok "已备份 → $DUMP ($(du -h "$DUMP" | cut -f1))"

# ---------- 2. 生成 .env ----------
say "2/6 生成 .env"
[[ -f "$REPO/.env" ]] && { cp "$REPO/.env" "$REPO/.env.bak.$(date +%s)"; warn "已有 .env,备份后覆盖"; }
ROOT_PASS="$(openssl rand -hex 18)"
[[ -n "$RMK" ]] || RMK="$(openssl rand -hex 32)"
cat > "$REPO/.env" <<EOF
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASS=${DB_PASS}
MYSQL_ROOT_PASSWORD=${ROOT_PASS}
SERVER_PORT=${SERVER_PORT}
REMEMBER_ME_KEY=${RMK}
TZ=Asia/Shanghai
RETENTION_DAYS=56
EOF
chmod 600 "$REPO/.env"; ok ".env 就绪(沿用 DB 名/用户/密码 + 端口 + REMEMBER_ME_KEY)"

# ---------- 3. 停旧 app 腾端口 ----------
say "3/6 停旧应用(只停 app,不删任何东西)"
if [[ "$MODE" == "systemd" ]]; then
  systemctl stop finance && ok "systemctl stop finance" || die "停 finance 失败(用 sudo 跑本脚本)"
else
  warn "macOS:请手动停掉旧应用(前台 java 按 Ctrl-C;或 launchctl unload 你的 plist),腾出端口 ${SERVER_PORT}。"
  read -r -p "停好后回车继续... " _
fi

# ---------- 构建镜像(GHCR 未发布时走源码构建)----------
say "构建镜像(若已配 GHCR 可改用 docker compose pull)"
$DC pull app 2>/dev/null || $DC build

# ---------- 4. 起 db + 灌 dump ----------
say "4/6 起 db 容器并导入数据"
$DC up -d db
for i in $(seq 1 30); do
  $DC exec -T db mysqladmin ping -h127.0.0.1 -uroot -p"$ROOT_PASS" --silent >/dev/null 2>&1 && { ok "db 就绪"; break; }
  [[ "$i" -eq 30 ]] && die "db 容器迟迟不就绪"; sleep 3
done
gunzip -c "$DUMP" | $DC exec -T db mysql -uroot -p"$ROOT_PASS" "$DB_NAME"
ok "数据已导入容器(含 schema_history → entrypoint 不会重放迁移)"

# ---------- 5. 搬 uploads ----------
say "5/6 搬运上传文件 uploads"
if [[ -d "$UPLOAD_SRC" ]] && [[ -n "$(ls -A "$UPLOAD_SRC" 2>/dev/null || true)" ]]; then
  $DC run --rm --no-deps --user root -v "$UPLOAD_SRC":/src:ro --entrypoint sh app \
    -c 'cp -a /src/. /data/uploads/ 2>/dev/null || true; chown -R 10001:10001 /data/uploads' \
    && ok "uploads 已搬入卷" || warn "uploads 搬运有警告,登录后核对 logo 是否在"
else
  warn "上传目录空或不存在,跳过"
fi

# ---------- 6. 起 app + 验证 ----------
say "6/6 起 app + 备份 sidecar,验健康"
$DC up -d app backup
for i in $(seq 1 40); do
  code=$(curl -s -m5 -o /dev/null -w "%{http_code}" "http://127.0.0.1:${SERVER_PORT}/health" 2>/dev/null || echo 000)
  [[ "$code" == "200" ]] && { ok "Docker app 健康 · /health 200(${i}x3s)"; break; }
  [[ "$i" -eq 40 ]] && die "app 起来了但 /health 不通,看 $DC logs app"; sleep 3
done

echo
ok "迁移完成 · 现在跑在 Docker 上,数据已搬,版本未重放。"
echo "  备份:$DUMP(确认无误后可删)"
echo "  回滚:$DC down  并重启旧应用" $([[ "$MODE" == systemd ]] && echo "(systemctl start finance)")
echo "  满意后释放旧部署:" $([[ "$MODE" == systemd ]] && echo "systemctl disable finance + 可停宿主 MySQL" || echo "停掉旧 launchd / brew services mysql(若不再需要)")
echo "  反代/HTTPS:把你已有 nginx 的 proxy_pass 指到 127.0.0.1:${SERVER_PORT}(见 deploy/README.md)"
