#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/review-verify.sh  隔离审查环境的专项回归
#
# 默认只针对隔离环境：http://127.0.0.1:18100 + 库 vue_practise_review_20261009。
# 绝不连接或写入现有库 vue_practise_backend，也不触碰 8100/5173。
#
# 覆盖：
#   A 登录并发：同一账号并发登录的唯一记录、访问码并发确定性、token 轮换
#   B 权限与隔离：教师/学生权限边界、跨 workspace 读取、伪造访问码
#   C 重置范围：只影响该学生、只影响指定项目
#   D 图片清理：重置后磁盘文件是否真的被删除（不只检查数据库记录）
#   E 环境身份：隔离后端确实连到隔离库、图片目录确实独立、受保护库未被修改
#
# 安全约定：
#   * 先做 E0 环境身份核验，不通过就直接退出，不做任何写操作。
#   * 临时文件放在 umask 077 的私有目录，退出时由 trap 清理。
#   * 断言输出不打印 token / 访问码 / 口令等凭证值，只打印是否一致。
#
# 用法：
#   bash scripts/review-verify.sh                  # 全部（含写操作）
#   READ_ONLY=1 bash scripts/review-verify.sh      # 完全只读：不登录、不写任何数据
#                                                  （身份证明改用 /proc/<pid>/environ 直接核对）
#   OUT=/path/to/report.txt bash scripts/review-verify.sh
#
# 前置条件：MySQL 已运行；已执行 review-env.sh init 与 review-env.sh start。
# ---------------------------------------------------------------------------
set -uo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API="${API_BASE:-http://127.0.0.1:18100}"
RUNTIME_DIR="${RUNTIME_DIR:-$ROOT/.runtime/review-20261009}"
STATE_FILE="${STATE_FILE:-$RUNTIME_DIR/run/env.state}"
REPAIR_FILES_ROOT="${REPAIR_FILES_ROOT:-$RUNTIME_DIR/repair-files}"
DEFAULT_REPAIR_FILES_ROOT="${DEFAULT_REPAIR_FILES_ROOT:-$ROOT/backend/repair-files}"
OUT="${OUT:-$ROOT/docs/verification-review.txt}"

MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64}"
DB_SOCKET="${DB_SOCKET:-$HOME/var/mysql/db.sock}"
DB_NAME="${DB_NAME:-vue_practise_review_20261009}"
PROTECTED_DB="vue_practise_backend"
export LD_LIBRARY_PATH="${EXTRA_LIB:-$HOME/tools/opt/lib}:${LD_LIBRARY_PATH:-}"

STUDENT_A="${VERIFY_STUDENT_A:-DEMO2026001}"
STUDENT_B="${VERIFY_STUDENT_B:-DEMO2026002}"
STUDENT_C="${VERIFY_STUDENT_C:-DEMO2026003}"
TEACHER="${VERIFY_TEACHER:-DEMO_TEACHER}"
PASSWORD="${VERIFY_PASSWORD:-123456}"
FIXTURE="${FIXTURE:-$ROOT/scripts/fixtures/test-image.png}"
READ_ONLY="${READ_ONLY:-0}"
CONCURRENCY="${CONCURRENCY:-24}"

PASS=0; FAIL=0; SKIP=0
declare -a RESULTS=()
AV_BODY=""; AV_CODE=""

TMPDIR_PRIVATE="$(mktemp -d /tmp/rv-verify.XXXXXX)"
chmod 700 "$TMPDIR_PRIVATE"
cleanup() { rm -rf "$TMPDIR_PRIVATE"; }
trap cleanup EXIT INT TERM

ok()   { PASS=$((PASS+1)); RESULTS+=("通过    $1"); printf '通过    %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); RESULTS+=("失败    $1  <- $2"); printf '失败    %s  <- %s\n' "$1" "$2"; }
skip() { SKIP=$((SKIP+1)); RESULTS+=("未验证  $1  <- $2"); printf '未验证  %s  <- %s\n' "$1" "$2"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "期望 [$2] 实际 [$3]"; fi; }
# 凭证相关断言：只报告是否一致，绝不打印凭证值
check_secret_bool() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "两者不一致（具体值不输出）"; fi; }

MYSQL_BIN="$MYSQL_HOME/bin/mysql"

