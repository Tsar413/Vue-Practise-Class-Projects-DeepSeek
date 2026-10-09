<script setup>
import { computed, onMounted, ref } from 'vue'
import { sys, errorText } from '../api/client'
import { displayUser, displayClass, personName, aliasUserName, className as aliasClassName } from '../utils/privacy'
import { notify } from '../composables/ui'
import DataTable from '../components/DataTable.vue'
import Modal from '../components/Modal.vue'
import Detail from '../components/Detail.vue'

// 一个组件承载班级 / 系统用户 / 工作空间三种教师管理页，
// 差异只体现在接口根路径、表格列和可用操作上。
const props = defineProps({ kind: String })

const names = { classes: '班级', users: '用户', workspaces: '工作空间' }
const roots = {
  classes: '/api/sys-class',
  users: '/api/sys-user',
  workspaces: '/api/sys-workspace'
}
const root = computed(() => roots[props.kind])

const rows = ref([])
const busy = ref(false)
const error = ref('')
const mode = ref('all')
const lookup = ref('')
const modal = ref('')
const form = ref({})
const editing = ref(false)
const detail = ref(null)
const pending = ref(null)
const project = ref('ALL')
const saving = ref(false)

const columns = computed(() => props.kind === 'classes'
  ? ['id', 'className', 'status', 'createTime']
  : props.kind === 'users'
    ? ['id', 'realName', 'username', 'role', 'classId', 'status']
    : ['id', 'studentId', 'status', 'createTime'])

// 展示用副本：姓名与班级名称走脱敏层，原对象（rows）保持不变，
// 编辑/删除等操作仍使用原始行数据，避免把别名写回数据库。
const displayRows = computed(() => rows.value.map(row => {
  if (props.kind === 'users') return displayUser(row)
  if (props.kind === 'classes') return displayClass(row)
  return row
}))

// 用请求序号丢弃过期响应，避免快速切换查询条件时旧结果覆盖新结果。
let seq = 0

async function load() {
  const n = ++seq
  busy.value = true
  error.value = ''
  rows.value = []
  try {
    const path = mode.value === 'all'
      ? '/all'
      : `/${mode.value === 'class' ? 'classes' : 'one'}/${encodeURIComponent(lookup.value.trim())}`
    if (mode.value !== 'all' && !lookup.value.trim()) throw new Error('请填写查询编号。')
    const d = await sys('get', root.value + path)
    if (n === seq) rows.value = Array.isArray(d) ? d : d ? [d] : []
  } catch (e) {
    if (n === seq) error.value = errorText(e)
  } finally {
    if (n === seq) busy.value = false
  }
}

/** 按 ID 回原始行：表格传的是展示副本，直接编辑会把代称写回数据库。 */
function originalRow(row) {
  if (!row) return row
  const key = row.studentId || row.id
  return rows.value.find(item => (item.studentId || item.id) === key) || row
}

function edit(input) {
  const row = originalRow(input)
  editing.value = !!row
  // 编辑已有记录时表单字段保持**原始值**，不在表单里预填别名，
  // 否则保存会把别名写回真实字段（用户明确禁止）。
  // 界面上对应的输入框对已存在记录是只读的，并单独显示中性别名。
  if (row) {
    form.value = { ...row }
  } else {
    // 新建时用编号自动生成中性名称，不要求填写真实姓名
    form.value = {
      id: '',
      className: '',
      realName: '',
      username: '',
      classId: '',
      role: 'STUDENT'
    }
  }
  modal.value = 'edit'
  error.value = ''
}

