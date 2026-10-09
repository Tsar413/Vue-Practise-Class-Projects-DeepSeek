#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/start-all.sh  日常启动本地开发环境（MySQL + 后端 + 前端）
#
# 与首次初始化的区别：
#   * 本脚本只负责「启动」，不建库、不建表、不导入数据、不下载依赖。
#     首次新环境先准备并启动 MySQL，再执行 init-db.sh，详见运行手册
#
# 目录与端口分离（重要）：
#   MYSQL_DATA_DIR  MySQL 数据目录     —— 与应用运行目录解耦，可复用已有 MySQL 实例
#   DB_SOCKET       MySQL socket       —— 同上
#   RUN_DIR         应用状态与日志目录 —— 默认 <项目>/.runtime/run，按项目隔离
#   STATE_FILE      状态文件           —— 默认 $RUN_DIR/env.state
#   LOG_DIR         日志目录           —— 默认 $RUN_DIR
#   BACKEND_PORT    后端端口，作为 SERVER_PORT 传给 Spring Boot
#   FRONTEND_PORT   前端端口，以 --port/--strictPort 传给 Vite
#
# 就绪判定（三重核验，避免把其他项目或其他实例误判为成功）：
#   1. 端口存在监听进程；
#   2. 核对监听者的实际可执行文件、组件工作目录和主类，并匹配记录进程组；
#   3. HTTP 返回预期状态码，且响应体包含预期内容。
#
# 其他约定：
#   * 本项目上次记录仍有效且就绪校验通过 → 复用原进程（幂等，不产生重复进程）。
#   * 端口被非本项目进程占用 → 明确报错并非零退出，绝不抢占或杀掉对方。
#   * 状态文件的读改写使用 flock 串行化，避免并发双启动。
#   * 启动失败会回收本次启动的进程组，不遗留孤儿进程。
#
# 用法：bash scripts/start-all.sh
# ---------------------------------------------------------------------------
set -uo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/opt/jdk-21.0.12.1+1}"
MAVEN_HOME="${MAVEN_HOME:-$HOME/tools/opt/apache-maven-3.9.16}"
EXTRA_LIB="${EXTRA_LIB:-$HOME/tools/opt/lib}"

# 应用侧运行目录按项目隔离；MySQL 数据目录单独配置，二者互不影响
RUN_DIR="${RUN_DIR:-$ROOT/.runtime/run}"
LOG_DIR="${LOG_DIR:-$RUN_DIR}"
STATE_FILE="${STATE_FILE:-$RUN_DIR/env.state}"
LOCK_FILE="${LOCK_FILE:-$RUN_DIR/.env.lock}"
MYSQL_DATA_DIR="${MYSQL_DATA_DIR:-$HOME/var/mysql/data}"
MYSQL_LOG_DIR="${MYSQL_LOG_DIR:-$HOME/var/mysql/log}"
MYSQL_PID_FILE="${MYSQL_PID_FILE:-$HOME/var/mysql/db.pid}"
MYSQL_TMP_DIR="${MYSQL_TMP_DIR:-$HOME/var/mysql/tmp}"
DB_SOCKET="${DB_SOCKET:-$HOME/var/mysql/db.sock}"

BACKEND_PORT="${BACKEND_PORT:-8100}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
MYSQL_PORT="${MYSQL_PORT:-3306}"

START_MYSQL="${START_MYSQL:-1}"
START_BACKEND="${START_BACKEND:-1}"
START_FRONTEND="${START_FRONTEND:-1}"
BOOT_TIMEOUT_BACKEND="${BOOT_TIMEOUT_BACKEND:-150}"
BOOT_TIMEOUT_FRONTEND="${BOOT_TIMEOUT_FRONTEND:-90}"
BOOT_TIMEOUT_MYSQL="${BOOT_TIMEOUT_MYSQL:-60}"

export LD_LIBRARY_PATH="$EXTRA_LIB:${LD_LIBRARY_PATH:-}"
export JAVA_HOME

SELF_PID=$$
SELF_PGID="$(ps -o pgid= -p "$SELF_PID" 2>/dev/null | tr -d ' ')"
[ -n "$SELF_PGID" ] || SELF_PGID="$SELF_PID"

fail() { echo "错误：$*" >&2; exit 1; }

