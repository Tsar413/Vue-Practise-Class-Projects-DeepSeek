"""Deployment-specific acceptance from VM via public Nginx; synthetic server fixtures only.

No existing local-test guard is relaxed. SSH guard binds every run to the new
public database and persistent image roots. Credentials and raw state stay in
the private work directory. AI is a separate explicit action with max 2 calls.
"""
from pathlib import Path
import base64,hashlib,json,os,re,shlex,subprocess,sys,time,urllib.request,urllib.error,uuid

HERE=Path(os.environ['DEPLOY_ACCEPTANCE_DIR']).resolve()
assert HERE.is_dir() and (HERE.stat().st_mode & 0o077)==0, 'Private acceptance directory required'
BASE='http://106.14.114.66'
SSH='vue-practice-server'
DB='vue_practice_public'
OPENER=urllib.request.build_opener(urllib.request.ProxyHandler({}))
PNG=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
STATE=HERE/'public-acceptance-state.json'
REPORT=HERE/'public-acceptance-results.json'
def save(path,obj):
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),'w') as f:json.dump(obj,f,ensure_ascii=False,indent=2)
def remote(code):
    p=subprocess.run(['ssh',SSH,'python3 -'],input=code,text=True,capture_output=True)
    assert p.returncode==0,'Remote guard/check failed (output withheld)'
    return p.stdout
