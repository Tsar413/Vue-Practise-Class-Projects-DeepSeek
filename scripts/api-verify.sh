#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/api-verify.sh  接口 / 权限 / 数据隔离 / 重置 对照验证
#
# 直接对运行中的后端发起真实 HTTP 请求，输出“通过 / 失败”清单，
# 结论同时写入 docs/verification-api.txt。
#
# 覆盖内容：
#   登录与 Token 校验、角色权限边界、替换 ID 越权、实训访问码校验、
#   两名学生数据隔离、抢票主流程、报修主流程、账号/班级/空间状态联动、
#   重置范围、学生批量导入、主要异常情况。
#
# 前置条件：
#   后端已启动；数据库已执行 db/01-schema.sql 与 db/02-seed-demo-data.sql。
#   班级与学生的基准数据由本脚本自动初始化。
#
# 用法（写操作，只允许对隔离环境执行）：
#   API_BASE=http://127.0.0.1:18100 OUT=/tmp/api-verify-review.txt bash scripts/api-verify.sh
#
# 安全说明：
#   * 脚本对端口 8100（常规开发端口）直接拒绝执行，因为本套件包含重置与删除操作。
#   * 报告默认写入 docs/verification-api.txt；覆盖前会保留一份 .prev 历史副本，
#     可用 OUT 指定项目外的路径，避免冲掉既有结论。
# ---------------------------------------------------------------------------
set -uo pipefail

API="${API_BASE:-http://127.0.0.1:18100}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/docs/verification-api.txt}"
CLASS="${VERIFY_CLASS:-DEMO2026}"
STUDENT_A="${VERIFY_STUDENT_A:-DEMO2026001}"
STUDENT_B="${VERIFY_STUDENT_B:-DEMO2026002}"
STUDENT_C="${VERIFY_STUDENT_C:-DEMO2026003}"
TEACHER="${VERIFY_TEACHER:-DEMO_TEACHER}"
PASSWORD="${VERIFY_PASSWORD:-123456}"
FIXTURE="${FIXTURE:-$ROOT/scripts/fixtures/test-image.png}"
XLSX="${XLSX:-$ROOT/scripts/fixtures/students.xlsx}"

PASS=0
FAIL=0
declare -a RESULTS=()
AV_BODY=""
AV_CODE=""

ok()  { PASS=$((PASS+1)); RESULTS+=("通过  $1"); printf '通过  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); RESULTS+=("失败  $1  <- $2"); printf '失败  %s  <- %s\n' "$1" "$2"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "期望 [$2] 实际 [$3]"; fi; }

# rest <方法> <路径> [token] [json数据]
# 响应体存入 AV_BODY，状态码存入 AV_CODE；两者分开保存，JSON 不会被按行截断。
rest() {
  local method="$1" path="$2" token="${3:-}" data="${4:-}"
  AV_CODE=$(curl -sS -o "$TMPDIR_PRIVATE/body" -w '%{http_code}' "$API$path" -X "$method" \
    ${token:+-H "Authorization: Bearer $token"} \
    ${data:+-H 'Content-Type: application/json' -d "$data"})
  AV_BODY=$(cat "$TMPDIR_PRIVATE/body")
}

http() { rest "$@"; printf '%s\n%s\n' "$AV_BODY" "$AV_CODE"; }
code_of() { printf '%s\n' "$1" | tail -n 1; }
body_of() { printf '%s\n' "$1" | head -n -1; }

# jq_get <点分路径>   从标准输入读取 JSON，取值失败返回空字符串
jq_get() {
  python3 -c "
import sys, json
raw = sys.stdin.read().strip()
try:
    d = json.loads(raw) if raw else None
except Exception:
    d = None
for k in '$1'.split('.'):
    if d is None:
        break
    if isinstance(d, list):
        try:
            i = int(k)
        except ValueError:
            d = None; break
        d = d[i] if 0 <= i < len(d) else None
    elif isinstance(d, dict):
        d = d.get(k)
    else:
        d = None
print('' if d is None else d)
"
}

# json_data_len   从标准输入读取 JSON，返回 data 数组长度
json_data_len() {
  python3 -c "
import sys, json
raw = sys.stdin.read().strip()
try:
    d = json.loads(raw) if raw else {}
except Exception:
    print('PARSE_ERROR'); sys.exit()
data = d.get('data') if isinstance(d, dict) else None
print(len(data) if isinstance(data, list) else 'NO_DATA')
"
}

# json_booked_sum   统计 data 数组中的报名人数合计
json_booked_sum() {
  python3 -c "
import sys, json
try:
    d = json.load(sys.stdin).get('data') or []
except Exception:
    d = []
print(sum(a.get('bookedCount', 0) for a in d))
"
}

get_json() {
  rest GET "$1" "${2:-}"
  printf '%s' "$AV_BODY"
}

json_new_id() {
  rest "$1" "$2" "${3:-}" "${4:-}"
  printf '%s' "$AV_BODY" | jq_get data.id
}

login_code() {
  rest POST /login '' "{\"id\":\"$1\",\"password\":\"$PASSWORD\"}"
  printf '%s' "$AV_BODY" | jq_get data.apiAccessCode
}
login_token() {
  rest POST /login '' "{\"id\":\"$1\",\"password\":\"$PASSWORD\"}"
  printf '%s' "$AV_BODY" | jq_get data.token
}

ws_of() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin).get('data') or []
except Exception:
    d = []
print(sorted({u.get('workspaceId') for u in d}))"; }

ids_of() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin).get('data') or []
except Exception:
    d = []
print(','.join(str(u.get('id')) for u in d))"; }

# 选取“已发布 + 当前处于报名窗口 + 还有名额”的活动 ID。
# 只按 status=1 选取会命中“尚未开始报名”的活动，导致报名接口返回 409。
pick_open_activity() {
  python3 -c "
import sys, json, datetime
try:
    d = json.load(sys.stdin).get('data') or []
except Exception:
    d = []
now = datetime.datetime.now()
def parse(s):
    try:
        return datetime.datetime.strptime(s, '%Y-%m-%d %H:%M:%S')
    except Exception:
        return None
for a in d:
    if a.get('status') != 1:
        continue
    s, e = parse(a.get('bookingStartTime') or ''), parse(a.get('bookingEndTime') or '')
    if s and e and s <= now < e and (a.get('bookedCount') or 0) < (a.get('quota') or 0):
        print(a['id']); break
"
}

echo "=============================================================="
echo "API 对照验证  $(date '+%Y-%m-%d %H:%M:%S')"
echo "后端地址：$API    班级：$CLASS"
# Codex 最终复核：统一核验本机监听者、真实库、图片目录及运行记录。
API_BASE="$API" python3 "$ROOT/scripts/verify-review-target.py" || exit 3
umask 077
TMPDIR_PRIVATE=$(mktemp -d)
trap 'rm -rf "$TMPDIR_PRIVATE"' EXIT

# ---------------------- 0. 准备基准数据 ----------------------
echo
echo "--- 0. 准备班级基准数据 ---"

TTOKEN=$(login_token "$TEACHER")
if [ -z "$TTOKEN" ]; then
  echo "无法登录教师账号，请先导入 db/02-seed-demo-data.sql"
  exit 1
fi

# 抢票 / 报修的业务数据来自“按班级初始化”。已存在数据的空间会被后端自动跳过。
http POST "/api/sys-workspace/classes/$CLASS/ticket/initialize" "$TTOKEN" > /dev/null
http POST "/api/sys-workspace/classes/$CLASS/repair/initialize" "$TTOKEN" > /dev/null

S1CODE=$(login_code "$STUDENT_A")
S1TOKEN=$(login_token "$STUDENT_A")
S2CODE=$(login_code "$STUDENT_B")
S2TOKEN=$(login_token "$STUDENT_B")
A_ADMIN0=$(get_json "/api/practice/$S1CODE/repair/users?role=ADMIN" | jq_get data.0.id)

check "学生甲获得64位访问码" "64" "${#S1CODE}"
check "学生乙获得64位访问码" "64" "${#S2CODE}"
check "两名学生访问码不同" "different" \
  "$([ -n "$S1CODE" ] && [ "$S1CODE" != "$S2CODE" ] && echo different || echo same)"
check "学生甲抢票活动基准数据为4条" "4" \
  "$(get_json "/api/practice/$S1CODE/ticket/activities" | json_data_len)"
