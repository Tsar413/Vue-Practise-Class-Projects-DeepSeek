"""Business-contract regressions against real isolated MySQL and fake-only AI.

No expectation is generated from production functions. Contracts: docs/08 and
the user's teaching/AI acceptance requirements. Every test uses fresh synthetic
accounts; barriers establish ordering rather than arbitrary timing guesses.
"""
import concurrent.futures as futures
import hashlib,json,struct,threading,unittest,uuid,zlib
from support import *

class ContractCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        guard('protocol');cls.teacher=Account('DEMO_TEACHER');cls.prefix='B'+uuid.uuid4().hex[:10]
        cls.classes=[cls.prefix+'C1',cls.prefix+'C2']
        for id in cls.classes:
            status,_=cls.teacher.call('POST','/api/sys-class/one',dict(id=id,className='合成边界测试班'))
            assert status==200
    def setUp(self):
        guard('protocol');stub('mode',{'mode':'success'});self.a=self.student()
    def tearDown(self):stub('release',{});stub('mode',{'mode':'success'})
    def student(self,which=0):
        id=self.prefix+'S'+uuid.uuid4().hex[:9]
        self.ok(self.teacher,'POST','/api/sys-user/one',dict(id=id,username='合成测试学生',realName='合成测试学生',classId=self.classes[which],role='STUDENT'))
        return Account(id)
    def ok(self,who,method,path,data=None,status=200,**kw):
        actual,body=who.call(method,path,data,**kw)
        self.assertEqual(status,actual, f'{method} {path}: expected {status}, got {actual}; message={body.get("message","")}')
        return body.get('data')
    def task(self,publish=True,**changes):
        payload=dict(title='合成边界任务 '+uuid.uuid4().hex[:6],project='TICKET',objective='理解请求和状态',requirement='完成列表查询并处理错误',acceptance='查询和错误均可见',classIds=[self.classes[0]],fullScore=100,allowLate=False,deadline='2035-01-01 00:00:00')
        payload.update(changes)
        task=self.ok(self.teacher,'POST','/api/teaching/tasks',payload,status=201)
        if publish:self.ok(self.teacher,'POST',f'/api/teaching/tasks/{task["id"]}/status',{'action':'PUBLISH'})
        return task['id'],payload
    def body(self,**changes):
        d=dict(projectUrl='https://example.com/synthetic-work',content='合成完成说明',process='先观察再验证',requestKey=uuid.uuid4().hex);d.update(changes);return d
    def submit(self,tid,who=None,**changes):return self.ok(who or self.a,'POST',f'/api/teaching/tasks/{tid}/submissions',self.body(**changes),status=201)
    def attachment(self,tid,who=None,name='evidence.png'):
        return self.ok(who or self.a,'POST',f'/api/teaching/tasks/{tid}/attachments',status=201,upload=(name,PNG,'image/png'))
    def file(self,aid):return RUNTIME/'teaching-files'/sql(f'SELECT image_url FROM teaching_attachment WHERE id={aid}')
    def conversation(self,who=None,**changes):
        d=dict(project='TICKET');d.update(changes)
        return self.ok(who or self.a,'POST','/api/ai-tutor/conversations',d)['id']
    def ask(self,cid,**changes):
        d=dict(conversationId=cid,project='TICKET',question='TicketController_getAllActivities 应如何检查请求参数？',requestKey=uuid.uuid4().hex);d.update(changes);return d
    def model_count(self):return len(stub()['records'])
    def blocked_rows(self):
        return int(sql("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='teaching_task'"))

