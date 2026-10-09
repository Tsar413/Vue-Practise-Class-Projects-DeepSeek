#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/review-env.sh  隔离审查环境（独立库 + 独立运行目录 + 独立图片目录 + 独立后端端口）
#
# 目的：在不触碰现有 vue_practise_backend 库、不触碰现有 MySQL 数据目录、
#       不占用 8100/5173 的前提下，起一套完全隔离的后端，用于写操作回归。
#
# 隔离维度：
#   数据库      DB_NAME=vue_practise_review_20261009（新建，绝不 RESET 现有库）
#   应用账号    <DB_NAME>_app（新建独立账号，不改动默认账号 vue_practice 的口令与权限）
#   运行目录    <项目>/.runtime/review-20261009/run
#   图片目录    <项目>/.runtime/review-20261009/repair-files
#   后端端口    18100（只监听本机）
#   前端        默认不启动（审查不需要）
#
# 关键点：
#   * DB_NAME 会实际驱动后端的 DB_URL，并用 sed 把 db/*.sql 的库名改写为同一个库名，
#     保证「建库 / 建表 / 授权 / 种子 / 后端连接」五处始终一致。
#   * 现有库一旦已存在就不重复导入种子数据（seed 使用 ON DUPLICATE KEY UPDATE 幂等，
#     但仍避免对现有库做任何写操作）。
#   * 只复用已运行的 MySQL（START_MYSQL=0），不启动、不停止、不改动现有 MySQL 实例。
#
# 用法：
#   bash scripts/review-env.sh init     # 建库建表 + 演示数据（不删库）
#   bash scripts/review-env.sh start    # 启动隔离后端（18100）
#   bash scripts/review-env.sh stop     # 停止隔离后端（只停本项目进程组）
#   bash scripts/review-env.sh status   # 查看隔离环境状态
#   bash scripts/review-env.sh env      # 打印环境变量，供 source 使用
# ---------------------------------------------------------------------------
set -uo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REVIEW_ID="${REVIEW_ID:-review-20261009}"
RUNTIME_DIR="${RUNTIME_DIR:-$ROOT/.runtime/$REVIEW_ID}"
DB_NAME="${DB_NAME:-vue_practise_review_20261009}"
BACKEND_PORT="${BACKEND_PORT:-18100}"
REPAIR_FILES_ROOT="${REPAIR_FILES_ROOT:-$RUNTIME_DIR/repair-files}"
RUN_DIR="${RUN_DIR:-$RUNTIME_DIR/run}"
LOG_DIR="${LOG_DIR:-$RUN_DIR}"
STATE_FILE="${STATE_FILE:-$RUN_DIR/env.state}"

MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/opt/jdk-21.0.12.1+1}"
MAVEN_HOME="${MAVEN_HOME:-$HOME/tools/opt/apache-maven-3.9.16}"
EXTRA_LIB="${EXTRA_LIB:-$HOME/tools/opt/lib}"

# 复用已运行的现有 MySQL：数据目录与 socket 都由环境变量指定，不做任何改动
DB_SOCKET="${DB_SOCKET:-$HOME/var/mysql/db.sock}"
MYSQL_DATA_DIR="${MYSQL_DATA_DIR:-$HOME/var/mysql/data}"

# 隔离库使用独立账号，避免影响默认账号
DB_USER="${DB_USERNAME:-${DB_NAME}_app}"
DB_PASS="${DB_PASSWORD:-${DB_NAME}_local}"

export LD_LIBRARY_PATH="$EXTRA_LIB:${LD_LIBRARY_PATH:-}"
export JAVA_HOME

MYSQL=("$MYSQL_HOME/bin/mysql" --no-defaults --socket="$DB_SOCKET" -uroot)

# 护栏 1：隔离库名必须包含 review，避免误伤现有库
case "$DB_NAME" in
  *review*) ;;
  *) echo "拒绝执行：隔离审查环境的 DB_NAME 必须包含 review（当前：$DB_NAME）" >&2; exit 2;;