mkdir -p "$LOG_DIR" "$RUN_DIR"
[ -f "$LOCK_FILE" ] || : > "$LOCK_FILE"

# ---------- 生命周期锁 ----------
# 整个「检查是否已运行 → 启动 → 写状态」必须在同一把锁内完成，
# 否则两个并发执行的 start-all.sh 都会看到「未运行」并各自启动一套进程。
# 子进程启动前会关闭锁 FD（见 start_detached），避免锁被服务进程继承而长期不释放。
exec 9>>"$LOCK_FILE"
if ! flock -n 9; then
  echo "另一个 start-all.sh 正在执行（锁：$LOCK_FILE），本次退出以避免重复启动。" >&2
  exit 4
fi

# ---------------------------- 进程 / 端口工具 --------------------------------
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

# 就绪判定：监听者必须是「本项目 + 本组件」（严格命令类型），
# 再校验 HTTP 状态码与响应体中的完整预期内容。
# 三项都通过才算就绪，缺一不可。
# wait_ready <url> <期望状态码> <期望包含的内容> <端口> <backend|frontend> <超时秒>
wait_ready() {
  local url="$1" want_code="$2" want_text="$3" port="$4" kind="$5" timeout="$6"
  local i body code payload
  for ((i = 1; i <= timeout; i++)); do
    if port_listening "$port" && listener_is_our_service "$port" "$kind"; then
      body="$(curl -sS -m 3 -w $'\n%{http_code}' "$url" 2>/dev/null || true)"
      code="$(printf '%s' "$body" | tail -n 1)"
      payload="${body%$'\n'*}"
      if [ "$code" = "$want_code" ]; then
        if [ "$kind" = backend ]; then
          [ "$payload" = "$want_text" ] && return 0
        elif printf '%s' "$payload" | grep -qF "$want_text"; then
          return 0
        fi
      fi
    fi
    sleep 1
  done
  return 1
}

# ---------------------------- 状态文件（加锁读写） ---------------------------
read_state() {  # read_state <key> <col>
  [ -f "$STATE_FILE" ] || return 1
  awk -v k="$1" -v c="$2" '$1==k {print $c}' "$STATE_FILE" | tail -1
}

