#!/usr/bin/env bash
# =========================================================
# 家庭账房 · 生产服务器一键部署脚本(幂等)
#
# 一个脚本管两个场景:首装 + 迭代发版。
#
# 用法(在 prod 服务器,仓库目录内):
#   sudo bash deploy/deploy.sh
#
# 干啥:
#   首装 — apt 装 JDK21 / Maven / MySQL / nginx 等所有依赖
#         建系统用户 / 目录 / DB / /etc/finance.env / systemd / sudoers
#         编译 jar / 跑迁移 / 装 nginx / 启服务
#   迭代 — mysqldump 备份 / 备份旧 jar / 应用新 V*.sql / 切新 jar / restart / 健康检查 / 失败自动回滚
#
# 完全幂等:任何步骤都先检测当前状态,只在变了的时候动作。
# 失败安全:any step crash 都不留半完成态;12 步切 jar 之后失败 → auto rollback jar。
# =========================================================
set -euo pipefail

# ---------- 颜色 + 辅助 ----------
G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1;36m'; X=$'\033[0m'
say()  { echo; echo "${B}═══ $* ═══${X}"; }
ok()   { echo "${G}✓${X} $*"; }
warn() { echo "${Y}⚠${X} $*"; }
err()  { echo "${R}✗${X} $*" >&2; }
die()  { err "$1"; exit 1; }
ask()  { local q="$1" def="${2:-}"; local ans; read -p "$q${def:+ [$def]}: " ans; echo "${ans:-$def}"; }
ask_pw() { local q="$1"; local ans; read -s -p "$q: " ans; echo >&2; echo "$ans"; }

REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

# 用 MYSQL_PWD env var 而不是 -p 参数,避免每条 mysql 调用都打 password warning
mysql_run()      { MYSQL_PWD="$DB_PASS" mysql -h127.0.0.1 -u"$DB_USER" "$DB_NAME" "$@"; }
mysqldump_run()  { MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
                    -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"$DB_USER" "$DB_NAME" "$@"; }

# ---------- 0. 预飞 ----------
say "0/15 预飞"
[[ $EUID -eq 0 ]] || die "必须 sudo 跑(需要装包 / 改 systemd / 写 sudoers)"
[[ -d src/main/java && -d db/migration ]] || die "$(pwd) 不像本仓库根目录"
[[ -f deploy/finance.service && -f pom.xml ]] || die "deploy/finance.service 或 pom.xml 缺失"

if   [[ -f /etc/debian_version ]]; then OS=debian
elif [[ -f /etc/redhat-release ]]; then OS=rhel
else die "未识别的 OS(仅支持 Debian/Ubuntu / RHEL/CentOS/Alibaba)"; fi
ok "OS = $OS · 仓库 = $REPO_DIR"

NEEDS_DAEMON_RELOAD=0
NEEDS_FINANCE_RESTART=0
IS_ITERATION=0
[[ -f /etc/finance.env && -f /opt/finance/app.jar ]] && systemctl is-active --quiet finance 2>/dev/null && IS_ITERATION=1
[[ "$IS_ITERATION" == "1" ]] && ok "检测到 finance 已上线 — 本次为「迭代发版」模式" \
                              || ok "检测到首装(env / jar / 服务 任一缺失)"

# ============================================================
# A. 环境与依赖(幂等)
# ============================================================

say "1/15 Java 21"
if java -version 2>&1 | head -1 | grep -qE 'version "(21|22|23|24)\.'; then
  ok "已存在:$(java -version 2>&1 | head -1)"
else
  if [[ "$OS" == "debian" ]]; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless
  else
    dnf install -y java-21-openjdk-headless
  fi
  ok "已装"
fi
JAVA_BIN=$(readlink -f "$(command -v java)")

say "2/15 Maven"
if command -v mvn >/dev/null 2>&1; then
  ok "已存在:$(mvn -v 2>&1 | head -1 | awk '{print $1,$2,$3}')"
else
  if [[ "$OS" == "debian" ]]; then DEBIAN_FRONTEND=noninteractive apt-get install -y -qq maven; else dnf install -y maven; fi
  ok "已装"
fi

say "3/15 MySQL 8"
if systemctl is-active --quiet mysql 2>/dev/null || systemctl is-active --quiet mysqld 2>/dev/null; then
  ok "已运行"
else
  if [[ "$OS" == "debian" ]]; then DEBIAN_FRONTEND=noninteractive apt-get install -y -qq mysql-server; else dnf install -y mysql-server; fi
  systemctl enable --now mysql 2>/dev/null || systemctl enable --now mysqld
  ok "已启"
fi

say "4/15 nginx + apache2-utils + openssl"
if command -v nginx >/dev/null 2>&1; then
  ok "nginx 已存在"
else
  if [[ "$OS" == "debian" ]]; then DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx apache2-utils; else dnf install -y nginx httpd-tools; fi
  systemctl enable --now nginx
  ok "nginx 已装"
fi
command -v htpasswd >/dev/null 2>&1 || {
  if [[ "$OS" == "debian" ]]; then DEBIAN_FRONTEND=noninteractive apt-get install -y -qq apache2-utils; else dnf install -y httpd-tools; fi
}
command -v openssl >/dev/null 2>&1 || die "openssl 缺失"

say "5/15 系统用户 finance"
id finance >/dev/null 2>&1 && ok "已存在" || { useradd -r -m -d /home/finance -s /bin/bash finance; ok "已建"; }

say "6/15 目录"
install -d -o finance -g finance -m 755 /opt/finance/{logs,db,db/migration}
install -d -o finance -g finance -m 755 /var/finance/uploads
install -d -o finance -g finance -m 755 /var/backup/finance
ok "/opt/finance · /var/finance · /var/backup/finance 就位"

say "7/15 MySQL 库 + finance 用户"
DB_NAME="${DB_NAME:-finance}"
DB_USER="${DB_USER:-finance}"
if [[ -f /etc/finance.env ]]; then
  DB_PASS=$(grep '^DB_PASS=' /etc/finance.env | cut -d= -f2-)
  ok "从 /etc/finance.env 读到 DB_PASS"
elif mysql -sN -e "SHOW DATABASES" 2>/dev/null | grep -qx "$DB_NAME"; then
  DB_PASS=$(ask_pw "DB $DB_NAME 已存在,现有 ${DB_USER} 密码")
else
  DB_PASS_GEN=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24)
  DB_PASS=$(ask_pw "新建 ${DB_USER}@localhost 的密码(回车 = 自动生成)")
  [[ -z "$DB_PASS" ]] && { DB_PASS="$DB_PASS_GEN"; warn "自动生成 → $DB_PASS  (将写入 /etc/finance.env)"; }
  mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL
  ok "DB + 用户创建"
