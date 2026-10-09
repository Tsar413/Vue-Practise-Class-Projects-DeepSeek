"""Serial, explicitly paid PLATFORM acceptance. Never calls the vendor directly.

Run one stage at a time, inspect each answer before proceeding. The fixed ledger
cannot be reset by this script. Browser stage reserves before one manual click.
All accounts/tasks are freshly created synthetic fixtures in the guarded DB.
"""
import argparse,json,os,subprocess,time,uuid
from support import ROOT,RUNTIME,Account,guard,sql
from real_policy import Budget,load_settings

STATE=RUNTIME/'real-acceptance-state.json'
BUDGET=Budget(RUNTIME/'real-acceptance-budget.json')
QUESTIONS={
 'ticket':dict(question='请检查 TicketController_bookTicket 报名代码中请求方法、参数位置与workspace处理的具体错误。只给四步简短提示，总计约250汉字，引用实际接口，不要声称已运行。',codeSnippet='// base 表示本人实训接口前缀；这里只是变量，不含任何凭据\nawait axios.get(`${base}/ticket/activities/${activityId}/records`, { params: { workspaceId: 99 }, data: { userId: 102 } });'),
 'followup':dict(question='承接刚才同一报名问题，我已改为以下代码，但提示“缺少userId参数”。请基于 TicketController_bookTicket 指出还没改对的地方，给最小局部修改和验证步骤；四步，总计约250汉字。',codeSnippet='await axios.post(`${base}/ticket/activities/${activityId}/records`, { userId: 102 });',errorText='400：缺少userId参数'),
 'repair':dict(question='当前任务是校园报修。根据 RepairController_uploadImage 和 RepairController_createOrder，我用JSON上传本地文件名并把imageUrls传给工单，错在哪里？请说明正确上传格式、身份、图片类型和绑定字段。四步简短提示，总计约250汉字；不要混入其他项目。',codeSnippet='await axios.post(`${base}/repair/images`, { file: "fault.png", operatorId: 102, imageType: 1 });\nawait axios.post(`${base}/repair/orders`, { operatorId: 102, title: "测试报修", description: "虚构设备故障", imageUrls: ["fault.png"] });'),
 'browser':dict(question='针对当前报修任务，RepairController_uploadImage 上传成功返回附件id。我应该在 RepairController_createOrder 的哪个字段绑定它？能用其他报修人的图片吗？请四步简短解释与验证建议，总计约220汉字，不声称已执行测试。')
}

def save(value,path=STATE):
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),'w') as f:json.dump(value,f,ensure_ascii=False,indent=2)
def load():return json.loads(STATE.read_text())
def ok(account,method,path,body=None,status=200):
    actual,result=account.call(method,path,body)
    assert actual==status, f'{method} {path}: HTTP {actual}, expected {status}'
    return result.get('data')
def account(state,name='student'):return Account(state[name],mode='real')

