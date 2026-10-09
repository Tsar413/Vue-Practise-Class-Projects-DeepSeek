"""Fresh synthetic browser account, only after the guarded API suite has stopped."""
import json,uuid
from support import guard,Account,RUNTIME

def main():
    guard('protocol');teacher=Account('DEMO_TEACHER');suffix=uuid.uuid4().hex[:7]
    class_id='BROWSER_'+suffix;student_id='BROWSER_S_'+suffix
    for path,body in [('/api/sys-class/one',dict(id=class_id,className='边界浏览器验收班 '+suffix)),('/api/sys-user/one',dict(id=student_id,username='边界验收学生',realName='边界验收学生',classId=class_id,role='STUDENT'))]:
        status,_=teacher.call('POST',path,body);assert status==200
    fixture=dict(teacher='DEMO_TEACHER',student=student_id,classId=class_id,className='边界浏览器验收班 '+suffix,taskTitle='边界完整流程 '+suffix,frontend='http://127.0.0.1:15173')
    p=RUNTIME/'boundary-browser-fixture.json';p.write_text(json.dumps(fixture,ensure_ascii=False,indent=2));p.chmod(0o600)
    print(json.dumps(fixture,ensure_ascii=False)) # synthetic identifiers only; never print login tokens

if __name__=='__main__':main()
