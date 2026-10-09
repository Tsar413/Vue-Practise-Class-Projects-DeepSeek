"""Run explicitly in --mode off; failure to confirm isolation aborts all writes."""
import unittest,uuid,json
from support import *

class UnconfiguredContract(unittest.TestCase):
    def test_missing_configuration_preserves_honest_state(self):
        guard('off');teacher=Account('DEMO_TEACHER');sid='OFF'+uuid.uuid4().hex[:12]
        status,_=teacher.call('POST','/api/sys-user/one',dict(id=sid,username='合成未配置测试',realName='合成未配置测试',classId='DEMO2026',role='STUDENT'))
        self.assertEqual(200,status);actor=Account(sid)
        status,result=actor.call('POST','/api/ai-tutor/conversations',dict(project='REPAIR'));self.assertEqual(200,status);cid=result['data']['id']
        before=len(stub()['records'])
        status,result=actor.call('POST','/api/ai-tutor/ask',dict(conversationId=cid,project='REPAIR',question='如何处理报修失败？',requestKey=uuid.uuid4().hex))
        self.assertEqual(200,status);answer=result['data'];self.assertFalse(answer['configured']);self.assertIn('未配置',answer['notice']);self.assertFalse(answer.get('message'))
        self.assertEqual(before,len(stub()['records']))
        self.assertEqual('USER',sql(f'SELECT role FROM ai_tutor_message WHERE conversation_id={cid}'))

if __name__=='__main__':unittest.main(verbosity=2)
