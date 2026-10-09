<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { teaching, errorText } from '../../api/teaching'
import { markdown } from '../../composables/markdown'

const route = useRoute()
const task = ref(null)
const submission = ref(null)
const attachments = ref([])
const loading = ref(false)
const busy = ref(false)
const uploading = ref(false)
const error = ref('')
const notice = ref('')

const form = ref({ projectUrl: '', content: '', process: '' })
const sections = ref([{ title: '实现效果', content: '', attachmentIds: [] }])

const objectiveHtml = computed(() => markdown(task.value?.objective || ''))
const requirementHtml = computed(() => markdown(task.value?.requirement || ''))
const acceptanceHtml = computed(() => markdown(task.value?.acceptance || ''))
const versions = computed(() => submission.value?.versions || [])
const submitAllowed = computed(() => Boolean(task.value?.submitAllowed))
const closed = computed(() => task.value?.status === 2)

function newRequestKey() {
  const random = crypto.getRandomValues(new Uint8Array(12))
  return 'sub-' + Array.from(random, b => b.toString(16).padStart(2, '0')).join('')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    task.value = await teaching.taskDetail(route.params.id)
    submission.value = await teaching.mySubmission(route.params.id)
    attachments.value = await teaching.myAttachments(route.params.id) || []
    const draft = submission.value?.draft
    if (draft) {
      // 有草稿：直接用草稿恢复（字段 + 章节 + 截图归属）
      form.value = {
        projectUrl: draft.projectUrl || '',
        content: draft.content || '',
        process: draft.process || ''
      }
      if (draft.sections?.length) {
        sections.value = draft.sections.map(s => ({
          title: s.title,
          content: s.content,
          attachmentIds: (s.attachments || []).map(a => a.id)
        }))
      }
    } else if (submission.value?.versions?.length) {
      // 没有草稿但已提交过（例如被退回、或刷新后再进来）：
      // 用最新版本预填，学生只需改需要改的部分，保留原有截图再次提交。
      const latest = submission.value.versions[0]
      form.value = {
        projectUrl: latest.projectUrl || '',
        content: latest.content || '',
        process: latest.process || ''
      }
      if (latest.sections?.length) {
        sections.value = latest.sections.map(s => ({
          title: s.title,
          content: s.content,
          attachmentIds: (s.attachments || []).map(a => a.id)
        }))
      }
      notice.value = submission.value.status === 3
        ? `已根据第 ${latest.versionNo} 版预填内容，修改后点击“正式提交”即可再次提交（原截图可继续使用）。`
        : `已根据第 ${latest.versionNo} 版预填内容；再次提交会生成新版本，旧评价仍保留。`
    }
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function addSection() {
  if (sections.value.length >= 20) return
  sections.value.push({ title: `章节 ${sections.value.length + 1}`, content: '', attachmentIds: [] })
}

function removeSection(index) {
  if (sections.value.length <= 1) return
  sections.value.splice(index, 1)
}

function toggleAttachment(section, id) {
  const index = section.attachmentIds.indexOf(id)
  if (index >= 0) section.attachmentIds.splice(index, 1)
  else section.attachmentIds.push(id)
}

async function upload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  uploading.value = true
  error.value = ''
  try {
    const saved = await teaching.uploadAttachment(route.params.id, file)
    attachments.value = [saved, ...attachments.value]
    if (!sections.value[0].attachmentIds.includes(saved.id)) {
      sections.value[0].attachmentIds.push(saved.id)
    }
    notice.value = '截图已上传，记得保存草稿或提交。'
  } catch (e) {
    error.value = errorText(e)
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

async function removeAttachment(id) {
  error.value = ''
  try {
    await teaching.deleteAttachment(id)
    attachments.value = attachments.value.filter(a => a.id !== id)
    sections.value.forEach(s => {
      s.attachmentIds = s.attachmentIds.filter(item => item !== id)
    })
  } catch (e) {
    error.value = errorText(e)
  }
}

function payload() {
  return {
    projectUrl: form.value.projectUrl,
    content: form.value.content,
    process: form.value.process,
    sections: sections.value.map((s, index) => ({
      title: s.title || `章节 ${index + 1}`,
      content: s.content,
      sortOrder: index + 1,
      attachmentIds: [...s.attachmentIds]
    }))
  }
}

async function saveDraft() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    submission.value = await teaching.saveDraft(route.params.id, payload())
    notice.value = '草稿已保存，下次进入会自动恢复。'
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

// 一次提交尝试固定使用同一个 requestKey：
// 网络超时后用户重试（或前端重试）不会生成第二个版本，后端据此幂等。
const activeRequestKey = ref('')

async function submit() {
  const totalMarks = form.value.projectUrl.trim() || sections.value.some(s => s.attachmentIds.length)
  if (!totalMarks) {
    error.value = '请至少填写有效的成果链接或上传一张截图。'
    return
  }
  if (!form.value.content.trim()) {
    error.value = '完成说明不能为空。'
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (!activeRequestKey.value) activeRequestKey.value = newRequestKey()
    const body = { ...payload(), requestKey: activeRequestKey.value }
    submission.value = await teaching.submit(route.params.id, body)
    // 提交成功后清空，下一次修改再提交时使用新的键
    activeRequestKey.value = ''
    notice.value = '提交成功，等待教师评价。旧评价仍保留在对应版本上。'
    attachments.value = await teaching.myAttachments(route.params.id) || []
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
      <span class="eyebrow">学生 · 任务详情</span>
      <h1>{{ task?.title || '任务' }}</h1>
      <p v-if="task">
        截止 {{ task.deadline }}
        <template v-if="task.expired"> · 已过截止{{ task.allowLate ? '（允许逾期，提交会标记逾期）' : '（不允许逾期提交）' }}</template>
        · 满分 {{ task.fullScore }}
      </p>
    </div>
    <RouterLink class="button secondary" to="/tasks">返回任务列表</RouterLink>
  </section>

  <p v-if="loading" class="hint">正在加载…</p>
  <p v-if="error" class="hint error">{{ error }}</p>
  <p v-if="notice" class="hint">{{ notice }}</p>
  <p v-if="closed" class="hint">任务已关闭，只能查看历史与评价，不能再提交或修改。</p>

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
    <p class="hint">
      我的状态：{{ submission?.versionNo ? `第 ${submission.versionNo} 版` : '尚未正式提交' }}
      <template v-if="submission?.status === 2"> · 已通过（{{ submission.currentEvaluation?.score }} 分）</template>
      <template v-else-if="submission?.status === 3"> · 需修改，请根据评语调整后重新提交</template>
      <template v-else-if="submission?.versionNo"> · 待评价</template>
    </p>
    <RouterLink class="text-button" :to="`/tutor?project=${task.project}&taskId=${task.id}`">遇到问题？打开 AI 实训辅导 →</RouterLink>
  </section>

  <section v-if="task && !closed" class="panel">
    <div class="section-heading">
      <h2>我的成果</h2>
      <span>草稿可反复保存；正式提交会生成不可覆盖的新版本</span>
    </div>

    <div class="form-grid">
      <label class="wide">
        <span>成果项目或仓库链接（http/https）</span>
        <input v-model.trim="form.projectUrl" placeholder="https://github.com/..." />
      </label>
      <label class="wide">
        <span>完成说明（必填）</span>
        <textarea v-model="form.content" rows="5" placeholder="说明你完成了哪些功能、关键接口与结果"></textarea>
      </label>
      <label class="wide">
        <span>问题与解决过程</span>
        <textarea v-model="form.process" rows="4" placeholder="遇到什么问题、如何定位与解决"></textarea>
      </label>
    </div>

    <h3>成果截图</h3>
    <p class="hint">仅支持 JPEG/PNG/WebP，单张不超过 5MB。已随正式版本提交的截图不会被删除。</p>
    <input type="file" accept="image/png,image/jpeg,image/webp" :disabled="uploading" @change="upload" />
    <ul class="shot-list">
      <li v-for="item in attachments" :key="item.id">
        <button class="text-button" @click="openImage(item.id)">{{ item.originalName }}</button>
        <span class="muted">{{ Math.round(item.fileSize / 1024) }} KB</span>
        <button class="text-button danger" @click="removeAttachment(item.id)">删除</button>
      </li>
    </ul>

    <h3>章节与截图归属</h3>
    <article v-for="(section, index) in sections" :key="index" class="section-block">
      <label>
        <span>章节标题</span>
        <input v-model.trim="section.title" maxlength="200" />
      </label>
      <label>
        <span>章节说明</span>
        <textarea v-model="section.content" rows="3"></textarea>
      </label>
      <div class="shot-row">
        <label v-for="item in attachments" :key="item.id" class="inline">
          <input type="checkbox" :checked="section.attachmentIds.includes(item.id)" @change="toggleAttachment(section, item.id)" />
          <span>{{ item.originalName }}</span>
        </label>
      </div>
      <button class="text-button danger" :disabled="sections.length <= 1" @click="removeSection(index)">删除该章节</button>
    </article>
    <button class="button secondary" :disabled="sections.length >= 20" @click="addSection">添加章节</button>

    <div class="actions">
      <button class="button secondary" :disabled="busy || !submitAllowed" @click="saveDraft">
        {{ busy ? '处理中…' : '保存草稿' }}
      </button>
      <button class="button" :disabled="busy || !submitAllowed" @click="submit">
        {{ busy ? '处理中…' : '正式提交' }}
      </button>
    </div>
    <p v-if="!submitAllowed" class="hint">当前不可提交：任务已关闭或已过截止且不允许逾期。</p>
  </section>

  <section v-if="versions.length" class="panel">
    <div class="section-heading">
      <h2>历史版本与评价</h2>
      <span>共 {{ versions.length }} 版，旧评价保留在原版本上</span>
    </div>
    <article v-for="version in versions" :key="version.id" class="version-block">
      <header>
        <strong>第 {{ version.versionNo }} 版</strong>
        <span>{{ version.createTime }}<template v-if="version.late"> · 逾期提交</template></span>
      </header>
      <p v-if="version.projectUrl">链接：<a :href="version.projectUrl" target="_blank" rel="noopener noreferrer">{{ version.projectUrl }}</a></p>
      <p class="pre">{{ version.content }}</p>
      <p v-if="version.process" class="pre muted">问题与解决过程：{{ version.process }}</p>
      <div v-for="section in version.sections" :key="section.id || section.title" class="section-block">
        <h4>{{ section.title }}</h4>
        <p class="pre">{{ section.content }}</p>
        <div class="shot-row">
          <button v-for="shot in section.attachments" :key="shot.id" class="shot" @click="openImage(shot.id)">
            {{ shot.originalName }}
          </button>
        </div>
      </div>
      <ul v-if="version.evaluations?.length" class="eval-list">
        <li v-for="evalItem in version.evaluations" :key="evalItem.id">
          {{ evalItem.decision === 'PASS' ? '通过' : '退回修改' }}
          <template v-if="evalItem.score !== null && evalItem.score !== undefined"> · {{ evalItem.score }} 分</template>
          · {{ evalItem.createTime }}
          <div v-if="evalItem.comment" class="pre">{{ evalItem.comment }}</div>
        </li>
      </ul>
      <p v-else class="hint">该版本尚未评价。</p>
    </article>
  </section>
</template>
