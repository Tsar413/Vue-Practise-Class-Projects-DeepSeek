#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# scripts/migrate.py  教学与 AI 辅导增量迁移执行器
#
# 职责：
#   1. 目标库必须由调用方显式给出（--database），不猜、不建库、不切库；
#      库名先通过标识符校验，再进行任何 SQL 拼接。
#   2. 只允许连接本机 MySQL：socket 文件，或 127.0.0.1/localhost/::1。
#   3. 迁移锁用**数据库专属文件锁**（fcntl.flock）覆盖整个流程。
#      不要用 MySQL GET_LOCK：每次 mysql 客户端调用都是独立会话，
#      会话结束锁即释放，因此 GET_LOCK 在这里无法提供互斥保证。
#   4. 结构校验：表、关键列（含类型/排序规则）、唯一约束逐项核对；
#      只有「SQL 成功 + 校验通过」之后才写入 schema_migration（含 SHA-256）。
#   5. 失败保留已创建结构、不自动 DROP、退出非零并给出恢复步骤；
#      已存在且校验一致的结构对 IF NOT EXISTS 是幂等的，可直接重跑续做。
#   6. 不修改首次 applied_at；重复执行只更新 checksum/note。
#
# 用法：
#   python3 scripts/migrate.py --database <库> --status      # 只读状态
#   python3 scripts/migrate.py --database <库> --dry-run     # 只读预检
#   python3 scripts/migrate.py --database <库>               # 应用迁移
#
# 连接参数（默认本机 socket + root，可用环境变量覆盖）：
#   MYSQL_HOME / DB_SOCKET / DB_HOST / DB_PORT / DB_ROOT_USER / DB_ROOT_PASSWORD
# ---------------------------------------------------------------------------
import argparse
import fcntl
import hashlib
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SQL_FILE = ROOT / "db" / "03-teaching-ai-tutor.sql"
MIGRATION_VERSION = "2026-10-09-v3"  # v3：草稿字段 + 版本-附件关联 + 清理任务状态

# 本机 MySQL 客户端依赖 ~/tools/opt/lib 下的兼容动态库。
# 与仓库其他脚本保持一致，在执行任何 mysql 调用之前注入，
# 否则客户端会因缺少 libaio/libncurses 而静默失败
# （表现为「库不存在」这类误导性结论）。
_EXTRA_LIB = os.environ.get("EXTRA_LIB", str(Path.home() / "tools/opt/lib"))
if _EXTRA_LIB and Path(_EXTRA_LIB).is_dir():
    _existing = os.environ.get("LD_LIBRARY_PATH", "")
    if _EXTRA_LIB not in _existing.split(":"):
        os.environ["LD_LIBRARY_PATH"] = f"{_EXTRA_LIB}:{_existing}" if _existing else _EXTRA_LIB

NEW_TABLES = [
    "teaching_task",
    "teaching_task_class",
    "teaching_submission",
    "teaching_submission_version",
    "teaching_submission_section",
    "teaching_attachment",
    "teaching_version_attachment",
    "teaching_section_attachment",
    "teaching_file_delete_task",
    "teaching_evaluation",
    "teaching_submit_request",
    "ai_tutor_conversation",
    "ai_tutor_message",
]
MIGRATION_TABLE = "schema_migration"

# 迁移记录表由执行器负责创建。SQL 脚本保持「纯结构」，不含 INSERT，
# 因此这条建表语句必须在执行器里，否则写记录会因表不存在而失败
# （表现为每次执行都被当成首次执行）。
MIGRATION_TABLE_DDL = """
CREATE TABLE IF NOT EXISTS `schema_migration` (
  `version` varchar(64) NOT NULL COMMENT '迁移版本号',
  `checksum` varchar(128) NULL COMMENT '脚本 SHA-256',
  `applied_at` datetime NOT NULL COMMENT '首次应用时间',
  `note` varchar(500) NULL,
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='增量迁移版本记录'
"""