state_pid_alive() {  # 记录中的 PID 仍是当初那个进程（用启动时间防止 PID 复用）
  local key="$1" pid start
  pid="$(read_state "$key" 2)" || return 1
  [ -n "${pid:-}" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  start="$(read_state "$key" 3)"
  [ -n "${start:-}" ] || return 1
  [ "$(proc_start "$pid")" = "$start" ]
}

# 不能把同一工程的另一 RUN_DIR / 另一进程组当成已记录的实例。
listener_matches_state() {
  local key="$1" port="$2" pid pgid
  pid="$(port_owner_pid "$port")"; pgid="$(read_state "$key" 4)"
  [ -n "$pid" ] && [ -n "$pgid" ] && [ "$(proc_pgid "$pid")" = "$pgid" ] \
    && [ "$(proc_pgid "$(read_state "$key" 2)")" = "$pgid" ] \
    && is_our_service "$pid" "$key"
}

write_state() {  # write_state <key> <pid> <pgid>
  # 调用方已持有生命周期锁（FD 9），这里直接改文件，不再重复加锁
  local key="$1" pid="$2" pgid="$3" tmp
  tmp="$STATE_FILE.tmp.$$"
  if [ -f "$STATE_FILE" ]; then
    grep -v "^$key " "$STATE_FILE" > "$tmp" 2>/dev/null || : > "$tmp"
  else
    : > "$tmp"
  fi
  printf '%s %s %s %s\n' "$key" "$pid" "$(proc_start "$pid")" "$pgid" >> "$tmp"
  mv "$tmp" "$STATE_FILE"
}

clear_state() {
  local key="$1" tmp
  [ -f "$STATE_FILE" ] || return 0
  tmp="$STATE_FILE.tmp.$$"
  grep -v "^$key " "$STATE_FILE" > "$tmp" 2>/dev/null || : > "$tmp"
  mv "$tmp" "$STATE_FILE"
}

# 回收本次启动失败的进程组；绝不针对当前 shell 所在的进程组
reap_group() {
  local pgid="$1" label="$2"
  [ -n "${pgid:-}" ] || return 0
  if [ "$pgid" = "$SELF_PGID" ]; then
    echo "   警告：$label 的进程组与当前 shell 相同，跳过回收以避免误杀" >&2
    return 0
  fi
  kill -TERM -- "-$pgid" 2>/dev/null || true
  sleep 2
  kill -KILL -- "-$pgid" 2>/dev/null || true
  echo "   已回收 $label 进程组（pgid=$pgid）"
}

# 在 setsid 子进程中启动服务：子进程先写自身 PID（进程组组长）再 exec，
# 因此记录到的是真正的服务进程 PID/PGID，而不是 setsid 本身。
start_detached() {  # start_detached <pidfile> <logfile> <cmd...>
  local pidfile="$1" logfile="$2"; shift 2
  rm -f "$pidfile"
  # 9>&- ：不让子进程继承生命周期锁的 FD，否则锁会一直被服务进程持有
  setsid nohup bash -c 'echo $$ > "$1"; shift; exec "$@"' _ "$pidfile" "$@" \
    9>&- > "$logfile" 2>&1 &
  local i
  for ((i = 1; i <= 40; i++)); do
    [ -s "$pidfile" ] && break
    sleep 0.25
  done
  [ -s "$pidfile" ] || return 1
  cat "$pidfile"
}

# ---------------------------- 0. 预检 ---------------------------------------
echo "== 0/4 预检 =="
[ -x "$MYSQL_HOME/bin/mysqld" ]     || fail "缺少 MySQL：$MYSQL_HOME/bin/mysqld（不要重新下载，请检查 MYSQL_HOME）"
[ -x "$MYSQL_HOME/bin/mysqladmin" ] || fail "缺少 mysqladmin：$MYSQL_HOME/bin/mysqladmin"
[ -x "$JAVA_HOME/bin/java" ]        || fail "缺少 JDK 21：$JAVA_HOME/bin/java（请检查 JAVA_HOME）"
[ -x "$MAVEN_HOME/bin/mvn" ]        || fail "缺少 Maven：$MAVEN_HOME/bin/mvn（请检查 MAVEN_HOME）"
command -v node >/dev/null 2>&1     || fail "缺少 node（前端需要 Node.js）"
command -v npm  >/dev/null 2>&1     || fail "缺少 npm"
command -v curl >/dev/null 2>&1     || fail "缺少 curl"
[ -f "$ROOT/backend/pom.xml" ]       || fail "缺少后端工程：$ROOT/backend/pom.xml"
[ -f "$ROOT/frontend/package.json" ] || fail "缺少前端工程：$ROOT/frontend/package.json"
if [ "$START_FRONTEND" = "1" ]; then
  [ -d "$ROOT/frontend/node_modules" ] || fail "前端依赖缺失（$ROOT/frontend/node_modules），请先执行 npm install —— 本脚本不联网安装依赖"
fi

JAVA_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ "$JAVA_MAJOR" = "21" ] || fail "JDK 主版本应为 21，实际为 ${JAVA_MAJOR:-未知}（$JAVA_HOME）"

for p in "$BACKEND_PORT" "$FRONTEND_PORT" "$MYSQL_PORT"; do
  case "$p" in ''|*[!0-9]*) fail "端口必须是数字：$p";; esac
  if [ "$p" -lt 1 ] || [ "$p" -gt 65535 ]; then
    fail "端口必须在 1..65535 之间：$p"
  fi
done

echo "   JDK $("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
echo "   运行目录 $RUN_DIR"
echo "   状态文件 $STATE_FILE"
echo "   MySQL 数据目录 $MYSQL_DATA_DIR，socket $DB_SOCKET"

# ---------------------------- 1/4 MySQL -------------------------------------
echo "== 1/4 MySQL（端口 $MYSQL_PORT） =="
mysql_ping() { "$MYSQL_HOME/bin/mysqladmin" --no-defaults --socket="$DB_SOCKET" -uroot ping >/dev/null 2>&1; }

if [ "$START_MYSQL" != "1" ]; then
  mysql_ping || fail "MySQL 未在 $DB_SOCKET 就绪，但 START_MYSQL=$START_MYSQL；后续步骤需要数据库"
  echo "   已跳过启动（START_MYSQL=0），复用现有 MySQL：$DB_SOCKET"