check "学生甲报修工单基准数据为6条" "6" \
  "$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$A_ADMIN0" | json_data_len)"

# ---------------------- 1. 登录与凭证 ----------------------
echo
echo "--- 1. 登录、Token 校验与账号状态 ---"

r=$(http POST /login '' "{\"id\":\"$TEACHER\",\"password\":\"$PASSWORD\"}")
check "教师正确口令登录返回200" "200" "$(code_of "$r")"
check "教师登录返回角色 TEACHER" "TEACHER" "$(body_of "$r" | jq_get data.role)"
check "教师登录返回64位Token" "64" "$(printf '%s' "$(body_of "$r" | jq_get data.token)" | wc -c | tr -d ' ')"
check "教师登录不返回访问码" "" "$(body_of "$r" | jq_get data.apiAccessCode)"
TTOKEN=$(body_of "$r" | jq_get data.token)

r=$(http POST /login '' "{\"id\":\"$STUDENT_A\",\"password\":\"$PASSWORD\"}")
check "学生正确口令登录返回200" "200" "$(code_of "$r")"
check "学生登录返回角色 STUDENT" "STUDENT" "$(body_of "$r" | jq_get data.role)"
check "学生登录返回64位访问码" "64" \
  "$(printf '%s' "$(body_of "$r" | jq_get data.apiAccessCode)" | wc -c | tr -d ' ')"
check "重复登录后访问码保持不变" "true" "$([ "$S1CODE" = "$(body_of "$r" | jq_get data.apiAccessCode)" ] && echo true || echo false)"
NEW_TOKEN=$(body_of "$r" | jq_get data.token)
check "再次登录后网页Token已轮换" "rotated" \
  "$([ "$S1TOKEN" != "$NEW_TOKEN" ] && echo rotated || echo same)"
S1TOKEN="$NEW_TOKEN"

r=$(http POST /login '' "{\"id\":\"$STUDENT_A\",\"password\":\"wrong-password\"}")
check "口令错误返回401" "401" "$(code_of "$r")"
check "口令错误提示统一" "账号或密码错误" "$(body_of "$r" | jq_get message)"

r=$(http POST /login '' '{"id":"NO_SUCH_USER","password":"123456"}')
check "账号不存在与口令错误同样返回401" "401" "$(code_of "$r")"

r=$(http POST /login '' '{"id":"","password":""}')
check "账号口令为空返回400" "400" "$(code_of "$r")"

LONGID=$(printf 'a%.0s' $(seq 1 60))
r=$(http POST /login '' "{\"id\":\"$LONGID\",\"password\":\"$PASSWORD\"}")
check "账号超长返回400" "400" "$(code_of "$r")"

r=$(http POST /login '' '{}')
check "登录请求体缺少字段返回400" "400" "$(code_of "$r")"

r=$(http POST /login '' 'not-json')
check "登录请求体不是合法JSON返回400" "400" "$(code_of "$r")"

# ---------------------- 2. 权限边界 ----------------------
echo
echo "--- 2. 未登录、非法凭证与角色权限边界 ---"

r=$(http GET /api/sys-class/all '')
check "未登录访问系统接口返回401" "401" "$(code_of "$r")"
check "未登录提示请先登录" "请先登录" "$(body_of "$r" | jq_get message)"

r=$(http GET /api/sys-class/all 'not-a-token')
check "非法格式凭证返回401" "401" "$(code_of "$r")"

r=$(http GET /api/sys-class/all "$(printf 'b%.0s' $(seq 1 64))")
check "不存在的合法格式凭证返回401" "401" "$(code_of "$r")"

r=$(http GET /api/sys-user/all "$S1TOKEN")
check "学生访问全部用户列表返回403" "403" "$(code_of "$r")"

r=$(http GET "/api/sys-user/one/$STUDENT_A" "$S1TOKEN")
check "学生查询本人账号返回200" "200" "$(code_of "$r")"

r=$(http GET "/api/sys-user/one/$STUDENT_B" "$S1TOKEN")
check "学生查询他人账号返回403（替换学号越权被拦截）" "403" "$(code_of "$r")"

r=$(http GET "/api/sys-workspace/one/$STUDENT_B" "$S1TOKEN")
check "学生查询他人工作空间返回403" "403" "$(code_of "$r")"

r=$(http POST "/api/sys-workspace/one/$STUDENT_B/reset?project=TICKET" "$S1TOKEN")
check "学生重置他人项目返回403" "403" "$(code_of "$r")"

r=$(http GET /api/sys-class/all "$S1TOKEN")
check "学生访问班级列表返回403" "403" "$(code_of "$r")"

r=$(http POST /api/sys-user/one "$S1TOKEN" \
  "{\"id\":\"X1\",\"realName\":\"X\",\"role\":\"STUDENT\",\"classId\":\"$CLASS\"}")
check "学生创建账号返回403" "403" "$(code_of "$r")"

r=$(http GET /api/sys-user/all "$TTOKEN")
check "教师访问全部用户列表返回200" "200" "$(code_of "$r")"

# ---------------------- 3. 实训访问码 ----------------------
echo
echo "--- 3. 实训访问码校验 ---"

r=$(http GET "/api/practice/$(printf 'c%.0s' $(seq 1 64))/ticket/users" '')
check "不存在的访问码返回401" "401" "$(code_of "$r")"

r=$(http GET "/api/practice/short-code/ticket/users" '')
check "格式错误的访问码返回401" "401" "$(code_of "$r")"

r=$(http GET "/api/practice/$S1CODE/ticket/users" '')
check "有效访问码无需网页Token即可查询" "200" "$(code_of "$r")"
check "业务查询响应结构正确" "操作成功" "$(body_of "$r" | jq_get message)"

r=$(http GET "/api/practice/$S1CODE/unknown-project/users" '')
check "未知项目路径被拒绝（401鉴权或404路由）" "ok" \
  "$(case "$(code_of "$r")" in 401|404) echo ok;; *) echo "$(code_of "$r")";; esac)"

# ---------------------- 4. 数据隔离 ----------------------
echo
echo "--- 4. 两名学生之间的数据隔离 ---"

A_USERS=$(get_json "/api/practice/$S1CODE/ticket/users")
B_USERS=$(get_json "/api/practice/$S2CODE/ticket/users")
A_WS=$(printf '%s' "$A_USERS" | ws_of)
B_WS=$(printf '%s' "$B_USERS" | ws_of)
check "两名学生的抢票数据属于不同工作空间" "different" \
  "$([ -n "$A_WS" ] && [ "$A_WS" != "$B_WS" ] && echo different || echo same)"

A_IDS=$(printf '%s' "$A_USERS" | ids_of)
B_IDS=$(printf '%s' "$B_USERS" | ids_of)
check "两名学生的模拟用户主键不重叠" "disjoint" "$(python3 -c "
a = set(x for x in '$A_IDS'.split(',') if x)
b = set(x for x in '$B_IDS'.split(',') if x)
print('disjoint' if a and b and not (a & b) else 'overlap')")"

A_DEV_WS=$(get_json "/api/practice/$S1CODE/repair/devices" | ws_of)
B_DEV_WS=$(get_json "/api/practice/$S2CODE/repair/devices" | ws_of)
check "两名学生的报修设备属于不同工作空间" "different" \
  "$([ -n "$A_DEV_WS" ] && [ "$A_DEV_WS" != "$B_DEV_WS" ] && echo different || echo same)"

B_ACT=$(get_json "/api/practice/$S2CODE/ticket/activities" | jq_get data.0.id)
if [ -n "$B_ACT" ]; then
  r=$(http GET "/api/practice/$S1CODE/ticket/activities/$B_ACT" '')
  check "用甲的访问码读取乙的活动返回404" "404" "$(code_of "$r")"
else
  bad "读取乙的活动ID" "返回为空"
fi

B_ADMIN=$(get_json "/api/practice/$S2CODE/repair/users?role=ADMIN" | jq_get data.0.id)
B_ORDER=$(get_json "/api/practice/$S2CODE/repair/orders?operatorId=$B_ADMIN" | jq_get data.0.id)
if [ -n "$B_ORDER" ]; then
  r=$(http GET "/api/practice/$S1CODE/repair/orders/$B_ORDER?operatorId=$B_ADMIN" '')
  check "用甲的访问码读取乙的工单返回403或404" "403/404" \
    "$(case "$(code_of "$r")" in 403|404) echo "403/404";; *) echo "$(code_of "$r")";; esac)"
