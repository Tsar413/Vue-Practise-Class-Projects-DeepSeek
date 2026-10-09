"""Codex contract tests. Fail closed before every HTTP write / SQL injection.

Only the fixed, existing local review database is accepted. No real model key
is read. Credentials in this suite are public synthetic fixtures.
"""
from pathlib import Path
import base64, contextlib, json, os, re, selectors, subprocess, time, urllib.error, urllib.request, uuid

ROOT = Path(__file__).resolve().parents[2]
DATABASE = 'vue_teaching_review_20261009'
RUNTIME = ROOT / '.runtime/teaching-review-20261009'
API = 'http://127.0.0.1:18100'
STUB = 'http://127.0.0.1:18091'
FIXTURE_KEY = 'boundary-protocol-fixture-not-a-real-key'
MYSQL_HOME = Path(os.environ.get('MYSQL_HOME', Path.home() / 'tools/opt/mysql-8.0.44-linux-glibc2.17-x86_64'))
MYSQL_ENV = dict(os.environ, LD_LIBRARY_PATH=str(Path.home() / 'tools/opt/lib'))
MYSQL = [str(MYSQL_HOME / 'bin/mysql'), '--no-defaults', '--socket=' + str(Path.home() / 'var/mysql/db.sock'), '-uroot', '-N', '-B', '--unbuffered', DATABASE]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')

def listener(port):
    ids=set(re.findall(r'pid=(\d+)', subprocess.check_output(['ss','-ltnpH',f'sport = :{port}'],text=True)))
    assert len(ids)<=1, 'Ambiguous listener'
    return int(next(iter(ids))) if ids else None

def guard(mode=None):
    pid=listener(18100); assert pid, 'No isolated backend'
    proc=Path('/proc')/str(pid)
    assert (proc/'cwd').resolve()==ROOT/'backend', 'Wrong backend cwd'
    assert (proc/'exe').resolve().name=='java'
    assert b'com.study.vuePractiseBackend.StudyVuePractiseBackendApplication' in (proc/'cmdline').read_bytes()
    env=dict(v.split(b'=',1) for v in (proc/'environ').read_bytes().split(b'\0') if b'=' in v)
    assert env.get(b'DB_URL',b'').decode().startswith('jdbc:mysql://127.0.0.1:3306/'+DATABASE+'?'), 'Unsafe DB'
    assert env.get(b'DB_USERNAME')==(DATABASE+'_app').encode()
    assert env.get(b'SERVER_PORT')==b'18100'
    for key,folder in [(b'REPAIR_FILES_ROOT','repair-files'),(b'TEACHING_FILES_ROOT','teaching-files')]:
        expected=RUNTIME/folder
        assert Path(env.get(key,b'').decode()).resolve()==expected, 'Unsafe image root'
        assert expected.resolve()==expected and expected!=ROOT/'backend'/folder
    # Never send even one request to a backend configured with a real provider.
    assert env.get(b'AI_TUTOR_API_KEY',b'') in [b'', FIXTURE_KEY.encode()]
    assert env.get(b'AI_TUTOR_BASE_URL')==STUB.encode()
    if mode=='protocol': assert env.get(b'AI_TUTOR_API_KEY')==FIXTURE_KEY.encode()
    if mode=='off': assert not env.get(b'AI_TUTOR_API_KEY')
    assert _sql('SELECT DATABASE()')==DATABASE
    actual=_sql("SELECT DISTINCT PROCESSLIST_DB FROM performance_schema.threads WHERE PROCESSLIST_USER='"+DATABASE+"_app' AND PROCESSLIST_DB IS NOT NULL").splitlines()
    assert actual==[DATABASE], 'Actual application MySQL sessions are not exclusively isolated'
    return env

def _sql(query):
    assert not re.search(r'\b(USE|TRUNCATE)\b|DROP\s+DATABASE',query,re.I), 'Unsafe SQL operation'
    return subprocess.check_output([*MYSQL,'-e',query],env=MYSQL_ENV,text=True,stderr=subprocess.PIPE).strip()

def sql(query):
    guard(); return _sql(query)

def request(method,path,data=None,token=None,upload=None):
    assert path.startswith('/') and not path.startswith('//')
    if method!='GET': guard()
    headers={'Accept':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    if upload is None:
        body=None if data is None else json.dumps(data).encode(); headers['Content-Type']='application/json'
    else:
        name,content,mime=upload; boundary='boundary-'+uuid.uuid4().hex
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n').encode()+content+f'\r\n--{boundary}--\r\n'.encode()
        headers['Content-Type']='multipart/form-data; boundary='+boundary
    req=urllib.request.Request(API+path, data=body, headers=headers, method=method)
    try: response=OPENER.open(req,timeout=25)
    except urllib.error.HTTPError as e: response=e
    with response:
        raw=response.read()
        try: data=json.loads(raw)
        except (ValueError,UnicodeDecodeError):data={'binary_bytes':len(raw)}
        return response.status,data

class Account:
    def __init__(self,id):
        status,body=request('POST','/login',dict(id=id,password='123456'))
        assert status==200, 'Synthetic fixture login failed'
        self.id=id; self.token=body['data']['token']
    def call(self,method,path,data=None,**kw):return request(method,path,data,self.token,**kw)

def stub(action='state',data=None):
    req=urllib.request.Request(STUB+'/control/'+action,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
    with OPENER.open(req,timeout=6) as r:return json.load(r)

def eventually(predicate,timeout=8,description='condition'):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        value=predicate()
        if value:return value
        # Poll a concrete state, never use elapsed sleep to establish ordering.
        time.sleep(.05)
    raise AssertionError('Timed out waiting for '+description)

class SqlSession:
    """Persistent MySQL connection for real transactional/advisory barriers."""
    def __enter__(self):
        guard(); self.p=subprocess.Popen(MYSQL,env=MYSQL_ENV,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0)
        return self
    def execute(self,query):
        marker='done_'+uuid.uuid4().hex
        self.p.stdin.write((query+"; SELECT '"+marker+"';\n").encode());self.p.stdin.flush()
        output=b''
        with selectors.DefaultSelector() as selector:
            selector.register(self.p.stdout,selectors.EVENT_READ)
            while True:
                if not selector.select(8):raise AssertionError('SQL barrier command timed out')
                chunk=os.read(self.p.stdout.fileno(),65536)
                assert chunk, 'SQL session closed unexpectedly'
                output+=chunk
                if (marker+'\n').encode() in output:return output.decode().splitlines()[:-1]
    def __exit__(self,*exc):
        self.p.stdin.close()
        try:self.p.wait(timeout=5)
        except subprocess.TimeoutExpired:self.p.terminate();self.p.wait(timeout=5)
        finally:self.p.stdout.close();self.p.stderr.close()

@contextlib.contextmanager
def trigger(table,timing,body):
    assert table in ['teaching_attachment','teaching_file_delete_task','ai_tutor_message','teaching_task','teaching_submission_version']
    name='boundary_'+uuid.uuid4().hex[:16]
    sql(f'DELIMITER //\nCREATE TRIGGER `{name}` {timing} ON `{table}` FOR EACH ROW BEGIN {body} END//\nDELIMITER ;')
    try:yield name
    finally:sql(f'DROP TRIGGER `{name}`')

def pending_lock(name):
    return int(sql("SELECT COUNT(*) FROM performance_schema.metadata_locks WHERE OBJECT_TYPE='USER LEVEL LOCK' AND OBJECT_NAME='"+name+"' AND LOCK_STATUS='PENDING'"))>0
