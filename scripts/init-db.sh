#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/init-db.sh  首次初始化数据库
#
# 只做三件事：
#   1. 建库建表（把 db/01-schema.sql 中的默认库名改写为本次使用的库名）
#   2. 新建只能访问该库的本机应用账号
#   3. 导入虚构演示数据（db/02-seed-demo-data.sql，同样改写库名）
#
# 只负责「首次创建」，不提供任何删除或重建入口：
#   * 目标库已存在        → 直接拒绝退出，不对已有库做任何写入；
#   * 目标账号已存在      → 直接拒绝退出，不改口令、不改权限（避免扩权）；
#   * 需要全新环境时，换一个库名即可，例如：
#       DB_NAME=vue_practise_backend_2 bash scripts/init-db.sh
#
# 安全约定：
#   * 只连接本机 MySQL；DB_HOST 仅允许回环地址，避免误连其他机器。
#   * 库名 / 账号名 / 口令在拼 SQL 前做字符校验（含换行与控制字符）。
#   * 自定义库必须使用由库名派生的独立账号，不得复用默认账号 vue_practice。
#
# 用法：
#   bash scripts/init-db.sh                                  # 初始化默认库
#   DB_NAME=my_demo_v1 bash scripts/init-db.sh               # 初始化独立库
#   DB_NAME=my_demo_v1 DB_PASSWORD='...' bash scripts/init-db.sh
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
RUN_DIR="${RUN_DIR:-$HOME/var}"
DB_DIR="$RUN_DIR/mysql"
SOCKET="${DB_SOCKET:-$DB_DIR/db.sock}"
DB_HOST="${DB_HOST:-127.0.0.1}"

DEFAULT_DB_NAME="vue_practise_backend"
DEFAULT_DB_USER="vue_practice"
# 与 backend/src/main/resources/application.yml 的默认值保持一致
DEFAULT_DB_PASS="vue_practice_local"

DB_NAME="${DB_NAME:-$DEFAULT_DB_NAME}"

if [ "$DB_NAME" = "$DEFAULT_DB_NAME" ]; then
  DB_USER="${DB_USERNAME:-$DEFAULT_DB_USER}"
  DB_PASS="${DB_PASSWORD:-$DEFAULT_DB_PASS}"
else
  DB_USER="${DB_USERNAME:-${DB_NAME}_app}"
  DB_PASS="${DB_PASSWORD:-${DB_NAME}_local}"
  if [ "$DB_USER" = "$DEFAULT_DB_USER" ]; then
    echo "拒绝执行：自定义库（$DB_NAME）不得复用默认账号 $DEFAULT_DB_USER。" >&2
    echo "如确需指定，请通过 DB_USERNAME 指定一个与库名相关且独立的账号名。" >&2
    exit 2
  fi
fi

export LD_LIBRARY_PATH="${EXTRA_LIB:-$HOME/tools/opt/lib}:${LD_LIBRARY_PATH:-}"
MYSQL_BIN="$MYSQL_HOME/bin/mysql"
[ -x "$MYSQL_BIN" ] || { echo "找不到 mysql 客户端：$MYSQL_BIN" >&2; exit 1; }

# ---------------------------- 参数校验 -------------------------------------
case "$DB_HOST" in
  127.0.0.1|localhost|::1) ;;
  *) echo "拒绝执行：DB_HOST 只允许本机回环地址（127.0.0.1 / localhost / ::1），收到：$DB_HOST" >&2; exit 2;;
esac

if ! printf '%s' "$DB_NAME" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]{0,63}$'; then
  echo "非法的 DB_NAME：只允许字母、数字、下划线，最长 64，且不能以数字开头" >&2
  exit 2
fi
if ! printf '%s' "$DB_USER" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]{0,31}$'; then
  echo "非法的 DB_USERNAME：只允许字母、数字、下划线，最长 32，且不能以数字开头" >&2
  exit 2
fi

# 口令会被拼进 SQL 字符串字面量，因此逐项排除破坏字面量的字符。
# 注意：grep 是按行匹配的，检测不出换行，必须单独判断。
if [ -z "$DB_PASS" ]; then
  echo "拒绝执行：DB_PASSWORD 不能为空" >&2
  exit 2
fi
if [ "${#DB_PASS}" -gt 64 ]; then
  echo "拒绝执行：DB_PASSWORD 长度不能超过 64" >&2
  exit 2
