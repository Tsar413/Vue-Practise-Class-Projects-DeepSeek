#!/usr/bin/env node
/**
 * tests/browser.mjs  真实浏览器端到端验证
 *
 * 使用本机已安装的 Google Chrome（playwright-core 驱动），对真实运行中的
 * 前后端做完整走查：登录、角色导航、班级/用户/空间管理、学生导入、
 * 项目数据浏览、接口地址复制、接口文档真实请求与冷却、数据重置、移动端布局。
 *
 * 所有断言都基于真实 HTTP 响应，不拦截、不伪造任何后端响应。
 * 数据表格的断言采用“等待出现”的方式，等真实请求返回后再判断。
 *
 * 前置条件：
 *   后端 http://127.0.0.1:8100 已启动
 *   前端 http://127.0.0.1:5173 已启动
 *   数据库已执行 db/01-schema.sql 与 db/02-seed-demo-data.sql
 *
 * 用法：
 *   node tests/browser.mjs
 * 环境变量：
 *   CHROME_PATH  浏览器可执行文件路径（默认 /opt/google/chrome/chrome）
 *   FRONTEND     前端地址（默认 http://localhost:5173）
 */
import { chromium } from 'playwright-core'
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
// tests/ 位于 frontend/ 下，素材与文档目录在仓库根目录
const root = join(here, '..', '..')
const shotDir = join(root, 'docs', 'screenshots')
mkdirSync(shotDir, { recursive: true })

const FRONTEND = process.env.FRONTEND || 'http://localhost:5173'
const CHROME = process.env.CHROME_PATH || '/opt/google/chrome/chrome'
const WAIT = 20000

const checks = []
const errors = []

function done(name) {
  checks.push({ name, passed: true })
  console.log('通过 ', name)
}
function failed(name, detail) {
  checks.push({ name, passed: false, detail })
  console.log('失败 ', name, '->', detail)
  process.exitCode = 1
}

// 等待文本真实出现（依赖真实请求返回），超时才算失败
async function waitText(locator, expected, name) {
  try {
    await locator.filter({ hasText: expected }).first().waitFor({ state: 'visible', timeout: WAIT })
    done(name)
  } catch {
    const actual = (await locator.first().innerText().catch(() => '')) || ''
    failed(name, `等待「${expected}」超时，当前内容「${actual.replace(/\s+/g, ' ').slice(0, 160)}」`)
  }
}
async function expectCount(locator, expected, name) {
  const n = await locator.count()
  if (n === expected) done(name)
  else failed(name, `期望 ${expected} 个，实际 ${n} 个`)
}
async function expectTrue(cond, name, detail = '') {
  if (cond) done(name)
  else failed(name, detail)
}
// 截图是辅助证据：失败时只提示，不影响功能结论。
const shotNotes = []
async function shot(name, options = {}) {
  const file = join(shotDir, name)
  // 截图中的访问码属于运行期凭证，统一遮蔽为占位符后再落盘
  options = { mask: [page.locator('code.address'), page.locator('.url-copy code')], ...options }
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await page.screenshot({ path: file, animations: 'disabled', caret: 'hide', ...options })
      return
    } catch (e) {
      await new Promise(r => setTimeout(r, 500))
    }
  }
  shotNotes.push(name)
  console.log('提示  截图失败（内存受限，非功能问题）：', name)
}

const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
  args: [
    '--no-sandbox',
    '--disable-dev-shm-usage',
    '--disable-gpu',
    '--disable-extensions',
    '--disable-background-networking',
    '--js-flags=--max-old-space-size=256'
  ]
})
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  permissions: ['clipboard-read', 'clipboard-write']
})
const page = await context.newPage()
page.on('pageerror', e => errors.push(e.message))
page.on('dialog', d => d.accept())

async function login(id, target = page) {
  await target.goto(`${FRONTEND}/login`)
  await target.getByLabel('系统用户编号', { exact: true }).fill(id)
  await target.getByLabel('登录密码').fill('123456')
  await target.getByRole('button', { name: '登录平台' }).click()
  await target.waitForURL(`${FRONTEND}/`)
}
// 打开一个新页面并登记错误监听（内存受限时用新页面代替整页刷新）
async function newPage() {
  const p = await context.newPage()
  p.on('pageerror', e => errors.push(e.message))
  p.on('dialog', d => d.accept())
  return p
}

