"""Codex's synthetic browser fixture; protocol-mode isolation is mandatory.

prepare creates only new test students/classes/tasks; verify checks that browser
save operations did not replace backing names or task text with display aliases.
No credentials are printed or stored; browser uses the existing public fixture
password from the test environment documentation.
"""
import sys,json,uuid
from support import guard,sql,RUNTIME
from test_anonymization import AnonymizationContracts
STATE=RUNTIME/'anonymization-browser.json'

def prepare():
    guard('protocol')
    AnonymizationContracts.setUpClass();c=AnonymizationContracts();c.setUp()
    sid=c.a.id;cid=c.classes[0]
    # Negative vectors exist only in isolated test data, not production seeds.
    sql(f"UPDATE sys_user SET real_name='测试姓名', username='Unknown Person' WHERE id='{sid}'")
    sql(f"UPDATE sys_class SET class_name='虚构示例学校一班' WHERE id='{cid}'")
    for table in ['ticket_user','repair_user']:
        sql(f"UPDATE {table} SET real_name='张明',department='电子信息工程系' WHERE workspace_id={c.wid}")
    for table in ['ticket_activity','repair_device']:
        sql(f"UPDATE {table} SET campus='新吴校区',location='致用楼412教室' WHERE workspace_id={c.wid}")
    tid,payload=c.task(title='张明的接口练习',objective='新吴校区电子信息工程系任务',requirement='张明完成查询接口，按文档处理参数',acceptance='接口响应符合约定')
    c.ok(c.a,'PUT',f'/api/teaching/tasks/{tid}/draft',c.body(content='张明完成列表查询',process='在致用楼定位问题'))
    conv=c.conversation(taskId=tid)
    sql(f"UPDATE ai_tutor_conversation SET title='张明的查询问题' WHERE id={conv}")
    # Insert synthetic history without any upstream model call.
    sql(f"INSERT INTO ai_tutor_message (conversation_id,student_id,role,content,create_time) VALUES ({conv},'{sid}','USER','张明在新吴校区学习查询',NOW()),({conv},'{sid}','ASSISTANT','请联系老师检查接口。张明应根据文档核对参数。',NOW())")
    state=dict(student=sid,classId=cid,taskId=tid,conversationId=conv,workspace=c.wid)
    STATE.write_text(json.dumps(state));STATE.chmod(0o600);print(json.dumps(state))

def verify():
    guard('protocol');s=json.loads(STATE.read_text())
    assert sql(f"SELECT CONCAT(real_name,'|',username) FROM sys_user WHERE id='{s['student']}'")=='测试姓名|Unknown Person'
    assert sql(f"SELECT class_name FROM sys_class WHERE id='{s['classId']}'")=='虚构示例学校一班'
    assert sql(f"SELECT CONCAT(title,'|',requirement) FROM teaching_task WHERE id={s['taskId']}")=='张明的接口练习|张明完成查询接口，按文档处理参数'
    assert sql(f"SELECT draft_content FROM teaching_submission WHERE task_id={s['taskId']} AND student_id='{s['student']}'")=='张明完成列表查询'
    print('PASS: raw username, name, class, task and submission draft preserved after browser saves')

if __name__=='__main__':
    {'prepare':prepare,'verify':verify}[sys.argv[1]]()