fi
case "$DB_PASS" in
  *"'"*|*'"'*|*'\'*|*'`'*)
    echo "拒绝执行：DB_PASSWORD 不能包含单引号、双引号、反斜杠或反引号。" >&2
    exit 2;;
esac
case "$DB_PASS" in
  *$'\n'*|*$'\r'*|*$'\t'*)
    echo "拒绝执行：DB_PASSWORD 不能包含换行、回车或制表符。" >&2
    exit 2;;
esac
case "$DB_PASS" in
  *[[:cntrl:]]*)
    echo "拒绝执行：DB_PASSWORD 不能包含控制字符。" >&2
    exit 2;;
esac

MYSQL=("$MYSQL_BIN" --no-defaults --socket="$SOCKET" -uroot)

if ! "${MYSQL[@]}" -e 'SELECT 1' >/dev/null 2>&1; then
  echo "无法连接本机 MySQL（socket=$SOCKET）" >&2
  echo "请先启动 MySQL：bash scripts/start-all.sh" >&2
  exit 1
fi

db_exists() {
  "${MYSQL[@]}" -N -B -e \
    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB_NAME';" 2>/dev/null | grep -q '^1$'
}

user_exists() {
  "${MYSQL[@]}" -N -B -e \
    "SELECT COUNT(*) FROM mysql.user WHERE user='$DB_USER' AND host='$DB_HOST';" 2>/dev/null | grep -q '^1$'
}

# ------------------- 前置检查：库或账号已存在直接拒绝 -----------------------
# 这一步在任何写操作之前完成，保证「已存在的库/账号」不会被本脚本改动。
if db_exists; then
  echo "已存在数据库：\`$DB_NAME\`。本脚本只做首次初始化，不覆盖既有数据，已退出。" >&2
  echo "如需全新环境，请换一个库名，例如：" >&2
  echo "  DB_NAME=${DB_NAME}_v2 bash scripts/init-db.sh" >&2
  exit 5
fi
if user_exists; then
  echo "已存在数据库账号：$DB_USER@$DB_HOST。为避免修改既有口令或扩大权限，本脚本不再继续。" >&2
  echo "如需全新环境，请指定另一个账号，例如：" >&2
  echo "  DB_NAME=$DB_NAME DB_USERNAME=${DB_USER}_v2 bash scripts/init-db.sh" >&2
  exit 6
fi

# ---------------------------- 建库建表 --------------------------------------
echo "== 建库建表（库：$DB_NAME） =="
sed "s/\`$DEFAULT_DB_NAME\`/\`$DB_NAME\`/g" "$ROOT/db/01-schema.sql" | "${MYSQL[@]}"

# ---------------------------- 新建应用账号 ----------------------------------
echo "== 新建应用账号（$DB_USER@$DB_HOST） =="
"${MYSQL[@]}" <<SQL
CREATE USER '$DB_USER'@'$DB_HOST' IDENTIFIED WITH mysql_native_password BY '$DB_PASS';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'$DB_HOST';
FLUSH PRIVILEGES;
SQL

# 校验：账号确实只被授予本库权限。
# 注意：SHOW GRANTS 一般会带一行 "GRANT USAGE ON *.*"，这是「无权限」的占位行，
# 必须排除，否则会被误判为拥有全局权限。
GRANTS=$("${MYSQL[@]}" -N -B -e "SHOW GRANTS FOR '$DB_USER'@'$DB_HOST';" 2>/dev/null || true)
REAL_GRANTS=$(printf '%s\n' "$GRANTS" \
  | grep -v 'GRANT USAGE ON \*\.\*' \
  | grep -v '^$' || true)
if [ -z "$REAL_GRANTS" ]; then
  echo "授权校验失败：账号 $DB_USER 没有任何实际权限" >&2
  exit 1
fi
if ! printf '%s\n' "$REAL_GRANTS" | grep -q "\`$DB_NAME\`"; then
  echo "授权校验失败：账号 $DB_USER 未被授予 \`$DB_NAME\` 的权限" >&2
  exit 1
fi
if printf '%s\n' "$REAL_GRANTS" | grep -qE 'ON \*\.\*|ON \`%\`\.\*'; then
  echo "安全校验失败：账号 $DB_USER 拥有本库以外的权限，请人工检查" >&2
  exit 1
fi
echo "   已新建账号并仅授权 \`$DB_NAME\`"

# ---------------------------- 种子数据 --------------------------------------
echo "== 导入虚构演示数据 =="
sed "s/\`$DEFAULT_DB_NAME\`/\`$DB_NAME\`/g" "$ROOT/db/02-seed-demo-data.sql" | "${MYSQL[@]}" "$DB_NAME"

# ---------------------------- 结果校验 --------------------------------------
TABLE_COUNT=$("${MYSQL[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")
USER_COUNT=$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM \`$DB_NAME\`.sys_user;")
[ "$TABLE_COUNT" -ge 15 ] || { echo "建表数量异常：$TABLE_COUNT" >&2; exit 1; }
[ "$USER_COUNT" -ge 1 ] || { echo "演示账号导入异常：$USER_COUNT" >&2; exit 1; }

echo
echo "完成。库：$DB_NAME（$TABLE_COUNT 张表，$USER_COUNT 个演示账号）"
echo "应用连接：jdbc:mysql://$DB_HOST:3306/$DB_NAME?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&serverTimezone=GMT%2B8"
echo "应用账号：$DB_USER（口令通过 DB_PASSWORD 环境变量提供，不在此输出）"
echo
echo "演示账号（口令见 db/02-seed-demo-data.sql 注释，属公开的本地演示口令）："
echo "  教师  DEMO_TEACHER"
echo "  学生  DEMO2026001 / DEMO2026002 / DEMO2026003"
echo "班级：DEMO2026（ Vue 实训示范班）"
