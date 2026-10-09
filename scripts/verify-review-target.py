#!/usr/bin/env python3
"""Codex: fail before write tests unless the local review process is identified."""
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

def require(value, message):
    if not value:
        raise ValueError(message)

def main():
    root = Path(__file__).resolve().parents[1]
    api = os.environ.get('API_BASE', 'http://127.0.0.1:18100')
    require(api == 'http://127.0.0.1:18100', '只允许本虚拟机回环测试入口 18100')
    database = os.environ.get('DB_NAME', 'vue_practise_review_20261009')
    require(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,63}', database) and 'review' in database,
            '测试库名必须是合法的独立 review 库')
    runtime = Path(os.environ.get('RUNTIME_DIR', str(root/'.runtime/review-20261009'))).resolve()
    require(runtime.is_relative_to(root/'.runtime') and runtime != root/'.runtime/run',
            '测试运行目录必须位于独立 .runtime 子目录')
    expected_images = Path(os.environ.get('REPAIR_FILES_ROOT', str(runtime/'repair-files'))).resolve()
    require(expected_images.is_relative_to(runtime), '测试图片目录必须位于独立运行目录')
    output = subprocess.check_output(['ss', '-ltnpH', 'sport = :18100'], text=True)
    pids = set(re.findall(r'pid=(\d+)', output))
    require(len(pids) == 1, '测试端口无唯一可核验监听者')
    pid = pids.pop()
    proc = Path('/proc')/pid
    require((proc/'cwd').resolve() == root/'backend', '监听者工作目录不匹配')
    require((proc/'exe').resolve().name == 'java' and
            b'com.study.vuePractiseBackend.StudyVuePractiseBackendApplication' in (proc/'cmdline').read_bytes(),
            '监听者不是本项目后端')
    # Never print process environment or credentials.
    env = dict(item.split(b'=', 1) for item in (proc/'environ').read_bytes().split(b'\0') if b'=' in item)
    url = env.get(b'DB_URL', b'').decode()
    require(url.startswith('jdbc:mysql://'), '缺少明确 JDBC 配置')
    parsed = urlsplit(url[5:])
    require(parsed.hostname in ('127.0.0.1', 'localhost', '::1') and parsed.port == 3306 and
            parsed.path == '/'+database, '后端实际数据库连接不是指定本机测试库')
    require(env.get(b'SERVER_PORT') == b'18100', '实际服务端口不匹配')
    require(Path(env.get(b'REPAIR_FILES_ROOT', b'').decode()).resolve() == expected_images,
            '后端实际图片目录不匹配')
    state = Path(os.environ.get('STATE_FILE', str(runtime/'run/env.state')))
    rows = [line.split() for line in state.read_text().splitlines() if line.startswith('backend ')]
    require(len(rows) == 1 and len(rows[0]) == 4, '后端运行记录缺失或歧义')
    require(str(os.getpgid(int(pid))) == rows[0][3], '监听进程组与运行记录不匹配')
    print('通过：本机测试后端、数据库、图片目录及进程组隔离核验（不输出凭据）')

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Do not echo arbitrary exception strings, which could include a JDBC URL.
        print('拒绝执行：独立测试目标校验失败，请检查本机监听者、库名、图片目录及运行记录（不输出配置值）。', file=sys.stderr)
        sys.exit(3)
