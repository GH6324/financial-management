#!/usr/bin/env bash
# =========================================================
# 机器证明:我们发布的镜像里【没有富途的任何文件】(v1.17)
#
# 为什么这么查:只 grep Dockerfile 只能证明"我没写",证明不了"层里没有"
# (多阶段构建、COPY --from、base image 继承都能把文件带进来)。所以看的是
# 最终镜像 export 出来的完整文件清单 —— 所有层都在里面,写法躲不过。
#
# 用法:bash scripts/scan-image-no-futu.sh <镜像名或 digest>
# 退出码:0 = 干净;1 = 发现富途制品(CI 里据此让构建失败)
# =========================================================
set -uo pipefail

IMG="${1:-}"
[ -n "$IMG" ] || { echo "用法: $0 <image>" >&2; exit 2; }

# 富途制品特征:可执行文件名 / 它的动态库 / 安装包本身
PATTERNS='(^|/)(FutuOpenD|FTUpdate|FTWebSocket|F3CChart)$|libf3c[a-z]*\.so|Futu_?OpenD.*\.tar\.gz|\.AppImage$'

echo "· 扫描镜像层:$IMG"
CID="$(docker create "$IMG" true 2>/dev/null)" || { echo "✗ 无法创建容器(镜像拉到了吗?)" >&2; exit 2; }
trap 'docker rm -f "$CID" >/dev/null 2>&1' EXIT

HITS="$(docker export "$CID" | tar -tf - 2>/dev/null | grep -nE "$PATTERNS" | head -20)"

if [ -n "$HITS" ]; then
  echo "✗ 镜像里出现了富途制品 —— 我们不分发别人的软件,OpenD 必须运行时从官网下载:" >&2
  printf '%s\n' "$HITS" >&2
  exit 1
fi

echo "✓ 干净:镜像层里没有任何富途制品"