def prepare():
    assert not STATE.exists() and not BUDGET.path.exists(), 'Existing acceptance run; inspect/resume, never silently replace'
    teacher=Account('DEMO_TEACHER',mode='real');suffix=uuid.uuid4().hex[:8]
    state={'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'classId':'REAL_C_'+suffix,'student':'REAL_S_'+suffix,'other':'REAL_O_'+suffix,'calls':{},'tasks':{},'conversations':{}}
    for class_id in [state['classId'],'REAL_OTHER_'+suffix]:
        ok(teacher,'POST','/api/sys-class/one',dict(id=class_id,className='虚构真实API验收班 '+class_id))
    for name,class_id in [('student',state['classId']),('other','REAL_OTHER_'+suffix)]:
        ok(teacher,'POST','/api/sys-user/one',dict(id=state[name],username='虚构API验收学生',realName='虚构API验收学生',classId=class_id,role='STUDENT'))
    student=account(state)
    for project in ['TICKET','REPAIR']:
        body=dict(title='真实API隔离验收 '+project+' '+suffix,project=project,objective='学习严格遵循接口契约',requirement='仅使用当前项目文档。'+('正确报名，参数位置正确，空间由服务器解析。' if project=='TICKET' else '正确上传故障图片并通过附件ID创建报修工单。'),acceptance='能够解释错误、最小修改及自行验证步骤；不伪称已执行。',classIds=[state['classId']],fullScore=100,allowLate=False,deadline='2035-01-01 00:00:00')
        task=ok(teacher,'POST','/api/teaching/tasks',body,status=201)
        ok(teacher,'POST',f'/api/teaching/tasks/{task["id"]}/status',{'action':'PUBLISH'})
        state['tasks'][project]=task['id']
        ok(student,'GET',f'/api/teaching/tasks/{task["id"]}')
        state['conversations'][project]=ok(student,'POST','/api/ai-tutor/conversations',dict(project=project,taskId=task['id']))['id']
    save(state)
    config=load_settings(ROOT)
    print(json.dumps({'commit':state['commit'],'endpoint':config['AI_TUTOR_BASE_URL']+'/chat/completions','model':config['AI_TUTOR_MODEL'],'maxOutputTokens':600,'tasks':state['tasks'],'student':state['student']},ensure_ascii=False))

def call(scenario):
    state=load();project='TICKET' if scenario in ('ticket','followup','replay') else 'REPAIR'
    if scenario=='replay':
        assert 'ticket' in state['calls'];body=state['calls']['ticket']['request']
    else:
        body=dict(QUESTIONS[scenario],project=project,taskId=state['tasks'][project],conversationId=state['conversations'][project],requestKey='real_'+uuid.uuid4().hex)
    student=account(state);number=BUDGET.reserve(scenario);started=time.monotonic()
    try:status,response=student.call('POST','/api/ai-tutor/ask',body,timeout=80)
    except Exception as error:
        BUDGET.finish(number,{'error':type(error).__name__,'elapsedSeconds':round(time.monotonic()-started,3)});raise RuntimeError('Platform request failed; reserved budget consumed, inspect before continuing') from None
    elapsed=round(time.monotonic()-started,3);data=response.get('data') or {};message=data.get('message') or {}
    metrics=dict(httpStatus=status,elapsedSeconds=elapsed,conversationId=data.get('conversationId'),messageId=message.get('id'),answerChars=len(message.get('content','')),channel='platform-http')
    BUDGET.finish(number,metrics)
    state['calls'][scenario]={'request':body,'response':response,'metrics':metrics};save(state)
    print(json.dumps(metrics,ensure_ascii=False))
    assert status==200 and data.get('configured') and message.get('content','').strip(), 'No successful real answer; do not auto-retry'
    if scenario=='replay':assert message==state['calls']['ticket']['response']['data']['message'],'Cached result differs'
    verify_messages(state)

def verify_messages(state):
    evidence=[]
    for project,cid in state['conversations'].items():
        rows=sql(f"SELECT role,COUNT(*) FROM ai_tutor_message WHERE conversation_id={int(cid)} GROUP BY role ORDER BY role",mode='real').splitlines()
        counts={r.split('\t')[0]:int(r.split('\t')[1]) for r in rows}
        expected=sum(1 for name in state['calls'] if name!='replay' and ('TICKET' if name in ('ticket','followup') else 'REPAIR')==project)
        assert counts==({'ASSISTANT':expected,'USER':expected} if expected else {}),'Duplicate or missing saved messages'
        assert sql(f'SELECT message_count FROM ai_tutor_conversation WHERE id={int(cid)}',mode='real')==str(expected*2)
        for scenario,call in state['calls'].items():
            if call['request']['project']!=project:continue
            msg=call['response']['data']['message'];mid=int(msg['id'])
            stored=bytes.fromhex(sql(f'SELECT HEX(content) FROM ai_tutor_message WHERE id={mid}',mode='real')).decode()
            assert stored==msg['content'],'Stored assistant differs from API response'
            preceding=bytes.fromhex(sql(f"SELECT HEX(content) FROM ai_tutor_message WHERE conversation_id={int(cid)} AND role='USER' AND id<{mid} ORDER BY id DESC LIMIT 1",mode='real')).decode()
            refs=msg.get('contextRefs') or []
            assert refs,'Actual source references missing'
            for ref in refs:
                if ref['type']=='API':assert ref['id'] in preceding, 'Reference absent from sent context'
                if ref['type']=='TASK':assert str(ref['id'])==str(state['tasks'][project]) and ref['title'] in preceding
                if ref['type']=='DOC':assert (ROOT/'backend/src/main/resources'/ref['id']).is_file()
                forbidden='/repair/' if project=='TICKET' else '/ticket/'
                assert forbidden not in json.dumps(ref), 'Cross-project source'
            assert ('RepairController_' if project=='TICKET' else 'TicketController_') not in preceding,'Cross-project prompt'
        evidence.append(dict(project=project,conversationId=cid,rows=counts,messageCount=expected*2))
    save(evidence,RUNTIME/'real-acceptance-message-checks.json')
    print(json.dumps({'databaseMessageChecks':evidence},ensure_ascii=False))

def isolation():
    state=load();other=account(state,'other')
    for project,cid in state['conversations'].items():
        ok(other,'GET',f'/api/ai-tutor/conversations/{cid}/messages',status=404)
        ok(other,'GET',f'/api/teaching/tasks/{state["tasks"][project]}',status=404)
    data=ok(other,'GET','/api/ai-tutor/conversations')
    assert not any(c['id'] in state['conversations'].values() for c in data)
    print('Other synthetic student: task/message reads 404; conversation list excludes owner sessions; no paid calls')

def browser_reserve():
    state=load();number=BUDGET.reserve('browser')
    state['browserReservation']=number
    state['browserAccessCount']=sum(1 for p in (RUNTIME/'run/access').glob('*') for line in p.read_text().splitlines() if line.startswith('POST /api/ai-tutor/ask '))
    save(state)
    print(json.dumps({'reservation':number,'student':state['student'],'url':f'http://127.0.0.1:15173/tasks/{state["tasks"]["REPAIR"]}','question':QUESTIONS['browser']['question']},ensure_ascii=False))

def browser_collect():
    state=load();student=state['student']
    # One browser question has a unique fixed prefix; identifiers are generated safe ASCII.
    assert student.replace('_','').isalnum()
    ids=sql("SELECT m.conversation_id FROM ai_tutor_message m JOIN ai_tutor_conversation c ON c.id=m.conversation_id WHERE c.student_id='"+student+"' AND m.role='USER' AND m.content LIKE '【学生提问】%针对当前报修任务%'",mode='real').splitlines()
    assert len(ids)==1,'Expected exactly one browser USER; do not click again'
    cid=int(ids[0]);rows=sql(f"SELECT id,role,HEX(content),HEX(context_refs) FROM ai_tutor_message WHERE conversation_id={cid} ORDER BY id",mode='real').splitlines()
    assert len(rows)==2 and [r.split('\t')[1] for r in rows]==['USER','ASSISTANT']
    parts=rows[1].split('\t');content=bytes.fromhex(parts[2]).decode();refs=json.loads(bytes.fromhex(parts[3]).decode())
    assert content.strip() and all('/ticket/' not in json.dumps(r) for r in refs)
    user_content=bytes.fromhex(rows[0].split('\t')[2]).decode()
    assert 'TicketController_' not in user_content
    assert sql(f'SELECT message_count FROM ai_tutor_conversation WHERE id={cid}',mode='real')=='2'
    for ref in refs:
        if ref['type']=='API':assert ref['id'] in user_content
        if ref['type']=='TASK':assert str(ref['id'])==str(state['tasks']['REPAIR']) and ref['title'] in user_content
        if ref['type']=='DOC':assert (ROOT/'backend/src/main/resources'/ref['id']).is_file()
    # Only method/path/status/elapsed are logged. Tomcat 11 %D is microseconds.
    # Use just the browser call's access-log position recorded before its click.
    logged=[line for p in sorted((RUNTIME/'run/access').glob('*')) for line in p.read_text().splitlines() if line.startswith('POST /api/ai-tutor/ask ')]
    before=state.get('browserAccessCount',0)
    assert len(logged)==before+1, 'Expected exactly one browser platform call; inspect without retrying'
    access=logged[before].split();assert int(access[2])==200
    elapsed=round(int(access[3])/1000000,6)
    entry=BUDGET.read()['attempts'][state['browserReservation']-1]
    if entry['state']=='reserved':BUDGET.finish(state['browserReservation'],dict(httpStatus=200,elapsedSeconds=elapsed,conversationId=cid,messageId=int(parts[0]),answerChars=len(content),channel='browser-server-access-log'))
    save({'conversationId':cid,'assistantId':int(parts[0]),'content':content,'contextRefs':refs,'rowCount':2,'httpStatus':200,'serverElapsedSeconds':elapsed},RUNTIME/'real-browser-answer.json')
    print(json.dumps({'conversationId':cid,'assistantId':int(parts[0]),'answerChars':len(content),'rowCount':2},ensure_ascii=False))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=['prepare','ticket','followup','repair','replay','isolation','browser-reserve','browser-collect','verify']);parser.add_argument('--mode',choices=['real'],required=True);args=parser.parse_args()
    guard('real')
    if args.stage=='prepare':prepare()
    elif args.stage in ('ticket','followup','repair','replay'):call(args.stage)
    elif args.stage=='isolation':isolation()
    elif args.stage=='browser-reserve':browser_reserve()
    elif args.stage=='browser-collect':browser_collect()
    else:verify_messages(load())

if __name__=='__main__':main()