elif mysql_ping; then
  echo "   MySQL 已在运行（socket=$DB_SOCKET）"
else
  [ -f "$MYSQL_DATA_DIR/auto.cnf" ] || fail "MySQL 数据目录未初始化：$MYSQL_DATA_DIR（不要重新下载，请检查 MYSQL_DATA_DIR）"
  OWNER="$(port_owner_pid "$MYSQL_PORT")"
  if [ -n "$OWNER" ]; then
    fail "端口 $MYSQL_PORT 已被其他进程占用（pid=$OWNER），且当前 socket（$DB_SOCKET）不是本项目的 MySQL。
       请先停止该进程，或改用 MYSQL_PORT / DB_SOCKET 指向已有实例；本脚本不会停止其他服务。"
  fi
  mkdir -p "$MYSQL_LOG_DIR" "$MYSQL_TMP_DIR"
  nohup "$MYSQL_HOME/bin/mysqld" --no-defaults \
    --basedir="$MYSQL_HOME" --datadir="$MYSQL_DATA_DIR" \
    --socket="$DB_SOCKET" --port="$MYSQL_PORT" --bind-address=127.0.0.1 \
    --pid-file="$MYSQL_PID_FILE" --log-error="$MYSQL_LOG_DIR/error.log" \
    --tmpdir="$MYSQL_TMP_DIR" --mysqlx=OFF \
    --skip-name-resolve --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
    9>&- >/dev/null 2>&1 &
  MYSQL_BOOT_PID=$!
  READY=0
  for ((i = 1; i <= BOOT_TIMEOUT_MYSQL; i++)); do
    if mysql_ping; then READY=1; break; fi
    kill -0 "$MYSQL_BOOT_PID" 2>/dev/null || break
    sleep 1
  done
  if [ "$READY" != "1" ]; then
    kill -TERM "$MYSQL_BOOT_PID" 2>/dev/null
    fail "MySQL 在 ${BOOT_TIMEOUT_MYSQL} 秒内未就绪，请查看 $MYSQL_LOG_DIR/error.log"
  fi
  echo "   MySQL 已启动（pid=$(cat "$MYSQL_PID_FILE" 2>/dev/null || echo "$MYSQL_BOOT_PID")）"
fi

if [ "$START_BACKEND" = "1" ]; then
# 解析后端将要使用的连接参数，并做三项只读核验：
#   1. DB_URL 只允许本机回环地址；
#   2. DB_URL 指向的库必须存在；
#   3. 应用账号必须能连上该库并读取业务表（只查 information_schema 不足以证明有权限）。
# 只输出解析出的库名/端口/账号，绝不打印 DB_URL 全文（可能含口令）或口令本身。
DB_NAME_CFG="${DB_NAME:-vue_practise_backend}"
case "$DB_NAME_CFG" in
  ''|*[!A-Za-z0-9_]*) fail "非法的 DB_NAME：$DB_NAME_CFG（只允许字母、数字、下划线）";;
esac

# DB_URL 未设置时，由经校验的 DB_NAME 构造并导出，保证后端连接的就是这个库
if [ -z "${DB_URL:-}" ]; then
  DB_URL="jdbc:mysql://127.0.0.1:3306/${DB_NAME_CFG}?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&serverTimezone=GMT%2B8"
  export DB_URL
  echo "   未设置 DB_URL，已按 DB_NAME=$DB_NAME_CFG 构造并导出给后端"
fi

# 禁止指向非本机数据库
DB_URL_HOSTPART="${DB_URL#jdbc:mysql://}"
DB_URL_HOSTPART="${DB_URL_HOSTPART%%/*}"
case "$DB_URL_HOSTPART" in
  127.0.0.1:*|localhost:*|\[::1\]:*) ;;
  *) fail "拒绝执行：DB_URL 只允许连接本机回环地址（当前主机段不以 127.0.0.1/localhost/[::1] 开头）。";;
esac

APP_DB_NAME=$(printf '%s' "$DB_URL" | sed -n 's|.*://[^/]*/\([^?]*\).*|\1|p')
APP_DB_PORT=$(printf '%s' "$DB_URL" | sed -n 's|.*://[^:/]*:\([0-9]*\)/.*|\1|p')
if [ -z "$APP_DB_NAME" ]; then
  fail "无法从 DB_URL 解析出库名（不打印 URL 内容，请检查格式是否为 jdbc:mysql://主机:端口/库名?...）"
