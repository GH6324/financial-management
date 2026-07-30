#!/usr/bin/env bash
# 从备份恢复 · v1.6.28
#
# 为什么需要它:恢复是**最容易出错、后果最重**的操作,而在 v1.6.28 之前它只存在于 FAQ 里的一段裸命令 ——
# 要用户自己找文件、手填 root 密码、还得记得先停 app(不停就是边写边读,数据会花)。
#
# 本脚本的四条纪律:
#   ① 列出可用备份让你选,不用记文件名;
#   ② **灌入之前先把当前库 dump 一份**(叫 before-restore-*)—— 恢复错了还能回来;
#   ③ 全程停 app 再灌,灌完自动起回来;
#   ④ 要你输入 RESTORE 确认(这是覆盖性操作,不接受"顺手回车")。
#
# 用法:bash deploy/restore.sh [备份文件名]
#   非交互(灾备演练 / 自动化):FINANCE_RESTORE_CONFIRM=RESTORE bash deploy/restore.sh <备份文件名>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/_common-env.sh"

say "· 部署形态:${MODE}"
PICK="${1:-}"

list_docker(){ $DC exec -T backup sh -c "ls -1t ${BK_DIR}/*.sql.gz 2>/dev/null | head -20" | tr -d '\r'; }
list_systemd(){ ls -1t "${BK_DIR}"/*.sql.gz 2>/dev/null | head -20; }

if [[ "$MODE" == docker ]]; then FILES="$(list_docker)"; else FILES="$(list_systemd)"; fi
[[ -n "$FILES" ]] || die "没找到任何备份(${BK_DIR}/*.sql.gz)。
  先备份一次:bash deploy/backup-now.sh"

if [[ -z "$PICK" ]]; then
  say ""
  say "  可用备份(新→旧):"
  i=0; while IFS= read -r f; do i=$((i+1)); printf '    %2d) %s\n' "$i" "$(basename "$f")"; done <<< "$FILES"
  say ""
  printf '  选哪个?输序号(直接回车 = 1,最新的一份):'
  read -r n </dev/tty || n=""
  n="${n:-1}"
  case "$n" in ''|*[!0-9]*) die "序号得是数字" ;; esac
  PICK="$(printf '%s\n' "$FILES" | sed -n "${n}p")"
  [[ -n "$PICK" ]] || die "没有第 ${n} 个"
else
  case "$PICK" in */*) : ;; *) PICK="${BK_DIR}/${PICK}" ;; esac
fi
say ""
say "  将恢复:$(basename "$PICK")"
say "  ⚠ 这会用这份备份**覆盖当前数据库的全部内容**。恢复前我会先把当前库另存一份(before-restore-*),"
say "    所以万一选错还能回来 —— 但请仍然确认一下这是你要的那份。"
# 确认闸门:交互时要手输 RESTORE;非交互(CI / 灾备演练脚本)要显式传
# FINANCE_RESTORE_CONFIRM=RESTORE。两者都没有 → 拒绝执行(fail-closed:覆盖性操作不接受默认同意)。
ans="${FINANCE_RESTORE_CONFIRM:-}"
if [[ -z "$ans" ]]; then
  if [[ -e /dev/tty ]]; then
    printf '  确认恢复?输入 RESTORE(区分大小写)才继续:'
    read -r ans </dev/tty || ans=""
  else
    die "非交互环境且没有 FINANCE_RESTORE_CONFIRM=RESTORE → 已拒绝执行(覆盖性操作不接受默认同意)。"
  fi
fi
[[ "$ans" == "RESTORE" ]] || die "已取消,什么都没动。"

STAMP="$(date +%Y%m%d-%H%M%S)"
if [[ "$MODE" == docker ]]; then
  DBU="$(envval DB_USER)"; DBU="${DBU:-finance}"
  DBN="$(envval DB_NAME)"; DBN="${DBN:-finance}"
  DBP="$(envval DB_PASS)"; [[ -n "$DBP" ]] || die "读不到 .env 里的 DB_PASS"
  SAFE="${BK_DIR}/before-restore-${STAMP}.sql.gz"
  say "· 先把当前库另存:$(basename "$SAFE")"
  # 必须 gzip + 校验:这份是"恢复选错时用来撤销"的退路,坏了比没有更糟。
  # v1.6.28 第一版这里写的是 `cat > 文件.sql.gz`(忘了压)—— 退路本身不可用。
  $DC exec -T db env MYSQL_PWD="$DBP" mysqldump --no-tablespaces --single-transaction --quick \
      -u"$DBU" "$DBN" 2>/dev/null | $DC exec -T backup sh -c "gzip -9 > ${SAFE}" \
    || die "另存当前库失败 → **中止恢复**(不能在没有退路的情况下覆盖)"
  $DC exec -T backup sh -c "gunzip -t ${SAFE} && gunzip -c ${SAFE} | head -40 | grep -q 'CREATE TABLE'" \
    || die "另存的那份校验不过 → **中止恢复**(退路不可用就不动当前库)"
  say "· 停 app(避免边写边读)…"; $DC stop app >/dev/null 2>&1 || true
  say "· 灌入备份…"
  if $DC exec -T backup sh -c "gunzip -c ${PICK}" \
     | $DC exec -T db env MYSQL_PWD="$DBP" mysql -u"$DBU" "$DBN"; then
    say "  ✓ 恢复完成"
  else
    say "  ✗ 恢复失败 —— 当前库可能处于半灌状态。回到恢复前:"
    say "      bash deploy/restore.sh $(basename "$SAFE")"
    $DC start app >/dev/null 2>&1 || true
    die "恢复失败(另存的那份是 $(basename "$SAFE"))"
  fi
  say "· 起回 app…"; $DC start app >/dev/null 2>&1 || true
  PORT="$(envval SERVER_PORT)"; PORT="${PORT:-20000}"
  for i in $(seq 1 45); do
    curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1 && { say "  ✓ 应用已就绪 → http://127.0.0.1:${PORT}"; break; }
    sleep 2
  done
else
  set -a; . /etc/finance.env; set +a
  SAFE="${BK_DIR}/before-restore-${STAMP}.sql.gz"
  say "· 先把当前库另存:$SAFE"
  MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
      -u"$DB_USER" "$DB_NAME" 2>/dev/null | gzip > "$SAFE" \
    || die "另存当前库失败 → **中止恢复**"
  { gunzip -t "$SAFE" && gunzip -c "$SAFE" | head -40 | grep -q 'CREATE TABLE'; } \
    || die "另存的那份校验不过 → **中止恢复**(退路不可用就不动当前库)"
  say "· 停 finance…"; sudo systemctl stop finance || true
  if gunzip -c "$PICK" | MYSQL_PWD="$DB_PASS" mysql -u"$DB_USER" "$DB_NAME"; then
    say "  ✓ 恢复完成"
  else
    sudo systemctl start finance || true
    die "恢复失败(另存的那份是 $SAFE · 可用 bash deploy/restore.sh $SAFE 回到恢复前)"
  fi
  say "· 起回 finance…"; sudo systemctl start finance || true
fi
say ""
say "  恢复前的那份留在:$(basename "$SAFE")(想撤销这次恢复就恢复它)"