fi
MYSQL_PWD="$DB_PASS" mysql -h127.0.0.1 -u"$DB_USER" "$DB_NAME" -e "SELECT 1" >/dev/null 2>&1 || die "${DB_USER} 登 mysql 失败"
ok "${DB_USER}@localhost 登录验证通过"

say "8/15 /etc/finance.env"
if [[ -f /etc/finance.env ]]; then
  ok "已存在,跳过(重置 → 先删 /etc/finance.env)"
else
  REMEMBER_KEY=$(openssl rand -hex 32)
  SERVER_PORT=$(ask "服务监听端口" "20000")
  cat > /etc/finance.env <<EOF
# /etc/finance.env — deploy.sh 生成 $(date -Iseconds)
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASS=${DB_PASS}
UPLOAD_ROOT=/var/finance/uploads
REMEMBER_ME_KEY=${REMEMBER_KEY}
BACKUP_DIR=/var/backup/finance
RETENTION_DAYS=56
SERVER_PORT=${SERVER_PORT}
FAMILY_ID=1
SERVER_ADDRESS=127.0.0.1
# FINANCE_LLM_QWEN_API_KEY=
# FINANCE_LLM_DEEPSEEK_API_KEY=
EOF
  chmod 640 /etc/finance.env; chown root:finance /etc/finance.env
  ok "写入(640, root:finance)"
fi
SERVER_PORT=$(grep '^SERVER_PORT=' /etc/finance.env | cut -d= -f2- | tr -d '"' || echo 20000)

# ============================================================
# B. 数据库迁移
# ============================================================

