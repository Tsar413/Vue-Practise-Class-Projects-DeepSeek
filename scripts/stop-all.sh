#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/stop-all.sh  停止本地开发环境（前端 + 后端 + MySQL）
#
# 用法：bash scripts/stop-all.sh
# ---------------------------------------------------------------------------
set -uo pipefail

RUN_DIR="${RUN_DIR:-$HOME/var}"
MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
DB_DIR="$RUN_DIR/mysql"
export LD_LIBRARY_PATH="${EXTRA_LIB:-$HOME/tools/opt/lib}:${LD_LIBRARY_PATH:-}"

echo "== 停止前端 =="
PIDS=$(pgrep -f "vite --host 0.0.0.0" || true)
if [ -n "$PIDS" ]; then kill $PIDS 2>/dev/null && echo "   已停止：$PIDS"; else echo "   未在运行"; fi

echo "== 停止后端 =="
PIDS=$(pgrep -f "spring-boot:run" || true)
if [ -n "$PIDS" ]; then kill $PIDS 2>/dev/null && echo "   已停止：$PIDS"; else echo "   未在运行"; fi
# 等待端口释放
for i in $(seq 1 20); do
  curl -sS -m 1 -o /dev/null http://127.0.0.1:8100/hello 2>/dev/null || break
  sleep 1
done

echo "== 停止 MySQL =="
if "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_DIR/db.sock" -uroot ping >/dev/null 2>&1; then
  "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_DIR/db.sock" -uroot shutdown && echo "   已停止"
else
  echo "   未在运行"
fi