fi
[ -n "$APP_DB_PORT" ] || APP_DB_PORT="$MYSQL_PORT"
[[ "$APP_DB_NAME" =~ ^[A-Za-z_][A-Za-z0-9_]{0,63}$ ]] || fail "DB_URL 库名格式无效"
[[ "$APP_DB_PORT" =~ ^[0-9]{1,5}$ ]] && ((10#$APP_DB_PORT >= 1 && 10#$APP_DB_PORT <= 65535)) || fail "DB_URL 端口无效"
[ -z "${DB_NAME:-}" ] || [ "$DB_NAME" = "$APP_DB_NAME" ] || fail "DB_NAME 与 DB_URL 库名不一致"

# 应用账号的默认值与 init-db.sh 的派生规则保持一致：
# 默认库用默认账号，自定义库用「库名_app」与「库名_local」
if [ "$APP_DB_NAME" = "vue_practise_backend" ]; then
  DEFAULT_APP_USER="vue_practice"
  DEFAULT_APP_PASS="vue_practice_local"
else
  DEFAULT_APP_USER="${APP_DB_NAME}_app"
  DEFAULT_APP_PASS="${APP_DB_NAME}_local"
fi
APP_DB_USER="${DB_USERNAME:-$DEFAULT_APP_USER}"
APP_DB_PASS="${DB_PASSWORD:-$DEFAULT_APP_PASS}"
export DB_USERNAME="$APP_DB_USER" DB_PASSWORD="$APP_DB_PASS"

echo "   后端将连接：库 $APP_DB_NAME，端口 $APP_DB_PORT，账号 $APP_DB_USER"

if ! "$MYSQL_HOME/bin/mysql" --no-defaults --socket="$DB_SOCKET" -uroot -N -B \
     -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$APP_DB_NAME';" 2>/dev/null \
     | grep -q '^1$'; then
  fail "DB_URL 指向的库不存在：$APP_DB_NAME。
       请先用 init-db.sh 初始化该库（DB_NAME=$APP_DB_NAME bash scripts/init-db.sh），
       或修正 DB_NAME / DB_URL 使其指向一个已存在的库。"
fi

# 用 --database 直接连库并读取业务表，验证「连接 + 库级权限」而不只是库存在
if ! MYSQL_PWD="$APP_DB_PASS" "$MYSQL_HOME/bin/mysql" --no-defaults \
       --protocol=TCP -h 127.0.0.1 -P "$APP_DB_PORT" -u "$APP_DB_USER" \
       --database="$APP_DB_NAME" -N -B \
       -e "SELECT COUNT(*) FROM (SELECT 1 FROM sys_user LIMIT 0) t;" >/dev/null 2>&1; then
  fail "应用账号无法访问目标库：$APP_DB_USER@127.0.0.1:$APP_DB_PORT/$APP_DB_NAME。
       口令错误、授权缺失或该库尚未建表都可能导致这一结果。
       可检查 DB_USERNAME / DB_PASSWORD，或用 DB_NAME=<库名> bash scripts/init-db.sh 初始化全新环境。"
fi
echo "   应用账号连接校验通过（库 $APP_DB_NAME，$("$MYSQL_HOME/bin/mysql" --no-defaults --socket="$DB_SOCKET" -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$APP_DB_NAME';" 2>/dev/null) 张表）"

fi

# ---------------------------- 2/4 后端 --------------------------------------
echo "== 2/4 后端（端口 $BACKEND_PORT） =="
if [ "$START_BACKEND" != "1" ]; then
  echo "   已跳过（START_BACKEND=0）"
elif state_pid_alive backend && listener_matches_state backend "$BACKEND_PORT" \
     && wait_ready "http://127.0.0.1:$BACKEND_PORT/hello" 200 "Hello Spring Boot!" "$BACKEND_PORT" backend 3; then
  # 已有记录时同样要过完整健康检查：PID 相同但服务未就绪（启动中/已挂起）不会被当成“已在运行”
  echo "   后端已在运行且健康检查通过（pid=$(read_state backend 2)，pgid=$(read_state backend 4)）"
elif curl -sS -m 2 "http://127.0.0.1:$BACKEND_PORT/hello" 2>/dev/null | grep -q 'Hello Spring Boot!'; then
  # 有服务在响应，但不是「本状态文件记录的、命令类型匹配的后端进程」。
  # 可能情形：同项目其他 RUN_DIR 的实例、别的项目、或在项目目录下启动的模拟服务。
  # 本脚本只复用自己记录并核验过的进程，因此这里直接失败。
  if state_pid_alive backend; then
    fail "端口 $BACKEND_PORT 的监听进程与状态文件记录不匹配：记录的进程不是本项目的后端服务
       （pid=$(read_state backend 2)，命令类型校验未通过）。
       为避免接管来路不明的服务，脚本不会复用它。请先停止该进程，或改用 BACKEND_PORT。"
  else
    fail "端口 $BACKEND_PORT 上有一个响应 /hello 的服务，但状态文件没有对应记录
       （可能是其他 RUN_DIR 的实例、其他项目，或在项目目录下启动的模拟服务）。
       本脚本只复用自己启动并记录的服务，不会接管它。请先停止该进程，或改用 BACKEND_PORT。"
  fi
else
  OWNER="$(port_owner_pid "$BACKEND_PORT")"
  if [ -n "$OWNER" ]; then
    fail "端口 $BACKEND_PORT 已被占用（pid=$OWNER，cwd=$(proc_cwd "$OWNER")），
       它不匹配本项目后端服务的命令类型，或未通过就绪校验。
       请先处理该进程，或改用 BACKEND_PORT；本脚本不会停止其他服务。"
  fi
  cd "$ROOT/backend" || fail "无法进入 $ROOT/backend"
  # 显式把 BACKEND_PORT 作为 SERVER_PORT 传给 Spring Boot，
  # 保证监听端口与就绪校验使用的端口一致，而不是依赖外部环境变量。
  BACKEND_PID="$(start_detached "$LOG_DIR/backend.pid" "$LOG_DIR/backend.log" \
    env SERVER_PORT="$BACKEND_PORT" \
    "$MAVEN_HOME/bin/mvn" -B -DskipTests spring-boot:run)" \
    || fail "无法启动后端进程（pidfile 未生成）"
  BACKEND_PGID="$(proc_pgid "$BACKEND_PID")"
  echo "   启动中（pid=$BACKEND_PID，pgid=${BACKEND_PGID:-未知}，端口 $BACKEND_PORT）"
  if ! wait_ready "http://127.0.0.1:$BACKEND_PORT/hello" 200 "Hello Spring Boot!" "$BACKEND_PORT" backend "$BOOT_TIMEOUT_BACKEND"; then
    echo "--- backend.log 末尾 40 行 ---" >&2
    tail -n 40 "$LOG_DIR/backend.log" >&2 || true
    reap_group "${BACKEND_PGID:-$BACKEND_PID}" "后端"
    fail "后端在 ${BOOT_TIMEOUT_BACKEND} 秒内未通过就绪校验（端口 $BACKEND_PORT，日志 $LOG_DIR/backend.log）"
  fi
  write_state backend "$BACKEND_PID" "${BACKEND_PGID:-$BACKEND_PID}"
  echo "   后端已就绪：http://127.0.0.1:$BACKEND_PORT"
fi

# ---------------------------- 3/4 前端 --------------------------------------
echo "== 3/4 前端（端口 $FRONTEND_PORT） =="
if [ "$START_FRONTEND" != "1" ]; then
  echo "   已跳过（START_FRONTEND=0）"
elif state_pid_alive frontend && listener_matches_state frontend "$FRONTEND_PORT" \
     && wait_ready "http://127.0.0.1:$FRONTEND_PORT/" 200 '<div id="app">' "$FRONTEND_PORT" frontend 3; then
  echo "   前端已在运行且健康检查通过（pid=$(read_state frontend 2)，pgid=$(read_state frontend 4)）"
elif curl -sS -m 2 -o /dev/null "http://127.0.0.1:$FRONTEND_PORT/" 2>/dev/null; then
  if state_pid_alive frontend; then
    fail "端口 $FRONTEND_PORT 的监听进程与状态文件记录不匹配：记录的进程不是本项目的前端服务
       （pid=$(read_state frontend 2)，命令类型校验未通过）。
       为避免接管来路不明的服务，脚本不会复用它。请先停止该进程，或改用 FRONTEND_PORT。"
  else
    fail "端口 $FRONTEND_PORT 上有服务在响应，但状态文件没有对应记录
       （可能是其他 RUN_DIR 的实例、其他项目，或在项目目录下启动的模拟服务）。
       本脚本只复用自己启动并记录的服务，不会接管它。请先停止该进程，或改用 FRONTEND_PORT。"
  fi
else
  OWNER="$(port_owner_pid "$FRONTEND_PORT")"
  if [ -n "$OWNER" ]; then
    fail "端口 $FRONTEND_PORT 已被占用（pid=$OWNER，cwd=$(proc_cwd "$OWNER")），
       该监听者不在本项目根目录 $ROOT 下，或未通过就绪校验。
       请先处理该进程，或改用 FRONTEND_PORT；本脚本不会停止其他服务。"
  fi
  cd "$ROOT/frontend" || fail "无法进入 $ROOT/frontend"
  [ -f .env.local ] || cp .env.example .env.local
  # 前端只需要 VITE_* 构建期变量：
  # 用 env -u 显式剔除后端/AI 密钥，避免本机包装脚本导出的 AI_TUTOR_API_KEY、
  # DB_PASSWORD、DB_ROOT_PASSWORD 被开发服务器进程继承（浏览器产物本来也不含它们）。
  # 只影响这三个变量，其它环境变量、端口与进程身份判定保持不变。
  FRONTEND_PID="$(start_detached "$LOG_DIR/frontend.pid" "$LOG_DIR/frontend.log" \
    env -u AI_TUTOR_API_KEY -u DB_PASSWORD -u DB_ROOT_PASSWORD \
    "$ROOT/frontend/node_modules/.bin/vite" --host 0.0.0.0 --port "$FRONTEND_PORT" --strictPort)" \
    || fail "无法启动前端进程（pidfile 未生成）"
  FRONTEND_PGID="$(proc_pgid "$FRONTEND_PID")"
  echo "   启动中（pid=$FRONTEND_PID，pgid=${FRONTEND_PGID:-未知}，端口 $FRONTEND_PORT）"
  if ! wait_ready "http://127.0.0.1:$FRONTEND_PORT/" 200 '<div id="app">' "$FRONTEND_PORT" frontend "$BOOT_TIMEOUT_FRONTEND"; then
    echo "--- frontend.log 末尾 40 行 ---" >&2
    tail -n 40 "$LOG_DIR/frontend.log" >&2 || true
    reap_group "${FRONTEND_PGID:-$FRONTEND_PID}" "前端"
    fail "前端在 ${BOOT_TIMEOUT_FRONTEND} 秒内未通过就绪校验（端口 $FRONTEND_PORT，日志 $LOG_DIR/frontend.log）"
  fi
  write_state frontend "$FRONTEND_PID" "${FRONTEND_PGID:-$FRONTEND_PID}"
  echo "   前端已就绪：http://127.0.0.1:$FRONTEND_PORT"
fi

# ---------------------------- 4/4 汇总 --------------------------------------
echo "== 4/4 启动完成 =="
echo
echo "本次实际启动的组件"
[ "$START_MYSQL" = "1" ]    && echo "  MySQL  端口 $MYSQL_PORT"     || echo "  MySQL  跳过（复用 $DB_SOCKET）"
[ "$START_BACKEND" = "1" ]  && echo "  后端   端口 $BACKEND_PORT"   || echo "  后端   已跳过"
[ "$START_FRONTEND" = "1" ] && echo "  前端   端口 $FRONTEND_PORT"  || echo "  前端   已跳过"
echo
echo "访问地址"
[ "$START_FRONTEND" = "1" ] && echo "  前端（本机）：  http://127.0.0.1:$FRONTEND_PORT"
[ "$START_BACKEND" = "1" ]  && echo "  后端（本机）：  http://127.0.0.1:$BACKEND_PORT"
[ "$START_BACKEND" = "1" ]  && echo "  后端连通检查：  http://127.0.0.1:$BACKEND_PORT/hello"
echo
echo "状态文件：$STATE_FILE"
echo "日志：$LOG_DIR/backend.log  $LOG_DIR/frontend.log"
echo "停止：bash scripts/stop-all.sh"
