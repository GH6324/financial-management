#!/usr/bin/env bash
# 家庭账房 · v0.7 · 首次初始化 .env(生成随机密钥)
# 用法:在仓库根目录 `bash deploy/docker-init.sh`,然后 `docker compose up -d`
set -euo pipefail

cd "$(dirname "$0")/.."   # 仓库根

if [[ -f .env ]]; then
  echo "✗ .env 已存在,不覆盖。要重置先删 .env(注意会换密钥)。"; exit 1
fi
[[ -f .env.example ]] || { echo "✗ 找不到 .env.example,确认在仓库根目录跑"; exit 1; }

rand() { openssl rand -hex "${1:-24}"; }

DB_PASS="$(rand 18)"
ROOT_PASS="$(rand 18)"
RMK="$(rand 32)"

cp .env.example .env
# 跨平台 sed(GNU/BSD 都用 -i.bak 再删)
sed -i.bak \
  -e "s|^DB_PASS=.*|DB_PASS=${DB_PASS}|" \
  -e "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=${ROOT_PASS}|" \
  -e "s|^REMEMBER_ME_KEY=.*|REMEMBER_ME_KEY=${RMK}|" \
  .env
rm -f .env.bak

chmod 600 .env
echo "✓ 已生成 .env(随机 DB 密码 / root 密码 / REMEMBER_ME_KEY)"

# 探测环境是否已就绪:引擎(daemon)起了吗 + Compose V2 在吗
# 三件事(docker 装没装 / 引擎起没起 / compose 有没有)在这里只做一个「就绪?」判断,
# 不就绪就不在这儿给零碎建议(容易把「根本没装 docker」的人误导去装孤零零的 compose 插件)——
# 直接交给统一的一键脚本 docker-up.sh,它会逐项自检并按你这台机器给出可复制的修复命令。
DC=""
if docker info >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    DC="docker compose"
  elif command -v docker-compose >/dev/null 2>&1; then
    case "$(docker-compose version --short 2>/dev/null || true)" in 2.*|v2.*) DC="docker-compose" ;; esac
  fi
fi

if [[ -n "$DC" ]]; then
  echo "  环境已就绪,下一步:$DC up -d"
else
  echo ""
  echo "  ⚠ 这台机器的 Docker 环境还没就绪 —— 可能没装 docker、引擎(daemon)没启动、或缺 Compose V2。"
  echo "    别自己一步步猜(在 Mac 上尤其容易踩坑:brew 装的 docker 只是命令行、引擎在虚拟机里要单独起)。"
  echo "    直接跑下面这条,它会自检并按你的系统给出可直接复制的修复命令,再把服务起起来:"
  echo ""
  echo "        bash deploy/docker-up.sh"
  echo ""
fi
echo "  LLM key / 短信 aksk / 阈值 等运营参数,登录后走管理页配置(不在 .env 里)。"
