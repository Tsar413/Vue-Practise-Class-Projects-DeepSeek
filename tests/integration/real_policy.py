"""Explicit opt-in policy for paid acceptance; no shell sourcing or default real mode."""
import fcntl,json,os,shlex,time
from contextlib import contextmanager
from pathlib import Path

ALLOWED_BASES = frozenset({'https://api.deepseek.com', 'https://api.deepseek.com/v1'})
MAX_ATTEMPTS = 5

def validate_settings(values):
    assert values.get('AI_TUTOR_BASE_URL') in ALLOWED_BASES, 'Real mode only permits exact DeepSeek HTTPS endpoints'
    assert values.get('AI_TUTOR_MODEL','').strip(), 'Tutor model is missing'
    key=values.get('AI_TUTOR_API_KEY','')
    assert key and key!='boundary-protocol-fixture-not-a-real-key', 'Independent tutor credential is required'
    return {name:values[name] for name in ('AI_TUTOR_BASE_URL','AI_TUTOR_MODEL','AI_TUTOR_API_KEY')}

def load_settings(root):
    path=root/'.runtime/ai-tutor.env'
    assert not path.is_symlink() and path.is_file(), 'Private tutor configuration required'
    stat=path.stat()
    assert stat.st_uid==os.getuid() and stat.st_mode & 0o077==0, 'Tutor configuration must be owner-only'
    values={}
    for line in path.read_text().splitlines():
        words=shlex.split(line,comments=True)
        if words and words[0]=='export':words=words[1:]
        if not words:continue
        assert len(words)==1 and '=' in words[0], 'Only literal KEY=value configuration is accepted'
        name,value=words[0].split('=',1)
        if name in ('AI_TUTOR_BASE_URL','AI_TUTOR_MODEL','AI_TUTOR_API_KEY'):values[name]=value
    return validate_settings(values)

class Budget:
    """Persist before sending, retain failed attempts, never retry/reset automatically.

    Counts platform attempts (including cache replay) conservatively. A browser
    reservation consumes one slot BEFORE its one manual click. This does not
    claim to be a provider-side billing counter.
    """
    def __init__(self,path):self.path=Path(path)
    @contextmanager
    def locked(self):
        self.path.parent.mkdir(parents=True,exist_ok=True)
        fd=os.open(str(self.path)+'.lock',os.O_CREAT|os.O_RDWR,0o600)
        with os.fdopen(fd,'r+') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            yield
    def read(self):return json.loads(self.path.read_text()) if self.path.exists() else {'attempts':[]}
    def write(self,data):
        temp=self.path.with_suffix('.tmp')
        with os.fdopen(os.open(temp,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600),'w') as f:json.dump(data,f,ensure_ascii=False,indent=2)
        os.replace(temp,self.path)
    def reserve(self,scenario):
        with self.locked():
            state=self.read();items=state['attempts']
            assert len(items)<MAX_ATTEMPTS, 'Five-attempt budget exhausted; do not create a new ledger to bypass it'
            assert all(x['state']=='finished' for x in items), 'An in-flight/uncertain attempt exists; inspect it before continuing'
            assert all(x['scenario']!=scenario for x in items), 'Scenario already attempted; no automatic retries'
            entry={'number':len(items)+1,'scenario':scenario,'state':'reserved','started':time.time()}
            items.append(entry);self.write(state);return entry['number']
    def finish(self,number,metrics):
        with self.locked():
            state=self.read();entry=state['attempts'][number-1]
            assert entry['state']=='reserved'
            # Caller passes only HTTP status/timing/message IDs, never auth data.
            assert set(metrics)<= {'httpStatus','elapsedSeconds','messageId','conversationId','answerChars','error','channel'}
            entry.update(metrics,state='finished');self.write(state)