# 只读查询：显式只读事务，防止误写
mysql_ro() {
  "$MYSQL_BIN" --no-defaults --socket="$DB_SOCKET" -uroot -N -B \
    -e "SET SESSION TRANSACTION READ ONLY; $1" 2>/dev/null
}
# 读写语句：只允许作用于隔离库（调用方必须带上带 review 的库名）
mysql_rw() {
  "$MYSQL_BIN" --no-defaults --socket="$DB_SOCKET" -uroot -N -B -e "$1" 2>/dev/null
}

rest() {
  local method="$1" path="$2" token="${3:-}" data="${4:-}"
  AV_CODE=$(curl -sS -o "$TMPDIR_PRIVATE/body" -w '%{http_code}' "$API$path" -X "$method" \
    ${token:+-H "Authorization: Bearer $token"} \
    ${data:+-H 'Content-Type: application/json' -d "$data"})
  AV_BODY="$(cat "$TMPDIR_PRIVATE/body")"
}
code_of() { printf '%s\n' "$1" | tail -n 1; }
body_of() { printf '%s\n' "$1" | head -n -1; }
http() { rest "$@"; printf '%s\n%s\n' "$AV_BODY" "$AV_CODE"; }

jq_get() {
  python3 -c "
import sys, json
raw = sys.stdin.read().strip()
try:
    d = json.loads(raw) if raw else None
except Exception:
    d = None
for k in '$1'.split('.'):
    if d is None: break
    if isinstance(d, list):
        try: i = int(k)
        except ValueError: d = None; break
        d = d[i] if 0 <= i < len(d) else None
    elif isinstance(d, dict): d = d.get(k)
    else: d = None
print('' if d is None else d)
"
}
json_len() {
  python3 -c "
import sys, json
try: d = json.loads(sys.stdin.read().strip() or '{}')
except Exception: print('PARSE_ERROR'); sys.exit()
data = d.get('data') if isinstance(d, dict) else None
print(len(data) if isinstance(data, list) else 'NO_DATA')
"
}
ids_of() {
  python3 -c "
import sys,json
try: d=json.load(sys.stdin).get('data') or []
except Exception: d=[]
print(','.join(str(u.get('id')) for u in d))"
}
ws_of() {
  python3 -c "
import sys,json
try: d=json.load(sys.stdin).get('data') or []
except Exception: d=[]
print(sorted({u.get('workspaceId') for u in d}))"
}

# 一次登录同时取 token 与 access code（两次登录会轮换 token）
login_both() {
  rest POST /login '' "{\"id\":\"$1\",\"password\":\"$PASSWORD\"}"
  printf '%s %s\n' "$(printf '%s' "$AV_BODY" | jq_get data.token)" \
                   "$(printf '%s' "$AV_BODY" | jq_get data.apiAccessCode)"
}

echo "=============================================================="
echo "隔离审查专项回归  $(date '+%Y-%m-%d %H:%M:%S')"
echo "隔离后端：$API"
echo "隔离库：  $DB_NAME"
echo "受保护库：$PROTECTED_DB（只读校验）"
echo "写操作：  $([ "$READ_ONLY" = "1" ] && echo '完全只读：不登录、不写任何数据' || echo '执行，仅限隔离库与隔离图片目录')"
echo "=============================================================="

API_BASE="$API" DB_NAME="$DB_NAME" REPAIR_FILES_ROOT="$REPAIR_FILES_ROOT" STATE_FILE="$STATE_FILE" python3 "$ROOT/scripts/verify-review-target.py" || exit 3

# Codex：正常环境可能已有图片；比较完整清单和内容哈希，不能假设空目录。
protected_images_snapshot() {
  python3 - "$DEFAULT_REPAIR_FILES_ROOT" <<'PYIMAGES'
from pathlib import Path
import hashlib,json,sys
root=Path(sys.argv[1])
files={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file()}
print(hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest())
PYIMAGES
}
PROTECTED_IMAGES_BEFORE=$(protected_images_snapshot) || exit 3

# ============ E0. 环境身份核验（必须先通过，否则拒绝任何写测试）============
echo
echo "--- E0. 隔离环境身份核验（写测试前置条件） ---"

