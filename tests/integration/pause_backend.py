"""Bounded fault injection for browser timeout checks, isolated Java only."""
import os,signal,time
from pathlib import Path
from support import guard,listener

guard('protocol');pid=listener(18100);proc=Path('/proc')/str(pid);birth=(proc/'stat').read_text().split()[21]
def abort(signum,frame):raise SystemExit(128+signum)
signal.signal(signal.SIGTERM,abort);signal.signal(signal.SIGINT,abort)
try:
    os.kill(pid,signal.SIGSTOP)
    print('Isolated backend paused for at most 45 seconds; automatic resume armed',flush=True)
    time.sleep(45)
finally:
    if proc.exists() and (proc/'stat').read_text().split()[21]==birth:os.kill(pid,signal.SIGCONT)
    print('Isolated backend resumed',flush=True)