say "9/15 数据库迁移(schema_history 幂等)"
install -m 755 -o finance -g finance db/apply.sh /opt/finance/db/apply.sh
install -m 644 -o finance -g finance db/migration/V*__*.sql /opt/finance/db/migration/
# .checksum-overrides 文件(以 . 开头)不会被 V*__*.sql glob 匹配 · 单独装
if [[ -f db/migration/.checksum-overrides ]]; then
  install -m 644 -o finance -g finance db/migration/.checksum-overrides /opt/finance/db/migration/.checksum-overrides
fi

BACKUP_FILE=""
if [[ "$IS_ITERATION" == "1" ]]; then
  TS=$(date +%Y%m%d-%H%M%S)
  BACKUP_FILE=/var/backup/finance/pre-deploy-${TS}.sql.gz
  set -a; . /etc/finance.env; set +a
  # 以 script 调用者身份(root via sudo)dump,写到 finance 拥有的备份目录,后续 chown
  mysqldump_run | gzip > "$BACKUP_FILE" || die "mysqldump 失败"
  chown finance:finance "$BACKUP_FILE"
  [[ $(stat -c%s "$BACKUP_FILE") -gt 1024 ]] || die "DB 备份产物 < 1KB"
  gunzip -t "$BACKUP_FILE" || die "DB 备份 gzip 完整性校验失败"
  ok "DB 备份 → $BACKUP_FILE($(du -h "$BACKUP_FILE" | cut -f1))"

  PENDING=""
  for f in db/migration/V*__*.sql; do
    name=$(basename "$f")
    in_h=$(mysql_run -sN -e "SELECT 1 FROM schema_history WHERE filename='$name'" 2>/dev/null)
    [[ -z "$in_h" ]] && PENDING="$PENDING  → $name\n"
  done
  if [[ -n "$PENDING" ]]; then
    echo; echo "${Y}本次增量迁移:${X}"; printf "$PENDING"; echo
    if [[ -t 0 ]]; then
      read -p "${B}确认 apply? [Y/n]${X} " yn
      [[ "$yn" == "n" || "$yn" == "N" ]] && die "用户取消,旧 jar 仍在跑"
    fi
  fi
fi

set -a; . /etc/finance.env; set +a
# apply.sh 以 root(脚本调用者)身份跑,MYSQL_PWD 让它不打 -p warning
MYSQL_PWD="$DB_PASS" bash /opt/finance/db/apply.sh
ok "迁移完毕"

# 9b. 修复 seed 用户 PLACEHOLDER 密码(仅首装会有)
PLACEHOLDER_COUNT=$(mysql_run -sN -e "SELECT COUNT(*) FROM member WHERE password_hash LIKE 'PLACEHOLDER%'" 2>/dev/null || echo 0)
if [[ "${PLACEHOLDER_COUNT:-0}" -gt 0 ]]; then
  warn "$PLACEHOLDER_COUNT 个种子用户密码为 PLACEHOLDER(prod DevSeedRunner 不跑)"
  ADMIN_PW=$(ask_pw "种子用户临时密码(回车 = demo1234,登入后强制改)")
  ADMIN_PW="${ADMIN_PW:-demo1234}"
  HASH=$(htpasswd -bnBC 10 "" "$ADMIN_PW" | tr -d ':\n')
  mysql_run -e "UPDATE member SET password_hash = '$HASH', must_change_pw = 1 WHERE password_hash LIKE 'PLACEHOLDER%';"
  ok "种子用户密码 → bcrypt(临时)"
fi

# 10. 清 dev 演示数据(首装才会触发;sentinel + 真实数据双保险)
say "10/15 清 dev 演示数据"
SENTINEL=/opt/finance/.prod-cleaned
if [[ -f "$SENTINEL" ]]; then
  ok "已清过($SENTINEL 存在)"