else
  bad "读取乙的工单ID" "返回为空"
fi

if [ -n "$B_ADMIN" ]; then
  r=$(http GET "/api/practice/$S1CODE/repair/users/$B_ADMIN" '')
  check "用甲的访问码读取乙的模拟用户返回404" "404" "$(code_of "$r")"
else
  bad "读取乙的管理员ID" "返回为空"
fi

# ---------------------- 5. 抢票主流程 ----------------------
echo
echo "--- 5. 校园抢票主要业务流程（学生甲） ---"

A_TUSER=$(get_json "/api/practice/$S1CODE/ticket/users?role=USER&status=1" | jq_get data.0.id)
A_TUSER2=$(get_json "/api/practice/$S1CODE/ticket/users?role=USER&status=1" | jq_get data.1.id)
A_TADMIN=$(get_json "/api/practice/$S1CODE/ticket/users?role=ADMIN&status=1" | jq_get data.0.id)
check "抢票模拟用户与管理员可用" "ok" \
  "$([ -n "$A_TUSER" ] && [ -n "$A_TUSER2" ] && [ -n "$A_TADMIN" ] && echo ok || echo missing)"

A_ACT=$(get_json "/api/practice/$S1CODE/ticket/activities" | pick_open_activity)
check "存在正在报名且有名额的已发布活动" "ok" "$([ -n "$A_ACT" ] && echo ok || echo missing)"

r=$(http POST "/api/practice/$S1CODE/ticket/activities/$A_ACT/records?userId=$A_TUSER" '')
check "报名抢票成功" "200" "$(code_of "$r")"
A_REC=$(body_of "$r" | jq_get data.id)
A_TICKETNO=$(body_of "$r" | jq_get data.ticketNo)
check "票券编号以TP开头" "TP" "$(printf '%s' "$A_TICKETNO" | cut -c1-2)"
check "新票券状态为有效(1)" "1" "$(body_of "$r" | jq_get data.status)"

r=$(http POST "/api/practice/$S1CODE/ticket/activities/$A_ACT/records?userId=$A_TUSER" '')
check "重复报名被拒绝" "409" "$(code_of "$r")"

r=$(http POST "/api/practice/$S1CODE/ticket/activities/$A_ACT/records?userId=$A_TADMIN" '')
check "管理员身份不能报名（仅普通用户可报名）" "403" "$(code_of "$r")"

check "报名人数递增为1" "1" \
  "$(get_json "/api/practice/$S1CODE/ticket/activities/$A_ACT" | jq_get data.bookedCount)"

r=$(http PUT "/api/practice/$S1CODE/ticket/records/$A_REC/verify?operatorId=$A_TUSER" '')
check "普通用户核销票券返回403" "403" "$(code_of "$r")"

r=$(http PUT "/api/practice/$S1CODE/ticket/records/$A_REC/verify?operatorId=$A_TADMIN" '')
check "管理员核销票券成功" "200" "$(code_of "$r")"
check "核销后状态为2" "2" "$(body_of "$r" | jq_get data.status)"

r=$(http PUT "/api/practice/$S1CODE/ticket/records/$A_REC/verify?operatorId=$A_TADMIN" '')
check "重复核销被拒绝" "409" "$(code_of "$r")"
check "核销不释放名额" "1" \
  "$(get_json "/api/practice/$S1CODE/ticket/activities/$A_ACT" | jq_get data.bookedCount)"

r=$(http POST "/api/practice/$S1CODE/ticket/activities/$A_ACT/records?userId=$A_TUSER" '')
check "已核销用户不能再次报名" "409" "$(code_of "$r")"

r=$(http GET "/api/practice/$S1CODE/ticket/records/$A_REC?operatorId=$A_TUSER" '')
check "本人可以查询自己的票券" "200" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/records/$A_REC?operatorId=$A_TUSER2" '')
check "他人不能查询本人票券" "403" "$(code_of "$r")"
check "管理员可以查询活动报名名单" "1" \
  "$(get_json "/api/practice/$S1CODE/ticket/activities/$A_ACT/records?operatorId=$A_TADMIN" | json_data_len)"

A_ACT2=$(get_json "/api/practice/$S1CODE/ticket/activities" | pick_open_activity)
if [ -n "$A_ACT2" ]; then
  r=$(http POST "/api/practice/$S1CODE/ticket/activities/$A_ACT2/records?userId=$A_TUSER2" '')
  A_REC2=$(body_of "$r" | jq_get data.id)
  check "第二名普通用户报名成功" "200" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/ticket/records/$A_REC2/cancel?userId=$A_TUSER2" '')
  check "取消本人票券成功" "200" "$(code_of "$r")"
  check "取消后状态为0" "0" "$(body_of "$r" | jq_get data.status)"
  r=$(http PUT "/api/practice/$S1CODE/ticket/records/$A_REC2/cancel?userId=$A_TUSER2" '')
  check "重复取消保持幂等" "200" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/ticket/records/$A_REC/cancel?userId=$A_TUSER2" '')
  check "取消他人票券返回403" "403" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/ticket/records/$A_REC/cancel?userId=$A_TUSER" '')
  check "已核销票券不能取消" "409" "$(code_of "$r")"
else
  bad "选择第二个可用于报名的活动" "返回为空"
fi

r=$(http GET "/api/practice/$S1CODE/ticket/activities?campus=不存在的校区" '')
check "非法校区参数返回400" "400" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/activities?status=9" '')
check "非法状态参数返回400" "400" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/users?role=BAD" '')
check "非法角色参数返回400" "400" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/activities/9999999" '')
check "不存在的活动返回404" "404" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/records/9999999?operatorId=$A_TADMIN" '')
check "不存在的票券返回404" "404" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/users/NO_SUCH_USER" '')
check "不存在的抢票用户返回404" "404" "$(code_of "$r")"
r=$(http DELETE "/api/practice/$S1CODE/ticket/activities/$A_ACT2?operatorId=$A_TUSER" '')
check "普通用户删除活动返回403" "403" "$(code_of "$r")"

FUTURE_START=$(date -d '+1 day' '+%Y-%m-%dT%H:%M:%S')
FUTURE_END=$(date -d '+2 day' '+%Y-%m-%dT%H:%M:%S')
ACT_START=$(date -d '+3 day' '+%Y-%m-%dT%H:%M:%S')
ACT_END=$(date -d '+3 day +2 hours' '+%Y-%m-%dT%H:%M:%S')