// 新建时按用户填写的编号生成中性姓名，避免要求教师填真实姓名
function neutralNameFor(id, role) {
  const value = String(id || '').trim()
  if (!value) return ''
  return personName('', { role, id: value })
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    const f = form.value
    const data = props.kind === 'classes'
      ? { id: f.id, className: editing.value ? f.className : (f.className || aliasClassName('', f.id)) }
      : {
          id: f.id,
          username: f.username,
          // 编辑：沿用数据库中的真实原值（表单只读，不会被别名覆盖）
          // 新建：用「角色 + 编号」生成中性名称，无需填写真实姓名
          realName: editing.value ? f.realName : (f.realName || neutralNameFor(f.id, f.role)),
          classId: f.role === 'STUDENT' ? f.classId : null,
          role: f.role
        }
    await sys(editing.value ? 'put' : 'post', root.value + '/one', data)
    modal.value = ''
    notify('保存成功')
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}

async function show(row) {
  busy.value = true
  error.value = ''
  try {
    detail.value = await sys('get',
      root.value + '/one/' + encodeURIComponent(props.kind === 'workspaces' ? row.studentId : row.id))
    modal.value = 'detail'
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function action(type, input) {
  // 确认框与后续操作始终基于原始行
  const row = originalRow(input)
  pending.value = { type, row }
  project.value = 'ALL'
  modal.value = 'confirm'
  error.value = ''
}

// 破坏性操作前明确说明影响范围，避免误删班级或学生数据。
const impact = computed(() => {
  const p = pending.value
  if (!p) return ''
  if (p.type === 'delete') {
    return props.kind === 'classes'
      ? `将删除班级「${aliasClassName(p.row.className, p.row.id)}」及关联学生、登录凭证、工作空间和项目数据，此操作不可恢复。`
      : `将删除用户「${personName(p.row.realName, { role: p.row.role, id: p.row.id || p.row.userNo })}」及其登录凭证、工作空间和项目数据，此操作不可恢复。`
  }
  if (p.type === 'reset') {
    return `将重置学号 ${p.row.studentId} 的所选项目。重置后，本项目中的操作数据将恢复为初始数据，请确认已保存需要保留的内容。`
  }
  if (p.type === 'init') {
    return `将为班级 ${lookup.value} 的学生初始化所选项目。后端会跳过已存在项目数据的空间。`
  }
  return `将${p.row.status === 1 ? '停用 / 暂停' : '启用'}${names[props.kind]} ${p.row.studentId || p.row.id}。停用可能使对应账号或空间无法继续访问。`
  // 说明：这里用的是编号，不是姓名或班级名，无需脱敏
})

async function confirm() {
  saving.value = true
  error.value = ''
  try {
    const { type, row } = pending.value
    const id = encodeURIComponent(row?.studentId || row?.id || '')
    if (type === 'delete') await sys('delete', root.value + '/one/' + id)
    if (type === 'status') await sys('put', root.value + '/one/' + id + '/status', null, { status: row.status === 1 ? 0 : 1 })
    if (type === 'reset') await sys('post', root.value + '/one/' + id + '/reset', null, { project: project.value })
    if (type === 'init') {
      await sys('post', `/api/sys-workspace/classes/${encodeURIComponent(lookup.value.trim())}/${project.value.toLowerCase()}/initialize`)
    }
    modal.value = ''
    notify('操作成功')
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  // 工作空间默认按班级查询，避免一次性拉取全校数据
  if (props.kind === 'workspaces') mode.value = 'class'
  else load()
})
</script>

<template>
  <div class="section-heading">
    <div>
      <h1>{{ names[kind] }}管理</h1>
      <p>维护{{ names[kind] }}信息，为课堂实训做好准备。</p>
    </div>
    <button v-if="kind !== 'workspaces'" @click="edit(null)">＋ 新增{{ names[kind] }}</button>
  </div>

  <section class="panel">
    <form class="toolbar" @submit.prevent="load">
      <select v-model="mode" aria-label="查询方式">
        <option v-if="kind !== 'workspaces'" value="all">查询全部</option>
        <option value="one">{{ kind === 'workspaces' ? '按学号查询' : '按编号查询' }}</option>
        <option v-if="kind !== 'classes'" value="class">按班级查询</option>
      </select>
      <input v-if="mode !== 'all'" v-model="lookup"
             :placeholder="mode === 'class' ? '请输入班级编号' : kind === 'workspaces' ? '请输入学生学号' : '请输入编号'" required>
      <button :disabled="busy">{{ busy ? '正在查询…' : '查询' }}</button>
      <button v-if="kind === 'workspaces' && mode === 'class' && lookup.trim()" type="button"
              class="secondary" @click="action('init', null); project = 'TICKET'">按班级初始化</button>
    </form>
    <div v-if="error && !modal" class="alert error" role="alert">{{ error }}</div>

    <DataTable :rows="displayRows" :columns="columns" actions>
      <template #default="{ row }">
        <button class="text-button" :disabled="busy" @click="show(row)">详情</button>
        <button v-if="kind !== 'workspaces'" class="text-button" @click="edit(row)">编辑</button>
        <button class="text-button" @click="action('status', row)">{{ row.status === 1 ? '停用' : '启用' }}</button>
        <button v-if="kind === 'workspaces'" class="text-button" @click="action('reset', row)">重置</button>
        <button v-else class="text-button danger" @click="action('delete', row)">删除</button>
      </template>
    </DataTable>
  </section>

  <Modal v-if="modal"
         :title="modal === 'edit' ? (editing ? '编辑' : '新增') + names[kind] : modal === 'detail' ? names[kind] + '详情' : '请确认操作'"
         @close="!saving && (modal = '')">
    <div v-if="error" class="alert error" role="alert">{{ error }}</div>

    <form v-if="modal === 'edit'" @submit.prevent="save">
      <label>{{ kind === 'classes' ? '班级编号' : '系统用户编号（学生为学号）' }}
        <input v-model="form.id" :disabled="editing" maxlength="50" required>
      </label>
      <template v-if="kind === 'classes'">
        <label v-if="editing">班级名称（展示为中性代称，保存时保留原值）
          <input :value="aliasClassName(form.className, form.id)" readonly>
        </label>
        <label v-else>班级名称（可留空，保存时按编号自动生成中性代称）
          <input v-model="form.className" maxlength="50" placeholder="例如：班级A">
        </label>
      </template>
      <template v-else>
        <label v-if="editing">姓名（展示为中性代称，保存时保留原值）
          <input :value="personName(form.realName, { role: form.role, id: form.id })" readonly>
        </label>
        <label v-else>姓名（可留空，保存时按编号自动生成，无需填写真实姓名）
          <input v-model="form.realName" maxlength="50" placeholder="留空即按编号生成">
        </label>
        <label v-if="editing">用户名（展示为中性代称，保存时保留原值）
          <input :value="aliasUserName(form.username, personName(form.realName, { role: form.role, id: form.id }))" readonly>
        </label>
        <label v-else>用户名（选填）<input v-model="form.username" maxlength="50"></label>
        <label>系统角色
          <select v-model="form.role">
            <option value="STUDENT">学生</option>
            <option value="TEACHER">教师</option>
          </select>
        </label>
        <label v-if="form.role === 'STUDENT'">班级编号<input v-model="form.classId" required maxlength="50"></label>
        <p class="hint" v-if="!editing">新建账号初始密码为 123456。</p>
        <p class="hint" v-else>系统用户编号用于定位，不能修改。</p>
      </template>
      <button :disabled="saving">{{ saving ? '正在保存…' : '保存' }}</button>
    </form>

    <Detail v-if="modal === 'detail'" :value="detail" />

    <template v-if="modal === 'confirm'">
      <p class="confirm-copy">{{ impact }}</p>
      <label v-if="['init', 'reset'].includes(pending.type)">项目范围
        <select v-model="project">
          <option value="TICKET">校园抢票</option>
          <option value="REPAIR">校园报修</option>
          <option v-if="pending.type === 'reset'" value="ALL">全部项目</option>
        </select>
      </label>
      <div class="toolbar">
        <button :disabled="saving" @click="confirm">{{ saving ? '正在处理…' : '确认执行' }}</button>
        <button class="secondary" :disabled="saving" @click="modal = ''">取消</button>
      </div>
    </template>
  </Modal>
</template>