class TeachingBoundaries(ContractCase):
    def test_A01_student_teacher_actions_and_visibility(self):
        tid,payload=self.task(False);b=self.student(1);base=f'/api/teaching/tasks/{tid}'
        for method,path,body in [('POST','/api/teaching/tasks',payload),('PUT',base,payload),('POST',base+'/status',{'action':'PUBLISH'}),('POST',base+'/status',{'action':'CLOSE'})]:
            with self.subTest(method=method,path=path):self.ok(self.a,method,path,body,status=403)
        self.ok(self.a,'GET',base,status=404)
        self.assertNotIn(tid,[t['id'] for t in self.ok(self.a,'GET','/api/teaching/tasks')])
        self.ok(self.teacher,'POST',base+'/status',{'action':'PUBLISH'})
        self.ok(b,'GET',base,status=404)
        self.ok(self.teacher,'PUT',base,dict(payload,classIds=self.classes))
        self.ok(b,'GET',base)
        self.ok(self.a,'GET',base)
        self.ok(self.teacher,'POST',base+'/status',{'action':'CLOSE'})
        for method,suffix in [('PUT','draft'),('POST','submissions')]:self.ok(self.a,method,base+'/'+suffix,self.body(),status=409)

    def test_A02_validation_and_impossible_dates(self):
        tid,payload=self.task(False)
        invalid=[dict(title=''),dict(requirement=' '),dict(title='x'*201),dict(requirement='x'*20001),dict(project='UNKNOWN'),dict(classIds=[]),dict(classIds=['NO_SUCH_BOUNDARY_CLASS']),dict(fullScore=0),dict(deadline='2030-02-30 12:00:00'),dict(deadline='2030-02-28 24:00:00'),dict(referenceUrls=['javascript:alert(1)'])]
        for change in invalid:
            with self.subTest(field=list(change)[0],kind='invalid'):
                self.ok(self.teacher,'POST','/api/teaching/tasks',dict(payload,**change),status=400)
                self.ok(self.teacher,'PUT',f'/api/teaching/tasks/{tid}',dict(payload,**change),status=400)
        self.ok(self.teacher,'PUT',f'/api/teaching/tasks/{tid}',dict(payload,deadline='2028-02-29 12:00:00'))
        self.ok(self.teacher,'GET','/api/teaching/tasks/9223372036854775806',status=404)
        tid,_=self.task(False)
        self.ok(self.teacher,'POST',f'/api/teaching/tasks/{tid}/status',{'action':'DELETED'},status=400)

    def test_A03_deadline_boundaries_and_immutable_history_rules(self):
        for deadline,allow,want,late in [('2035-01-01 00:00:00',False,201,False),('2020-01-01 00:00:00',False,409,None),('2020-01-01 00:00:00',True,201,True)]:
            with self.subTest(deadline=deadline,allow=allow):
                tid,payload=self.task(deadline=deadline,allowLate=allow)
                result=self.ok(self.a,'POST',f'/api/teaching/tasks/{tid}/submissions',self.body(),status=want)
                if want==201:self.assertEqual(late,result['versions'][0]['late'])
        tid,payload=self.task(classIds=self.classes);submitted=self.submit(tid)
        self.ok(self.teacher,'POST','/api/teaching/evaluations',dict(submissionId=submitted['id'],versionId=submitted['versions'][0]['id'],decision='PASS',score=90))
        for change in [dict(project='REPAIR'),dict(classIds=[self.classes[1]]),dict(fullScore=89)]:
            with self.subTest(change=change):self.ok(self.teacher,'PUT',f'/api/teaching/tasks/{tid}',dict(payload,**change),status=409)

    def test_B01_draft_empty_fields_and_formal_contract(self):
        tid,_=self.task();base=f'/api/teaching/tasks/{tid}'
        self.ok(self.a,'PUT',base+'/draft',self.body())
        current=self.ok(self.a,'GET',base+'/submission');self.assertEqual([],current['versions'])
        self.ok(self.a,'PUT',base+'/draft',dict(projectUrl='',content='',process='',sections=[]))
        current=self.ok(self.a,'GET',base+'/submission')
        for key in ['projectUrl','content','process']:self.assertFalse((current.get('draft') or {}).get(key))
        self.assertEqual('0',sql(f'SELECT COUNT(*) FROM teaching_submission_version WHERE task_id={tid}'))
        for change in [dict(content=' '),dict(projectUrl='',sections=[]),dict(projectUrl='data:text/html,hello'),dict(content='x'*20001),dict(requestKey='bad')]:
            with self.subTest(field=list(change)[0]):self.ok(self.a,'POST',base+'/submissions',self.body(**change),status=400)

    def test_B02_concurrent_different_keys_preserve_every_version(self):
        tid,_=self.task();path=f'/api/teaching/tasks/{tid}/submissions';barrier=threading.Barrier(4)
        def send(i):barrier.wait(5);return self.a.call('POST',path,self.body(content='version-'+str(i)))
        with SqlSession() as lock:
            lock.execute(f'START TRANSACTION; SELECT id FROM teaching_task WHERE id={tid} FOR UPDATE')
            with futures.ThreadPoolExecutor(max_workers=4) as pool:
                pending=[pool.submit(send,i) for i in range(4)]
                try:eventually(lambda:self.blocked_rows()>=4,description='all four submissions blocked on task row')
                finally:lock.execute('COMMIT')
                results=[f.result(15) for f in pending]
        self.assertTrue(all(status==201 for status,_ in results))
        versions=self.ok(self.a,'GET',f'/api/teaching/tasks/{tid}/submission')['versions']
        self.assertEqual([4,3,2,1],[v['versionNo'] for v in versions])
        self.assertEqual({'version-'+str(i) for i in range(4)},{v['content'] for v in versions})

    def test_B03_same_key_and_draft_submit_races(self):
        tid,_=self.task();path=f'/api/teaching/tasks/{tid}';body=self.body();barrier=threading.Barrier(4)
        def send(_):barrier.wait(5);return self.a.call('POST',path+'/submissions',body)
        with futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(send,range(4)))
        self.assertTrue(all(s==201 for s,_ in results));self.assertEqual('1',sql(f'SELECT COUNT(*) FROM teaching_submission_version WHERE task_id={tid}'))
        barrier=threading.Barrier(2)
        def mutate(formal):
            barrier.wait(5)
            return self.a.call('POST' if formal else 'PUT',path+('/submissions' if formal else '/draft'),self.body(content='formal-survives' if formal else 'later-draft'))
        with futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(mutate,[True,False]))
        self.assertEqual([201,200],[s for s,_ in results]);versions=self.ok(self.a,'GET',path+'/submission')['versions']
        self.assertEqual(2,len(versions));self.assertEqual('formal-survives',versions[0]['content'])

    def test_B04_return_resubmit_historical_evaluation_and_identity(self):
        tid,_=self.task();b=self.student();attachment=self.attachment(tid);aid=attachment['id'];file=self.file(aid)
        sections=[dict(title='截图',content='说明',sortOrder=1,attachmentIds=[aid])]
        first=self.submit(tid,projectUrl='',sections=sections,studentId=b.id);sid=first['id'];v1=first['versions'][0]['id']
        self.assertEqual(self.a.id,first['studentId'])
        for score in [None,-1,101]:self.ok(self.teacher,'POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=v1,decision='PASS',score=score),status=400)
        self.ok(self.a,'POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=v1,decision='PASS',score=100),status=403)
        self.ok(self.teacher,'POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=v1,decision='REVISE',score=1),status=400)
        self.ok(self.teacher,'POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=v1,decision='REVISE',comment='补充失败提示'))
        second=self.submit(tid,sections=sections,content='补充完成');v2=second['versions'][0]['id']
        self.ok(self.teacher,'POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=v2,decision='PASS',score=88))
        self.ok(self.teacher,'POST','/api/teaching/evaluations',dict(submissionId=sid,versionId=v1,decision='PASS',score=40))
        current=self.ok(self.a,'GET',f'/api/teaching/tasks/{tid}/submission')
        self.assertEqual(88,current['currentEvaluation']['score']);self.assertEqual(2,current['status'])
        self.assertEqual(2,len(current['versions'][1]['evaluations']));self.assertTrue(file.exists())
        self.ok(self.a,'DELETE',f'/api/teaching/attachments/{aid}',status=409)
        self.ok(b,'GET',f'/api/teaching/attachments/{aid}/content',status=404)
        self.ok(b,'POST',f'/api/teaching/tasks/{tid}/submissions',self.body(sections=sections),status=404)
        self.ok(b,'GET',f'/api/teaching/submissions/{sid}',status=403)

    def test_B05_close_while_submission_waits_rechecks_state(self):
        tid,_=self.task()
        with SqlSession() as lock:
            lock.execute(f'START TRANSACTION; SELECT id FROM teaching_task WHERE id={tid} FOR UPDATE')
            with futures.ThreadPoolExecutor(max_workers=1) as pool:
                pending=pool.submit(self.a.call,'POST',f'/api/teaching/tasks/{tid}/submissions',self.body())
                try:
                    eventually(lambda:self.blocked_rows()>0,description='submission row lock wait')
                    # Equivalent committed close, inject only in test DB while request waits.
                    lock.execute(f'UPDATE teaching_task SET status=2 WHERE id={tid}; COMMIT')
                finally:lock.execute('ROLLBACK')
                self.assertEqual(409,pending.result(12)[0])
        self.assertEqual('0',sql(f'SELECT COUNT(*) FROM teaching_submission_version WHERE task_id={tid}'))

    def test_C01_image_content_size_pixels_and_filename(self):
        tid,_=self.task();path=f'/api/teaching/tasks/{tid}/attachments'
        def chunk(name,data):return struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data)&0xffffffff)
        bomb=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',4001,4000,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\0'))+chunk(b'IEND',b'')
        for label,data,status in [('nonimage',b'not-an-image',400),('corrupt',PNG[:40],400),('size',b'x'*(5*1024*1024+1),413),('pixels',bomb,400)]:
            with self.subTest(label=label):self.ok(self.a,'POST',path,status=status,upload=('fake.png',data,'image/png'))
        attachment=self.attachment(tid,name='../../outside.png');file=self.file(attachment['id'])
        self.assertTrue(file.is_relative_to(RUNTIME/'teaching-files'));self.assertEqual('outside.png',attachment['originalName']);self.assertEqual(PNG,file.read_bytes())

    def test_C02_upload_database_failure_removes_disk_file(self):
        tid,_=self.task();directory=RUNTIME/'teaching-files'/str(tid)
        before=set(directory.rglob('*')) if directory.exists() else set()
        name='boundary_'+uuid.uuid4().hex[:12]
        with SqlSession() as lock:
            lock.execute(f"SELECT GET_LOCK('{name}',0)")
            with trigger('teaching_attachment','BEFORE INSERT',f"IF NEW.student_id='{self.a.id}' THEN DO GET_LOCK('{name}',8); DO RELEASE_LOCK('{name}'); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='BOUNDARY_INJECT_UPLOAD'; END IF;"):
                with futures.ThreadPoolExecutor(max_workers=1) as pool:
                    pending=pool.submit(self.a.call,'POST',f'/api/teaching/tasks/{tid}/attachments',upload=('dbfailure.png',PNG,'image/png'))
                    try:
                        eventually(lambda:pending_lock(name),description='attachment insert reached after physical upload')
                        self.assertEqual(1,len([p for p in directory.rglob('*') if p.is_file()]))
                    finally:lock.execute(f"SELECT RELEASE_LOCK('{name}')")
                    self.assertEqual(500,pending.result(12)[0])
        after={p for p in directory.rglob('*') if p.is_file()} if directory.exists() else set()
        self.assertEqual({p for p in before if p.is_file()},after,'Rollback must remove a file written before failed DB insert')
        self.assertEqual('0',sql(f'SELECT COUNT(*) FROM teaching_attachment WHERE task_id={tid}'))

    def test_C03_delete_transaction_rollback_then_actual_cleanup(self):
        tid,_=self.task();b=self.student();own=self.attachment(tid);other=self.attachment(tid,b)
        aid=own['id'];file=self.file(aid);otherfile=self.file(other['id']);digest=hashlib.sha256(otherfile.read_bytes()).hexdigest()
        with trigger('teaching_file_delete_task','BEFORE INSERT',f"IF NEW.attachment_id={aid} THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='BOUNDARY_INJECT_DELETE'; END IF;"):
            self.ok(self.a,'DELETE',f'/api/teaching/attachments/{aid}',status=500)
        self.assertEqual(PNG,file.read_bytes());self.assertEqual('1',sql(f'SELECT deleted_at IS NULL FROM teaching_attachment WHERE id={aid}'))
        self.assertEqual('0',sql(f'SELECT COUNT(*) FROM teaching_file_delete_task WHERE attachment_id={aid}'))
        self.ok(self.a,'DELETE',f'/api/teaching/attachments/{aid}')
        eventually(lambda:not file.exists(),timeout=65,description='scheduled physical screenshot deletion')
        self.assertEqual(digest,hashlib.sha256(otherfile.read_bytes()).hexdigest())

    def test_C04_storage_path_and_symlink_cannot_escape(self):
        tid,_=self.task();att=self.attachment(tid);aid=att['id'];original=sql(f'SELECT image_url FROM teaching_attachment WHERE id={aid}')
        outside=RUNTIME/'boundary-outside.txt';outside.write_text('synthetic outside sentinel')
        link=RUNTIME/'teaching-files'/('boundary-link-'+uuid.uuid4().hex);link.symlink_to(outside)
        try:
            for path in ['../boundary-outside.txt',str(outside),link.name]:
                sql(f"UPDATE teaching_attachment SET image_url='{path}' WHERE id={aid}")
                status,_=self.a.call('GET',f'/api/teaching/attachments/{aid}/content')
                self.assertNotEqual(200,status,'Storage-path tampering must not read outside root')
                self.assertEqual('synthetic outside sentinel',outside.read_text())
        finally:
            sql(f"UPDATE teaching_attachment SET image_url='{original}' WHERE id={aid}");link.unlink();outside.unlink()

    def test_C05_reset_preserves_all_teaching_and_ai_rows_and_files(self):
        tid,_=self.task();att=self.attachment(tid);self.submit(tid,sections=[dict(title='图',attachmentIds=[att['id']])]);cid=self.conversation(taskId=tid)
        self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid,taskId=tid))
        tables=sql("SHOW TABLES").splitlines();tables=[t for t in tables if t.startswith(('teaching_','ai_tutor_')) and t!='teaching_file_delete_task']
        checksum=lambda:sql('CHECKSUM TABLE '+','.join('`'+t+'`' for t in tables))
        before=checksum();file=self.file(att['id']);digest=hashlib.sha256(file.read_bytes()).hexdigest()
        for project in ['TICKET','REPAIR']:self.ok(self.a,'POST',f'/api/sys-workspace/one/{self.a.id}/reset?project={project}')
        self.assertEqual(before,checksum());self.assertEqual(digest,hashlib.sha256(file.read_bytes()).hexdigest())