else
  AUDIT_COUNT=$(mysql_run -sN -e "SELECT COUNT(*) FROM audit_log WHERE actor_member_id IS NOT NULL" 2>/dev/null || echo 0)
  EXTRA_MEMBERS=$(mysql_run -sN -e "SELECT COUNT(*) FROM member WHERE id > 2" 2>/dev/null || echo 0)
  if [[ "${AUDIT_COUNT:-0}" -gt 50 || "${EXTRA_MEMBERS:-0}" -gt 0 ]]; then
    err "═══ 真实数据拦截 ═══"
    err "audit_log=${AUDIT_COUNT} / 额外成员=${EXTRA_MEMBERS} → 拒绝 TRUNCATE"
    err "若确实想清:手动 mysqldump → TRUNCATE → touch $SENTINEL → 重跑"
    die "中止"
  fi
  mysql_run <<'SQL'
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE cash_flow; TRUNCATE TABLE transfer; TRUNCATE TABLE period_snapshot;
TRUNCATE TABLE snapshot_todo; TRUNCATE TABLE period_member_completion;
TRUNCATE TABLE fx_rate; TRUNCATE TABLE audit_log; TRUNCATE TABLE backup_log;
TRUNCATE TABLE metrics_recompute_log; TRUNCATE TABLE period_reopen_log;
TRUNCATE TABLE period; TRUNCATE TABLE account;
SET FOREIGN_KEY_CHECKS=1;
SQL
  touch "$SENTINEL"; chown finance:finance "$SENTINEL"
  ok "11 张交易表清空"
fi

# ============================================================
# C. 编译 + 切 jar + 重启
# ============================================================

say "11/15 mvn package"
# 以脚本调用者身份(通常 sudo 后是 root)跑 mvn — 不要切到 finance 用户,
# 因为 REPO_DIR 可能在 /root/... 或 /home/<other>/... finance 用户读不到。

# 国内 maven central 直连极慢(首次 200MB 拉 20 分钟+)→ 装阿里云 mirror,10x 加速
# settings.xml 内容在 deploy/maven-settings.xml,这里只 cp + 装到目标 home/.m2/
HOME_DIR="${SUDO_USER:+/home/$SUDO_USER}"
[[ -z "${SUDO_USER:-}" ]] && HOME_DIR="$HOME"
[[ ! -d "$HOME_DIR" ]] && HOME_DIR="$HOME"
M2_SETTINGS="$HOME_DIR/.m2/settings.xml"
MAVEN_SRC="$REPO_DIR/deploy/maven-settings.xml"
if [[ ! -f "$MAVEN_SRC" ]]; then
  warn "deploy/maven-settings.xml 缺失,跳过 mirror 配置(走 maven central,国内慢)"
elif [[ ! -f "$M2_SETTINGS" ]] || ! grep -q 'aliyun' "$M2_SETTINGS"; then
  mkdir -p "$(dirname "$M2_SETTINGS")"
  cp "$MAVEN_SRC" "$M2_SETTINGS"
  [[ -n "${SUDO_USER:-}" ]] && chown -R "$SUDO_USER:$SUDO_USER" "$(dirname "$M2_SETTINGS")" 2>/dev/null || true
  ok "$M2_SETTINGS ← deploy/maven-settings.xml(阿里云 mirror)"
else
  ok "$M2_SETTINGS 已含 aliyun mirror,跳过"
fi

# 不加 -q 让用户看见 [INFO] 阶段进度,过滤掉下载行 spam(每个依赖 N 行 Downloading)
echo "  (首次构建国内下载 ~200MB 依赖,5-15 分钟;后续秒级)"
set +e
(cd "$REPO_DIR" && mvn -B -DskipTests package 2>&1 \
  | grep -vE '^\[INFO\] (Downloading|Downloaded from|Progress )')
MVN_RC=${PIPESTATUS[0]}
set -e
[[ $MVN_RC -eq 0 ]] || die "mvn package 失败(exit=$MVN_RC)"
[[ -f "$REPO_DIR/target/app.jar" ]] || die "target/app.jar 缺失"
chown finance:finance "$REPO_DIR/target/app.jar" 2>/dev/null || true
ok "jar $(du -h "$REPO_DIR/target/app.jar" | cut -f1)"

say "12/15 部署 jar(无变化则 skip)"
if [[ -f /opt/finance/app.jar ]] && cmp -s "$REPO_DIR/target/app.jar" /opt/finance/app.jar; then
  ok "jar 与本仓库构建一致,跳过覆盖 + 不重启"
