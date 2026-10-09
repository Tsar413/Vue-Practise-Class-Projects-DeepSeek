"""Reuse existing tools/database; real credentials are read only with explicit --mode real."""
import argparse,os,subprocess,sys,json,signal
from pathlib import Path
from support import ROOT,RUNTIME,DATABASE,STUB,FIXTURE_KEY,guard,listener,_sql,eventually,stub

def stub_process(action):
    RUNTIME.mkdir(exist_ok=True)
    record=RUNTIME/'boundary-stub-state.json';script=ROOT/'tests/integration/protocol_stub.py'
    if record.exists():
        saved=json.loads(record.read_text());proc=Path('/proc')/str(saved['pid'])
        if proc.exists():
            assert (proc/'cwd').resolve()==ROOT and str(script).encode() in (proc/'cmdline').read_bytes(), 'Stub identity mismatch'
            assert (proc/'stat').read_text().split()[21]==saved['birth'], 'Stub PID was reused'
            if action=='stub-start':return
            os.kill(saved['pid'],signal.SIGTERM)
            eventually(lambda:listener(18091) is None,description='protocol stub port release')
        record.unlink()
    if action=='stub-stop':return
    assert listener(18091) is None,'Refusing to replace an unrecorded process'
    with (RUNTIME/'boundary-stub.log').open('w') as log:
        p=subprocess.Popen([sys.executable,str(script)],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    record.write_text(json.dumps({'pid':p.pid,'birth':Path(f'/proc/{p.pid}/stat').read_text().split()[21]}));record.chmod(0o600)
    eventually(lambda:listener(18091)==p.pid,description='protocol stub readiness')
    assert stub()['mode']=='success'

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['start','stop','guard','stub-start','stub-stop']);p.add_argument('--mode',choices=['off','protocol','real'],default='protocol');a=p.parse_args()
    if a.action.startswith('stub-'):stub_process(a.action);print(a.action+' complete');return
    if a.action=='guard':guard(a.mode);print('Verified isolated DB, Java process, port, directories and '+a.mode+' model policy');return
    assert _sql('SELECT DATABASE()')==DATABASE,'Existing test DB required; never auto-create/reseed'
    assert _sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_tutor_message'")=='1','Apply additive migration to test DB first'
    RUNTIME.mkdir(exist_ok=True)
    env={k:v for k,v in os.environ.items() if not k.startswith('AI_TUTOR_')}
    env.update(JAVA_TOOL_OPTIONS='-Dspring.devtools.restart.enabled=false',RUN_DIR=str(RUNTIME/'run'),LOG_DIR=str(RUNTIME/'run'),STATE_FILE=str(RUNTIME/'run/env.state'),LOCK_FILE=str(RUNTIME/'run/.env.lock'),DB_NAME=DATABASE,DB_URL=f'jdbc:mysql://127.0.0.1:3306/{DATABASE}?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&serverTimezone=GMT%2B8',DB_USERNAME=DATABASE+'_app',DB_PASSWORD=DATABASE+'_local',REPAIR_FILES_ROOT=str(RUNTIME/'repair-files'),TEACHING_FILES_ROOT=str(RUNTIME/'teaching-files'),BACKEND_PORT='18100',FRONTEND_PORT='15173',SERVER_ADDRESS='127.0.0.1',START_MYSQL='0',START_BACKEND='1',START_FRONTEND='1',VITE_API_BASE_URL='http://127.0.0.1:18100',APP_CORS_ALLOWED_ORIGINS='http://127.0.0.1:15173,http://localhost:15173',SPRING_JPA_HIBERNATE_DDL_AUTO='validate',AI_TUTOR_API_KEY=FIXTURE_KEY if a.mode=='protocol' else '',AI_TUTOR_BASE_URL=STUB,AI_TUTOR_MODEL='local-protocol-fixture',AI_TUTOR_TIMEOUT_SECONDS='3',AI_TUTOR_CONNECT_TIMEOUT_SECONDS='2',AI_TUTOR_MIN_INTERVAL_SECONDS='0',AI_TUTOR_DAILY_LIMIT_PER_STUDENT='6',AI_TUTOR_MAX_CONCURRENT_GLOBAL='2',AI_TUTOR_MAX_CONCURRENT_PER_STUDENT='1',AI_TUTOR_ACQUIRE_TIMEOUT_MILLIS='150',NO_PROXY='localhost,127.0.0.1,::1',no_proxy='localhost,127.0.0.1,::1')
    if a.action=='start' and a.mode=='real':
        from real_policy import load_settings
        env.update(load_settings(ROOT))
        env.update(AI_TUTOR_ACCEPTANCE_MODE='real',AI_TUTOR_MAX_TOKENS='600',AI_TUTOR_TIMEOUT_SECONDS='60',AI_TUTOR_CONNECT_TIMEOUT_SECONDS='15',AI_TUTOR_MAX_CONTEXT_CHARS='24000',SERVER_TOMCAT_ACCESSLOG_ENABLED='true',SERVER_TOMCAT_ACCESSLOG_DIRECTORY=str(RUNTIME/'run/access'),SERVER_TOMCAT_ACCESSLOG_PATTERN='%m %U %s %D',SERVER_TOMCAT_ACCESSLOG_BUFFERED='false')
    if a.action=='start' and listener(18100):guard(a.mode)
    # Stop only PIDs recorded and checked by the existing project scripts.
    log=RUNTIME/('boundary-'+a.action+'.log')
    with log.open('w') as f:r=subprocess.run(['bash','scripts/'+('start-all.sh' if a.action=='start' else 'stop-all.sh')],cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=250)
    assert r.returncode==0,'Service script failed; inspect private runtime log'
    if a.action=='start':guard(a.mode)
    print(a.action+' isolated environment ('+a.mode+'); normal DB/config untouched')

if __name__=='__main__':main()
