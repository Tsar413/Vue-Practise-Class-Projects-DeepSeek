"""Codex contracts for anonymous mock data and legacy campus compatibility.

Real isolated MySQL only; no production reads/writes, no model calls. Historical
names below are negative test vectors, never new public mock examples.
"""
import json,uuid,urllib.parse
from support import Account,guard,request,sql
from test_boundaries import ContractCase

LEGACY_A='新吴校区'
LEGACY_B='藕塘校区'
FORBIDDEN=['张明','李华','王芳','陈晨','赵敏','赵师傅','孙师傅','周老师','吴老师','张老师',LEGACY_A,LEGACY_B,'致用楼','荟聚金逸影城','汽车工程学院','电子信息工程系']

class AnonymizationContracts(ContractCase):
    def setUp(self):
        super().setUp()
        self.ok(self.a,'POST',f'/api/sys-workspace/one/{self.a.id}/reset?project=ALL')
        status,result=request('POST','/login',{'id':self.a.id,'password':'123456'})
        assert status==200
        self.a.token=result['data']['token'];self.code=result['data']['apiAccessCode']
        self.wid=int(sql("SELECT id FROM sys_workspace WHERE student_id='"+self.a.id+"'"))
    def practice(self,project,path,params=None,method='GET',body=None,want=200):
        # Never put secret-bearing URLs into assertion messages or evidence.
        url='/api/practice/'+self.code+'/'+project+path
        if params:url+='?'+urllib.parse.urlencode(params)
        status,result=request(method,url,body)
        self.assertEqual(want,status,'Practice endpoint returned unexpected HTTP status')
        return result.get('data')
    def test_P01_new_mock_uses_anonymous_people_campuses_and_locations(self):
        for project,paths in [('ticket',['/users','/activities']),('repair',['/users','/devices'])]:
            for path in paths:
                data=self.practice(project,path)
                self.assertTrue(data)
                encoded=json.dumps(data,ensure_ascii=False)
                for term in FORBIDDEN:self.assertNotIn(term,encoded,'Legacy identifying mock text remains')
                if path=='/users':
                    self.assertEqual(len(data),len({r['realName'] for r in data}),'Aliases should distinguish simulated people')
    def test_P02_neutral_filters_find_legacy_and_new_records_without_rewriting(self):
        # All writes are guarded and target only this freshly generated workspace.
        activity=int(sql(f'SELECT MIN(id) FROM ticket_activity WHERE workspace_id={self.wid}'))
        device=int(sql(f'SELECT MIN(id) FROM repair_device WHERE workspace_id={self.wid}'))
        order=int(sql(f'SELECT MIN(id) FROM repair_order WHERE workspace_id={self.wid}'))
        for table,id in [('ticket_activity',activity),('repair_device',device),('repair_order',order)]:
            sql(f"UPDATE {table} SET campus='{LEGACY_A}' WHERE id={id} AND workspace_id={self.wid}")
        users=self.practice('repair','/users');admin=next(r['id'] for r in users if r['role']=='ADMIN' and r['status']==1)
        for project,path,extra,target in [('ticket','/activities',{},activity),('repair','/devices',{},device),('repair','/orders',{'operatorId':admin},order)]:
            aliased=self.practice(project,path,dict(extra,campus='校区A'))
            legacy=self.practice(project,path,dict(extra,campus=LEGACY_A))
            self.assertIn(target,{r['id'] for r in aliased})
            self.assertEqual({r['id'] for r in aliased},{r['id'] for r in legacy})
            self.assertTrue(all(r['workspaceId']==self.wid for r in aliased),'Campus alias must not widen workspace scope')
            self.practice(project,path,dict(extra,campus='未知校区'),want=400)
        for table,id in [('ticket_activity',activity),('repair_device',device),('repair_order',order)]:
            self.assertEqual(LEGACY_A,sql(f'SELECT campus FROM {table} WHERE id={id}'),'Read filter must preserve stored legacy value')
    def test_P03_documented_neutral_campus_can_create_activity_and_device(self):
        ticket_users=self.practice('ticket','/users');ticket_admin=next(r['id'] for r in ticket_users if r['role']=='ADMIN' and r['status']==1)
        payload=dict(operatorId=ticket_admin,activityName='中性校区契约测试',description='虚构测试活动',campus='校区B',location='场所A',quota=10,bookingStartTime='2035-01-01T09:00:00',bookingEndTime='2035-01-02T09:00:00',activityStartTime='2035-01-03T09:00:00',activityEndTime='2035-01-03T11:00:00')
        created=self.practice('ticket','/activities',method='POST',body=payload)
        self.assertEqual('校区B',created['campus'])
        repair_users=self.practice('repair','/users');repair_admin=next(r['id'] for r in repair_users if r['role']=='ADMIN' and r['status']==1)
        device=self.practice('repair','/devices',method='POST',body=dict(operatorId=repair_admin,deviceNo='PRIVACY_'+uuid.uuid4().hex[:8],deviceName='示例设备',deviceType='设备类型A',campus='校区B',location='教学楼A'))
        self.assertEqual('校区B',device['campus'])
        self.assertEqual(self.wid,device['workspaceId'])
