#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/stop-all.sh  停止本项目的本地开发环境
#
# 安全约定：
#   * 优先按状态文件中记录的「PID + 进程启动时间 + 进程组」精确停止，
#     停止前逐项复核：PID 仍存在、启动时间未变（防 PID 复用）、进程组未变、
#     进程属于本项目目录、且进程组不是当前 shell 自身的进程组。
#   * 记录中的父进程已经消失（例如 mvn 退出但 java 子进程仍在监听）时，
#     按端口找到真正的监听者，复核其归属与进程组后再停，不遗留孤儿。
#   * 不使用 pgrep/pkill 等宽泛匹配，不会误停其他项目或系统上的同名进程。
#   * 端口由其他进程占用时不做任何处理，只提示。
#   * 停止后等待端口真正释放；超时才报告失败。
#   * 不删除数据库、不删除数据目录、不清理日志。
#
# 用法：
#   bash scripts/stop-all.sh                  # 停止前端 + 后端（默认保留 MySQL）
#   STOP_MYSQL=1 bash scripts/stop-all.sh     # 同时停止本项目启动的 MySQL
# ---------------------------------------------------------------------------
set -uo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
EXTRA_LIB="${EXTRA_LIB:-$HOME/tools/opt/lib}"

# 与 start-all.sh 使用同一套目录约定，可用环境变量覆盖
RUN_DIR="${RUN_DIR:-$ROOT/.runtime/run}"
LOG_DIR="${LOG_DIR:-$RUN_DIR}"
STATE_FILE="${STATE_FILE:-$RUN_DIR/env.state}"
LOCK_FILE="${LOCK_FILE:-$RUN_DIR/.env.lock}"
DB_SOCKET="${DB_SOCKET:-$HOME/var/mysql/db.sock}"
MYSQL_LOG_DIR="${MYSQL_LOG_DIR:-$HOME/var/mysql/log}"

BACKEND_PORT="${BACKEND_PORT:-8100}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"

export LD_LIBRARY_PATH="$EXTRA_LIB:${LD_LIBRARY_PATH:-}"

EXIT_CODE=0
SELF_PID=$$
SELF_PGID="$(ps -o pgid= -p "$SELF_PID" 2>/dev/null | tr -d ' ')"
[ -n "$SELF_PGID" ] || SELF_PGID="$SELF_PID"

mkdir -p "$RUN_DIR" 2>/dev/null || true
[ -f "$LOCK_FILE" ] || : > "$LOCK_FILE" 2>/dev/null || true

# 与 start-all.sh 共用同一把生命周期锁：
# 停止过程中不允许另一个 start/stop 并发修改状态文件。
exec 9>>"$LOCK_FILE" 2>/dev/null || true
if [ -f "$LOCK_FILE" ]; then
  if ! flock -n 9; then
    echo "另一个 start-all.sh / stop-all.sh 正在执行（锁：$LOCK_FILE），本次退出。" >&2
    exit 4
  fi
fi

proc_start() { awk '{print $22}' "/proc/$1/stat" 2>/dev/null; }
proc_cwd()   { readlink "/proc/$1/cwd" 2>/dev/null; }
proc_cmd()   { tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null; }
proc_pgid()  { ps -o pgid= -p "$1" 2>/dev/null | tr -d ' '; }

port_owner_pid() {
  ss -ltnpH "sport = :$1" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2
}
port_listening() { ss -ltnH "sport = :$1" 2>/dev/null | grep -q .; }

# ---------------------------------------------------------------------------
# 严格的服务身份判定
#
# 判定一个进程是否就是「本项目、本组件」的服务进程，必须同时满足：
#   1. 命令类型匹配（后端＝Maven 启动 Spring Boot 且主类属于本项目；
#      前端＝本项目的 vite 可执行文件或本项目 npm run 脚本）；
#   2. 工作目录落在本项目内。
# 只在「项目目录里起了个别的服务」不会被认作本项目服务，
# 因此不会发生「陌生健康端口被当成本项目已在运行」的情况。
# ---------------------------------------------------------------------------
proc_exe() { readlink -f "/proc/$1/exe" 2>/dev/null; }
proc_ppid() { awk '{print $4}' "/proc/$1/stat" 2>/dev/null; }

