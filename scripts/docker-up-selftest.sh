#!/usr/bin/env bash
# docker-up.sh 拉镜像失败时的分诊自测(v1.26.1)· 不需要真的 docker / colima / Mac。
#
# 起因:一位 Mac + colima 用户装不上,脚本说「中国大陆访问 Docker Hub 被限速/阻断」,
# 然后去改 colima 虚拟机里的镜像源、重启引擎,重试照样失败。真实原因是虚拟机里 DNS 坏了
# (/etc/resolv.conf 是断掉的软链接 → 引擎去问 [::1]:53 → connection refused)。
# 脚本把 docker pull 的报错整个丢掉了,所以看不出来。
#
# 这里用假的 docker / colima / uname 顶替,各跑一遍 docker-up.sh:
#   dns → 必须说出「原因是 DNS」、带上 docker 原话、查出 resolv.conf 断链,并且**不许动镜像源配置**
#   tls → 仍走原来的「限速 → 配镜像源」,但要带上 docker 原话
# 用法:bash scripts/docker-up-selftest.sh   · 全过退出 0,否则打印哪条没过并退出 1
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d /tmp/docker-up-selftest.XXXXXX)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"

cat > "$T/bin/docker" <<'EOF'
#!/bin/bash
echo "docker $*" >> "$SELFTEST_LOG"
case "$1" in
  info) exit 0 ;;
  compose) [[ "$2" == version ]] && { echo "Docker Compose version v2.29.0"; exit 0; }; exit 0 ;;
  pull)
    img="$2"; host="${img%%/*}"; [[ "$img" == */* && "$host" == *.* ]] || host="registry-1.docker.io"
    case "$FAKE_PULL_ERR" in
      dns) echo "Error response from daemon: Get \"https://$host/v2/\": dial tcp: lookup $host on [::1]:53: read udp [::1]:51234->[::1]:53: read: connection refused" >&2 ;;
      tls) echo "Error response from daemon: Get \"https://$host/v2/\": net/http: TLS handshake timeout" >&2 ;;
    esac
    exit 1 ;;
esac
exit 0
EOF
cat > "$T/bin/colima" <<'EOF'
#!/bin/bash
echo "colima $*" >> "$SELFTEST_LOG"
case "$1" in
  status) exit 0 ;;
  ssh)
    [[ "$*" == *"test -e /etc/resolv.conf"* ]] && exit 1                       # 断链:目标不存在
    [[ "$*" == *"readlink /etc/resolv.conf"* ]] && { echo "/run/systemd/resolve/stub-resolv.conf"; exit 0; }
    exit 0 ;;
esac
exit 0
EOF
cat > "$T/bin/uname" <<'EOF'
#!/bin/bash
[[ "${1:-}" == "-s" ]] && { echo Darwin; exit 0; }; exec /usr/bin/uname "$@"
EOF
chmod +x "$T/bin/"*

fail=0
check(){ if eval "$2"; then echo "  ✓ $1"; else echo "  ✗ $1"; fail=1; fi; }

run_case(){   # $1 = dns|tls → 输出落在 $T/out-$1,调用记录落在 $T/calls-$1
  local k="$1" w="$T/repo-$1"
  mkdir -p "$w/deploy"; cp "$ROOT/deploy/docker-up.sh" "$w/deploy/"; cp "$ROOT/.env.example" "$w/"
  printf 'SERVER_PORT=29999\n' > "$w/.env"     # 不去碰本机真在跑的服务
  : > "$T/calls-$k"
  PATH="$T/bin:$PATH" SELFTEST_LOG="$T/calls-$k" FAKE_PULL_ERR="$k" FINANCE_NO_UPDATE_CHECK=1 \
    FINANCE_ASSUME_YES=1 timeout 120 bash "$w/deploy/docker-up.sh" </dev/null >"$T/out-$k" 2>&1
  return 0
}

echo "· DNS 坏了(colima 虚拟机里 resolv.conf 断链)"
run_case dns
check "说出原因是 DNS"                       "grep -q '原因是 DNS' '$T/out-dns'"
check "带上 docker 的原话"                    "grep -q 'on \[::1\]:53' '$T/out-dns'"
check "查出 resolv.conf 是断掉的链接"          "grep -q 'stub-resolv.conf' '$T/out-dns'"
check "不再说成「限速/阻断」"                  "! grep -q '限速/阻断的典型表现' '$T/out-dns'"
check "没去改镜像源、没重启引擎"               "! grep -qE 'tee /etc/docker/daemon.json|restart docker|colima restart' '$T/calls-dns'"
check "提醒别用 colima delete(会删数据库)"    "grep -q 'colima delete' '$T/out-dns'"

echo "· 网络超时(TLS handshake timeout)"
run_case tls
check "仍按限速处理并带上 docker 的原话"       "grep -q '限速/阻断的典型表现' '$T/out-tls' && grep -q 'TLS handshake timeout' '$T/out-tls'"
check "仍会去配镜像源(原来的处置没被改掉)"     "grep -q 'tee /etc/docker/daemon.json' '$T/calls-tls'"

if [[ $fail -ne 0 ]]; then
  echo "--- dns 输出 ---"; cat "$T/out-dns"; echo "--- tls 输出 ---"; cat "$T/out-tls"
  exit 1
fi
echo "全部通过"