esac
# 护栏 2：端口必须是隔离端口，禁止指向 8100/5173 等常规端口
case "$BACKEND_PORT" in
  8100|5173|4173|3306)
    echo "拒绝执行：隔离环境不得使用常规端口 $BACKEND_PORT，请改用 18100 等专用端口" >&2; exit 2;;
esac
case "$BACKEND_PORT" in ''|*[!0-9]*) echo "非法端口：$BACKEND_PORT" >&2; exit 2;; esac
if [ "$BACKEND_PORT" -lt 1024 ] || [ "$BACKEND_PORT" -gt 65535 ]; then
  echo "端口越界：$BACKEND_PORT" >&2; exit 2
fi

# 护栏 3：图片目录必须在隔离运行目录内，禁止指向项目默认 repair-files
case "$REPAIR_FILES_ROOT" in
  "$RUNTIME_DIR"/*) ;;
  *) echo "拒绝执行：REPAIR_FILES_ROOT 必须位于隔离运行目录 $RUNTIME_DIR 内（当前：$REPAIR_FILES_ROOT）" >&2; exit 2;;
esac

# Codex：解析真实目录，防止路径回退或符号链接把测试文件指向正常目录。
python3 - "$ROOT" "$RUNTIME_DIR" "$REPAIR_FILES_ROOT" "$RUN_DIR" <<'PYGUARD' || exit 2
from pathlib import Path
import sys
root, runtime, images, run = map(lambda v: Path(v).resolve(), sys.argv[1:])
assert runtime.is_relative_to(root/'.runtime') and runtime != root/'.runtime/run', 'Invalid isolated runtime'
assert images.is_relative_to(runtime) and run.is_relative_to(runtime), 'Invalid isolated data directory'
PYGUARD

# 护栏 4：标识符只允许字母数字下划线
printf '%s' "$DB_NAME" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]{0,63}$' \
  || { echo "非法库名：$DB_NAME" >&2; exit 2; }
printf '%s' "$DB_USER" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]{0,31}$' \
  || { echo "非法账号名：$DB_USER" >&2; exit 2; }

db_url() {
  printf 'jdbc:mysql://127.0.0.1:3306/%s?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&serverTimezone=GMT%%2B8' "$DB_NAME"
}

require_mysql() {
  "${MYSQL[@]}" -e 'SELECT 1' >/dev/null 2>&1 || {
    echo "无法连接本机 MySQL（socket=$DB_SOCKET）。请先启动 MySQL，本脚本不会自行启动或改动它。" >&2
    exit 1; }
}

db_exists() {
  "${MYSQL[@]}" -N -B -e \
    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB_NAME';" 2>/dev/null | grep -q '^1$'
}

# env：只输出非秘密配置。
# 口令不打印到终端（终端输出可能进入日志或截图）；需要时由调用方通过
# DB_PASSWORD 环境变量自行提供，脚本内部默认值只在进程内使用。
cmd_env() {
  cat <<ENV
export REVIEW_ID='$REVIEW_ID'
export RUNTIME_DIR='$RUNTIME_DIR'
export DB_NAME='$DB_NAME'
export DB_USERNAME='$DB_USER'
export DB_URL='$(db_url)'
export BACKEND_PORT='$BACKEND_PORT'
export REPAIR_FILES_ROOT='$REPAIR_FILES_ROOT'
export RUN_DIR='$RUN_DIR'
export STATE_FILE='$STATE_FILE'
export API_BASE='http://127.0.0.1:$BACKEND_PORT'
# DB_PASSWORD 未输出：请使用脚本内默认值，或自行 export DB_PASSWORD=...
ENV
}

cmd_init() {
  require_mysql
  mkdir -p "$REPAIR_FILES_ROOT" "$LOG_DIR"

  if db_exists; then
    echo "隔离库 $DB_NAME 已存在：跳过建库与种子导入，不对它做任何写操作。"
    return 0
  fi

  echo "创建隔离库 $DB_NAME（通过 init-db.sh，保证建库/建表/授权/种子库名一致）"
  DB_NAME="$DB_NAME" DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASS" \
    DB_SOCKET="$DB_SOCKET" MYSQL_HOME="$MYSQL_HOME" EXTRA_LIB="$EXTRA_LIB" \
    RUN_DIR="$HOME/var" \
    bash "$ROOT/scripts/init-db.sh" || return 1

  echo "隔离图片目录：$REPAIR_FILES_ROOT"
  echo "隔离运行目录：$LOG_DIR"
}

cmd_start() {
  mkdir -p "$REPAIR_FILES_ROOT" "$LOG_DIR"
  require_mysql
  db_exists || { echo "隔离库 $DB_NAME 不存在，请先执行：bash scripts/review-env.sh init" >&2; exit 1; }

  echo "== 启动隔离后端（端口 $BACKEND_PORT，库 $DB_NAME） =="
  DB_URL="$(db_url)" \
  DB_USERNAME="$DB_USER" \
  DB_PASSWORD="$DB_PASS" \
  REPAIR_FILES_ROOT="$REPAIR_FILES_ROOT" \
  RUN_DIR="$RUN_DIR" LOG_DIR="$LOG_DIR" STATE_FILE="$STATE_FILE" \
  LOCK_FILE="$RUN_DIR/.env.lock" \
  BACKEND_PORT="$BACKEND_PORT" \
  MYSQL_DATA_DIR="$MYSQL_DATA_DIR" DB_SOCKET="$DB_SOCKET" \
  SERVER_ADDRESS=127.0.0.1 \
  START_MYSQL=0 START_BACKEND=1 START_FRONTEND=0 \
  MYSQL_HOME="$MYSQL_HOME" JAVA_HOME="$JAVA_HOME" MAVEN_HOME="$MAVEN_HOME" EXTRA_LIB="$EXTRA_LIB" \
  bash "$ROOT/scripts/start-all.sh"
}

cmd_stop() {
  RUN_DIR="$RUN_DIR" LOG_DIR="$LOG_DIR" STATE_FILE="$STATE_FILE" \
    LOCK_FILE="$RUN_DIR/.env.lock" \
    BACKEND_PORT="$BACKEND_PORT" FRONTEND_PORT="${FRONTEND_PORT:-1}" \
    DB_SOCKET="$DB_SOCKET" MYSQL_HOME="$MYSQL_HOME" EXTRA_LIB="$EXTRA_LIB" \
    bash "$ROOT/scripts/stop-all.sh"
}

cmd_status() {
  echo "=== 隔离审查环境状态 ==="
  echo "库名        : $DB_NAME"
  echo "后端端口    : $BACKEND_PORT"
  echo "运行目录    : $LOG_DIR"
  echo "图片目录    : $REPAIR_FILES_ROOT"
  echo "状态文件    : $STATE_FILE"
  echo
  if curl -sS -m 3 "http://127.0.0.1:$BACKEND_PORT/hello" 2>/dev/null | grep -q 'Hello Spring Boot!'; then
    echo "隔离后端    : 运行中（http://127.0.0.1:$BACKEND_PORT）"
  else
    echo "隔离后端    : 未运行"
  fi
  require_mysql
  if db_exists; then
    local tables users
    tables=$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")
    users=$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM \`$DB_NAME\`.sys_user;" 2>/dev/null || echo 0)
    echo "隔离库      : 存在（$tables 张表，$users 个系统账号）"
  else
    echo "隔离库      : 不存在"
  fi
  if "${MYSQL[@]}" -N -B -e \
       "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='vue_practise_backend';" 2>/dev/null | grep -q '^1$'; then
    local t2 u2
    t2=$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='vue_practise_backend';")
    u2=$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM vue_practise_backend.sys_user;" 2>/dev/null || echo 0)
    echo "现有默认库  : 存在（$t2 张表，$u2 个系统账号）—— 本脚本从不修改它"
  fi
}

case "${1:-status}" in
  init)   cmd_init ;;
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  env)    cmd_env ;;
  *) echo "用法：bash scripts/review-env.sh {init|start|stop|status|env}" >&2; exit 2 ;;
esac