else
  [[ -f /opt/finance/app.jar ]] && install -m 644 -o finance -g finance /opt/finance/app.jar /opt/finance/app.jar.prev && ok "旧 jar 备份 → app.jar.prev"
  install -m 644 -o finance -g finance "$REPO_DIR/target/app.jar" /opt/finance/app.jar
  ok "新 jar 写入"
  NEEDS_FINANCE_RESTART=1
fi

say "13/15 systemd unit + sudoers"
RENDERED=$(mktemp)
sed "s|/usr/lib/jvm/java-21-openjdk-amd64/bin/java|${JAVA_BIN}|" deploy/finance.service > "$RENDERED"
if [[ -f /etc/systemd/system/finance.service ]] && cmp -s "$RENDERED" /etc/systemd/system/finance.service; then
  ok "systemd unit 已是最新"
  rm -f "$RENDERED"
else
  install -m 644 "$RENDERED" /etc/systemd/system/finance.service
  rm -f "$RENDERED"
  NEEDS_DAEMON_RELOAD=1; NEEDS_FINANCE_RESTART=1
  ok "systemd unit 写入"
fi
systemctl is-enabled --quiet finance 2>/dev/null || systemctl enable finance >/dev/null 2>&1

SUDOERS_TMP=$(mktemp)
cat > "$SUDOERS_TMP" <<EOF
finance ALL=(root) NOPASSWD: /bin/cp /opt/finance/app.jar /opt/finance/app.jar.prev
finance ALL=(root) NOPASSWD: /bin/cp /opt/finance/app.jar.prev /opt/finance/app.jar
finance ALL=(root) NOPASSWD: /bin/cp ${REPO_DIR}/target/app.jar /opt/finance/app.jar
finance ALL=(root) NOPASSWD: /bin/systemctl start finance
finance ALL=(root) NOPASSWD: /bin/systemctl stop finance
finance ALL=(root) NOPASSWD: /bin/systemctl restart finance
finance ALL=(root) NOPASSWD: /bin/systemctl status finance
finance ALL=(root) NOPASSWD: /bin/journalctl -u finance *
EOF
if [[ -f /etc/sudoers.d/finance ]] && cmp -s "$SUDOERS_TMP" /etc/sudoers.d/finance; then
  ok "sudoers 已是最新"
  rm -f "$SUDOERS_TMP"
else
  install -m 440 "$SUDOERS_TMP" /etc/sudoers.d/finance
  rm -f "$SUDOERS_TMP"
  visudo -cf /etc/sudoers.d/finance >/dev/null || die "sudoers 语法错"
  ok "sudoers 写入"
fi

say "14/15 启服务 + 健康检查"
[[ "$NEEDS_DAEMON_RELOAD" == "1" ]] && { systemctl daemon-reload; ok "daemon-reload"; }

auto_rollback_jar() {
  echo; echo "${R}═════ 自动回滚 jar(DB 不动)═════${X}"
  if [[ -f /opt/finance/app.jar.prev ]]; then
    cp /opt/finance/app.jar.prev /opt/finance/app.jar
    systemctl restart finance
    # 跟主健康检查同款 15×2s = 30s 上限,不要只 sleep 5(Spring Boot 3 启动通常 8-15s)
    local ok_flag=0
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
      sleep 2
      curl -sf "http://127.0.0.1:${SERVER_PORT}/health" >/dev/null 2>&1 && { ok "旧 jar 还原后 /health 200($((i*2))s)"; ok_flag=1; break; }
    done
    [[ $ok_flag -ne 1 ]] && {
      err "回滚后服务 30s 仍不正常,journalctl 末 30 行:"
      journalctl -u finance --no-pager -n 30 >&2
    }
  else
    err "没有 app.jar.prev,无法 jar 回滚 → 手动检查 journalctl -u finance -n 80"
  fi
  [[ -n "$BACKUP_FILE" ]] && err "DB 备份保留 $BACKUP_FILE(评估后手动 gunzip + mysql 还原)"
}

if systemctl is-active --quiet finance; then
  [[ "$NEEDS_FINANCE_RESTART" == "1" ]] && { systemctl restart finance; ok "restart"; } || ok "已在跑且无变化,不重启"
else
  systemctl start finance; ok "start"; NEEDS_FINANCE_RESTART=1
