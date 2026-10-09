// Codex-authored display contracts. Names below are synthetic/legacy negative vectors.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { personName, className, privacyText, campusName, departmentName, displayUser, displayClass, privacyValue } from '../src/utils/privacy.js'

test('姓名一律使用角色与稳定编号，未知中英文姓名也不回显', () => {
  for (const raw of ['张明', '测试姓名', 'Example Person', '张老师']) {
    const shown = personName(raw, { role: 'STUDENT', id: 'ST001' })
    assert.ok(shown.includes('ST001'))
    assert.ok(shown.includes('学生'))
    assert.ok(!shown.includes(raw))
    assert.notEqual(shown, personName(raw, { role: 'STUDENT', id: 'ST002' }))
  }
})

test('班级别名与编号绑定，不允许班级开头的原始中文名称绕过', () => {
  for (const raw of ['虚构示例学校一班', 'Vue 实训示范班', '班级张明组']) {
    const shown = className(raw, 'C001')
    assert.ok(shown.includes('C001'))
    assert.ok(!shown.includes(raw))
  }
})

test('展示副本不改变真实业务字段，编辑提交仍可保留原值', () => {
  const user = Object.freeze({ id: 'S001', role: 'STUDENT', realName: '测试姓名', username: 'raw-account' })
  const classroom = Object.freeze({ id: 'C001', className: '原始班级名称' })
  const shownUser = displayUser(user), shownClass = displayClass(classroom)
  assert.notEqual(shownUser, user)
  assert.notEqual(shownClass, classroom)
  assert.equal(shownUser.username, user.username, 'Display metadata must not replace fields reused by editing')
  assert.equal(user.realName, '测试姓名')
  assert.equal(user.username, 'raw-account')
  assert.equal(classroom.className, '原始班级名称')
  assert.ok(!shownUser.displayName.includes('测试姓名'))
  assert.ok(!shownClass.displayName.includes('原始班级名称'))
})

test('自由文本中的已知mock人名校区院系地点脱敏，普通教学说明保留', () => {
  const raw = '张明在新吴校区电子信息工程系致用楼；李华在藕塘校区汽车工程学院。'
  const result = privacyText(raw)
  for (const name of ['张明', '新吴', '电子信息工程系', '致用楼', '李华', '藕塘', '汽车工程学院']) assert.ok(!result.includes(name), name)
  const ordinary = '请联系老师检查接口。同学们应先阅读接口文档。GET /api/practice/{accessCode}/ticket/users'
  assert.equal(privacyText(ordinary), ordinary)
  assert.equal(campusName('新吴校区'), '校区A')
  assert.equal(campusName('藕塘校区'), '校区B')
  assert.equal(departmentName('电子信息工程系'), '院系A')
})

test('前端文档与AI文档逐字节一致，公开示例没有历史明确名称', () => {
  const root = new URL('../src/data/', import.meta.url)
  const paths = readdirSync(root, { recursive: true }).filter(p => p === 'system-spec.json' || p.endsWith('/operations.json'))
  assert.equal(paths.length, 11)
  for (const name of paths) {
    const front = readFileSync(new URL(`../src/data/${name}`, import.meta.url), 'utf8')
    const back = readFileSync(new URL(`../../backend/src/main/resources/ai-docs/${name}`, import.meta.url), 'utf8')
    assert.equal(front, back)
    assert.doesNotMatch(front, /张明|李华|王芳|陈晨|赵敏|赵师傅|孙师傅|周老师|吴老师|张老师|新吴|藕塘|致用楼|荟聚|汽车工程学院|电子信息工程系/)
    assert.ok(JSON.parse(front).length > 0)
  }
})


test('嵌套JSON按字段匿名化未知姓名和班级，原对象及协议编号不变', () => {
  const raw = { data: [{ id: 'S77', role: 'STUDENT', username: 'Unknown Person', realName: '虚构姓名', classId: 'C77', className: '虚构学校一班' }], total: 1 }
  const before = JSON.stringify(raw)
  const shown = privacyValue(raw)
  assert.equal(JSON.stringify(raw), before)
  assert.equal(shown.data[0].id, 'S77')
  assert.equal(shown.total, 1)
  for (const term of ['Unknown Person', '虚构姓名', '虚构学校一班']) assert.ok(!JSON.stringify(shown).includes(term))
  assert.ok(shown.data[0].realName.includes('S77'))
  assert.ok(shown.data[0].className.includes('C77'))
})
