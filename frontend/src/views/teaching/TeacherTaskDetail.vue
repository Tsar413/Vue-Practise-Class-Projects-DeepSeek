<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { teaching, errorText } from '../../api/teaching'
import { markdown } from '../../composables/markdown'
import { personName, privacyText } from '../../utils/privacy'

const route = useRoute()
const task = ref(null)
const stats = ref(null)
const rows = ref([])
const classFilter = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const detail = ref(null)
const evaluateForm = ref({ submissionId: null, versionId: null, decision: 'PASS', score: null, comment: '' })

const stateText = {
  NOT_SUBMITTED: '未提交',
  DRAFT: '草稿',
  SUBMITTED: '待评价',
  EVALUATED: '已评价',
  REVISING: '需修改'
}
const stateClass = {
  NOT_SUBMITTED: 'tag-closed',
  DRAFT: 'tag-draft',
  SUBMITTED: 'tag-open',
  EVALUATED: 'tag-done',
  REVISING: 'tag-warn'
}

const objectiveHtml = computed(() => markdown(task.value?.objective || ''))
const requirementHtml = computed(() => markdown(task.value?.requirement || ''))
const acceptanceHtml = computed(() => markdown(task.value?.acceptance || ''))
const versions = computed(() => detail.value?.versions || [])