fi

if [[ "$NEEDS_FINANCE_RESTART" == "1" ]]; then
  HEALTH_OK=0
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    sleep 2
    curl -sf "http://127.0.0.1:${SERVER_PORT}/health" >/dev/null 2>&1 && { ok "/health 200($((i*2))s)"; HEALTH_OK=1; break; }
  done
  if [[ $HEALTH_OK -ne 1 ]]; then
    journalctl -u finance --no-pager -n 30; err "服务 30s 未起来"
    [[ "$IS_ITERATION" == "1" ]] && auto_rollback_jar
    exit 1
  fi
fi

# 烟测:GET /login 返回 200 + 含 form 表单(_csrf 或 <form 之一即可)
# 给 Thymeleaf 多 2s 暖机(prod cache=true 时第一次渲染会编译模板)
sleep 2
SMOKE_TMP=$(mktemp)
SMOKE_CODE=$(curl -s -o "$SMOKE_TMP" -w "%{http_code}" "http://127.0.0.1:${SERVER_PORT}/login")
SMOKE_SIZE=$(stat -c%s "$SMOKE_TMP" 2>/dev/null || echo 0)
if [[ "$SMOKE_CODE" != "200" ]] \
   || ! grep -qE '_csrf|<form|name="username"' "$SMOKE_TMP"; then
  err "/login 烟测失败 · HTTP=$SMOKE_CODE size=${SMOKE_SIZE}B"
  echo "--- /login 响应前 30 行 ---" >&2
  head -30 "$SMOKE_TMP" >&2
  echo "--- journalctl 末 20 行 ---" >&2
  journalctl -u finance --no-pager -n 20 >&2
  rm -f "$SMOKE_TMP"
  [[ "$IS_ITERATION" == "1" ]] && auto_rollback_jar
  exit 1
fi
rm -f "$SMOKE_TMP"
ok "/health + /login 烟测通过(HTTP 200 · size=${SMOKE_SIZE}B)"

# 15. nginx 反代(首装时 prompt,迭代不动)
say "15/15 nginx :80 反代"
NGINX_CONF=/etc/nginx/sites-available/finance.conf
if { [[ -f $NGINX_CONF && -L /etc/nginx/sites-enabled/finance.conf ]] || [[ -f /etc/nginx/conf.d/finance.conf ]]; }; then
  ok "nginx + finance.conf 已就位"
elif [[ "$IS_ITERATION" != "1" ]] && [[ -t 0 ]]; then
  read -p "现在装 nginx 反代 :80 → :${SERVER_PORT} 吗? [Y/n] " yn
  if [[ "$yn" != "n" && "$yn" != "N" ]]; then
    SN=$(ask "nginx server_name(域名,纯 IP 访问就回车用 _)" "_")
    bash deploy/nginx-setup.sh "$SN"
  fi
else
  warn "跳过(后续:sudo bash deploy/nginx-setup.sh [域名])"
fi

# ---------- 完成 ----------
echo
echo "${G}═══════════════════════════════════════════════${X}"
if [[ "$IS_ITERATION" == "1" ]]; then
  echo "${G}  发版完成 · commit $(git rev-parse --short HEAD 2>/dev/null || echo n/a)${X}"
  echo "${G}═══════════════════════════════════════════════${X}"
  echo "DB 备份:  $BACKUP_FILE"
  echo "旧 jar:   /opt/finance/app.jar.prev"
  echo
  echo "24h 内无问题 → rm /opt/finance/app.jar.prev"
  echo "回滚:bash deploy/rollback.sh"
else
  echo "${G}  首装完成 · 服务在 :${SERVER_PORT} 监听${X}"
  echo "${G}═══════════════════════════════════════════════${X}"
  echo
  echo "浏览器访问 http://<server-ip>/(装 nginx 走 :80)"
  echo "          http://<server-ip>:${SERVER_PORT}/(无 nginx)"
  echo
  echo "默认账号:diwa / wangergou + 你刚设的临时密码(默认 demo1234)"
  echo "首次登入会强制改密。"
  echo
  echo "下次发版迭代:git pull && sudo bash deploy/deploy.sh"
fi
echo
echo "日志:sudo journalctl -u finance -f"