try {
  /* -------------------- 未登录保护 -------------------- */
  await page.goto(`${FRONTEND}/teacher/classes`)
  await page.waitForURL(url => url.pathname === '/login')
  done('未登录访问受保护路由会跳转登录页')
  await shot('login-desktop.png')

  /* -------------------- 教师端 -------------------- */
  await login('DEMO_TEACHER')
  await waitText(page.locator('.toprole'), '教师', '教师登录成功并显示角色')
  await expectCount(page.getByRole('link', { name: '我的项目数据', exact: true }), 0, '教师导航不显示学生页面')
  await waitText(page.locator('.welcome h1'), '示范教师', '教师首页显示姓名')

  await page.getByRole('link', { name: '班级管理', exact: true }).click()
  await waitText(page.locator('tbody'), 'DEMO2026', '班级列表展示演示班级')
  await waitText(page.locator('tbody'), 'Vue 实训示范班', '班级列表展示班级名称')
  const classRow = page.getByRole('row').filter({ has: page.getByRole('cell', { name: 'DEMO2026', exact: true }) })
  await classRow.getByRole('button', { name: '编辑', exact: true }).click()
  await expectTrue(await page.getByLabel('班级编号', { exact: true }).isDisabled(), '编辑班级时编号不可修改')
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await shot('teacher-classes.png')

  await page.getByRole('link', { name: '系统用户', exact: true }).click()
  await waitText(page.locator('tbody'), 'DEMO2026001', '用户列表展示学生账号')
  await waitText(page.locator('tbody'), '学生甲', '用户列表展示学生姓名')
  await waitText(page.locator('tbody'), '示范教师', '用户列表展示教师账号')

  await page.getByRole('link', { name: '工作空间', exact: true }).click()
  await page.getByPlaceholder('请输入班级编号').fill('DEMO2026')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await waitText(page.locator('tbody'), 'DEMO2026001', '按班级查询到学生工作空间')
  await waitText(page.locator('tbody'), '启用', '工作空间状态为启用')

  await page.getByRole('link', { name: '学生导入', exact: true }).click()
  await page.getByLabel('选择学生名单').setInputFiles(join(root, 'scripts', 'fixtures', 'students.xlsx'))
  await page.getByRole('button', { name: '上传并导入' }).click()
  await waitText(page.getByRole('heading', { name: '导入结果' }), '导入结果', '批量导入返回结果面板')
  await page.locator('.stats').waitFor({ state: 'visible', timeout: WAIT })
  const stats = await page.locator('.stats > div').allInnerTexts()
  const stat = label => Number((stats.find(t => t.includes(label)) || '0').replace(/\D/g, '') || 0)
  await expectTrue(stat('处理人数') === 4 && stat('失败人数') === 1
    && stat('成功人数') + stat('跳过人数') === 3,
    '导入结果统计与预期一致（4 行、失败 1 行、成功+跳过 3 行）', stats.join(' | '))
  await waitText(page.locator('tbody'), '跳过', '导入结果列出被跳过的行')
  await waitText(page.locator('tbody'), '失败', '导入结果列出失败的行')
  await shot('import-result.png')

  await page.getByRole('link', { name: '接口文档', exact: true }).click()
  await waitText(page.locator('.badge.enabled'), '23 个接口', '教师系统文档展示23个接口')
  await page.getByRole('button', { name: '校园抢票', exact: true }).click()
  await waitText(page.locator('.badge.enabled'), '15 个接口', '抢票文档展示15个接口')
  await page.getByRole('button', { name: '全部展开', exact: true }).click()
  await page.locator('.endpoint-summary').first().click()
  await expectCount(page.getByRole('button', { name: '发送请求', exact: true }), 0, '教师业务文档只读，不提供发送按钮')
  await expectCount(page.locator('.endpoint-body .alert'), 0, '教师业务文档不显示修改提示')

  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await page.waitForURL(url => url.pathname === '/login')
  done('教师退出登录返回登录页')

  /* -------------------- 学生端 -------------------- */
  await login('DEMO2026001')
  await waitText(page.locator('.toprole'), '学生', '学生登录成功并显示角色')
  await waitText(page.locator('.detail-list'), 'DEMO2026001', '学生首页读取到本人工作空间')
  await shot('student-home.png')

  await page.goto(`${FRONTEND}/teacher/users`)
  await page.waitForURL(`${FRONTEND}/`)
  done('学生直接访问教师路由被拦截')

  await page.getByRole('link', { name: '接口地址', exact: true }).click()
  await page.getByRole('button', { name: '复制地址', exact: true }).first().click()
  const copied = await page.evaluate(() => navigator.clipboard.readText())
  await expectTrue(
    /^http:\/\/localhost:8100\/api\/practice\/[0-9a-f]{64}\/ticket$/.test(copied),
    '复制到的接口基础地址格式正确（含本人访问码）',
    copied)

  /* -------------------- 我的项目数据 -------------------- */
  await page.getByRole('link', { name: '我的项目数据', exact: true }).click()
  await waitText(page.locator('tbody'), 'N0001', '抢票模拟用户列表加载成功')
  await page.getByRole('button', { name: '活动列表', exact: true }).click()
  await waitText(page.locator('tbody'), '教职工电影观影活动', '抢票活动列表加载成功')
  await page.getByRole('button', { name: '票券记录', exact: true }).click()
  await waitText(page.locator('.table-tools'), '共', '票券记录查询完成')

  await page.getByRole('button', { name: '校园报修', exact: true }).click()
  await waitText(page.locator('tbody'), 'R0001', '报修模拟用户列表加载成功')
  await page.getByRole('button', { name: '设备列表', exact: true }).click()
  await waitText(page.locator('tbody'), '教室前后门锁及把手', '报修设备列表加载成功')
  await page.getByRole('button', { name: '工单列表', exact: true }).click()
  await waitText(page.locator('tbody'), 'BX0001', '报修工单列表加载成功')

  const orderRow = page.getByRole('row').filter({ has: page.getByRole('cell', { name: 'BX0001', exact: true }) })
  await orderRow.getByRole('button', { name: '查看详情' }).click()
  await waitText(page.getByRole('dialog'), 'BX0001', '工单详情弹窗展示工单编号')
  await page.getByRole('button', { name: '处理记录', exact: true }).click()
  await waitText(page.getByRole('dialog'), '报修人提交工单', '工单处理记录展示处理内容')
  await page.getByRole('button', { name: '工单评价', exact: true }).click()
  await waitText(page.getByRole('dialog'), '尚未评价', '未评价工单返回明确提示')
  await page.getByRole('button', { name: '工单图片', exact: true }).click()
  await waitText(page.getByRole('dialog'), '暂无图片', '无图片工单给出空状态提示')
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await shot('repair-data.png')

  /* -------------------- 移动端布局 -------------------- */
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('link', { name: '接口地址', exact: true }).click()
  await expectTrue(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    '390px 宽度下接口地址页无横向溢出')
  await shot('addresses-mobile.png', {
    mask: [page.locator('code.address')]
  })
  await page.getByRole('link', { name: '数据重置', exact: true }).click()
  await expectTrue(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    '390px 宽度下数据重置页无横向溢出')
  await page.setViewportSize({ width: 1440, height: 1000 })

  /* -------------------- 接口文档真实请求 -------------------- */
  await page.getByRole('navigation').getByRole('link', { name: '接口文档', exact: true }).click()
  await expectCount(page.getByRole('button', { name: '系统管理', exact: true }), 0, '学生文档不显示系统管理分组')
  await waitText(page.locator('.badge.enabled'), '15 个接口', '学生默认进入抢票文档')

  // 本地参数校验失败不启动冷却
  await page.getByLabel('搜索接口').fill('查询抢票活动详情')
  await page.locator('.endpoint-summary').first().click()
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  await waitText(page.locator('.endpoint-body .alert.error'), '请填写', '缺少必填参数时给出本地校验提示')
  const deadlineAfterLocal = await page.evaluate(() => localStorage.getItem('practice:DEMO2026001:docs:deadline'))
  await expectTrue(deadlineAfterLocal === null, '本地校验失败不启动十秒冷却')

  // 真实请求成功并启动冷却
  await page.getByLabel('搜索接口').fill('查询抢票活动列表')
  await page.locator('.endpoint-summary').first().click()
  await page.getByRole('button', { name: '发送请求', exact: true }).click()
  await waitText(page.locator('.response-meta'), 'HTTP 200', '文档真实请求返回 HTTP 200')
  await waitText(page.locator('.response pre'), '教职工电影观影活动', '实际响应包含真实业务数据')
  await waitText(page.locator('.cooldown-banner'), '秒后再次发送请求', '请求成功后显示冷却提示')
  await shot('docs-response.png')

  // 冷却截止时间存放在 localStorage：关闭页面后重新打开仍应生效
  const deadlineDocs = await page.evaluate(() => localStorage.getItem('practice:DEMO2026001:docs:deadline'))
  await expectTrue(!!deadlineDocs, '请求成功后写入十秒冷却截止时间')
  await page.close()
  const page2 = await newPage()
  await login('DEMO2026001', page2)
  await page2.getByRole('navigation').getByRole('link', { name: '接口文档', exact: true }).click()
  await waitText(page2.locator('.cooldown-banner'), '秒后再次发送请求', '重新打开页面后冷却仍保留')
  await page2.getByLabel('搜索接口').fill('查询抢票模拟用户列表')
  await page2.locator('.endpoint-summary').first().click()
  await expectTrue(
    await page2.locator('.endpoint-body .toolbar button').first().isDisabled(),
    '冷却期间发送按钮禁用')
  const page2Deadline = await page2.evaluate(() => localStorage.getItem('practice:DEMO2026001:docs:deadline'))
  await expectTrue(page2Deadline === deadlineDocs, '冷却截止时间没有被重置')

  await page2.getByRole('link', { name: '我的项目数据', exact: true }).click()
  await page2.getByRole('button', { name: '刷新数据', exact: true }).click()
  await waitText(page2.locator('tbody'), 'N0001', '只读数据查询不受文档冷却限制')

  /* -------------------- 数据重置（学生乙） -------------------- */
  await page2.getByRole('button', { name: '退出登录', exact: true }).click()
  await login('DEMO2026002', page2)
  await page2.getByRole('link', { name: '数据重置', exact: true }).click()
  await page2.getByRole('button', { name: '重置所选项目', exact: true }).click()
  await page2.getByRole('button', { name: '确认重置', exact: true }).click()
  await waitText(page2.locator('.alert.success'), '重置成功', '学生自助重置成功')
  // 冷却按钮文案为“请在 mm:ss 后再次重置”，这里轮询读取真实按钮文本
  let coolText = ''
  for (let i = 0; i < 20; i++) {
    coolText = (await page2.locator('.reset-panel button').first().innerText().catch(() => '')) || ''
    if (coolText.includes('后再次重置')) break
    await page2.waitForTimeout(300)
  }
  await expectTrue(coolText.includes('后再次重置'), '重置成功后按钮进入五分钟冷却', coolText)
  await expectTrue(await page2.locator('.reset-panel button').first().isDisabled(),
    '五分钟冷却期间重置按钮禁用')
  await shot('reset-cooldown.png')

  const deadline = await page2.evaluate(() => localStorage.getItem('practice:DEMO2026002:reset:deadline'))
  await expectTrue(!!deadline, '重置成功后写入五分钟冷却截止时间')
  await page2.close()
  const page3 = await newPage()
  await login('DEMO2026002', page3)
  await page3.getByRole('navigation').getByRole('link', { name: '数据重置', exact: true }).click()
  const deadline3 = await page3.evaluate(() => localStorage.getItem('practice:DEMO2026002:reset:deadline'))
  await expectTrue(deadline === deadline3, '重新打开页面后重置冷却截止时间不变')
  await page3.close()

  const page4 = await newPage()
  await login('DEMO2026001', page4)
  await page4.getByRole('navigation').getByRole('link', { name: '数据重置', exact: true }).click()
  await expectTrue(
    await page4.getByRole('button', { name: '重置所选项目', exact: true }).isEnabled(),
    '另一名学生不受该冷却影响')
  await page4.close()

  /* -------------------- 结束 -------------------- */
  await expectTrue(errors.length === 0, '浏览器控制台无未处理脚本错误', errors.join(' | '))
} catch (e) {
  failed('浏览器走查整体执行', String(e).slice(0, 400))
} finally {
  const pass = checks.filter(c => c.passed).length
  const fail = checks.filter(c => !c.passed).length
  writeFileSync(join(root, 'docs', 'verification-browser.txt'),
    ['浏览器端到端验证结果',
      `时间：${new Date().toISOString()}`,
      `前端：${FRONTEND}`,
      `通过 ${pass} 项，失败 ${fail} 项`,
      '',
      ...checks.map(c => `${c.passed ? '通过' : '失败'}  ${c.name}${c.detail ? '  <- ' + c.detail : ''}`)
    ].join('\n'), 'utf8')
  writeFileSync(join(root, 'docs', 'verification-browser.json'),
    JSON.stringify({ date: new Date().toISOString(), frontend: FRONTEND, checks, errors }, null, 2), 'utf8')
  await browser.close()
  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`)
  if (fail > 0) process.exitCode = 1
}