# (表, 列, 期望 column_type, 期望 collation 或 None)
KEY_COLUMNS = [
    ("teaching_task", "teacher_id", "varchar(50)", "utf8mb4_general_ci"),
    ("teaching_task", "status", "tinyint", None),
    ("teaching_task_class", "class_id", "varchar(50)", "utf8mb4_general_ci"),
    ("teaching_submission", "version_no", "int", None),
    ("teaching_submission", "draft_content", "text", "utf8mb4_general_ci"),
    ("teaching_submission", "draft_project_url", "varchar(500)", "utf8mb4_general_ci"),
    ("teaching_submission", "draft_process", "text", "utf8mb4_general_ci"),
    ("teaching_submission", "draft_update_time", "datetime", None),
    ("teaching_submission", "student_id", "varchar(50)", "utf8mb4_general_ci"),
    ("teaching_submission_version", "version_no", "int", None),
    ("teaching_submission_section", "is_draft", "tinyint", None),
    ("teaching_attachment", "deleted_at", "datetime", None),
    ("teaching_version_attachment", "attachment_id", "bigint", None),
    ("teaching_section_attachment", "section_id", "bigint", None),
    ("teaching_file_delete_task", "processed", "tinyint", None),
    ("teaching_submission_section", "deleted_at", "datetime", None),
    ("teaching_evaluation", "is_current", "tinyint", None),
    ("teaching_evaluation", "version_id", "bigint", None),
    ("teaching_submit_request", "request_key", "varchar(64)", "utf8mb4_general_ci"),
    ("ai_tutor_message", "context_refs", "text", "utf8mb4_general_ci"),
    ("ai_tutor_conversation", "status", "tinyint", None),
]
# (表, 索引名, 期望列顺序)
UNIQUE_KEYS = [
    ("teaching_task_class", "uk_teaching_task_class", ["task_id", "class_id"]),
    ("teaching_submission", "uk_teaching_submission_task_student", ["task_id", "student_id"]),
    ("teaching_submission_version", "uk_teaching_version_no", ["submission_id", "version_no"]),
    ("teaching_submit_request", "uk_teaching_submit_request", ["submission_id", "request_key"]),
    ("teaching_version_attachment", "uk_teaching_version_attachment", ["version_id", "attachment_id"]),
    ("teaching_section_attachment", "uk_teaching_section_attachment", ["section_id", "attachment_id"]),
]

LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1", ""}


def env(name, default=""):
    return os.environ.get(name, default).strip()


def mysql_bin():
    home = env("MYSQL_HOME", str(Path.home() / "tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64"))
    binary = Path(home) / "bin" / "mysql"
    if not binary.exists():
        sys.exit(f"找不到 mysql 客户端：{binary}（可用 MYSQL_HOME 指定）")
    return str(binary)


def connection_target():
    """返回 (参数列表, 描述)。只允许本机连接。"""
    socket = env("DB_SOCKET", str(Path.home() / "var/mysql/db.sock"))
    if socket and Path(socket).exists():
        return ["--socket", socket], f"socket {socket}"
    host = env("DB_HOST", "127.0.0.1")
    if host not in LOCAL_HOSTS:
        sys.exit(f"拒绝执行：DB_HOST 只允许本机回环地址，收到 {host}")
    return (["--protocol", "TCP", "-h", host or "127.0.0.1", "-P", env("DB_PORT", "3306")],
            f"TCP {host or '127.0.0.1'}")


def base_cmd(database=None):
    target, _ = connection_target()
    cmd = [mysql_bin(), "--no-defaults"] + target + ["-u", env("DB_ROOT_USER", "root")]
    password = env("DB_ROOT_PASSWORD")
    if password:
        # 通过环境变量传递，不出现在命令行与进程列表
        os.environ["MYSQL_PWD"] = password
    if database:
        cmd += ["--database", database]
    return cmd + ["-N", "-B"]