def guard():
    ssh_config=subprocess.check_output(['ssh','-G',SSH],text=True)
    assert 'hostname 106.14.114.66\n' in ssh_config, 'SSH alias points at wrong host'
    remote('''import subprocess,json,shlex
from pathlib import Path
p=Path('/etc/vue-practice/application.env')
v=dict(shlex.split(l)[0].split('=',1) for l in p.read_text().splitlines())
assert v['DB_URL'].startswith('jdbc:mysql://127.0.0.1:3306/vue_practice_public?')
assert v['DB_USERNAME']=='vue_public_app' and v['SERVER_ADDRESS']=='127.0.0.1'
assert v['SPRING_JPA_HIBERNATE_DDL_AUTO']=='validate'
for k,n in [('REPAIR_FILES_ROOT','repair-files'),('TEACHING_FILES_ROOT','teaching-files')]:
 assert Path(v[k]).resolve()==Path('/opt/vue-practice/data')/n
pid=subprocess.check_output(['systemctl','show','vue-practice','-p','MainPID','--value'],text=True).strip()
e=dict(x.split(b'=',1) for x in Path('/proc/'+pid+'/environ').read_bytes().split(b'\\0') if b'=' in x)
for k in ['DB_URL','DB_USERNAME','SERVER_ADDRESS','REPAIR_FILES_ROOT','TEACHING_FILES_ROOT']:
 assert e[k.encode()]==v[k].encode()
q="SELECT DISTINCT PROCESSLIST_DB FROM performance_schema.threads WHERE PROCESSLIST_USER='vue_public_app' AND PROCESSLIST_DB IS NOT NULL"
assert subprocess.check_output(['mysql','-N','-e',q],text=True).strip()=='vue_practice_public'
''')
def request(method,path,body=None,token=None,upload=False,expected=200):
    headers={'Accept':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if upload:
        boundary='public-'+uuid.uuid4().hex
        payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n').encode()+PNG+f'\r\n--{boundary}--\r\n'.encode()
        headers['Content-Type']='multipart/form-data; boundary='+boundary
    else:
        payload=None if body is None else json.dumps(body).encode();headers['Content-Type']='application/json'
    started=time.monotonic()
    try:r=OPENER.open(urllib.request.Request(BASE+path,data=payload,headers=headers,method=method),timeout=85)
    except urllib.error.HTTPError as e:r=e
    with r:
        raw=r.read();status=r.status;ctype=r.headers.get('Content-Type','')
    elapsed=round(time.monotonic()-started,3)
    assert status==expected,f'{method} HTTP {status}, expected {expected} (path withheld: may contain access code)'
    try:data=json.loads(raw)
    except (ValueError,UnicodeDecodeError):data=raw
    return data,dict(httpStatus=status,elapsedSeconds=elapsed,bytes=len(raw),contentType=ctype)
class Account:
    def __init__(self,id):
        c=next(x for x in json.loads((HERE/'demo-accounts.json').read_text()) if x['id']==id)
        self.id=id;d,_=request('POST','/login',{'id':id,'password':c['password']});self.data=d['data'];self.token=self.data['token']
    def call(self,method,path,body=None,**kw):
        d,_=request(method,path,body,self.token,**kw)
        return d.get('data') if isinstance(d,dict) else d
    def practice(self,path):return '/api/practice/'+self.data['apiAccessCode']+path
def mark(name,**details):
    r=json.loads(REPORT.read_text()) if REPORT.exists() else []
    r.append(dict(scenario=name,result='PASS',**details));save(REPORT,r);print('PASS:',name)
def full():
    guard();assert not STATE.exists(),'Existing run; inspect and resume explicitly'
    teacher=Account('DEMO_TEACHER');a=Account('DEMO2026001');b=Account('DEMO2026002');reset=Account('DEMO2026003')
    mark('teacher and three synthetic students login')
    html,_=request('GET','/');assert b'<div id="app">' in html
    for path in ['/docs','/tasks','/teacher/tasks','/ai-tutor']:
        page,_=request('GET',path);assert page==html
    for path in re.findall(rb'(?:src|href)="([^"]+\.(?:js|css))"',html):request('GET',path.decode())
    mark('public home, static assets and history refresh')
    assert teacher.call('GET','/api/sys-class/all')
    assert len(teacher.call('GET','/api/sys-user/all'))==4
    assert len(teacher.call('GET','/api/sys-workspace/classes/DEMO2026'))==3
    teacher.call('POST','/api/sys-workspace/classes/DEMO2026/ticket/initialize')
    teacher.call('POST','/api/sys-workspace/classes/DEMO2026/repair/initialize')
    for x in [a,b,reset]:
        assert x.call('GET',x.practice('/ticket/activities'))
        assert x.call('GET',x.practice('/repair/devices'))
    mark('class/student/workspace and both projects initialized and queried')
    payload=dict(title='公网演示：报修接口与成果提交',project='REPAIR',objective='理解上传、工单和成果反馈流程',requirement='按真实接口上传图片、查询设备并提交演示成果；使用虚构数据。',acceptance='请求字段与文档一致，正确展示状态及错误提示。',classIds=['DEMO2026'],fullScore=100,allowLate=False,deadline='2035-01-01 00:00:00')
    task=teacher.call('POST','/api/teaching/tasks',payload,expected=201);tid=task['id']
    s={'taskId':tid};save(STATE,s)
    a.call('GET',f'/api/teaching/tasks/{tid}',expected=404)
    teacher.call('POST',f'/api/teaching/tasks/{tid}/status',{'action':'PUBLISH'})
    assert a.call('GET',f'/api/teaching/tasks/{tid}')['id']==tid
    assert b.call('GET',f'/api/teaching/tasks/{tid}')['id']==tid
    mark('teacher draft/publish and assigned student visibility')
    attachment=a.call('POST',f'/api/teaching/tasks/{tid}/attachments',upload=True,expected=201)
    aid=attachment['id'];s['teachingAttachmentId']=aid;save(STATE,s)
    image=a.call('GET',f'/api/teaching/attachments/{aid}/content');assert image.startswith(b'\x89PNG')
    b.call('GET',f'/api/teaching/attachments/{aid}/content',expected=404)
    body=dict(projectUrl='https://example.com/fictional-demo',content='部署验收专用虚构成果：完成接口查询与图片上传。',process='核对接口文档，再检查响应状态与错误提示。',sections=[dict(title='演示截图',content='自动生成的合成像素图片',sortOrder=0,attachmentIds=[aid])])
    draft=a.call('PUT',f'/api/teaching/tasks/{tid}/draft',body);assert not draft['versions']
    body['requestKey']=uuid.uuid4().hex
    v1=a.call('POST',f'/api/teaching/tasks/{tid}/submissions',body,expected=201)
    sid=v1['id'];vid1=v1['versions'][0]['id'];s['submissionId']=sid;save(STATE,s)
    teacher.call('POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=vid1,decision='REVISE',comment='部署验收虚构反馈：补充错误处理说明。'))
    body.update(content='部署验收专用虚构成果：已补充错误处理说明。',requestKey=uuid.uuid4().hex)
    v2=a.call('POST',f'/api/teaching/tasks/{tid}/submissions',body,expected=201);vid2=v2['versions'][0]['id']
    teacher.call('POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=vid2,decision='PASS',score=88,comment='部署流程测试分数，非真实课堂成绩。'))
    final=a.call('GET',f'/api/teaching/tasks/{tid}/submission')
    assert len(final['versions'])==2 and final['currentEvaluation']['score']==88
    assert final['versions'][1]['evaluations'][0]['decision']=='REVISE'
    b.call('GET',f'/api/teaching/submissions/{sid}',expected=403)
    theirs=b.call('GET',f'/api/teaching/tasks/{tid}/submission');assert theirs is None or theirs['id']!=sid
    assert teacher.call('GET',f'/api/teaching/submissions/{sid}')['id']==sid
    mark('draft/upload/submit/return/resubmit/score/history and two-student isolation')
    reporters=a.call('GET',a.practice('/repair/users?role=REPORTER'));operator=reporters[0]['id']
    ra=a.call('POST',a.practice(f'/repair/images?operatorId={operator}&imageType=1'),upload=True)
    rid=ra['id'];s['repairAttachmentId']=rid
    for action in ['preview','download']:
        raw=a.call('GET',a.practice(f'/repair/images/{rid}/{action}'));assert raw.startswith(b'\x89PNG')
        b.call('GET',b.practice(f'/repair/images/{rid}/{action}'),expected=404)
    device=a.call('GET',a.practice('/repair/devices'))[0]
    order=a.call('POST',a.practice('/repair/orders'),dict(operatorId=operator,deviceId=device['id'],title='部署验收虚构工单',description='合成数据用于验证持久图片与查询',imageIds=[rid]))
    oid=order['id'];s['orderId']=oid;s['operatorId']=operator;save(STATE,s)
    detail=a.call('GET',a.practice(f'/repair/orders/{oid}?operatorId={operator}'));assert detail
    mark('repair image upload/preview/download/order binding and workspace isolation')
    s['aiConversationId']=a.call('POST','/api/ai-tutor/conversations',dict(project='REPAIR',taskId=tid))['id'];save(STATE,s)
    b.call('GET',f'/api/ai-tutor/conversations/{s["aiConversationId"]}/messages',expected=404)
    before=a.call('GET',f'/api/teaching/tasks/{tid}/submission')
    reset.call('POST','/api/sys-workspace/one/DEMO2026003/reset?project=TICKET')
    assert reset.call('GET',reset.practice('/repair/devices'))
    assert a.call('GET',f'/api/teaching/tasks/{tid}/submission')==before
    assert a.call('GET','/api/ai-tutor/conversations')
    mark('dedicated third-student TICKET reset preserves REPAIR and other teaching/AI records')
    for who in [teacher,a,b,reset]:
        who.call('POST','/logout');who.call('GET','/api/teaching/tasks',expected=401)
    mark('teacher/student logout invalidates platform tokens')
def ai():
    guard();s=json.loads(STATE.read_text());ledger=HERE/'real-ai-budget.json'
    attempts=json.loads(ledger.read_text()) if ledger.exists() else []
    assert len(attempts)<2 and all(x.get('finished') for x in attempts),'Real call budget unavailable'
    attempts.append(dict(started=time.time(),finished=False));save(ledger,attempts)
    a=Account('DEMO2026001')
    body=dict(project='REPAIR',taskId=s['taskId'],conversationId=s['aiConversationId'],requestKey='public_'+uuid.uuid4().hex,
      question='请基于 RepairController_uploadImage 和 RepairController_createOrder，指出下面图片上传和绑定字段的错误，列出实际所需参数及最小修改。四步简短回答，约250汉字；不要声称运行过代码。',
      codeSnippet='await axios.post(`${base}/repair/images`, { file: "fault.png", operatorId: 102, imageType: 1 });\nawait axios.post(`${base}/repair/orders`, { operatorId: 102, title: "虚构报修", description: "合成设备故障", imageUrls: ["fault.png"] });')
    started=time.monotonic()
    try:
        response,metrics=request('POST','/api/ai-tutor/ask',body,a.token)
        save(HERE/'real-ai-response.json',response)
        data=response['data'];msg=data['message'];assert data['configured'] and msg['content'].strip()
        messages=a.call('GET',f'/api/ai-tutor/conversations/{s["aiConversationId"]}/messages')
        save(HERE/'real-ai-messages.json',messages)
        assert len(messages)==2*len(attempts),'Missing or duplicated persisted turns'
        assert all('/ticket/' not in json.dumps(ref) for ref in msg['contextRefs'])
        assert any(ref['type']=='TASK' and str(ref['id'])==str(s['taskId']) for ref in msg['contextRefs'])
        attempts[-1].update(metrics,finished=True,answerChars=len(msg['content']),usage=data.get('usage','not provided'))
        mark('real DeepSeek via public platform ask; nonempty response, source project and saved turn',**metrics)
    except Exception as e:
        attempts[-1].update(finished=True,error=type(e).__name__,elapsedSeconds=round(time.monotonic()-started,3));raise
    finally:save(ledger,attempts)
def persistence():
    guard();s=json.loads(STATE.read_text());a=Account('DEMO2026001')
    detail=a.call('GET',f'/api/teaching/tasks/{s["taskId"]}/submission')
    assert len(detail['versions'])==2 and detail['currentEvaluation']['score']==88
    assert a.call('GET',f'/api/teaching/attachments/{s["teachingAttachmentId"]}/content').startswith(b'\x89PNG')
    assert a.call('GET',a.practice(f'/repair/images/{s["repairAttachmentId"]}/download')).startswith(b'\x89PNG')
    assert a.call('GET',a.practice(f'/repair/orders/{s["orderId"]}?operatorId={s["operatorId"]}'))
    assert len(a.call('GET',f'/api/ai-tutor/conversations/{s["aiConversationId"]}/messages'))==2
    mark('service restart preserves submissions/feedback/order/images/real AI conversation')
if __name__=='__main__':{'full':full,'ai':ai,'persistence':persistence}[sys.argv[1]]()