async function load() {
  loading.value = true
  error.value = ''
  try {
    task.value = await teaching.taskDetail(route.params.id)
    stats.value = await teaching.taskStats(route.params.id)
    rows.value = await teaching.taskSubmissions(route.params.id, classFilter.value) || []
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function changeStatus(action) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    task.value = await teaching.changeTaskStatus(route.params.id, action)
    notice.value = action === 'PUBLISH' ? '任务已发布，学生现在可以看到。' : '任务已关闭；已有提交、截图与评价全部保留。'
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function openSubmission(submissionId) {
  error.value = ''
  try {
    detail.value = await teaching.submissionDetail(submissionId)
    const latest = detail.value.versions?.[0]
    evaluateForm.value = {
      submissionId,
      versionId: latest?.id ?? null,
      decision: 'PASS',
      score: latest ? task.value.fullScore : null,
      comment: ''
    }
  } catch (e) {
    error.value = errorText(e)
  }
}

async function submitEvaluation() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const payload = {
      submissionId: evaluateForm.value.submissionId,
      versionId: evaluateForm.value.versionId,
      decision: evaluateForm.value.decision,
      comment: evaluateForm.value.comment
    }
    if (evaluateForm.value.decision === 'PASS') payload.score = Number(evaluateForm.value.score)
    await teaching.evaluate(payload)
    notice.value = '评价已保存。'
    detail.value = await teaching.submissionDetail(evaluateForm.value.submissionId)
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function openImage(id) {
  try {
    const url = await teaching.attachmentBlob(id)
    window.open(url, '_blank', 'noopener')
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (e) {
    error.value = errorText(e)
  }
}

onMounted(load)
</script>

<template>
  <section class="page-head">
    <div>
      <span class="eyebrow">教师 · 任务详情</span>
      <h1>{{ privacyText(task?.title || '任务') }}</h1>
      <p v-if="task">
        {{ task.project === 'TICKET' ? '校园抢票' : '校园设备报修' }} ·
        截止 {{ task.deadline }} · 满分 {{ task.fullScore }} ·
        {{ task.allowLate ? '允许逾期' : '不允许逾期' }} ·
        状态 {{ ['草稿', '已发布', '已关闭'][task.status] }}
      </p>
    </div>
    <div class="actions">
      <RouterLink class="button secondary" :to="`/teacher/tasks/${route.params.id}/edit`">编辑</RouterLink>
      <button v-if="task?.status === 0" class="button" :disabled="busy" @click="changeStatus('PUBLISH')">发布</button>
      <button v-if="task?.status === 1" class="button secondary" :disabled="busy" @click="changeStatus('CLOSE')">关闭</button>
    </div>
  </section>

  <p v-if="loading" class="hint">正在加载…</p>
  <p v-if="error" class="hint error">{{ error }}</p>
  <p v-if="notice" class="hint">{{ notice }}</p>

  <section v-if="task" class="panel">
    <template v-if="task.objective">
      <h2>教学目标</h2>
      <div class="rich" v-html="objectiveHtml"></div>
    </template>
    <h2>任务要求</h2>
    <div class="rich" v-html="requirementHtml"></div>
    <template v-if="task.acceptance">
      <h2>验收标准</h2>
      <div class="rich" v-html="acceptanceHtml"></div>
    </template>
    <p v-if="task.referenceUrls?.length">
      参考资料：
      <a v-for="url in task.referenceUrls" :key="url" :href="url" target="_blank" rel="noopener noreferrer">{{ url }}</a>
    </p>
    <p>分配班级：{{ (task.classIds || []).join('、') || '未分配' }}</p>
  </section>

  <section v-if="stats" class="panel">
    <div class="section-heading">
      <h2>提交情况</h2>
      <span>应提交 {{ stats.expectedCount }} · 未提交 {{ stats.notSubmittedCount }} · 待评价 {{ stats.pendingCount }} · 已评价 {{ stats.evaluatedCount }} · 需修改 {{ stats.revisingCount }}</span>
    </div>
    <label class="inline">
      <span>按班级筛选</span>
      <select v-model="classFilter" @change="load">
        <option value="">全部班级</option>
        <option v-for="id in task.classIds" :key="id" :value="id">{{ id }}</option>
      </select>
    </label>
    <table class="table">
      <thead>
        <tr><th>学号</th><th>姓名</th><th>班级</th><th>状态</th><th>版本</th><th>逾期</th><th>评分</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.studentId">
          <td>{{ row.studentId }}</td>
          <td>{{ personName(row.studentName, { role: 'STUDENT', id: row.studentId }) }}</td>
          <td>{{ row.classId }}</td>
          <td><span class="tag" :class="stateClass[row.state]">{{ stateText[row.state] }}</span></td>
          <td>{{ row.versionNo ? `第 ${row.versionNo} 版` : '—' }}</td>
          <td>{{ row.late ? '是' : '否' }}</td>
          <td>{{ row.latestScore ?? '—' }}{{ row.latestDecision === 'REVISE' ? '（需修改）' : '' }}</td>
          <td>
            <button v-if="row.submissionId" class="text-button" @click="openSubmission(row.submissionId)">查看/评价</button>
          </td>
        </tr>
      </tbody>
    </table>
  </section>

  <section v-if="detail" class="panel">
    <div class="section-heading">
      <h2>{{ personName(detail.studentName, { role: 'STUDENT', id: detail.studentId }) }}（{{ detail.studentId }}）的成果</h2>
      <span>{{ versions.length }} 个正式版本</span>
    </div>

    <article v-for="version in versions" :key="version.id" class="version-block">
      <header>
        <strong>第 {{ version.versionNo }} 版</strong>
        <span>{{ version.createTime }}<template v-if="version.late"> · 逾期提交</template></span>
      </header>
      <p v-if="version.projectUrl">成果链接：<a :href="version.projectUrl" target="_blank" rel="noopener noreferrer">{{ version.projectUrl }}</a></p>
      <p class="pre">{{ privacyText(version.content) }}</p>
      <p v-if="version.process" class="pre muted">问题与解决过程：{{ privacyText(version.process) }}</p>
      <div v-for="section in version.sections" :key="section.id || section.title" class="section-block">
        <h4>{{ privacyText(section.title) }}</h4>
        <p class="pre">{{ privacyText(section.content) }}</p>
        <div class="shot-row">
          <button v-for="shot in section.attachments" :key="shot.id" class="shot" @click="openImage(shot.id)">
            图片 {{ shot.id }}（{{ Math.round(shot.fileSize / 1024) }} KB）
          </button>
        </div>
      </div>
      <ul v-if="version.evaluations?.length" class="eval-list">
        <li v-for="evalItem in version.evaluations" :key="evalItem.id">
          {{ evalItem.decision === 'PASS' ? '通过' : '退回修改' }}
          <template v-if="evalItem.score !== null && evalItem.score !== undefined"> · {{ evalItem.score }} 分</template>
          · {{ personName(evalItem.teacherName, { role: 'TEACHER', id: evalItem.teacherId }) }} · {{ evalItem.createTime }}
          <div v-if="evalItem.comment" class="pre">{{ privacyText(evalItem.comment) }}</div>
        </li>
      </ul>
      <p v-else class="hint">该版本尚未评价。</p>
    </article>

    <form class="form-grid" @submit.prevent="submitEvaluation">
      <h3 class="wide">给出评价</h3>
      <label>
        <span>评价版本</span>
        <select v-model.number="evaluateForm.versionId">
          <option v-for="version in versions" :key="version.id" :value="version.id">第 {{ version.versionNo }} 版</option>
        </select>
      </label>
      <label>
        <span>结论</span>
        <select v-model="evaluateForm.decision">
          <option value="PASS">通过（给分）</option>
          <option value="REVISE">退回修改</option>
        </select>
      </label>
      <label v-if="evaluateForm.decision === 'PASS'">
        <span>分数（0 - {{ task.fullScore }}）</span>
        <input v-model.number="evaluateForm.score" type="number" min="0" :max="task.fullScore" required />
      </label>
      <label class="wide">
        <span>评语</span>
        <textarea v-model="evaluateForm.comment" rows="3" placeholder="指出完成情况与改进方向"></textarea>
      </label>
      <div class="wide actions">
        <button class="button" type="submit" :disabled="busy">保存评价</button>
      </div>
    </form>
  </section>
</template>