r=$(http POST "/api/practice/$S1CODE/ticket/activities" '' \
  "{\"operatorId\":$A_TUSER,\"activityName\":\"x\",\"campus\":\"新吴校区\",\"location\":\"y\",\"quota\":5,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
check "普通用户创建活动返回403" "403" "$(code_of "$r")"

NEW_ACT=$(json_new_id POST "/api/practice/$S1CODE/ticket/activities" '' \
  "{\"operatorId\":$A_TADMIN,\"activityName\":\"联调验证活动\",\"description\":\"联调验证用活动\",\"campus\":\"新吴校区\",\"location\":\"联调验证地点\",\"quota\":5,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
check "管理员创建活动草稿成功" "ok" "$([ -n "$NEW_ACT" ] && echo ok || echo fail)"

if [ -n "$NEW_ACT" ]; then
  check "新建活动初始状态为草稿(0)" "0" \
    "$(get_json "/api/practice/$S1CODE/ticket/activities/$NEW_ACT" | jq_get data.status)"

  r=$(http POST "/api/practice/$S1CODE/ticket/activities" '' \
    "{\"operatorId\":$A_TADMIN,\"activityName\":\"时间顺序错误\",\"campus\":\"新吴校区\",\"location\":\"y\",\"quota\":5,\"bookingStartTime\":\"$FUTURE_END\",\"bookingEndTime\":\"$FUTURE_START\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
  check "报名开始晚于结束返回400" "400" "$(code_of "$r")"

  r=$(http POST "/api/practice/$S1CODE/ticket/activities" '' \
    "{\"operatorId\":$A_TADMIN,\"activityName\":\"非法校区\",\"campus\":\"不存在校区\",\"location\":\"y\",\"quota\":5,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
  check "非法校区创建活动返回400" "400" "$(code_of "$r")"

  r=$(http POST "/api/practice/$S1CODE/ticket/activities" '' \
    "{\"operatorId\":$A_TADMIN,\"activityName\":\"名额非法\",\"campus\":\"新吴校区\",\"location\":\"y\",\"quota\":0,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
  check "名额为0创建活动返回400" "400" "$(code_of "$r")"

  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$NEW_ACT" '' \
    "{\"operatorId\":$A_TADMIN,\"activityName\":\"联调验证活动（已修改）\",\"campus\":\"藕塘校区\",\"location\":\"联调验证地点二\",\"quota\":6,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
  check "修改草稿活动成功" "200" "$(code_of "$r")"

  # 当前正处于报名窗口，不能发布（发布要求报名尚未结束即可，这里仍可发布）
  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$NEW_ACT/status" '' \
    "{\"operatorId\":$A_TADMIN,\"status\":1}")
  check "发布活动成功" "200" "$(code_of "$r")"
  check "发布后状态为1" "1" "$(body_of "$r" | jq_get data.status)"

  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$NEW_ACT/status" '' \
    "{\"operatorId\":$A_TADMIN,\"status\":1}")
  check "重复发布保持幂等" "200" "$(code_of "$r")"

  # 该活动报名尚未开始，后端在发布前会重新校验时间字段，
  # 传入非法目标状态 0 时先被参数校验拦下（400）；已关闭活动不可修改则见后面的 409 断言。
  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$NEW_ACT/status" '' \
    "{\"operatorId\":$A_TADMIN,\"status\":0}")
  check "非法的目标状态返回400" "400" "$(code_of "$r")"

  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$NEW_ACT/status" '' \
    "{\"operatorId\":$A_TADMIN,\"status\":3}")
  check "目标状态超出1/2返回400" "400" "$(code_of "$r")"

  r=$(http DELETE "/api/practice/$S1CODE/ticket/activities/$NEW_ACT?operatorId=$A_TADMIN" '')
  check "已发布活动不能删除" "409" "$(code_of "$r")"

  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$NEW_ACT/status" '' \
    "{\"operatorId\":$A_TADMIN,\"status\":2}")
  check "关闭活动成功" "200" "$(code_of "$r")"
  check "关闭后状态为2" "2" "$(body_of "$r" | jq_get data.status)"

  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$NEW_ACT" '' \
    "{\"operatorId\":$A_TADMIN,\"activityName\":\"已关闭活动改名\",\"campus\":\"新吴校区\",\"location\":\"y\",\"quota\":6,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
  check "已关闭活动不允许修改" "409" "$(code_of "$r")"
fi

# 报名已开始的已发布活动：不允许修改报名与活动时间
if [ -n "$A_ACT" ]; then
  r=$(http PUT "/api/practice/$S1CODE/ticket/activities/$A_ACT" '' \
    "{\"operatorId\":$A_TADMIN,\"activityName\":\"正在报名的活动改名\",\"campus\":\"新吴校区\",\"location\":\"调整后的地点\",\"quota\":50,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
  check "报名已开始后不能修改报名与活动时间" "409" "$(code_of "$r")"
fi

DRAFT_ACT=$(json_new_id POST "/api/practice/$S1CODE/ticket/activities" '' \
  "{\"operatorId\":$A_TADMIN,\"activityName\":\"待删除草稿\",\"campus\":\"藕塘校区\",\"location\":\"联调验证地点\",\"quota\":5,\"bookingStartTime\":\"$FUTURE_START\",\"bookingEndTime\":\"$FUTURE_END\",\"activityStartTime\":\"$ACT_START\",\"activityEndTime\":\"$ACT_END\"}")
if [ -n "$DRAFT_ACT" ]; then
  r=$(http DELETE "/api/practice/$S1CODE/ticket/activities/$DRAFT_ACT?operatorId=$A_TADMIN" '')
  check "删除草稿活动成功" "200" "$(code_of "$r")"
  r=$(http GET "/api/practice/$S1CODE/ticket/activities/$DRAFT_ACT" '')
  check "删除后查询活动返回404" "404" "$(code_of "$r")"
else
  bad "创建待删除草稿" "返回为空"
fi

# ---------------------- 6. 报修主流程 ----------------------
echo
echo "--- 6. 校园设备报修主要业务流程（学生甲） ---"

R_REPORTER=$(get_json "/api/practice/$S1CODE/repair/users?role=REPORTER&status=1" | jq_get data.0.id)
R_MAINT=$(get_json "/api/practice/$S1CODE/repair/users?role=MAINTAINER&status=1" | jq_get data.0.id)
R_MAINT2=$(get_json "/api/practice/$S1CODE/repair/users?role=MAINTAINER&status=1" | jq_get data.1.id)
R_ADMIN=$(get_json "/api/practice/$S1CODE/repair/users?role=ADMIN&status=1" | jq_get data.0.id)
R_DEV=$(get_json "/api/practice/$S1CODE/repair/devices?status=1" | jq_get data.0.id)
check "报修四类模拟身份可用" "ok" \
  "$([ -n "$R_REPORTER" ] && [ -n "$R_MAINT" ] && [ -n "$R_MAINT2" ] && [ -n "$R_ADMIN" ] && [ -n "$R_DEV" ] && echo ok || echo missing)"

A_ORDER=$(json_new_id POST "/api/practice/$S1CODE/repair/orders" '' \
  "{\"operatorId\":$R_REPORTER,\"deviceId\":$R_DEV,\"title\":\"联调：设备定期检查\",\"description\":\"<p>课堂联调：设备检查，需要上门确认运行状态。</p>\",\"contactPhone\":\"13800000001\"}")
check "报修人按已登记设备创建工单成功" "ok" "$([ -n "$A_ORDER" ] && echo ok || echo fail)"

ORDER_NO=""
if [ -n "$A_ORDER" ]; then
  DETAIL=$(get_json "/api/practice/$S1CODE/repair/orders/$A_ORDER?operatorId=$R_ADMIN")
  check "新工单状态为待分派(1)" "1" "$(printf '%s' "$DETAIL" | jq_get data.order.status)"
  ORDER_NO=$(printf '%s' "$DETAIL" | jq_get data.order.orderNo)
  check "工单编号以BX开头" "BX" "$(printf '%s' "$ORDER_NO" | cut -c1-2)"
  check "工单详情返回报修人姓名" "ok" \
    "$([ -n "$(printf '%s' "$DETAIL" | jq_get data.reporterName)" ] && echo ok || echo missing)"
  check "创建工单已生成处理记录" "1" \
    "$(get_json "/api/practice/$S1CODE/repair/orders/$A_ORDER/process-records?operatorId=$R_REPORTER" | json_data_len)"
fi

r=$(http POST "/api/practice/$S1CODE/repair/orders" '' \
  "{\"operatorId\":$R_MAINT,\"deviceId\":$R_DEV,\"title\":\"x\",\"description\":\"<p>abc</p>\"}")
check "维修师傅创建工单返回403" "403" "$(code_of "$r")"

r=$(http POST "/api/practice/$S1CODE/repair/orders" '' \
  "{\"operatorId\":$R_REPORTER,\"deviceName\":\"x\",\"deviceType\":\"电脑\",\"campus\":\"新吴校区\",\"location\":\"y\",\"title\":\"z\",\"description\":\"<p>   </p>\"}")
check "故障描述无有效文字返回400" "400" "$(code_of "$r")"

r=$(http POST "/api/practice/$S1CODE/repair/orders" '' \
  "{\"operatorId\":$R_REPORTER,\"deviceName\":\"x\",\"deviceType\":\"电脑\",\"campus\":\"不存在校区\",\"location\":\"y\",\"title\":\"z\",\"description\":\"<p>abc</p>\"}")
check "未登记设备使用非法校区返回400" "400" "$(code_of "$r")"

r=$(http POST "/api/practice/$S1CODE/repair/orders" '' \
  "{\"operatorId\":$R_REPORTER,\"title\":\"缺少设备信息\",\"description\":\"<p>abc</p>\"}")
check "缺少设备信息返回400" "400" "$(code_of "$r")"

r=$(http POST "/api/practice/$S1CODE/repair/orders" '' \
  "{\"operatorId\":$R_REPORTER,\"deviceId\":9999999,\"title\":\"不存在的设备\",\"description\":\"<p>abc</p>\"}")
check "使用不存在的设备创建工单返回404" "404" "$(code_of "$r")"

if [ -n "$A_ORDER" ]; then
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/assign" '' \
    "{\"operatorId\":$R_REPORTER,\"maintainerId\":$R_MAINT}")
  check "报修人派单返回403" "403" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/assign" '' \
    "{\"operatorId\":$R_ADMIN,\"maintainerId\":$R_REPORTER}")
  check "派单给非维修师傅返回403" "403" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/assign" '' \
    "{\"operatorId\":$R_ADMIN,\"maintainerId\":$R_MAINT}")
  check "管理员派单成功" "200" "$(code_of "$r")"
  check "派单后状态为待接单(2)" "2" "$(body_of "$r" | jq_get data.status)"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/assign" '' \
    "{\"operatorId\":$R_ADMIN,\"maintainerId\":$R_MAINT}")
  check "重复分派给同一师傅保持幂等" "200" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/assign" '' \
    "{\"operatorId\":$R_ADMIN,\"maintainerId\":$R_MAINT2}")
  check "改派给第二位师傅成功" "200" "$(code_of "$r")"
  check "改派后维修师傅ID已更新" "$R_MAINT2" "$(body_of "$r" | jq_get data.maintainerId)"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/assign" '' \
    "{\"operatorId\":$R_ADMIN,\"maintainerId\":$R_MAINT}")
  check "改回第一位师傅成功" "200" "$(code_of "$r")"
fi

if [ -n "$A_ORDER" ]; then
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/accept?operatorId=$R_MAINT2" '')
  check "非被分派师傅接单返回403" "403" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/accept?operatorId=$R_MAINT" '')
  check "被分派师傅接单成功" "200" "$(code_of "$r")"
  check "接单后状态为维修中(3)" "3" "$(body_of "$r" | jq_get data.status)"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/accept?operatorId=$R_MAINT" '')
  check "重复接单保持幂等" "200" "$(code_of "$r")"
fi

if [ -n "$A_ORDER" ]; then
  r=$(http POST "/api/practice/$S1CODE/repair/orders/$A_ORDER/process-records" '' \
    "{\"operatorId\":$R_MAINT,\"content\":\"已检查设备供电，准备试运行。\"}")
  check "追加维修记录成功" "200" "$(code_of "$r")"
  r=$(http POST "/api/practice/$S1CODE/repair/orders/$A_ORDER/process-records" '' \
    "{\"operatorId\":$R_REPORTER,\"content\":\"非师傅写入\"}")
  check "报修人追加维修记录返回403" "403" "$(code_of "$r")"
  r=$(http POST "/api/practice/$S1CODE/repair/orders/$A_ORDER/process-records" '' \
    "{\"operatorId\":$R_MAINT,\"content\":\"   \"}")
  check "维修说明为空返回400" "400" "$(code_of "$r")"
fi

if [ -f "$FIXTURE" ]; then
  IMG_MAINT=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$R_MAINT" -F "imageType=2" -F "file=@$FIXTURE" | jq_get data.id)
  check "维修师傅上传维修图片成功" "ok" "$([ -n "$IMG_MAINT" ] && echo ok || echo fail)"

  FAULT_IMG=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$R_REPORTER" -F "imageType=1" -F "file=@$FIXTURE" | jq_get data.id)
  check "报修人上传故障图片成功" "ok" "$([ -n "$FAULT_IMG" ] && echo ok || echo fail)"

  WRONG_TYPE=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$R_REPORTER" -F "imageType=2" -F "file=@$FIXTURE" | jq_get message)
  check "报修人上传维修图片被拒绝" "仅维修师傅可以上传维修图片" "$WRONG_TYPE"

  BAD_FILE=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$R_MAINT" -F "imageType=2" -F "file=@$ROOT/scripts/api-verify.sh" | jq_get message)
  check "非图片文件被拒绝" "ok" \
    "$(printf '%s' "$BAD_FILE" | grep -q "图片" && echo ok || echo "$BAD_FILE")"

  if [ -n "$FAULT_IMG" ]; then
    r=$(http GET "/api/practice/$S1CODE/repair/images/$FAULT_IMG/preview" '')
    check "图片预览返回200" "200" "$(code_of "$r")"
    check "预览返回真实图片内容" "ok" \
      "$(head -c 4 "$TMPDIR_PRIVATE/body" | od -An -tx1 | tr -d ' \n' | grep -qiE '89504e47|ffd8ff' && echo ok || echo 非图片)"
    r=$(http GET "/api/practice/$S1CODE/repair/images/$FAULT_IMG/download" '')
    check "图片下载返回200" "200" "$(code_of "$r")"
    r=$(http GET "/api/practice/$S2CODE/repair/images/$FAULT_IMG/preview" '')
    check "用乙的访问码读取甲的图片返回404（跨空间隔离）" "404" "$(code_of "$r")"
    r=$(http DELETE "/api/practice/$S1CODE/repair/images/$FAULT_IMG?operatorId=$R_MAINT" '')
    check "非上传人删除临时图片返回403" "403" "$(code_of "$r")"
    r=$(http DELETE "/api/practice/$S1CODE/repair/images/$FAULT_IMG?operatorId=$R_REPORTER" '')
    check "上传人可以删除未关联的临时图片" "200" "$(code_of "$r")"
  fi
else
  echo "跳过  图片上传相关断言（未找到素材：$FIXTURE）"
fi

r=$(http GET "/api/practice/$S1CODE/repair/images/9999999/preview" '')
check "不存在图片预览返回404" "404" "$(code_of "$r")"

if [ -n "$A_ORDER" ] && [ -f "$FIXTURE" ]; then
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/submit" '' \
    "{\"operatorId\":$R_MAINT,\"repairResult\":\"已处理。\",\"imageIds\":[]}")
  check "提交维修结果缺少必填图片返回400" "400" "$(code_of "$r")"

  IMG3=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$R_MAINT" -F "imageType=2" -F "file=@$FIXTURE" | jq_get data.id)
  IMG4=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
    -F "operatorId=$R_MAINT" -F "imageType=2" -F "file=@$FIXTURE" | jq_get data.id)

  if [ -n "$IMG3" ] && [ -n "$IMG4" ]; then
    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/submit" '' \
      "{\"operatorId\":$R_MAINT,\"repairResult\":\"已更换损坏部件并测试通过。\",\"imageIds\":[$IMG3,$IMG4]}")
    check "提交维修结果成功" "200" "$(code_of "$r")"
    check "提交后状态为待确认(4)" "4" "$(body_of "$r" | jq_get data.status)"

    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/submit" '' \
      "{\"operatorId\":$R_MAINT,\"repairResult\":\"重复提交\",\"imageIds\":[]}")
    check "重复提交维修结果保持幂等" "200" "$(code_of "$r")"
    check "重复提交后仍为待确认(4)" "4" "$(body_of "$r" | jq_get data.status)"

    check "工单关联2张维修图片" "2" \
      "$(get_json "/api/practice/$S1CODE/repair/orders/$A_ORDER/images?operatorId=$R_REPORTER" | json_data_len)"

    TIMELINE=$(get_json "/api/practice/$S1CODE/repair/orders/$A_ORDER/process-records?operatorId=$R_REPORTER" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin).get('data') or []
except Exception:
    d = []
print(','.join(x.get('action', '?') for x in d))")
    # 派单 → 改派给第二位师傅 → 改回第一位师傅，共三条 ASSIGN 记录
    check "处理记录时间线包含创建/派单/改派/接单/记录/提交" \
      "CREATE,ASSIGN,ASSIGN,ASSIGN,ACCEPT,RECORD,SUBMIT" "$TIMELINE"

    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/return" '' \
      "{\"operatorId\":$R_REPORTER,\"content\":\"维修后仍有异响，请复查。\"}")
    check "报修人退回维修成功" "200" "$(code_of "$r")"
    check "退回后状态回到维修中(3)" "3" "$(body_of "$r" | jq_get data.status)"

    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/confirm?operatorId=$R_REPORTER" '')
    check "维修中工单不能直接确认完成" "409" "$(code_of "$r")"

    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/submit" '' \
      "{\"operatorId\":$R_MAINT,\"repairResult\":\"复用已绑定图片\",\"imageIds\":[$IMG3]}")
    check "复用已绑定图片被拒绝" "409" "$(code_of "$r")"

    IMG5=$(curl -sS -X POST "$API/api/practice/$S1CODE/repair/images" \
      -F "operatorId=$R_MAINT" -F "imageType=2" -F "file=@$FIXTURE" | jq_get data.id)
    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/submit" '' \
      "{\"operatorId\":$R_MAINT,\"repairResult\":\"复查后重新固定并测试正常。\",\"imageIds\":[$IMG5]}")
    check "退回后使用新附件重新提交成功" "200" "$(code_of "$r")"

    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/confirm?operatorId=$R_MAINT" '')
    check "师傅确认完成返回403" "403" "$(code_of "$r")"
    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/confirm?operatorId=$R_REPORTER" '')
    check "报修人确认完成成功" "200" "$(code_of "$r")"
    check "确认后状态为已完成(5)" "5" "$(body_of "$r" | jq_get data.status)"
    r=$(http PUT "/api/practice/$S1CODE/repair/orders/$A_ORDER/confirm?operatorId=$R_REPORTER" '')
    check "重复确认完成保持幂等" "200" "$(code_of "$r")"

    r=$(http POST "/api/practice/$S1CODE/repair/orders/$A_ORDER/evaluation" '' \
      "{\"operatorId\":$R_REPORTER,\"score\":5,\"content\":\"处理及时，谢谢。\"}")
    check "已完成工单评价成功" "200" "$(code_of "$r")"
    r=$(http POST "/api/practice/$S1CODE/repair/orders/$A_ORDER/evaluation" '' \
      "{\"operatorId\":$R_REPORTER,\"score\":4,\"content\":\"重复评价\"}")
    check "重复评价被拒绝" "409" "$(code_of "$r")"
    check "查询工单评价评分为5" "5" \
      "$(get_json "/api/practice/$S1CODE/repair/orders/$A_ORDER/evaluation?operatorId=$R_REPORTER" | jq_get data.score)"
    r=$(http POST "/api/practice/$S1CODE/repair/orders/$A_ORDER/evaluation" '' \
      "{\"operatorId\":$R_REPORTER,\"score\":6,\"content\":\"越界评分\"}")
    check "评分超出1-5返回400" "400" "$(code_of "$r")"
  else
    bad "上传维修结果图片" "返回为空"
  fi
fi

CANCEL_ORDER=$(json_new_id POST "/api/practice/$S1CODE/repair/orders" '' \
  "{\"operatorId\":$R_REPORTER,\"deviceName\":\"待撤回设备\",\"deviceType\":\"打印机\",\"campus\":\"新吴校区\",\"location\":\"联调验证机房\",\"title\":\"联调：待撤回工单\",\"description\":\"<p>用于验证撤回流程。</p>\"}")
if [ -n "$CANCEL_ORDER" ]; then
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$CANCEL_ORDER/cancel?operatorId=$R_REPORTER" '')
  check "报修人撤回待分派工单成功" "200" "$(code_of "$r")"
  check "撤回后状态为已撤销(0)" "0" "$(body_of "$r" | jq_get data.status)"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$CANCEL_ORDER/cancel?operatorId=$R_REPORTER" '')
  check "重复撤回保持幂等" "200" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/orders/$CANCEL_ORDER/confirm?operatorId=$R_REPORTER" '')
  check "已撤销工单不能确认完成" "409" "$(code_of "$r")"
fi

r=$(http GET "/api/practice/$S1CODE/repair/orders?operatorId=$R_REPORTER&scope=BAD" '')
check "非法 scope 参数返回400" "400" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/repair/orders?operatorId=$R_REPORTER&status=9" '')
check "非法工单状态返回400" "400" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/repair/orders/9999999?operatorId=$R_ADMIN" '')
check "不存在工单返回404" "404" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/repair/users/9999999" '')
check "不存在的报修用户返回404" "404" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/repair/devices/9999999" '')
check "不存在的设备返回404" "404" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/repair/devices?campus=不存在校区" '')
check "非法校区查询设备返回400" "400" "$(code_of "$r")"

REPORTER_ONLY=$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_REPORTER" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin).get('data') or []
except Exception:
    d = []
print('ok' if d and all(str(o.get('reporterId')) == '$R_REPORTER' for o in d) else ('empty' if not d else 'leak'))")
check "报修人查询结果只包含本人提交的工单" "ok" "$REPORTER_ONLY"

if [ -n "$ORDER_NO" ]; then
  # 曾参与 = 处理记录的 operatorId 或 targetUserId 命中；改派后两位师傅都曾参与
  check "被改派过的师傅按参与范围可查到工单" "1" \
    "$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_MAINT2&scope=PARTICIPATED&keyword=$(printf '%s' "$ORDER_NO" | head -c 20)" | json_data_len)"
  check "当前分派师傅按参与范围可查到工单" "1" \
    "$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_MAINT&scope=PARTICIPATED&keyword=$(printf '%s' "$ORDER_NO" | head -c 20)" | json_data_len)"
  check "按当前分派范围只查询分派给本人的工单" "1" \
    "$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_MAINT&scope=ASSIGNED&keyword=$(printf '%s' "$ORDER_NO" | head -c 20)" | json_data_len)"
fi

r=$(http POST "/api/practice/$S1CODE/repair/users" '' \
  "{\"operatorId\":$R_REPORTER,\"userNo\":\"X0001\",\"realName\":\"测试\",\"role\":\"REPORTER\"}")
check "非管理员创建模拟用户返回403" "403" "$(code_of "$r")"

NEW_USER=$(json_new_id POST "/api/practice/$S1CODE/repair/users" '' \
  "{\"operatorId\":$R_ADMIN,\"userNo\":\"XTEST1\",\"realName\":\"临时测试用户\",\"role\":\"REPORTER\",\"phone\":\"13800000099\",\"status\":1}")
check "管理员创建模拟用户成功" "ok" "$([ -n "$NEW_USER" ] && echo ok || echo fail)"
if [ -n "$NEW_USER" ]; then
  r=$(http POST "/api/practice/$S1CODE/repair/users" '' \
    "{\"operatorId\":$R_ADMIN,\"userNo\":\"XTEST1\",\"realName\":\"重复编号\",\"role\":\"REPORTER\"}")
  check "重复模拟用户编号被拒绝" "409" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/users/$NEW_USER" '' \
    "{\"operatorId\":$R_ADMIN,\"userNo\":\"CHANGED\",\"realName\":\"临时测试用户\",\"role\":\"REPORTER\"}")
  check "修改模拟用户编号被拒绝" "400" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/users/$NEW_USER" '' \
    "{\"operatorId\":$R_ADMIN,\"userNo\":\"XTEST1\",\"realName\":\"临时测试用户二\",\"role\":\"REPORTER\",\"status\":0}")
  check "修改模拟用户成功" "200" "$(code_of "$r")"
  r=$(http POST "/api/practice/$S1CODE/repair/users/switch?userNo=XTEST1" '')
  check "停用模拟身份不能切换" "403" "$(code_of "$r")"
  r=$(http DELETE "/api/practice/$S1CODE/repair/users/$NEW_USER?operatorId=$R_ADMIN" '')
  check "删除无业务关联的模拟用户成功" "200" "$(code_of "$r")"
fi

r=$(http DELETE "/api/practice/$S1CODE/repair/users/$R_REPORTER?operatorId=$R_ADMIN" '')
check "删除有业务关联的模拟用户被拒绝" "409" "$(code_of "$r")"
r=$(http PUT "/api/practice/$S1CODE/repair/users/$R_REPORTER" '' \
  "{\"operatorId\":$R_ADMIN,\"userNo\":\"R0001\",\"realName\":\"张明\",\"role\":\"ADMIN\",\"status\":1}")
check "已参与业务的用户不能改角色" "409" "$(code_of "$r")"

r=$(http POST "/api/practice/$S1CODE/repair/devices" '' \
  "{\"operatorId\":$R_REPORTER,\"deviceNo\":\"XDEV1\",\"deviceName\":\"测试设备\",\"deviceType\":\"电脑\",\"campus\":\"新吴校区\",\"location\":\"测试地点\"}")
check "非管理员创建设备返回403" "403" "$(code_of "$r")"
NEW_DEV=$(json_new_id POST "/api/practice/$S1CODE/repair/devices" '' \
  "{\"operatorId\":$R_ADMIN,\"deviceNo\":\"XDEV1\",\"deviceName\":\"临时测试设备\",\"deviceType\":\"电脑\",\"campus\":\"新吴校区\",\"location\":\"测试地点\"}")
check "管理员创建设备成功" "ok" "$([ -n "$NEW_DEV" ] && echo ok || echo fail)"
if [ -n "$NEW_DEV" ]; then
  r=$(http POST "/api/practice/$S1CODE/repair/devices" '' \
    "{\"operatorId\":$R_ADMIN,\"deviceNo\":\"XDEV1\",\"deviceName\":\"重复编号设备\",\"deviceType\":\"电脑\",\"campus\":\"新吴校区\",\"location\":\"测试地点\"}")
  check "重复设备编号被拒绝" "409" "$(code_of "$r")"
  r=$(http PUT "/api/practice/$S1CODE/repair/devices/$NEW_DEV/status?operatorId=$R_ADMIN&status=0" '')
  check "停用设备成功" "200" "$(code_of "$r")"
  check "停用后设备状态为0" "0" "$(body_of "$r" | jq_get data.status)"
  r=$(http PUT "/api/practice/$S1CODE/repair/devices/$NEW_DEV" '' \
    "{\"operatorId\":$R_ADMIN,\"deviceNo\":\"CHANGED\",\"deviceName\":\"临时测试设备\",\"deviceType\":\"电脑\",\"campus\":\"新吴校区\",\"location\":\"测试地点\"}")
  check "修改设备编号被拒绝" "400" "$(code_of "$r")"
fi

# ---------------------- 7. 状态联动 ----------------------
echo
echo "--- 7. 账号与工作空间状态联动 ---"

S3CODE=$(login_code "$STUDENT_C")
r=$(http PUT "/api/sys-workspace/one/$STUDENT_C/status?status=0" "$TTOKEN")
check "教师暂停学生丙工作空间成功" "200" "$(code_of "$r")"
r=$(http GET "/api/practice/$S3CODE/ticket/users" '')
check "工作空间暂停后访问码不可用" "403" "$(code_of "$r")"
r=$(http PUT "/api/sys-workspace/one/$STUDENT_C/status?status=1" "$TTOKEN")
check "教师恢复学生丙工作空间成功" "200" "$(code_of "$r")"
r=$(http GET "/api/practice/$S3CODE/ticket/users" '')
check "工作空间恢复后同一访问码仍可用" "200" "$(code_of "$r")"

r=$(http PUT "/api/sys-user/one/$STUDENT_C/status?status=0" "$TTOKEN")
check "教师停用学生丙账号成功" "200" "$(code_of "$r")"
r=$(http POST /login '' "{\"id\":\"$STUDENT_C\",\"password\":\"$PASSWORD\"}")
check "停用账号无法登录" "403" "$(code_of "$r")"
r=$(http GET "/api/practice/$S3CODE/ticket/users" '')
check "停用后长期访问码被拒绝" "403" "$(code_of "$r")"
r=$(http PUT "/api/sys-user/one/$STUDENT_C/status?status=1" "$TTOKEN")
check "教师恢复学生丙账号成功" "200" "$(code_of "$r")"
r=$(http GET "/api/practice/$S3CODE/ticket/users" '')
check "账号恢复后访问码恢复可用" "200" "$(code_of "$r")"

r=$(http PUT "/api/sys-class/one/$CLASS/status?status=0" "$TTOKEN")
check "教师停用班级成功" "200" "$(code_of "$r")"
r=$(http POST /login '' "{\"id\":\"$STUDENT_A\",\"password\":\"$PASSWORD\"}")
check "班级停用后学生无法登录" "403" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/users" '')
check "班级停用后访问码被拒绝" "403" "$(code_of "$r")"
r=$(http PUT "/api/sys-class/one/$CLASS/status?status=1" "$TTOKEN")
check "教师恢复班级成功" "200" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/users" '')
check "班级恢复后访问码恢复可用" "200" "$(code_of "$r")"

r=$(http PUT "/api/sys-class/one/$CLASS/status?status=9" "$TTOKEN")
check "班级状态非法值返回400" "400" "$(code_of "$r")"
r=$(http PUT "/api/sys-user/one/$STUDENT_A/status?status=9" "$TTOKEN")
check "账号状态非法值返回400" "400" "$(code_of "$r")"
r=$(http GET /api/sys-class/one/NO_SUCH_CLASS "$TTOKEN")
check "不存在的班级返回404" "404" "$(code_of "$r")"
r=$(http GET /api/sys-user/one/NO_SUCH_USER "$TTOKEN")
check "不存在的系统用户返回404" "404" "$(code_of "$r")"
r=$(http GET /api/sys-workspace/one/NO_SUCH_USER "$TTOKEN")
check "不存在的工作空间返回404" "404" "$(code_of "$r")"
r=$(http PUT /api/sys-class/one/NO_SUCH_CLASS/status?status=1 "$TTOKEN")
check "修改不存在班级状态返回404" "404" "$(code_of "$r")"
r=$(http PUT /api/sys-user/one/NO_SUCH_USER/status?status=1 "$TTOKEN")
check "修改不存在账号状态返回404" "404" "$(code_of "$r")"
r=$(http POST /api/sys-user/one "$TTOKEN" \
  "{\"id\":\"$STUDENT_A\",\"realName\":\"重复编号\",\"role\":\"STUDENT\",\"classId\":\"$CLASS\"}")
check "重复用户编号返回409" "409" "$(code_of "$r")"
r=$(http POST /api/sys-user/one "$TTOKEN" '{"id":"NEWSTU1","realName":"新学生","role":"STUDENT"}')
check "学生未填班级返回400" "400" "$(code_of "$r")"
r=$(http POST /api/sys-user/one "$TTOKEN" \
  "{\"id\":\"NEWSTU1\",\"realName\":\"新学生\",\"role\":\"STUDENT\",\"classId\":\"NO_SUCH_CLASS\"}")
check "学生班级不存在返回404" "404" "$(code_of "$r")"

r=$(http POST /logout '' '')
check "缺少凭证退出登录返回401" "401" "$(code_of "$r")"

S1TOKEN=$(login_token "$STUDENT_A")
r=$(http POST /logout "$S1TOKEN" '')
check "携带凭证退出登录成功" "200" "$(code_of "$r")"
r=$(http GET "/api/sys-user/one/$STUDENT_A" "$S1TOKEN")
check "退出后原网页Token失效" "401" "$(code_of "$r")"
r=$(http GET "/api/practice/$S1CODE/ticket/users" '')
check "退出网页登录不影响长期访问码" "200" "$(code_of "$r")"
S1TOKEN=$(login_token "$STUDENT_A")

TMP_ID="TMPDEL001"
http POST /api/sys-user/one "$TTOKEN" \
  "{\"id\":\"$TMP_ID\",\"realName\":\"临时删除账号\",\"role\":\"STUDENT\",\"classId\":\"$CLASS\"}" > /dev/null
r=$(http GET "/api/sys-workspace/one/$TMP_ID" "$TTOKEN")
check "新建学生自动获得工作空间" "200" "$(code_of "$r")"
TMP_CODE=$(login_code "$TMP_ID")
http POST "/api/sys-workspace/classes/$CLASS/ticket/initialize" "$TTOKEN" > /dev/null
check "新建学生可初始化项目数据" "4" \
  "$(get_json "/api/practice/$TMP_CODE/ticket/activities" | json_data_len)"
r=$(http DELETE "/api/sys-user/one/$TMP_ID" "$TTOKEN")
check "删除学生账号成功" "200" "$(code_of "$r")"
r=$(http GET "/api/practice/$TMP_CODE/ticket/users" '')
check "删除后原访问码失效" "401/403" \
  "$(case "$(code_of "$r")" in 401|403) echo "401/403";; *) echo "$(code_of "$r")";; esac)"
r=$(http GET "/api/sys-workspace/one/$TMP_ID" "$TTOKEN")
check "删除后工作空间不存在" "404" "$(code_of "$r")"

# ---------------------- 8. 重置范围 ----------------------
echo
echo "--- 8. 重置只影响当前学生和指定项目 ---"

A_TICKET_BOOKED=$(get_json "/api/practice/$S1CODE/ticket/activities" | json_booked_sum)
A_ORDERS_BEFORE=$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_ADMIN" | json_data_len)
B_TICKET_USERS_BEFORE=$(get_json "/api/practice/$S2CODE/ticket/users" | ids_of)
B_BOOKED_BEFORE=$(get_json "/api/practice/$S2CODE/ticket/activities" | json_booked_sum)

r=$(http POST "/api/practice/$S1CODE/ticket/users/switch?userNo=N0001" '')
check "抢票模拟身份切换可用" "200" "$(code_of "$r")"
check "切换返回的用户编号为N0001" "N0001" "$(body_of "$r" | jq_get data.userNo)"
r=$(http POST "/api/practice/$S1CODE/ticket/users/switch?userNo=N0004" '')
check "已停用的模拟身份不能切换" "403" "$(code_of "$r")"
r=$(http POST "/api/practice/$S1CODE/repair/users/switch?userNo=R0001" '')
check "报修模拟身份切换可用" "200" "$(code_of "$r")"

check "重置前甲存在报名数据" "has" "$([ "${A_TICKET_BOOKED:-0}" -gt 0 ] && echo has || echo none)"

r=$(http POST "/api/sys-workspace/one/$STUDENT_A/reset?project=TICKET" "$TTOKEN")
check "教师重置学生甲抢票项目成功" "200" "$(code_of "$r")"
check "重置后甲抢票报名人数归零" "0" \
  "$(get_json "/api/practice/$S1CODE/ticket/activities" | json_booked_sum)"
check "重置后甲抢票活动回到基准4条" "4" \
  "$(get_json "/api/practice/$S1CODE/ticket/activities" | json_data_len)"
# 重置会重建模拟用户，数据库主键随之变化，必须重新查询
A_TUSER_AFTER_RESET=$(get_json "/api/practice/$S1CODE/ticket/users?role=USER&status=1" | jq_get data.0.id)
check "重置后甲抢票票券记录清空" "0" \
  "$(get_json "/api/practice/$S1CODE/ticket/users/$A_TUSER_AFTER_RESET/records" | json_data_len)"
check "只重置抢票时甲的报修工单数量不变" "$A_ORDERS_BEFORE" \
  "$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_ADMIN" | json_data_len)"
check "重置甲不影响乙的抢票数据（主键不变）" "$B_TICKET_USERS_BEFORE" \
  "$(get_json "/api/practice/$S2CODE/ticket/users" | ids_of)"
check "重置甲不影响乙的报名人数" "$B_BOOKED_BEFORE" \
  "$(get_json "/api/practice/$S2CODE/ticket/activities" | json_booked_sum)"
rest GET "/api/practice/$S1CODE/ticket/users"
check "重置后访问码保持不变" "200" "$AV_CODE"

r=$(http POST "/api/sys-workspace/one/$STUDENT_A/reset?project=REPAIR" "$TTOKEN")
check "教师重置学生甲报修项目成功" "200" "$(code_of "$r")"
R_ADMIN_AFTER=$(get_json "/api/practice/$S1CODE/repair/users?role=ADMIN&status=1" | jq_get data.0.id)
check "报修重置后管理员工号已更新" "changed" \
  "$([ "$R_ADMIN" != "$R_ADMIN_AFTER" ] && echo changed || echo same)"
check "报修重置后工单回到6条基准数据" "6" \
  "$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_ADMIN_AFTER" | json_data_len)"
check "报修重置后第一张工单只保留初始处理记录" "1" \
  "$(get_json "/api/practice/$S1CODE/repair/orders/$(get_json "/api/practice/$S1CODE/repair/orders?operatorId=$R_ADMIN_AFTER" | jq_get data.0.id)/process-records?operatorId=$R_ADMIN_AFTER" | json_data_len)"

r=$(http POST "/api/sys-workspace/one/$STUDENT_A/reset?project=BAD" "$TTOKEN")
check "非法项目参数返回400" "400" "$(code_of "$r")"
r=$(http POST "/api/sys-workspace/one/NO_SUCH_STUDENT/reset?project=TICKET" "$TTOKEN")
check "不存在的工作空间返回404" "404" "$(code_of "$r")"
r=$(http POST "/api/sys-workspace/one/$TEACHER/reset?project=TICKET" "$TTOKEN")
check "教师账号没有工作空间，重置返回404" "404" "$(code_of "$r")"

r=$(http POST /login '' "{\"id\":\"$STUDENT_B\",\"password\":\"$PASSWORD\"}")
S2TOKEN=$(body_of "$r" | jq_get data.token)
r=$(http POST "/api/sys-workspace/one/$STUDENT_B/reset?project=ALL" "$S2TOKEN")
check "学生自助重置本人全部项目成功" "200" "$(code_of "$r")"
check "自助重置后抢票数据回到基准4条" "4" \
  "$(get_json "/api/practice/$S2CODE/ticket/activities" | json_data_len)"
B_ADMIN_AFTER=$(get_json "/api/practice/$S2CODE/repair/users?role=ADMIN&status=1" | jq_get data.0.id)
check "自助重置后报修工单回到基准6条" "6" \
  "$(get_json "/api/practice/$S2CODE/repair/orders?operatorId=$B_ADMIN_AFTER" | json_data_len)"

# ---------------------- 9. 批量导入 ----------------------
echo
echo "--- 9. 学生批量导入 ---"

if [ -f "$XLSX" ]; then
  IMPORT=$(curl -sS -X POST "$API/api/sys-user/import" -H "Authorization: Bearer $TTOKEN" -F "file=@$XLSX")
  check "导入文件共处理4行数据" "4" "$(printf '%s' "$IMPORT" | jq_get data.total)"
  check "已存在的学号被跳过" "1" "$(printf '%s' "$IMPORT" | jq_get data.skippedCount)"
  check "班级不存在的行失败" "1" "$(printf '%s' "$IMPORT" | jq_get data.failedCount)"
  check "成功导入2名学生" "2" "$(printf '%s' "$IMPORT" | jq_get data.successCount)"

  r=$(http POST /login '' '{"id":"IMPORT9001","password":"123456"}')
  check "新导入学生可用初始口令登录" "200" "$(code_of "$r")"
  IMPORTED_CODE=$(body_of "$r" | jq_get data.apiAccessCode)
  check "新导入学生自动获得工作空间与访问码" "64" "${#IMPORTED_CODE}"
  rest GET "/api/practice/$IMPORTED_CODE/ticket/activities"
  check "新导入学生空间已启用（业务接口可访问）" "200" "$AV_CODE"

  r=$(http DELETE /api/sys-user/one/IMPORT9001 "$TTOKEN")
  check "清理导入的测试账号" "200" "$(code_of "$r")"
  r=$(http DELETE /api/sys-user/one/IMPORT9002 "$TTOKEN")
  check "清理第二个导入的测试账号" "200" "$(code_of "$r")"

  IMPORT_ERR=$(curl -sS -X POST "$API/api/sys-user/import" -H "Authorization: Bearer $TTOKEN" \
    -F "file=@$ROOT/scripts/api-verify.sh" | jq_get message)
  check "非 Excel 文件被拒绝" "仅支持.xls或.xlsx文件" "$IMPORT_ERR"

  IMPORT_DENIED=$(curl -sS -o "$TMPDIR_PRIVATE/body" -w '%{http_code}' -X POST "$API/api/sys-user/import" \
    -H "Authorization: Bearer $S1TOKEN" -F "file=@$XLSX")
  check "学生调用导入接口返回403" "403" "$IMPORT_DENIED"
else
  echo "跳过  Excel 批量导入（未找到素材：$XLSX）"
fi

echo
echo "=============================================================="
printf '通过 %d 项，失败 %d 项\n' "$PASS" "$FAIL"
echo "=============================================================="

# 覆盖报告前先留一份历史副本，避免旧结论被静默冲掉
if [ -f "$OUT" ]; then
  cp "$OUT" "$OUT.prev" 2>/dev/null || true
fi

{
  echo "API 对照验证结果"
  echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo "后端：$API"
  echo "班级：$CLASS"
  echo "通过 $PASS 项，失败 $FAIL 项"
  echo
  for line in "${RESULTS[@]}"; do echo "$line"; done
} > "$OUT"
echo "结果已写入 $OUT"

[ "$FAIL" -eq 0 ]
