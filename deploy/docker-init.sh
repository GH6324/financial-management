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

# 探测 compose 命令(v2 插件 `docker compose` 优先,回退老版 `docker-compose`)
DC=""
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
fi

if [[ -n "$DC" ]]; then
  echo "  下一步:$DC up -d"
else
  echo "  ⚠ 没探测到可用的 compose:"
  echo "    · 装了 Docker Desktop / OrbStack 一般自带 \`docker compose\`(v2),确认它在运行"
  echo "    · 用 Homebrew 装的 docker CLI:再 \`brew install docker-compose\`,并按提示软链到"
  echo "      ~/.docker/cli-plugins/docker-compose(否则 \`docker compose\` 带空格的写法用不了)"
  echo "    · 实在不行可直接用老版:\`docker-compose up -d\`"
fi
echo "  LLM key / 短信 aksk / 阈值 等运营参数,登录后走管理页配置(不在 .env 里)。"