class AiBoundaries(ContractCase):
    def test_D01_roles_idor_and_context_mismatch(self):
        tid,_=self.task();cid=self.conversation(taskId=tid);b=self.student()
        paths=[('GET','/api/ai-tutor/conversations',None),('POST','/api/ai-tutor/conversations',dict(project='TICKET')),('GET',f'/api/ai-tutor/conversations/{cid}/messages',None),('DELETE',f'/api/ai-tutor/conversations/{cid}',None),('POST','/api/ai-tutor/ask',self.ask(cid))]
        for method,path,body in paths:
            with self.subTest(role='teacher',method=method):self.ok(self.teacher,method,path,body,status=403)
        for method,path,body in paths[2:]:self.ok(b,method,path,body,status=404)
        self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid,project='REPAIR'),status=400)
        self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid,taskId=9223372036854775806),status=400)
        self.ok(self.a,'POST','/api/ai-tutor/conversations',dict(project='TICKET',taskId=9223372036854775806),status=404)

    def test_D02_input_boundaries_and_actual_context_references(self):
        tid,_=self.task(requirement='BOUNDARY_TASK_CONTEXT unique teaching requirement');cid=self.conversation(taskId=tid)
        for change in [dict(question=' '),dict(question='x'*2001),dict(codeSnippet='x'*4001),dict(errorText='x'*2001)]:
            with self.subTest(field=list(change)[0]):self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid,**change),status=400)
        answer=self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid,question='活动列表 '+('q'*1994),codeSnippet='x'*4000,errorText='x'*2000))
        wire=json.dumps(stub()['records'][-1]['request'],ensure_ascii=False)
        self.assertIn('BOUNDARY_TASK_CONTEXT',wire)
        refs=answer['message']['contextRefs'];self.assertTrue(any(r['type']=='TASK' and str(r['id'])==str(tid) for r in refs))
        def operation_ids(value):
            if isinstance(value,dict):
                found={value['operationId']} if value.get('operationId') else set()
                for child in value.values():found.update(operation_ids(child))
                return found
            if isinstance(value,list):
                return set().union(*(operation_ids(child) for child in value)) if value else set()
            return set()
        for ref in refs:
            with self.subTest(type=ref['type'],id=ref['id']):
                if ref['type']=='TASK':
                    self.assertEqual(str(tid),str(ref['id']))
                    self.assertIn('BOUNDARY_TASK_CONTEXT',wire)
                elif ref['type']=='API':self.assertIn(str(ref['id']),wire)
                elif ref['type']=='DOC':
                    source=(ROOT/'backend/src/main/resources'/ref['id']).resolve()
                    self.assertTrue(source.is_relative_to(ROOT/'backend/src/main/resources/ai-docs'))
                    self.assertTrue(source.is_file())
                    operations=operation_ids(json.loads(source.read_text()))
                    self.assertTrue(any(op in wire for op in operations),'Referenced document must contribute an actual operation to provider context')
                else:self.fail('Unknown reference contract type')
        self.assertNotIn('RepairController_',wire)

    def test_D03_same_key_concurrent_one_upstream_call(self):
        cid=self.conversation();body=self.ask(cid);before=self.model_count();stub('mode',{'mode':'hold'});barrier=threading.Barrier(4)
        def send(_):barrier.wait(5);return self.a.call('POST','/api/ai-tutor/ask',body)
        with futures.ThreadPoolExecutor(max_workers=4) as pool:
            pending=[pool.submit(send,i) for i in range(4)]
            try:self.assertTrue(stub('wait',{'count':before+1})['arrived'])
            finally:stub('release',{})
            results=[f.result(10) for f in pending]
        self.assertTrue(all(status==200 for status,_ in results));self.assertEqual(before+1,self.model_count())
        self.assertEqual('2',sql(f'SELECT COUNT(*) FROM ai_tutor_message WHERE conversation_id={cid}'))

    def test_D04_personal_and_global_concurrency_release(self):
        b=self.student();c=self.student();ca=self.conversation();cb=self.conversation(b);cc=self.conversation(c);before=self.model_count();stub('mode',{'mode':'hold'})
        with futures.ThreadPoolExecutor(max_workers=2) as pool:
            first=pool.submit(self.a.call,'POST','/api/ai-tutor/ask',self.ask(ca))
            try:
                self.assertTrue(stub('wait',{'count':before+1})['arrived'])
                self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(ca),status=503)
                second=pool.submit(b.call,'POST','/api/ai-tutor/ask',self.ask(cb))
                self.assertTrue(stub('wait',{'count':before+2})['arrived'])
                self.ok(c,'POST','/api/ai-tutor/ask',self.ask(cc),status=503)
                self.assertEqual(before+2,self.model_count())
            finally:stub('release',{})
            self.assertEqual(200,first.result(10)[0]);self.assertEqual(200,second.result(10)[0])
        stub('mode',{'mode':'success'});self.ok(c,'POST','/api/ai-tutor/ask',self.ask(cc))

    def test_D05_daily_limit_and_deleted_conversations(self):
        cid=self.conversation()
        for _ in range(5):self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid))
        self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid)) # sixth is allowed
        before=self.model_count();self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(cid),status=409)
        self.ok(self.a,'DELETE',f'/api/ai-tutor/conversations/{cid}')
        new=self.conversation();self.ok(self.a,'POST','/api/ai-tutor/ask',self.ask(new),status=409)
        self.assertEqual(before,self.model_count(),'Deleting conversations must not replenish daily quota')

    def test_D06_delete_while_upstream_is_held(self):
        cid=self.conversation();before=self.model_count();stub('mode',{'mode':'hold'})
        with futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(self.a.call,'POST','/api/ai-tutor/ask',self.ask(cid))
            try:
                self.assertTrue(stub('wait',{'count':before+1})['arrived']);self.ok(self.a,'DELETE',f'/api/ai-tutor/conversations/{cid}')
                count=sql(f'SELECT COUNT(*) FROM ai_tutor_message WHERE conversation_id={cid}')
            finally:stub('release',{})
            self.assertEqual(404,pending.result(10)[0])
        self.assertEqual(count,sql(f'SELECT COUNT(*) FROM ai_tutor_message WHERE conversation_id={cid}'))
        self.assertEqual('0',sql(f'SELECT status FROM ai_tutor_conversation WHERE id={cid}'))

    def test_D07_delete_between_last_alive_check_and_message_insert(self):
        cid=self.conversation();name='boundary_'+uuid.uuid4().hex[:12]
        with SqlSession() as lock:
            lock.execute(f"SELECT GET_LOCK('{name}',0)")
            with trigger('ai_tutor_message','BEFORE INSERT',f"IF NEW.conversation_id={cid} AND NEW.role='ASSISTANT' THEN DO GET_LOCK('{name}',8); DO RELEASE_LOCK('{name}'); END IF;"):
                with futures.ThreadPoolExecutor(max_workers=2) as pool:
                    pending=pool.submit(self.a.call,'POST','/api/ai-tutor/ask',self.ask(cid))
                    try:
                        eventually(lambda:pending_lock(name),description='assistant insert suspended after alive check')
                        deletion=pool.submit(self.a.call,'DELETE',f'/api/ai-tutor/conversations/{cid}')
                        # Both legal serializations are supported: deletion wins (no
                        # later insert), or a real DB lock serializes deletion after
                        # the answer commit. Neither permits post-deletion messages.
                        def delete_blocked():
                            return int(sql("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='ai_tutor_conversation'"))>0
                        eventually(lambda:deletion.done() or delete_blocked(),description='delete completion or verified DB serialization')
                        deleted_first=deletion.done()
                        if deleted_first:
                            self.assertEqual(200,deletion.result()[0])
                            count=sql(f'SELECT COUNT(*) FROM ai_tutor_message WHERE conversation_id={cid}')
                    finally:lock.execute(f"SELECT RELEASE_LOCK('{name}')")
                    self.assertEqual(404 if deleted_first else 200,pending.result(12)[0])
                    self.assertEqual(200,deletion.result(12)[0])
        final_count=sql(f'SELECT COUNT(*) FROM ai_tutor_message WHERE conversation_id={cid}')
        self.assertEqual(count if deleted_first else '2',final_count,'No message may commit after completed conversation deletion')
        self.assertEqual('0',sql(f'SELECT status FROM ai_tutor_conversation WHERE id={cid}'))

    def test_D08_provider_failures_release_permits_and_keep_login(self):
        for mode in ['auth','rate','server','timeout','disconnect','emptybody','empty','nonjson','oversize']:
            with self.subTest(mode=mode):
                actor=self.student();cid=self.conversation(actor);stub('mode',{'mode':mode})
                status,body=actor.call('POST','/api/ai-tutor/ask',self.ask(cid))
                self.assertEqual(503,status);self.assertTrue(body.get('message'));self.assertNotIn('PRIVATE_FIXTURE_UPSTREAM_DETAIL',json.dumps(body));self.assertNotIn(FIXTURE_KEY,json.dumps(body))
                self.ok(actor,'GET',f'/api/sys-user/one/{actor.id}')
                stub('release',{});stub('mode',{'mode':'success'});self.ok(actor,'POST','/api/ai-tutor/ask',self.ask(cid))

    def test_D09_credentials_sentinels_never_leave_or_persist(self):
        cid=self.conversation();sentinels=['boundary-fake-key','boundary-fake-token','boundary-fake-password','a'*64]
        body=self.ask(cid,codeSnippet='const apiKey="boundary-fake-key"; const x={"password":"boundary-fake-password"};',errorText='Authorization: Bearer boundary-fake-token',question='解释 /api/practice/'+sentinels[3]+'/ticket/activity/all 的报错，不要执行我的代码。')
        self.ok(self.a,'POST','/api/ai-tutor/ask',body)
        wire=json.dumps(stub()['records'][-1],ensure_ascii=False);stored=json.dumps(self.ok(self.a,'GET',f'/api/ai-tutor/conversations/{cid}/messages'),ensure_ascii=False)
        for sentinel in sentinels:self.assertNotIn(sentinel,wire);self.assertNotIn(sentinel,stored)
        self.assertNotIn('tools',stub()['records'][-1]['request'])

    def test_D10_answer_insert_failure_is_atomic_and_retry_recovers(self):
        cid=self.conversation();body=self.ask(cid)
        with trigger('ai_tutor_message','BEFORE INSERT',f"IF NEW.conversation_id={cid} AND NEW.role='ASSISTANT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='BOUNDARY_INJECT_ASSISTANT'; END IF;"):
            self.ok(self.a,'POST','/api/ai-tutor/ask',body,status=500)
        self.assertEqual('1',sql(f'SELECT COUNT(*) FROM ai_tutor_message WHERE conversation_id={cid}'))
        self.assertEqual('1',sql(f'SELECT message_count FROM ai_tutor_conversation WHERE id={cid}'))
        self.ok(self.a,'POST','/api/ai-tutor/ask',body)
        self.assertEqual('1',sql(f"SELECT COUNT(*) FROM ai_tutor_message WHERE conversation_id={cid} AND role='ASSISTANT'"))

if __name__=='__main__':unittest.main(verbosity=2)
