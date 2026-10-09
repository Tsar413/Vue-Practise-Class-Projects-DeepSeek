<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { teaching, errorText } from '../../api/teaching'
import { sys } from '../../api/client'
import { className as aliasClassName, privacyText } from '../../utils/privacy'

const route = useRoute()
const router = useRouter()

const editing = computed(() => Boolean(route.params.id))


const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const classes = ref([])

const form = reactive({
  title: '',
  project: 'TICKET',
  objective: '',
  requirement: '',
  acceptance: '',
  referenceUrlsText: '',
  deadline: '',
  fullScore: 100,
  allowLate: false,
  classIds: []
})

function toLocalInput(value) {
  if (!value) return ''
  return String(value).replace(' ', 'T').slice(0, 16)
}

function toApiTime(value) {
  if (!value) return ''
  const normalized = value.length === 16 ? `${value}:00` : value
  return normalized.replace('T', ' ')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    classes.value = await sys('get', '/api/sys-class/all') || []
  } catch (e) {
    // 班级列表读取失败时仍允许编辑其它字段，只是无法选择班级
    error.value = `班级列表读取失败：${errorText(e)}`
  }
  if (editing.value) {
    try {
      const task = await teaching.taskDetail(route.params.id)
      form.title = task.title || ''
      form.project = task.project || 'TICKET'
      form.objective = task.objective || ''
      form.requirement = task.requirement || ''
      form.acceptance = task.acceptance || ''
      form.referenceUrlsText = (task.referenceUrls || []).join('\n')
      form.deadline = toLocalInput(task.deadline)
      form.fullScore = task.fullScore ?? 100
      form.allowLate = Boolean(task.allowLate)
      form.classIds = [...(task.classIds || [])]
    } catch (e) {
      error.value = errorText(e)
    }
  }
  loading.value = false
}

function toggleClass(id) {
  const index = form.classIds.indexOf(id)
  if (index >= 0) form.classIds.splice(index, 1)
  else form.classIds.push(id)
}

async function save() {
  saving.value = true
  error.value = ''
  notice.value = ''
  const referenceUrls = form.referenceUrlsText
    .split('\n').map(s => s.trim()).filter(Boolean)
  const payload = {
    title: form.title,
    project: form.project,
    objective: form.objective,
    requirement: form.requirement,
    acceptance: form.acceptance,
    referenceUrls,
    deadline: toApiTime(form.deadline),
    fullScore: Number(form.fullScore),
    allowLate: form.allowLate,
    classIds: [...form.classIds]
  }
  try {
    if (editing.value) {
      await teaching.updateTask(route.params.id, payload)
      notice.value = '已保存。'
    } else {
      const created = await teaching.createTask(payload)
      notice.value = '任务已创建为草稿，确认内容后请点击“发布”。'
      router.replace(`/teacher/tasks/${created.id}`)
    }
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-head">
    <div>
      <span class="eyebrow">教师 · 实训任务</span>
      <h1>{{ editing ? '编辑任务' : '新建任务' }}</h1>
      <p>保存后为草稿；发布前学生看不到。发布需要至少选择一个班级。</p>
    </div>
    <RouterLink class="button secondary" to="/teacher/tasks">返回任务列表</RouterLink>
  </section>

  <p v-if="loading" class="hint">正在加载…</p>
  <form v-else class="panel form-grid" @submit.prevent="save">
    <label>
      <span>任务标题</span>
      <input :value="privacyText(form.title)" @input="form.title = $event.target.value" maxlength="200" required placeholder="例如：完成抢票报名接口的联调" />
    </label>

    <label>
      <span>所属实训项目</span>
      <select v-model="form.project">
        <option value="TICKET">校园抢票（TICKET）</option>
        <option value="REPAIR">校园设备报修（REPAIR）</option>
      </select>
    </label>

    <label class="wide">
      <span>教学目标</span>
      <textarea :value="privacyText(form.objective)" @input="form.objective = $event.target.value" rows="3" placeholder="本次任务希望学生掌握的能力"></textarea>
    </label>

    <label class="wide">
      <span>任务要求</span>
      <textarea :value="privacyText(form.requirement)" @input="form.requirement = $event.target.value" rows="6" required placeholder="支持 Markdown，例如：&#10;- 使用 POST /api/practice/{accessCode}/ticket/records 报名&#10;- 处理名额不足的情况"></textarea>
    </label>

    <label class="wide">
      <span>验收标准</span>
      <textarea :value="privacyText(form.acceptance)" @input="form.acceptance = $event.target.value" rows="4" placeholder="例如：接口返回 200，数据库出现对应报名记录"></textarea>
    </label>

    <label class="wide">
      <span>参考资料链接（每行一条，仅 http/https，最多 10 条）</span>
      <textarea v-model="form.referenceUrlsText" rows="3" placeholder="https://example.com/doc"></textarea>
    </label>

    <label>
      <span>截止时间</span>
      <input v-model="form.deadline" type="datetime-local" required />
    </label>

    <label>
      <span>满分</span>
      <input v-model.number="form.fullScore" type="number" min="1" max="1000" required />
    </label>

    <label class="inline">
      <input v-model="form.allowLate" type="checkbox" />
      <span>允许逾期提交（逾期会在成果上标记）</span>
    </label>

    <fieldset class="wide">
      <legend>分配到班级（可多选）</legend>
      <p v-if="!classes.length" class="hint">没有可选班级，请先在“班级管理”中创建。</p>
      <label v-for="item in classes" :key="item.id" class="inline">
        <input type="checkbox" :checked="form.classIds.includes(item.id)" @change="toggleClass(item.id)" />
        <span>{{ aliasClassName(item.className, item.id) }}（{{ item.id }}）</span>
      </label>
    </fieldset>

    <p v-if="error" class="hint error wide">{{ error }}</p>
    <p v-if="notice" class="hint wide">{{ notice }}</p>

    <div class="wide actions">
      <button class="button" type="submit" :disabled="saving">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </div>
  </form>
</template>
