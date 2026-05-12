#!/usr/bin/env bash
# =========================================================
# db/apply.sh — 顺序执行 db/migration/V*__*.sql,跳过已执行
# 见 TDD § 3.3
#
# 用法:
#   DB_HOST=127.0.0.1 DB_PORT=3306 \
#   DB_USER=finance DB_PASS=xxx DB_NAME=finance \
#   ./db/apply.sh
# =========================================================
set -euo pipefail

# 若 DB_* 没在 env 里,自动 source /etc/finance.env(prod 标准路径)
if [[ -z "${DB_USER:-}" && -r /etc/finance.env ]]; then
  set -a; . /etc/finance.env; set +a
fi

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:?DB_USER 环境变量未设置(可在 /etc/finance.env 配置)}"
DB_PASS="${DB_PASS:?DB_PASS 环境变量未设置(可在 /etc/finance.env 配置)}"
DB_NAME="${DB_NAME:?DB_NAME 环境变量未设置(可在 /etc/finance.env 配置)}"

# 用 MYSQL_PWD env var 而不是 -p 参数,避免每次调用都打 "Using a password" warning
export MYSQL_PWD="$DB_PASS"
MYSQL=( mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" )

# 1) 确保 history 表存在(幂等)
"${MYSQL[@]}" <<'SQL'
CREATE TABLE IF NOT EXISTS schema_history (
    filename     VARCHAR(255) NOT NULL,
    checksum     CHAR(64)     NOT NULL,
    applied_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_schema_history PRIMARY KEY (filename)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL

# 2) 遍历 V*__*.sql,排序后逐个 apply
SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
MIGRATION_DIR="$SCRIPT_DIR/migration"

if [ ! -d "$MIGRATION_DIR" ]; then
    echo "✗ migration 目录不存在: $MIGRATION_DIR" >&2
    exit 1
fi

shopt -s nullglob
files=( "$MIGRATION_DIR"/V*__*.sql )
shopt -u nullglob

if [ ${#files[@]} -eq 0 ]; then
    echo "(no migration files found)"
    exit 0
fi

# 文件名自然排序(V1__/V2__/.../V10__)
IFS=$'\n' files=( $( printf "%s\n" "${files[@]}" | sort -V ) )
unset IFS

for f in "${files[@]}"; do
    name=$(basename "$f")
    expected=$(sha256sum "$f" | cut -d' ' -f1)
    applied=$( "${MYSQL[@]}" -sN -e "SELECT checksum FROM schema_history WHERE filename='$name'" || true )

    if [ -z "$applied" ]; then
        echo "→ applying $name"
        "${MYSQL[@]}" < "$f"
        "${MYSQL[@]}" -e "INSERT INTO schema_history (filename, checksum) VALUES ('$name', '$expected')"
    elif [ "$applied" != "$expected" ]; then
        # 允许已知 override 名单中的文件自动更新 checksum
        # (用于仅注释 / 种子 display_name 之类不影响 DDL 的修订 · 一次性场景)
        OVERRIDES_FILE="$MIGRATION_DIR/.checksum-overrides"
        if [ -f "$OVERRIDES_FILE" ] && grep -qE "^${name}([[:space:]]|$)" "$OVERRIDES_FILE"; then
            REASON=$(grep -E "^${name}([[:space:]]|$)" "$OVERRIDES_FILE" | head -1 | awk -F$'\t' '{ for(i=2;i<=NF;i++) printf "%s ", $i }' | sed 's/[[:space:]]*$//')
            echo "⚠ $name checksum 偏离 · 在 .checksum-overrides 名单 · 自动更新"
            echo "   旧 = $applied"
            echo "   新 = $expected"
            echo "   原因 = ${REASON:-(未注明)}"
            "${MYSQL[@]}" -e "UPDATE schema_history SET checksum='$expected', applied_at=CURRENT_TIMESTAMP WHERE filename='$name'"
        else
            echo "✗ $name 校验失败:" >&2
            echo "  已执行 checksum = $applied" >&2
            echo "  当前文件 checksum = $expected" >&2
            echo "  已发布版本不允许修改;请新增一个 V<n+1>__... .sql 来修复" >&2
            echo "  (如果改动确实只涉及注释/种子且不影响 DDL,可加入 db/migration/.checksum-overrides)" >&2
            exit 1
        fi
    else
        echo "✓ $name (skipped, already applied)"
    fi
done

echo "all migrations applied."
