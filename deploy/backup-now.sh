#!/usr/bin/env bash
# 立刻备份一次 · v1.6.28
#
# 为什么需要它:我们在文档里让用户"更新前先备份一下",却没给过一条命令 ——
# Docker 下备份是 sidecar 每 24h 自己跑的,想"现在就来一份"没有入口(v1.6.28 补)。
# 备份完会告诉你文件在哪、怎么拷到宿主机 —— 备份躺在命名卷里等于半个备份。
#
# 用法:bash deploy/backup-now.sh [输出目录]
#   给了输出目录 → 顺便把这份备份拷到宿主机那个目录(推荐:异地留一份)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/_common-env.sh"

OUT_DIR="${1:-}"
STAMP="$(date +%Y%m%d-%H%M%S)"
say "· 部署形态:${MODE}"

if [[ "$MODE" == docker ]]; then
  DBU="$(envval DB_USER)"; DBU="${DBU:-finance}"
  DBN="$(envval DB_NAME)"; DBN="${DBN:-finance}"
  DBP="$(envval DB_PASS)"
  [[ -n "$DBP" ]] || die "读不到 .env 里的 DB_PASS(在仓库根目录跑本脚本)"
  F="manual-${STAMP}.sql.gz"
  say "· 正在 dump(容器内 ${BK_DIR}/${F})…"
  # 在 db 容器里 dump,写到 backup 服务挂的同一个 backups 卷 —— 但 db 容器没挂那个卷,
  # 所以从 db 容器 dump 到 stdout,再由 backup 容器写盘(两边都在 compose 内网,不落宿主)。
  # 注意必须 **gzip**:文件名是 .sql.gz,内容就得真是 gzip。
  # v1.6.28 第一版这里写的是 `cat > 文件.sql.gz`(忘了压)→ 文件存在、du 看得到、
  # 于是打印了"✓ 已备份",但 restore 时 `gzip: not in gzip format` —— **备份是坏的而我报了成功**。
  # 靠"文件生成了"判断备份成功是不够的,见下面的完整性校验。
  if ! $DC exec -T db env MYSQL_PWD="$DBP" mysqldump --no-tablespaces --single-transaction --quick \
        -u"$DBU" "$DBN" 2>/dev/null | $DC exec -T backup sh -c "gzip -9 > ${BK_DIR}/${F}"; then
    $DC exec -T backup sh -c "rm -f ${BK_DIR}/${F}" >/dev/null 2>&1 || true
    die "备份失败。先看数据库起没起:$DC ps  ·  $DC logs --tail=30 db"
  fi
  # 完整性校验:能解压 + 解出来确实是 SQL dump(里面得有建表语句)。
  # **唯一能证明备份可用的是能不能读回来** —— 所以这一步不是可选的。
  if ! $DC exec -T backup sh -c "gunzip -t ${BK_DIR}/${F} && gunzip -c ${BK_DIR}/${F} | head -40 | grep -q 'CREATE TABLE'"; then
    $DC exec -T backup sh -c "rm -f ${BK_DIR}/${F}" >/dev/null 2>&1 || true
    die "备份文件校验不过(不是有效的 gzip 或不含建表语句)→ 已删掉,不留一个假备份。"
  fi
  SZ="$($DC exec -T backup sh -c "du -h ${BK_DIR}/${F} 2>/dev/null | cut -f1" | tr -d '\r')"
  say "  ✓ 已备份并校验通过:${BK_DIR}/${F}(${SZ:-?})"
  say ""
  say "  这份备份在 Docker 命名卷里(容器删了也还在,但**卷被删就没了**)。拷到宿主机:"
  say "    $DC cp backup:${BK_DIR}/${F} ./${F}"
  if [[ -n "$OUT_DIR" ]]; then
    mkdir -p "$OUT_DIR" || die "输出目录建不了:$OUT_DIR"
    if $DC cp "backup:${BK_DIR}/${F}" "${OUT_DIR}/${F}" 2>/dev/null; then
      say "  ✓ 已拷到宿主机:${OUT_DIR}/${F}"
    else
      say "  ✗ 拷到宿主机失败(compose 版本可能不支持 cp)· 手动跑上面那条命令"
    fi
  fi
  say "  看全部备份:$DC exec backup ls -lh ${BK_DIR}/"
else
  command -v mysqldump >/dev/null 2>&1 || die "没装 mysqldump(systemd 形态需要 mysql client)"
  set -a; . /etc/finance.env; set +a
  mkdir -p "$BK_DIR" || die "备份目录建不了:$BK_DIR(可能要 sudo)"
  F="${BK_DIR}/manual-${STAMP}.sql.gz"
  say "· 正在 dump → ${F} …"
  if ! MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
        -u"$DB_USER" "$DB_NAME" 2>/dev/null | gzip > "$F"; then
    rm -f "$F"; die "备份失败 · 看 systemctl status finance / mysql 是否可连"
  fi
  if ! { gunzip -t "$F" && gunzip -c "$F" | head -40 | grep -q 'CREATE TABLE'; }; then
    rm -f "$F"; die "备份文件校验不过 → 已删掉,不留一个假备份。"
  fi
  say "  ✓ 已备份并校验通过:${F}($(du -h "$F" | cut -f1))"
  [[ -n "$OUT_DIR" ]] && { mkdir -p "$OUT_DIR" && cp "$F" "$OUT_DIR/" && say "  ✓ 已复制到 ${OUT_DIR}/"; }
  say "  看全部备份:ls -lh ${BK_DIR}/"
fi
say ""
say "  恢复用:bash deploy/restore.sh"
