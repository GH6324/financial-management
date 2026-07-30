#!/usr/bin/env bash
# 一键诊断 · v1.6.28
#
# 为什么需要它:用户卡住时不知道该收集什么,我们也拿不到有效信息 —— 来回三四轮才问清"你是 Docker 还是直装、
# 哪个版本、容器起没起"。这条命令一次收齐,输出可以直接贴进 issue。
#
# 只读:不改任何配置、不重启任何东西。**已自动脱敏**(密码/密钥不外泄)。
# 用法:bash deploy/doctor.sh    (输出到终端;要存文件自己重定向)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/_common-env.sh"

h(){ printf '\n──── %s ────\n' "$*"; }
mask(){ sed -E 's/(PASS|PASSWORD|KEY|SECRET|TOKEN|AKSK|APIKEY)[A-Z_]*=.*/\1...=***已脱敏***/I'; }

echo "家庭账房 · 诊断报告 · $(date '+%F %T')"
echo "(只读 · 密码类已脱敏 · 可直接贴 issue)"

h "1. 部署形态与版本"
echo "形态: ${MODE}"
PORT="$(envval SERVER_PORT)"; PORT="${PORT:-20000}"
HV="$(curl -fsS -m 5 "http://127.0.0.1:${PORT}/health" 2>/dev/null || echo '(/health 不通)')"
echo "健康: ${HV}"
echo "仓库: $(git -C "$REPO_ROOT" describe --tags --always 2>/dev/null || echo '?') · 分支 $(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
echo "本地是否有未提交改动: $(test -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" && echo 是 || echo 否)"

h "2. 宿主环境"
echo "OS: $(uname -srm)"
command -v docker >/dev/null 2>&1 && echo "docker: $(docker --version 2>/dev/null)" || echo "docker: 未安装"
[[ -n "$DC" ]] && echo "compose: $($DC version --short 2>/dev/null || echo '?')"
echo "内存: $(free -h 2>/dev/null | awk '/^Mem:/{print $2" 总 / "$7" 可用"}' || echo '?')"
echo "磁盘: $(df -h "$REPO_ROOT" 2>/dev/null | awk 'NR==2{print $2" 总 / "$4" 可用 / 用了 "$5}')"

if [[ "$MODE" == docker ]]; then
  h "3. 容器状态"
  $DC ps 2>&1 | head -12
  h "4. 镜像版本"
  $DC images 2>/dev/null | head -8 || true
  h "5. 数据卷(数据在这里 · 别手删)"
  docker volume ls 2>/dev/null | grep -E "db-data|uploads|backups" || echo "(没找到本项目的卷)"
  h "6. 备份清单(最近 8 份)"
  $DC exec -T backup sh -c "ls -lht ${BK_DIR}/ 2>/dev/null | head -9" 2>/dev/null | tr -d '\r' || echo "(backup 容器没起,查不到)"
  h "7. app 最近的错误日志(最多 25 行)"
  $DC logs --tail=400 app 2>&1 | grep -iE "error|exception|caused by|refused|denied" | tail -25 || echo "(没有匹配到错误行)"
  h "8. db 最近日志(尾 10 行)"
  $DC logs --tail=10 db 2>&1 | tail -10
else
  h "3. 服务状态"
  systemctl status finance --no-pager 2>&1 | head -8
  h "4. 备份清单(最近 8 份)"
  ls -lht "$BK_DIR" 2>/dev/null | head -9 || echo "(${BK_DIR} 不可读)"
  h "5. 最近的错误日志(最多 25 行)"
  sudo -n journalctl -u finance --since "1 hour ago" 2>/dev/null | grep -iE "error|exception|caused by" | tail -25 \
    || echo "(拿不到 journal · 试 sudo journalctl -u finance)"
fi

h "9. .env 关键项(已脱敏)"
if [[ -f "$REPO_ROOT/.env" ]]; then
  grep -E "^(SERVER_PORT|DB_NAME|DB_USER|TZ|MYSQL_IMAGE|RETENTION_DAYS|FINANCE_)" "$REPO_ROOT/.env" 2>/dev/null | mask
  echo "(其余项含密钥,未输出)"
else
  echo "(仓库根目录没有 .env)"
fi

h "10. 常见自查"
echo "· 版本没变? → 镜像可能还在 CI 构建(约 12 分钟);见 bash deploy/docker-up.sh 的版本结论"
echo "· 数据像是空的? → 可能连到了另一个数据卷:docker volume ls | grep db-data"
echo "· 想恢复备份? → bash deploy/restore.sh(会先另存当前库)"
echo ""
echo "报告结束。贴 issue 时请连同你做了什么操作一起说明:https://github.com/LuoDi-Nate/financial-management/issues"
