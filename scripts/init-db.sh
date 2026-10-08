#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/init-db.sh  初始化本地测试数据库
#
# 1. 建库建表（db/01-schema.sql）
# 2. 创建仅能访问本库的应用账号（可通过环境变量覆盖口令）
# 3. 导入虚构演示数据（db/02-seed-demo-data.sql）
#
# 用法：
#   bash scripts/init-db.sh                 # 全新初始化
#   RESET=1 bash scripts/init-db.sh         # 先删除同名数据库再重建
#
# 注意：脚本只操作本机 MySQL，不会连接任何线上数据库。
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
RUN_DIR="${RUN_DIR:-$HOME/var}"
DB_DIR="$RUN_DIR/mysql"
SOCKET="${DB_SOCKET:-$DB_DIR/db.sock}"
DB_NAME="${DB_NAME:-vue_practise_backend}"
DB_USER="${DB_USERNAME:-vue_practice}"
DB_PASS="${DB_PASSWORD:-vue_practice_local}"
export LD_LIBRARY_PATH="${EXTRA_LIB:-$HOME/tools/opt/lib}:${LD_LIBRARY_PATH:-}"

MYSQL="$MYSQL_HOME/bin/mysql --no-defaults --socket=$SOCKET -uroot"

if ! $MYSQL -e 'SELECT 1' >/dev/null 2>&1; then
  echo "无法连接本机 MySQL（socket=$SOCKET），请先执行 scripts/start-all.sh"
  exit 1
fi

if [ "${RESET:-0}" = "1" ]; then
  echo "== 删除旧数据库 $DB_NAME =="
  $MYSQL -e "DROP DATABASE IF EXISTS \`$DB_NAME\`;"
fi

echo "== 建库建表 =="
$MYSQL < "$ROOT/db/01-schema.sql"

echo "== 创建应用账号 =="
$MYSQL <<SQL
CREATE USER IF NOT EXISTS '$DB_USER'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '$DB_PASS';
ALTER USER '$DB_USER'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '$DB_PASS';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

echo "== 导入虚构演示数据 =="
$MYSQL "$DB_NAME" < "$ROOT/db/02-seed-demo-data.sql"

echo
echo "完成。演示账号（口令均为 123456）："
echo "  教师  DEMO_TEACHER"
echo "  学生  DEMO2026001 / DEMO2026002 / DEMO2026003"
echo "班级：DEMO2026（Vue 实训示范班）"