# Codex 最终复核：核对监听进程本身，不通过祖先或任意路径参数接管进程。
is_our_service() {
  local pid="$1" kind="$2" cwd exe cmd
  cwd="$(proc_cwd "$pid")"; exe="$(proc_exe "$pid")"; cmd="$(proc_cmd "$pid")"
  case "$kind" in
    backend)
      [ "$cwd" = "$ROOT/backend" ] && [ "${exe##*/}" = java ] || return 1
      case "$cmd" in
        *com.study.vuePractiseBackend.StudyVuePractiseBackendApplication*) return 0;;
        *org.codehaus.plexus.classworlds.launcher.Launcher*spring-boot:run*) return 0;;
      esac;;
    frontend)
      [ "$cwd" = "$ROOT/frontend" ] && [ "${exe##*/}" = node ] || return 1
      case "$cmd" in *"$ROOT/frontend/node_modules/.bin/vite"*) return 0;; esac;;
  esac
  return 1
}
listener_is_our_service() {
  local pid
  pid="$(port_owner_pid "$1")"
  [ -n "$pid" ] && is_our_service "$pid" "$2"
}

state_field() {
  [ -f "$STATE_FILE" ] || return 1
  awk -v k="$1" -v c="$2" '$1==k {print $c}' "$STATE_FILE" | tail -1
}

clear_state() {
  local key="$1" tmp
  [ -f "$STATE_FILE" ] || return 0
  tmp="$STATE_FILE.tmp.$$"
  grep -v "^$key " "$STATE_FILE" > "$tmp" 2>/dev/null || : > "$tmp"
  mv "$tmp" "$STATE_FILE"
}

# 终止一个已经过校验的进程组
kill_group_verified() {
  local pgid="$1" pid="$2" label="$3"

  if [ "$pgid" = "$SELF_PGID" ]; then
    echo "   $label：目标进程组与当前 shell 相同（pgid=$pgid），为避免误杀自身不做处理" >&2
    echo "        请在新终端中执行：kill $pid" >&2
    return 1
  fi

  echo "   $label：向进程组 pgid=$pgid（组长 pid=$pid）发送 SIGTERM"
  kill -TERM -- "-$pgid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
  return 0
}

wait_port_released() {
  local port="$1" i
  for ((i = 1; i <= STOP_TIMEOUT; i++)); do
    port_listening "$port" || return 0
    sleep 1
  done
  return 1
}

# 停止状态文件记录的一个组件
stop_component() {
  local key="$1" port="$2" label="$3"
  local pid start pgid cur_pgid listener listener_pgid

  pid="$(state_field "$key" 2 || true)"
  start="$(state_field "$key" 3 || true)"
  pgid="$(state_field "$key" 4 || true)"

  # ---------- 情况 A：状态文件没有记录 ----------
  if [ -z "${pid:-}" ]; then
    listener="$(port_owner_pid "$port")"
    if [ -n "$listener" ]; then
      if listener_is_our_service "$port" "$([ "$key" = backend ] && echo backend || echo frontend)"; then
        echo "   $label：状态文件无记录，但端口 $port 的监听者属于本项目（pid=$listener）"
        echo "        该进程不是本脚本启动的，未做处理。确认后可执行：kill $listener"
      else
        echo "   $label：未运行（端口 $port 由其他进程占用 pid=$listener，不属于本项目，未做处理）"
      fi
    else
      echo "   $label：未运行"
    fi
    return 0
  fi

  # ---------- 情况 B：记录的父进程仍在 ----------
  if kill -0 "$pid" 2>/dev/null && [ "$(proc_start "$pid")" = "$start" ]; then
    if ! is_our_service "$pid" "$([ "$key" = backend ] && echo backend || echo frontend)"; then
      echo "   $label：pid=$pid 不属于本项目（cwd=$(proc_cwd "$pid")），未做处理" >&2
      clear_state "$key"; EXIT_CODE=1; return 0
    fi
    # 进程组必须与状态文件记录一致。
    # 不一致说明这个 PID 已经不是当初启动的那个进程（或进程组被外部改动），
    # 此时按“实际进程组”停止会波及无关进程，因此直接拒绝并提示人工处理。
    cur_pgid="$(proc_pgid "$pid")"
    if [ -z "${pgid:-}" ] || [ "$cur_pgid" != "$pgid" ]; then
      echo "   $label：pid=$pid 的实际进程组（${cur_pgid:-未知}）与记录（${pgid:-无}）不一致，已拒绝停止。" >&2
      echo "        为避免误杀无关进程，请人工确认后处理：kill $pid" >&2
      EXIT_CODE=1
      return 0
    fi

    kill_group_verified "$pgid" "$pid" "$label" || { EXIT_CODE=1; return 0; }

    if ! wait_port_released "$port"; then
      echo "   $label：${STOP_TIMEOUT}s 内未释放端口 $port，发送 SIGKILL"
      [ "$pgid" = "$SELF_PGID" ] || kill -KILL -- "-$pgid" 2>/dev/null
      port_listening "$port" || { echo "   $label：已停止，端口 $port 已释放"; clear_state "$key"; return 0; }
    fi

    if port_listening "$port"; then
      echo "   $label：端口 $port 仍被占用，请人工检查" >&2
      EXIT_CODE=1
    else
      echo "   $label：已停止，端口 $port 已释放"
      clear_state "$key"
    fi
    return 0
  fi

  # ---------- 情况 C：记录的父进程已消失（防 PID 复用 / 处理孤儿子进程） ----------
  if kill -0 "$pid" 2>/dev/null; then
    echo "   $label：pid=$pid 已被系统复用为其他进程（启动时间不匹配），不按该 PID 停止"
  else
    echo "   $label：记录中的父进程 pid=$pid 已退出（可能是 mvn/npm 退出但子进程仍在）"
  fi

  listener="$(port_owner_pid "$port")"
  if [ -z "$listener" ]; then
    echo "   $label：端口 $port 未被占用，清理状态记录即可"
    clear_state "$key"
    return 0
  fi

  if ! listener_is_our_service "$port" "$([ "$key" = backend ] && echo backend || echo frontend)"; then
    echo "   $label：端口 $port 的监听者 pid=$listener 不属于本项目，未做处理" >&2
    clear_state "$key"; return 0
  fi

  listener_pgid="$(proc_pgid "$listener")"
  echo "   $label：端口 $port 的监听者 pid=$listener 属于本项目（实际 pgid=${listener_pgid:-未知}，记录 pgid=${pgid:-无}）"

  # 监听者的进程组必须与状态文件记录一致才接管。
  # 不一致说明它很可能是另一个 RUN_DIR（或另一个项目）启动的服务，
  # 即使端口相同也不能由本脚本停止，直接拒绝并提示人工处理。
  if [ -z "${pgid:-}" ] || [ "$listener_pgid" != "$pgid" ]; then
    echo "   $label：端口 $port 的监听者 pid=$listener 的进程组（${listener_pgid:-未知}）与记录（${pgid:-无}）不一致，已拒绝接管。" >&2
    echo "        它可能属于另一个运行目录或另一个项目，请人工确认后处理：kill $listener" >&2
    clear_state "$key"
    EXIT_CODE=1
    return 0
  fi
  kill_group_verified "$listener_pgid" "$listener" "$label" || { EXIT_CODE=1; return 0; }

  if ! wait_port_released "$port"; then
    echo "   $label：${STOP_TIMEOUT}s 内未释放端口 $port，发送 SIGKILL"
    [ "$listener_pgid" = "$SELF_PGID" ] || kill -KILL -- "-$listener_pgid" 2>/dev/null
  fi

  if port_listening "$port"; then
    echo "   $label：端口 $port 仍被占用，请人工检查" >&2
    EXIT_CODE=1
  else
    echo "   $label：已停止，端口 $port 已释放"
    clear_state "$key"
  fi
}