def run_sql(sql, database=None):
    proc = subprocess.run(base_cmd(database), input=sql.encode("utf-8"), capture_output=True)
    return (proc.returncode,
            proc.stdout.decode("utf-8", "replace"),
            proc.stderr.decode("utf-8", "replace"))


def query(sql, database):
    code, out, err = run_sql(sql, database)
    if code != 0:
        raise RuntimeError(f"查询失败：{err.strip()[:400]}")
    return out.strip()


def identifier_ok(name):
    return bool(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", name))


def file_sha256():
    return hashlib.sha256(SQL_FILE.read_bytes()).hexdigest()


def database_exists(name):
    """返回 (是否存在, 错误信息)。错误信息用于区分「真的没有」与「连不上」。"""
    code, out, err = run_sql(
        f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '{name}';")
    if code != 0:
        return False, err.strip()[:300] or "连接 MySQL 失败"
    return out.strip() == "1", ""


def table_exists(database, table):
    return query(
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE table_schema = DATABASE() AND table_name = '{table}';", database) == "1"


def existing_new_tables(database):
    names = ",".join(f"'{t}'" for t in NEW_TABLES)
    rows = query(
        "SELECT table_name FROM information_schema.tables "
        f"WHERE table_schema = DATABASE() AND table_name IN ({names}) ORDER BY table_name;",
        database)
    return [r for r in rows.splitlines() if r.strip()]


def read_migration_row(database):
    if not table_exists(database, MIGRATION_TABLE):
        return None
    rows = query(
        "SELECT version, IFNULL(checksum,''), DATE_FORMAT(applied_at,'%Y-%m-%d %H:%i:%s') "
        f"FROM {MIGRATION_TABLE} WHERE version = '{MIGRATION_VERSION}';", database)
    if not rows:
        return None
    parts = rows.split("\t")
    return {"version": parts[0],
            "checksum": parts[1] if len(parts) > 1 else "",
            "applied_at": parts[2] if len(parts) > 2 else ""}


def check_structure(database):
    """校验新增结构；返回问题列表（空表示完整）。"""
    present = set(existing_new_tables(database))
    problems = [f"缺少表：{t}" for t in NEW_TABLES if t not in present]
    if problems:
        return problems

    for table, column, col_type, collation in KEY_COLUMNS:
        sql = ("SELECT COUNT(*) FROM information_schema.columns "
               f"WHERE table_schema = DATABASE() AND table_name = '{table}' "
               f"AND column_name = '{column}' AND column_type = '{col_type}'")
        if collation:
            sql += f" AND collation_name = '{collation}'"
        if query(sql + ";", database) != "1":
            want = col_type + (f" / {collation}" if collation else "")
            problems.append(f"列不符合预期：{table}.{column}（要求 {want}）")

    for table, index, columns in UNIQUE_KEYS:
        got = query(
            "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
            "FROM information_schema.statistics "
            f"WHERE table_schema = DATABASE() AND table_name = '{table}' "
            f"AND index_name = '{index}' AND non_unique = 0;", database)
        want = ",".join(columns)
        if got != want:
            problems.append(f"唯一约束不符合预期：{table}.{index} 期望 [{want}] 实际 [{got}]")

    return problems


def apply_sql(database, sql_text):
    if re.search(r"^\s*USE\s+", sql_text, flags=re.IGNORECASE | re.MULTILINE):
        sys.exit("迁移脚本中不应出现 USE 语句（目标库必须由执行器传入），已拒绝执行。")
    prefix = "SET NAMES utf8mb4;\n"
    proc = subprocess.run(base_cmd(database), input=(prefix + sql_text).encode("utf-8"),
                          capture_output=True)
    return (proc.returncode,
            proc.stdout.decode("utf-8", "replace"),
            proc.stderr.decode("utf-8", "replace"))


def ensure_migration_table(database):
    """保证迁移记录表存在；失败即中止，避免「结构已建但记录写不进去」。"""
    code, _, err = run_sql(MIGRATION_TABLE_DDL + ";", database)
    if code != 0:
        return False, err.strip()[:300]
    return True, ""


def write_migration_record(database, checksum):
    """
    写入迁移记录；仅在 SQL 与结构校验都通过后调用。
    ON DUPLICATE 分支不覆盖 applied_at，只更新 checksum/note，
    避免重复执行刷新首次应用时间，也不会留下空 checksum。
    """
    ok, err = ensure_migration_table(database)
    if not ok:
        return 1, "", f"创建 {MIGRATION_TABLE} 表失败：{err}"
    sql = (
        f"INSERT INTO {MIGRATION_TABLE} (version, checksum, applied_at, note) VALUES "
        f"('{MIGRATION_VERSION}', '{checksum}', NOW(), "
        "'教学任务/成果版本/评价/AI辅导 增量结构')\n"
        "ON DUPLICATE KEY UPDATE checksum = VALUES(checksum), note = VALUES(note);\n")
    return run_sql(sql, database)


def print_recovery_steps(database, checksum, detail_lines):
    print("", file=sys.stderr)
    print("恢复步骤（已创建的结构全部保留，未自动删除任何表或数据）：", file=sys.stderr)
    for line in detail_lines:
        print(f"  {line}", file=sys.stderr)
    print(f"  查看状态：python3 scripts/migrate.py --database {database} --status", file=sys.stderr)
    print("  修复原因后直接重跑同一条命令：已存在且一致的结构对 IF NOT EXISTS 是幂等的。",
          file=sys.stderr)
    print("  本轮不删除任何结构：脚本不会 DROP，也不建议为了重跑而删表。", file=sys.stderr)
    print("  重复执行本脚本对已存在且一致的结构是幂等的；已建好的表会保留并被后续校验覆盖。",
          file=sys.stderr)
    print("  若确有结构冲突且无法前向修复，请先备份该库，再由人工评估是否重建。",
          file=sys.stderr)
    print(f"  （本次脚本 SHA-256：{checksum}）", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description="应用教学与 AI 辅导增量迁移")
    parser.add_argument("--database", required=True, help="目标库名（必填，执行器不猜库）")
    parser.add_argument("--dry-run", action="store_true", help="只读预检，不修改数据库")
    parser.add_argument("--status", action="store_true", help="只读显示迁移状态")
    args = parser.parse_args()

    database = args.database.strip()
    # 任何 SQL 之前先校验库名与连接范围
    if not identifier_ok(database):
        sys.exit(f"库名不合法：{args.database}")
    target_desc = connection_target()[1]
    if not SQL_FILE.exists():
        sys.exit(f"找不到迁移脚本：{SQL_FILE}")

    checksum = file_sha256()
    sql_text = SQL_FILE.read_text(encoding="utf-8")

    exists, conn_error = database_exists(database)
    if conn_error:
        sys.exit(f"无法查询数据库列表（连接或客户端异常）：{conn_error}")
    if not exists:
        sys.exit(f"库不存在：{database}。请先用 scripts/init-db.sh 创建；本脚本不建库、不删库。")

    # 只读分支
    if args.status or args.dry_run:
        row = read_migration_row(database)
        present = existing_new_tables(database)
        problems = check_structure(database)
        print(f"库：{database}（连接：{target_desc}）")
        print(f"迁移版本：{MIGRATION_VERSION}")
        print(f"脚本 SHA-256：{checksum}")
        print(f"新增表：{len(present)}/{len(NEW_TABLES)}")
        if row:
            state = "一致" if row["checksum"] == checksum else (
                "为空" if not row["checksum"] else "不一致")
            print(f"迁移记录：applied_at={row['applied_at']} checksum={state}")
        else:
            print("迁移记录：无（尚未记录为已应用）")
        if args.dry_run:
            if not present:
                print("预检结果：目标库尚无新增结构，可执行迁移。")
            elif problems:
                print(f"预检结果：存在未完成的结构（{len(problems)} 项），执行迁移可续做：")
                for item in problems[:10]:
                    print(f"  - {item}")
            else:
                print("预检结果：结构已完整。")
        return 0

    # 写流程：先取文件锁（覆盖整个流程），再执行 SQL 与校验
    lock_dir = ROOT / ".runtime"
    lock_dir.mkdir(parents=True, exist_ok=True)
    lock_path = lock_dir / f"migrate-{database}.lock"
    with open(lock_path, "w") as lock_file:
        try:
            fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            sys.exit(f"另一个迁移正在进行（锁：{lock_path}），请稍后重试。")
        try:
            print("=" * 68)
            print("教学与 AI 辅导增量迁移")
            print(f"  目标库      ：{database}（连接：{target_desc}）")
            print(f"  迁移版本    ：{MIGRATION_VERSION}")
            print(f"  脚本        ：{SQL_FILE.relative_to(ROOT)}")
            print(f"  脚本 SHA-256：{checksum}")
            print(f"  迁移锁      ：{lock_path}（fcntl 文件锁，覆盖整个流程）")
            print("=" * 68)

            row = read_migration_row(database)
            if row is not None:
                if not row["checksum"]:
                    sys.exit(f"拒绝执行：库中已有 {MIGRATION_VERSION} 记录但 checksum 为空，"
                             "无法确认已应用的是哪一版脚本。请人工核对该记录后重试。")
                if row["checksum"] != checksum:
                    sys.exit("拒绝执行：脚本校验和与该库已记录的版本不一致。\n"
                             f"  已记录：{row['checksum']}\n  当前  ：{checksum}\n"
                             "  脚本已被修改；请新增迁移版本，或人工确认后修正记录。")

            problems = check_structure(database)
            if not problems and row is not None:
                print("结构完整且迁移记录一致，无需重复执行。")
                return 0
            if problems:
                print(f"当前有 {len(problems)} 项结构待补齐，继续执行（IF NOT EXISTS 幂等）：")
                for item in problems[:10]:
                    print(f"  - {item}")

            print("开始执行 SQL …")
            started = time.time()
            code, _, err = apply_sql(database, sql_text)
            elapsed = time.time() - started
            if code != 0:
                print(f"SQL 执行失败（{elapsed:.1f}s）；结构保留，未做任何自动删除。",
                      file=sys.stderr)
                print("  原始错误（已截断）：", file=sys.stderr)
                print("  " + err.strip()[:1200].replace("\n", "\n  "), file=sys.stderr)
                print_recovery_steps(database, checksum, ["本次 SQL 未完整执行，迁移记录未写入。"])
                return 1
            print(f"SQL 执行完成（{elapsed:.1f}s）。校验新增结构 …")

            problems = check_structure(database)
            if problems:
                print("结构校验未通过；未写入迁移记录，结构保留：", file=sys.stderr)
                for item in problems:
                    print(f"  - {item}", file=sys.stderr)
                print_recovery_steps(database, checksum, ["结构不完整，请修复后重跑。"])
                return 1

            print(f"结构校验通过：{len(NEW_TABLES)} 张新增表、关键列与唯一约束齐备。")
            code, _, err = write_migration_record(database, checksum)
            if code != 0:
                print("结构已创建，但写入迁移记录失败：", file=sys.stderr)
                print("  " + err.strip()[:600], file=sys.stderr)
                print_recovery_steps(database, checksum,
                                     ["请修复后重跑；重跑只补写记录，不会重复建表。"])
                return 1

            row = read_migration_row(database)
            print("迁移完成。")
            print(f"  新增表：{len(existing_new_tables(database))}/{len(NEW_TABLES)}")
            print(f"  迁移记录：version={row['version']} applied_at={row['applied_at']} "
                  f"checksum={'已记录' if row['checksum'] == checksum else row['checksum']}")
            return 0
        finally:
            fcntl.flock(lock_file, fcntl.LOCK_UN)


if __name__ == "__main__":
    sys.exit(main())
