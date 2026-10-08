#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/start-all.sh  启动本地开发环境（MySQL + 后端 + 前端）
#
# 依赖位置可用环境变量覆盖：
#   MYSQL_HOME   MySQL 安装目录        （默认 $HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64）
#   JAVA_HOME    JDK 21 安装目录       （默认 $HOME/tools/opt/jdk-21.0.12.1+1）
#   MAVEN_HOME   Maven 安装目录        （默认 $HOME/tools/opt/apache-maven-3.9.16）
#   EXTRA_LIB    额外动态库目录        （默认 $HOME/tools/opt/lib，提供 libaio.so.1）
#   RUN_DIR      运行时目录            （默认 $HOME/var）
#
# 用法：bash scripts/start-all.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/opt/jdk-21.0.12.1+1}"
MAVEN_HOME="${MAVEN_HOME:-$HOME/tools/opt/apache-maven-3.9.16}"
EXTRA_LIB="${EXTRA_LIB:-$HOME/tools/opt/lib}"
RUN_DIR="${RUN_DIR:-$HOME/var}"
DB_DIR="$RUN_DIR/mysql"
export LD_LIBRARY_PATH="$EXTRA_LIB:${LD_LIBRARY_PATH:-}"
export JAVA_HOME

mkdir -p "$RUN_DIR/run" "$DB_DIR/log"

echo "== 1/3 启动 MySQL =="
if "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_DIR/db.sock" -uroot ping >/dev/null 2>&1; then
  echo "   MySQL 已在运行"
else
  nohup "$MYSQL_HOME/bin/mysqld" --no-defaults \
    --basedir="$MYSQL_HOME" --datadir="$DB_DIR/data" \
    --socket="$DB_DIR/db.sock" --port=3306 --bind-address=127.0.0.1 \
    --pid-file="$DB_DIR/db.pid" --log-error="$DB_DIR/log/error.log" \
    --tmpdir="$DB_DIR/tmp" --mysqlx=OFF \
    --skip-name-resolve --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
    >/dev/null 2>&1 &
  for i in $(seq 1 60); do
    if "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_DIR/db.sock" -uroot ping >/dev/null 2>&1; then
      echo "   MySQL 已启动（${i}s）"; break
    fi
    sleep 1
  done
fi

echo "== 2/3 启动后端（端口 8100） =="
cd "$ROOT/backend"
if curl -sS -m 2 http://127.0.0.1:8100/hello >/dev/null 2>&1; then
  echo "   后端已在运行"
else
  nohup "$MAVEN_HOME/bin/mvn" -B -DskipTests spring-boot:run \
    > "$RUN_DIR/run/backend.log" 2>&1 &
  for i in $(seq 1 120); do
    if curl -sS -m 2 http://127.0.0.1:8100/hello 2>/dev/null | grep -q Hello; then
      echo "   后端已启动（${i}s）：http://127.0.0.1:8100"; break
    fi
    sleep 1
  done
fi

echo "== 3/3 启动前端（端口 5173） =="
cd "$ROOT/frontend"
if curl -sS -m 2 -o /dev/null http://127.0.0.1:5173/ 2>/dev/null; then
  echo "   前端已在运行"
else
  [ -f .env.local ] || cp .env.example .env.local
  nohup npm run dev > "$RUN_DIR/run/frontend.log" 2>&1 &
  for i in $(seq 1 60); do
    if curl -sS -m 2 -o /dev/null http://127.0.0.1:5173/ 2>/dev/null; then
      echo "   前端已启动（${i}s）：http://127.0.0.1:5173"; break
    fi
    sleep 1
  done
fi

echo
echo "访问地址"
echo "  前端（本机）：  http://127.0.0.1:5173"
echo "  后端（本机）：  http://127.0.0.1:8100"
echo "  后端连通检查：  http://127.0.0.1:8100/hello"
echo
echo "日志：$RUN_DIR/run/backend.log  $RUN_DIR/run/frontend.log  $DB_DIR/log/error.log"