echo "== 停止本项目的前端与后端 =="
echo "   状态文件：$STATE_FILE"
stop_component frontend "$FRONTEND_PORT" "前端"
stop_component backend  "$BACKEND_PORT"  "后端"

# 清理状态文件中已失效的记录
if [ -f "$STATE_FILE" ]; then
  TMP="$STATE_FILE.tmp.$$"
  : > "$TMP"
  while read -r key pid start pgid; do
    [ -n "${key:-}" ] || continue
    if kill -0 "$pid" 2>/dev/null && [ "$(proc_start "$pid")" = "$start" ]; then
      printf '%s %s %s %s\n' "$key" "$pid" "$start" "$pgid" >> "$TMP"
    fi
  done < "$STATE_FILE"
  mv "$TMP" "$STATE_FILE"
fi

# ---------------------------- 可选：停止 MySQL ------------------------------
if [ "${STOP_MYSQL:-0}" = "1" ]; then
  echo "== 停止本项目 socket 对应的 MySQL =="
  if "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_SOCKET" -uroot ping >/dev/null 2>&1; then
    if "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_SOCKET" -uroot shutdown; then
      for i in $(seq 1 30); do
        "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_SOCKET" -uroot ping >/dev/null 2>&1 || break
        sleep 1
      done
      if "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_SOCKET" -uroot ping >/dev/null 2>&1; then
        echo "   MySQL 仍在运行，请人工检查" >&2
        EXIT_CODE=1
      else
        echo "   MySQL 已停止（数据目录未做任何删除）"
      fi
    else
      echo "   MySQL 停止失败" >&2
      EXIT_CODE=1
    fi
  else
    echo "   未运行（或 socket 不匹配：$DB_SOCKET）"
  fi
else
  echo "== MySQL 未处理（默认保留；如需停止请加 STOP_MYSQL=1） =="
fi

echo
if [ "$EXIT_CODE" -eq 0 ]; then
  echo "停止流程完成。"
else
  echo "停止流程完成，但有需要人工确认的项（见上方输出）。" >&2
fi
exit "$EXIT_CODE"
