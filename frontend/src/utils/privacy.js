/**
 * 全站展示脱敏层（纯函数，无副作用）。
 *
 * 目标：页面不出现明确人名、学校/院系/楼宇/商圈名称，统一改用「角色 + 编号」或
 * 「校区A / 院系A / 教学楼A」这类中性代称。
 *
 * 三条硬约束（改动时请遵守）：
 *  1. **不就地改写 API 原对象**：所有函数都返回新值，绝不修改传入对象；
 *     表单提交仍使用原始字段值，避免把别名写回数据库。
 *  2. **只做展示替换**：登录账号、主键、Token、访问码、workspace 与各表关联关系保持原样。
 *  3. **不做 DOM 遮盖**：不使用 MutationObserver 或直接改 DOM，
 *     统一在「数据格式化 / 模板 / markdown 边界」处理，避免闪露与破坏 Vue 响应式。
 *
 * 局限（不要对外声称「能识别任意姓名」）：
 *  * 只能识别下方登记的演示数据用名，以及有限的学校名称模式；
 *  * 无法可靠识别的自由输入（学生自己输入的真实姓名、学号）、图片像素中的文字，
 *    本层不做也不能做脱敏——这属于输入侧责任，需在使用说明中提示。
 */

// ---------------------------------------------------------------------------
// 1. 演示数据登记表：已知名称 -> 中性代称
// ---------------------------------------------------------------------------

/** 学生/普通用户：与后端 mock 编号一致，保证同一人处处显示同一个代称。 */
const PERSON_ALIASES = {
  张明: '模拟用户001',
  李华: '模拟用户002',
  王芳: '模拟用户003',
  陈晨: '模拟用户004',
  赵敏: '模拟用户005',
  赵师傅: '维修员示例',
  孙师傅: '维修员示例2',
  周老师: '教师示例',
  吴老师: '教师示例2',
  张老师: '教师示例'
}

/** 院系 / 行政部门：登记映射优先，未登记的具体院系统一泛化。 */
const DEPARTMENT_ALIASES = {
  电子信息工程系: '院系A',
  机电工程系: '院系B',
  城轨工程系: '院系C',
  汽车工程学院: '院系D',
  后勤管理处: '行政部门A',
  信息化建设与管理中心: '行政部门B'
}

/** 学校/班级名称。 */
const CLASS_ALIASES = {
  'Vue 实训示范班': '班级A'
}

/** 楼宇、场所、商圈等具体地点。 */
const PLACE_ALIASES = {
  致用楼: '教学楼A',
  荟聚: '商业中心',
  金逸影城: '影城',
  大学生活动中心: '学生活动中心',
  工会组长办公室: '办公室A',
  图书馆: '图书馆',
  行政楼: '行政楼',
  体艺馆: '体育馆',
  教学楼: '教学楼'
}

// 校区的「当前展示别名」（新数据即用这些值）
export const CAMPUS_A = '校区A'
export const CAMPUS_B = '校区B'

/**
 * 数据库/历史数据里已存在的旧校区值 -> 中性别名。
 * 这些别名只在**展示与筛选兼容**时使用，不改写任何原始记录。
 */
export const LEGACY_CAMPUS = {
  新吴校区: CAMPUS_A,
  藕塘校区: CAMPUS_B
}

// Codex review: field-aware aliases retain IDs; raw objects never feed display aliases back to writes.
const ROLES = { USER: '用户', ADMIN: '管理员', REPORTER: '报修人', MAINTAINER: '维修员', STUDENT: '学生', TEACHER: '教师' }
const text = value => value == null ? '' : String(value).trim()
const withId = (label, id) => label + text(id)
export function personName(raw, {role, id, fallback = '用户'} = {}) {
  return withId(ROLES[String(role || '').toUpperCase()] || fallback, id)
}
export function className(raw, id) { return withId('班级', id) }
export function campusName(raw) { return LEGACY_CAMPUS[text(raw)] || privacyText(raw) }
export function departmentName(raw) {
  const value = text(raw)
  if (!value) return ''
  return DEPARTMENT_ALIASES[value] || (/^(院系|行政部门)[A-Z0-9]+$/.test(value) ? value : '院系（模拟）')
}
export function locationName(raw) { return privacyText(raw) }
export function privacyText(raw) {
  if (raw == null) return ''
  let out = String(raw)
  for (const registry of [PERSON_ALIASES, CLASS_ALIASES, LEGACY_CAMPUS, PLACE_ALIASES, DEPARTMENT_ALIASES]) {
    for (const [from, to] of Object.entries(registry)) out = out.split(from).join(to)
  }
  // Conservative school-name pattern. This is not general-purpose person-name recognition.
  return out.replace(/[\u4e00-\u9fa5]{2,20}(?:大学|学院|学校)/g, '学校（示例）')
}
const NAME_FIELDS = new Set(['realname','username','studentname','teachername','reportername','maintainername','operatorname','uploadername','contactname','nickname','author'])
function identity(row, key) {
  const prefix = key.replace(/Name$/, '')
  const role = { student: 'STUDENT', teacher: 'TEACHER', reporter: 'REPORTER', maintainer: 'MAINTAINER' }[prefix] || row.role
  return { role, id: row[prefix + 'Id'] ?? row.userNo ?? row.studentId ?? row.userId ?? row.id }
}
export function privacyField(row, key) {
  const value = row[key], field = key.toLowerCase().replaceAll('_', '')
  if (value == null) return value
  if (NAME_FIELDS.has(field)) return personName(value, identity(row, key))
  if (field === 'classname') return className(value, row.classId ?? row.id)
  if (field === 'campus') return campusName(value)
  if (field === 'department' || field === 'dept') return departmentName(value)
  if (/^(originalName|originalFilename)$/.test(key)) return '图片' + (row.id ?? row.imageId ?? '')
  // Protocol identifiers/credentials/URLs must retain their semantics. Rendering is not authorization.
  if (/^(id|.*Id|.*No|token|apiAccessCode|accessCode|password|.*Url|.*Path)$/.test(key)) return value
  return privacyValue(value)
}
export function privacyValue(value) {
  if (value == null) return value
  if (typeof value === 'string') return privacyText(value)
  if (Array.isArray(value)) return value.map(privacyValue)
  if (typeof value === 'object') return Object.fromEntries(Object.keys(value).map(key => [key, privacyField(value, key)]))
  return value
}
export function privacyRef(ref) { return ref ? { ...ref, title: privacyText(ref.title), excerpt: privacyText(ref.excerpt) } : ref }
export function displayUser(user) {
  if (!user || typeof user !== 'object') return user
  const id = user.userNo ?? user.studentId ?? user.userId ?? user.id
  return { ...user, displayId: id, displayName: personName(user.realName, { role: user.role, id }) }
}
export function displayClass(item) { return item ? { ...item, displayName: className(item.className, item.id), displayId: item.id } : item }
export function aliasUserName(raw, alias) { return raw ? (alias || '用户') : '' }