ENV_OK=1
case "$DB_NAME" in *review*) ;; *) ENV_OK=0; bad "E0 隔离库名包含 review" "$DB_NAME";; esac
case "$API" in *:18100) ;; *) ENV_OK=0; bad "E0 API 地址是隔离端口 18100" "$API";; esac
case "$REPAIR_FILES_ROOT" in
  "$RUNTIME_DIR"/*) ;;
  *) ENV_OK=0; bad "E0 图片目录位于隔离运行目录内" "$REPAIR_FILES_ROOT";;
esac

if ! curl -sS -m 5 "$API/hello" 2>/dev/null | grep -q 'Hello Spring Boot!'; then
  echo "隔离后端未就绪：$API（请先 bash scripts/review-env.sh start）" >&2
  exit 1
fi

LISTENER=$(ss -ltnpH 'sport = :18100' 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
L_CWD=$(readlink "/proc/${LISTENER:-0}/cwd" 2>/dev/null)
case "$L_CWD" in
  "$ROOT"|"$ROOT"/*) ok "E0 端口 18100 监听者工作目录属于本项目" ;;
  *) ENV_OK=0; bad "E0 端口 18100 监听者工作目录属于本项目" "cwd=$L_CWD" ;;
esac
if [ -f "$STATE_FILE" ]; then
  R_PGID=$(awk '$1=="backend"{print $4}' "$STATE_FILE" | tail -1)
  L_PGID=$(ps -o pgid= -p "${LISTENER:-0}" 2>/dev/null | tr -d ' ')
  if [ -n "${R_PGID:-}" ] && [ "${R_PGID}" = "${L_PGID:-}" ]; then
    ok "E0 状态文件 PGID 与监听者实际 PGID 一致"
  else
    ENV_OK=0; bad "E0 状态文件 PGID 与监听者实际 PGID 一致" \
      "记录 ${R_PGID:-无} / 实际 ${L_PGID:-无}"
  fi
else
  ENV_OK=0; bad "E0 状态文件存在" "缺少 $STATE_FILE"
fi

# 直接从监听进程自身的环境读取配置（只读 /proc，不打印完整环境），
# 用来证明「这个后端进程连的就是隔离库与隔离图片目录」，
# 而不是依赖脚本传入的变量名或 HTTP 行为来推断。
BACKEND_PID_FOR_ENV="${LISTENER:-}"
BACKEND_ENV_FILE="/proc/${BACKEND_PID_FOR_ENV}/environ"
if [ -n "$BACKEND_PID_FOR_ENV" ] && [ -r "$BACKEND_ENV_FILE" ]; then
  read -r PROC_DB_URL PROC_FILES_ROOT PROC_SERVER_PORT < <(python3 - "$BACKEND_ENV_FILE" <<'PYENV'
import sys
want = {"DB_URL": "", "REPAIR_FILES_ROOT": "", "SERVER_PORT": ""}
with open(sys.argv[1], "rb") as f:
    for item in f.read().split(b"\0"):
        if b"=" not in item:
            continue
        k, _, v = item.partition(b"=")
        key = k.decode("utf-8", "replace")
        if key in want:
            want[key] = v.decode("utf-8", "replace")
print(want["DB_URL"], want["REPAIR_FILES_ROOT"], want["SERVER_PORT"] or "unset")
PYENV
)
  if printf '%s' "$PROC_DB_URL" | grep -q "/$DB_NAME?"; then
    ok "E0 后端进程环境中的 DB_URL 指向隔离库"
  else
    ENV_OK=0; bad "E0 后端进程环境中的 DB_URL 指向隔离库" "DB_URL 与隔离库名不匹配（不打印其值）"
  fi
  if [ "$PROC_FILES_ROOT" = "$REPAIR_FILES_ROOT" ]; then
    ok "E0 后端进程环境中的 REPAIR_FILES_ROOT 是隔离图片目录"
  else
    ENV_OK=0; bad "E0 后端进程环境中的 REPAIR_FILES_ROOT 是隔离图片目录" \
      "实际值与隔离目录不一致（实际：${PROC_FILES_ROOT:-空}）"
  fi
  if [ "${PROC_SERVER_PORT:-}" = "18100" ]; then
    ok "E0 后端进程环境中的 SERVER_PORT 与隔离端口一致"
  else
    ENV_OK=0; bad "E0 后端进程环境中的 SERVER_PORT 与隔离端口一致" "实际：${PROC_SERVER_PORT:-unset}"
  fi

else
  ENV_OK=0; bad "E0 可读取后端进程环境" "无法读取 $BACKEND_ENV_FILE"
fi

protected_snapshot() {
  mysql_ro "
SELECT CONCAT(
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$PROTECTED_DB'),'|',
  (SELECT COUNT(*) FROM \`$PROTECTED_DB\`.sys_user),'|',
  (SELECT COUNT(*) FROM \`$PROTECTED_DB\`.ticket_user),'|',
  (SELECT COUNT(*) FROM \`$PROTECTED_DB\`.repair_order),'|',
  (SELECT COUNT(*) FROM \`$PROTECTED_DB\`.sys_login_token));"
}
PROTECTED_BASELINE="$(protected_snapshot)"
check "E0 受保护库只读基线已取得" "ok" "$([ -n "$PROTECTED_BASELINE" ] && echo ok || echo fail)"

if [ "$ENV_OK" != "1" ] || [ "$FAIL" -gt 0 ]; then
  echo
  echo "环境身份核验未通过（ENV_OK=$ENV_OK，失败 $FAIL 项），已中止：不做任何写操作。" >&2
  {
    echo "隔离审查专项回归结果（已中止）"
    echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
    echo "原因：E0 环境身份核验未通过（失败 $FAIL 项）"
    echo "写操作：未执行任何写入"
    echo
    for line in "${RESULTS[@]}"; do echo "$line"; done
  } > "$OUT"
  echo "结果已写入 $OUT"
  exit 1
fi

# 只读模式：E0 通过后即输出结论并退出，不进行任何登录或业务操作
if [ "$READ_ONLY" = "1" ]; then
  echo
  echo "READ_ONLY=1：环境身份核验通过，未执行任何登录与写操作。"
  {
    echo "隔离审查环境身份核验结果（READ_ONLY=1）"
    echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
    echo "隔离后端：$API"
    echo "隔离库：$DB_NAME"
    echo "写操作：未执行任何写入"
    echo "通过 $PASS 项，失败 $FAIL 项"
    echo
    for line in "${RESULTS[@]}"; do echo "$line"; done
  } > "$OUT"
  echo "结果已写入 $OUT"
  exit 0
fi

# ============================ A. 登录并发 ==================================
echo
echo "--- A. 同一账号首次并发登录 ---"

if [ "$READ_ONLY" = "1" ]; then
  skip "A0-A2 并发登录与凭证一致性" "READ_ONLY=1（需要写入隔离库）"
else
  # A0 构造真正的「首次登录」场景：清空隔离库中这两个学生的登录记录
  mysql_rw "DELETE FROM \`$DB_NAME\`.sys_login_token WHERE user_id IN ('$STUDENT_A','$STUDENT_B');"
  check "A0 隔离库中两名学生的登录记录已清空" "0" \
    "$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.sys_login_token WHERE user_id IN ('$STUDENT_A','$STUDENT_B');")"

  run_concurrent() {  # run_concurrent <账号> <并发数> → 输出私有目录
    local id="$1" n="$2" dir="$TMPDIR_PRIVATE/conc.$1"
    mkdir -p "$dir" && chmod 700 "$dir"
    local i
    for ((i = 1; i <= n; i++)); do
      curl -sS -o "$dir/b.$i" -w '%{http_code}' -X POST "$API/login" \
        -H 'Content-Type: application/json' \
        -d "{\"id\":\"$id\",\"password\":\"$PASSWORD\"}" > "$dir/c.$i" 2>/dev/null &
    done
    wait
    printf '%s' "$dir"
  }

  for who in "$STUDENT_A" "$STUDENT_B"; do
    DIR="$(run_concurrent "$who" "$CONCURRENCY")"
    DBCODE=$(mysql_ro "SELECT api_access_code FROM \`$DB_NAME\`.sys_login_token WHERE user_id='$who';")

    # 全部比对在 python 内完成，只输出统计量，不输出任何凭证
    STATS=$(python3 - "$DIR" "$CONCURRENCY" "$DBCODE" <<'PY'
import json, sys, pathlib, hashlib
d, n, dbcode = pathlib.Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
codes, acc, hashes = [], [], []
for i in range(1, n + 1):
    c = (d / f"c.{i}").read_text().strip()
    codes.append(c)
    if c != "200":
        continue
    j = json.loads((d / f"b.{i}").read_text())
    acc.append(j["data"]["apiAccessCode"])
    hashes.append(hashlib.sha256(j["data"]["token"].encode()).hexdigest())
(d / "hashes").write_text("\n".join(hashes) + "\n")
print(f"{codes.count('200')} {len(set(codes))} {len(acc)} {len(set(acc))} "
      f"{sum(1 for a in acc if a == dbcode)}")
PY
)
    read -r C200 CUNIQ CN ACCUNIQ ACCEQ <<< "$STATS"

    ROWS=$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.sys_login_token WHERE user_id='$who';")
    check "A1 $who 并发 ${CONCURRENCY} 次后隔离库只有 1 条登录记录" "1" "${ROWS:-ERR}"
    check "A1 $who 并发登录全部返回 200（无唯一键冲突、无死锁）" "$CONCURRENCY" "$C200"
    check "A1 $who 返回状态码只有一种" "1" "$CUNIQ"
    check_secret_bool "A1 $who 所有成功响应返回同一个长期访问码" "1" "$ACCUNIQ"
    check_secret_bool "A1 $who 所有响应访问码与数据库最终值一致" "$CN" "$ACCEQ"

    HITS=0
    while read -r h; do
      [ -n "$h" ] || continue
      C=$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.sys_login_token WHERE user_id='$who' AND token_hash='$h';")
      HITS=$((HITS + C))
    done < "$DIR/hashes"
    check "A1 $who 并发响应中有且仅有 1 个 token 仍生效（其余已轮换）" "1" "$HITS"
  done

  read -r T1 C1 <<< "$(login_both "$STUDENT_A")"
  read -r T2 C2 <<< "$(login_both "$STUDENT_A")"
  check_secret_bool "A2 重复登录后长期访问码保持不变" "$C1" "$C2"
  check "A2 重复登录后网页 token 已轮换" "rotated" \
    "$([ -n "$T1" ] && [ -n "$T2" ] && [ "$T1" != "$T2" ] && echo rotated || echo same)"
  check "A2 重复登录后隔离库仍只有 1 条记录" "1" \
    "$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.sys_login_token WHERE user_id='$STUDENT_A';")"
fi

# ============================ B. 权限与隔离 ================================
echo
echo "--- B. 教师/学生权限与跨 workspace 隔离 ---"

read -r TTOKEN _       <<< "$(login_both "$TEACHER")"
read -r S1TOKEN S1CODE <<< "$(login_both "$STUDENT_A")"
read -r S2TOKEN S2CODE <<< "$(login_both "$STUDENT_B")"
check "B0 教师与学生均可在隔离环境登录" "ok" \
  "$([ -n "$TTOKEN" ] && [ -n "$S1TOKEN" ] && [ -n "$S2TOKEN" ] && echo ok || echo fail)"

r=$(http GET /api/sys-user/all "$S1TOKEN");              check "B1 学生读取全部系统用户返回403" "403" "$(code_of "$r")"
r=$(http GET "/api/sys-user/one/$STUDENT_A" "$S1TOKEN"); check "B2 学生读取本人账号返回200" "200" "$(code_of "$r")"
r=$(http GET "/api/sys-user/one/$STUDENT_B" "$S1TOKEN"); check "B3 学生读取他人账号返回403" "403" "$(code_of "$r")"
r=$(http GET "/api/sys-workspace/one/$STUDENT_B" "$S1TOKEN"); check "B4 学生读取他人工作空间返回403" "403" "$(code_of "$r")"
r=$(http GET /api/sys-user/all "$TTOKEN");               check "B5 教师读取全部系统用户返回200" "200" "$(code_of "$r")"
r=$(http GET "/api/practice/$(printf 'c%.0s' $(seq 1 64))/ticket/users" '')
check "B6 伪造访问码返回401" "401" "$(code_of "$r")"

# 越权尝试：应被拒绝，因此不会产生任何写入
r=$(http POST "/api/sys-workspace/one/$STUDENT_B/reset?project=TICKET" "$S1TOKEN")
check "B7 学生重置他人项目返回403" "403" "$(code_of "$r")"

A_USERS=$(rest GET "/api/practice/$S1CODE/ticket/users"; printf '%s' "$AV_BODY")
B_USERS=$(rest GET "/api/practice/$S2CODE/ticket/users"; printf '%s' "$AV_BODY")
A_WS=$(printf '%s' "$A_USERS" | ws_of)
B_WS=$(printf '%s' "$B_USERS" | ws_of)
check "B8 两名学生的抢票数据属于不同 workspace" "different" \
  "$([ -n "$A_WS" ] && [ "$A_WS" != "$B_WS" ] && echo different || echo same)"

B_ACT=$(rest GET "/api/practice/$S2CODE/ticket/activities"; printf '%s' "$AV_BODY" | jq_get data.0.id)
if [ -n "$B_ACT" ]; then
  r=$(http GET "/api/practice/$S1CODE/ticket/activities/$B_ACT" '')
  check "B9 用甲的访问码读取乙的活动返回404" "404" "$(code_of "$r")"
else
  skip "B9 跨 workspace 读取活动" "隔离环境中乙没有活动数据"
fi

B_ADMIN=$(rest GET "/api/practice/$S2CODE/repair/users?role=ADMIN"; printf '%s' "$AV_BODY" | jq_get data.0.id)
if [ -n "$B_ADMIN" ]; then
  r=$(http GET "/api/practice/$S1CODE/repair/users/$B_ADMIN" '')
  check "B10 用甲的访问码读取乙的模拟用户返回404" "404" "$(code_of "$r")"
else
  skip "B10 跨 workspace 读取模拟用户" "隔离环境中乙没有报修管理员数据"
fi

# ============================ C. 重置范围 ==================================
echo
echo "--- C. 重置只影响该学生的指定项目 ---"

if [ "$READ_ONLY" = "1" ]; then
  skip "C1-C6 重置范围" "READ_ONLY=1（需要写入隔离库）"
else
  A_ADMIN=$(rest GET "/api/practice/$S1CODE/repair/users?role=ADMIN"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  B_ADMIN2=$(rest GET "/api/practice/$S2CODE/repair/users?role=ADMIN"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  A_REPORTER=$(rest GET "/api/practice/$S1CODE/repair/users?role=REPORTER&status=1"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  A_DEV=$(rest GET "/api/practice/$S1CODE/repair/devices?status=1"; printf '%s' "$AV_BODY" | jq_get data.0.id)

  NEW_ORDER=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"operatorId\":$A_REPORTER,\"deviceId\":$A_DEV,\"title\":\"审查回归：重置范围\",\"description\":\"<p>用于验证重置只影响该学生。</p>\"}" \
    | jq_get data.id)
  check "C1 学生甲新增报修工单成功" "ok" "$([ -n "$NEW_ORDER" ] && echo ok || echo fail)"

  A_ORDERS_BEFORE=$(rest GET "/api/practice/$S1CODE/repair/orders?operatorId=$A_ADMIN"; printf '%s' "$AV_BODY" | json_len)
  B_ORDERS_BEFORE=$(rest GET "/api/practice/$S2CODE/repair/orders?operatorId=$B_ADMIN2"; printf '%s' "$AV_BODY" | json_len)
  B_TICKET_BEFORE=$(rest GET "/api/practice/$S2CODE/ticket/users"; printf '%s' "$AV_BODY" | ids_of)

  r=$(http POST "/api/sys-workspace/one/$STUDENT_A/reset?project=TICKET" "$TTOKEN")
  check "C2 教师重置学生甲抢票项目成功" "200" "$(code_of "$r")"
  check "C2 重置后甲的抢票活动回到 4 条基准数据" "4" \
    "$(rest GET "/api/practice/$S1CODE/ticket/activities"; printf '%s' "$AV_BODY" | json_len)"
  check "C2 只重置抢票时甲的报修工单数量不变" "$A_ORDERS_BEFORE" \
    "$(rest GET "/api/practice/$S1CODE/repair/orders?operatorId=$A_ADMIN"; printf '%s' "$AV_BODY" | json_len)"
  check "C2 重置甲不影响乙的报修工单数量" "$B_ORDERS_BEFORE" \
    "$(rest GET "/api/practice/$S2CODE/repair/orders?operatorId=$B_ADMIN2"; printf '%s' "$AV_BODY" | json_len)"
  check "C2 重置甲不影响乙的抢票模拟用户主键" "$B_TICKET_BEFORE" \
    "$(rest GET "/api/practice/$S2CODE/ticket/users"; printf '%s' "$AV_BODY" | ids_of)"

  r=$(http POST "/api/sys-workspace/one/$STUDENT_A/reset?project=REPAIR" "$TTOKEN")
  check "C3 教师重置学生甲报修项目成功" "200" "$(code_of "$r")"
  A_ADMIN3=$(rest GET "/api/practice/$S1CODE/repair/users?role=ADMIN"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  check "C3 报修重置后甲的工单回到 6 条基准数据" "6" \
    "$(rest GET "/api/practice/$S1CODE/repair/orders?operatorId=$A_ADMIN3"; printf '%s' "$AV_BODY" | json_len)"
  check "C3 重置甲报修不影响甲的抢票活动条数" "4" \
    "$(rest GET "/api/practice/$S1CODE/ticket/activities"; printf '%s' "$AV_BODY" | json_len)"
  check "C3 重置甲报修不影响乙的报修工单数量" "$B_ORDERS_BEFORE" \
    "$(rest GET "/api/practice/$S2CODE/repair/orders?operatorId=$B_ADMIN2"; printf '%s' "$AV_BODY" | json_len)"

  MISSING_WS=$(mysql_ro "
SELECT COUNT(*) FROM \`$DB_NAME\`.ticket_user tu
  LEFT JOIN \`$DB_NAME\`.sys_workspace w ON w.id = tu.workspace_id
 WHERE w.id IS NULL;")
  check "C4 隔离库不存在找不到 workspace 的抢票数据" "0" "${MISSING_WS:-ERR}"

  r=$(http POST "/api/sys-workspace/one/NO_SUCH_STUDENT/reset?project=TICKET" "$TTOKEN")
  check "C5 重置不存在的工作空间返回404" "404" "$(code_of "$r")"
  r=$(http POST "/api/sys-workspace/one/$STUDENT_A/reset?project=BAD" "$TTOKEN")
  check "C6 非法项目参数返回400" "400" "$(code_of "$r")"
fi

# ============================ D. 图片磁盘清理 ==============================
echo
echo "--- D. 图片清理与事务关系（重置后磁盘文件是否真的删除） ---"

if [ "$READ_ONLY" = "1" ]; then
  skip "D1-D7 图片磁盘清理" "READ_ONLY=1（需要写入隔离库与隔离图片目录）"
elif [ ! -f "$FIXTURE" ]; then
  skip "D1-D7 图片磁盘清理" "缺少测试图片 $FIXTURE（可先运行 node scripts/make-fixtures.mjs）"
else
  MNT=$(rest GET "/api/practice/$S1CODE/repair/users?role=MAINTAINER&status=1"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  REP=$(rest GET "/api/practice/$S1CODE/repair/users?role=REPORTER&status=1"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  ADM=$(rest GET "/api/practice/$S1CODE/repair/users?role=ADMIN&status=1"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  DEV=$(rest GET "/api/practice/$S1CODE/repair/devices?status=1"; printf '%s' "$AV_BODY" | jq_get data.0.id)

  # D1/D2：上传后落盘位置必须在隔离图片目录内，且不在项目默认图片目录
  UP=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$MNT" -F "imageType=2" -F "file=@$FIXTURE")
  IMG_ID=$(printf '%s' "$UP" | jq_get data.id)
  check "D1 维修师傅上传图片成功" "ok" "$([ -n "$IMG_ID" ] && echo ok || echo fail)"
  IMG_PATH=$(mysql_ro "SELECT image_url FROM \`$DB_NAME\`.repair_attachment WHERE id=$IMG_ID;")
  ABS_ISO="$REPAIR_FILES_ROOT/$IMG_PATH"
  ABS_DEFAULT="$DEFAULT_REPAIR_FILES_ROOT/$IMG_PATH"
  check "D2 上传的图片落在隔离图片目录内" "exists" \
    "$([ -n "$IMG_PATH" ] && [ -f "$ABS_ISO" ] && echo exists || echo missing)"
  check "D2 上传的图片没有落在项目默认图片目录" "absent" \
    "$([ ! -f "$ABS_DEFAULT" ] && echo absent || echo present)"

  OID=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/orders" -H 'Content-Type: application/json' \
    -d "{\"operatorId\":$REP,\"deviceId\":$DEV,\"title\":\"审查回归：图片清理\",\"description\":\"<p>用于验证重置删除图片文件。</p>\"}" \
    | jq_get data.id)
  rest PUT "/api/practice/$S1CODE/repair/orders/$OID/assign" '' \
    "{\"operatorId\":$ADM,\"maintainerId\":$MNT}" >/dev/null
  rest PUT "/api/practice/$S1CODE/repair/orders/$OID/accept?operatorId=$MNT" '' >/dev/null
  SUB=$(curl -sS -X PUT "$API/api/practice/$S1CODE/repair/orders/$OID/submit" \
    -H 'Content-Type: application/json' \
    -d "{\"operatorId\":$MNT,\"repairResult\":\"审查回归提交\",\"imageIds\":[$IMG_ID]}")
  check "D3 提交维修结果并绑定图片成功" "4" "$(printf '%s' "$SUB" | jq_get data.status)"
  check "D4 图片状态已置为已关联(1)" "1" \
    "$(mysql_ro "SELECT status FROM \`$DB_NAME\`.repair_attachment WHERE id=$IMG_ID;")"
  check "D4 工单图片关联记录已生成" "1" \
    "$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.repair_order_image WHERE attachment_id=$IMG_ID;")"

  r=$(http POST "/api/sys-workspace/one/$STUDENT_A/reset?project=REPAIR" "$TTOKEN")
  check "D5 重置甲的报修项目成功" "200" "$(code_of "$r")"
  check "D5 重置后甲名下的附件记录已清空" "0" \
    "$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.repair_attachment a JOIN \`$DB_NAME\`.sys_workspace w ON w.id=a.workspace_id WHERE w.student_id='$STUDENT_A';")"
  check "D5 重置后生成待清理任务" "1" \
    "$([ "$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.repair_file_delete_task WHERE image_url='$IMG_PATH';")" -ge 1 ] && echo 1 || echo 0)"

  GONE=0
  for ((i = 1; i <= 40; i++)); do
    [ ! -f "$ABS_ISO" ] && { GONE=1; break; }
    sleep 3
  done
  check "D6 重置后磁盘图片文件已被后台任务真实删除" "1" "$GONE"
  check "D6 清理任务记录已消费" "0" \
    "$(mysql_ro "SELECT COUNT(*) FROM \`$DB_NAME\`.repair_file_delete_task WHERE image_url='$IMG_PATH';")"

  # D7 事务关系：上传后主动删除临时图片，磁盘文件也应被清理
  REP=$(rest GET "/api/practice/$S1CODE/repair/users?role=REPORTER&status=1"; printf '%s' "$AV_BODY" | jq_get data.0.id)
  UP2=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$REP" -F "imageType=1" -F "file=@$FIXTURE")
  IMG2=$(printf '%s' "$UP2" | jq_get data.id)
  IMG2_PATH=$(mysql_ro "SELECT image_url FROM \`$DB_NAME\`.repair_attachment WHERE id=$IMG2;")
  if [ -n "$IMG2" ] && [ -n "$IMG2_PATH" ]; then
    r=$(http DELETE "/api/practice/$S1CODE/repair/images/$IMG2?operatorId=$REP" '')
    check "D7 上传人可删除未关联的临时图片" "200" "$(code_of "$r")"
    GONE2=0
    for ((i = 1; i <= 40; i++)); do
      [ ! -f "$REPAIR_FILES_ROOT/$IMG2_PATH" ] && { GONE2=1; break; }
      sleep 3
    done
    check "D7 删除临时图片后磁盘文件也被清理" "1" "$GONE2"
  else
    skip "D7 临时图片删除后的磁盘清理" "上传或查询失败"
  fi
fi

# ============================ E. 收尾校验 ==================================
echo
echo "--- E. 收尾校验 ---"

PROTECTED_NOW="$(protected_snapshot)"
check "E1 受保护库表数与抽样行数未变（完整校验见独立验收记录）" "$PROTECTED_BASELINE" "$PROTECTED_NOW"

PROTECTED_IMAGES_AFTER=$(protected_images_snapshot) || exit 3
check "E2 项目原图片文件清单及全部内容哈希保持不变" "$PROTECTED_IMAGES_BEFORE" "$PROTECTED_IMAGES_AFTER"

echo
echo "=============================================================="
printf '通过 %d 项，失败 %d 项，未验证 %d 项\n' "$PASS" "$FAIL" "$SKIP"
echo "=============================================================="

{
  echo "隔离审查专项回归结果"
  echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo "隔离后端：$API"
  echo "隔离库：$DB_NAME"
  echo "隔离图片目录：$REPAIR_FILES_ROOT"
  echo "受保护库：$PROTECTED_DB（只读校验）"
  echo "写操作：$([ "$READ_ONLY" = "1" ] && echo "未执行任何写入" || echo "已在隔离环境执行")"
  echo "通过 $PASS 项，失败 $FAIL 项，未验证 $SKIP 项"
  echo
  for line in "${RESULTS[@]}"; do echo "$line"; done
} > "$OUT"
echo "结果已写入 $OUT"

[ "$FAIL" -eq 0 ]
