<script setup>
import { privacyText } from '../../utils/privacy'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { teaching, errorText } from '../../api/teaching'
import { useAuth } from '../../stores/auth'
import { markdown } from '../../composables/markdown'

const auth = useAuth()
const tasks = ref([])
const loading = ref(false)
const error = ref('')

const statusText = { 0: '草稿', 1: '已发布', 2: '已关闭' }
const statusClass = { 0: 'tag-draft', 1: 'tag-open', 2: 'tag-closed' }

const rows = computed(() => tasks.value.map(t => ({
  ...t,
  statusLabel: statusText[t.status] ?? '未知',
  statusClass: statusClass[t.status] ?? '',
  deadlineText: t.deadline || '—',
  requirementHtml: markdown(t.requirement || '')
})))

async function load() {
  loading.value = true
  error.value = ''
  try {
    tasks.value = await teaching.listTasks() || []
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-head">
    <div>
      <span class="eyebrow">{{ auth.teacher ? '发布任务 · 组织实训' : '我的实训任务' }}</span>
      <h1>{{ auth.teacher ? '实训任务' : '老师布置的任务' }}</h1>
      <p v-if="auth.teacher">新建任务、安排班级、发布后学生即可查看并提交成果。</p>
      <p v-else>这里只显示分配给本班且已发布的任务；已关闭的任务可以查看历史与自己的评价。</p>
    </div>
    <RouterLink v-if="auth.teacher" class="button" to="/teacher/tasks/new">新建任务 →</RouterLink>
  </section>

  <p v-if="loading" class="hint">正在加载任务…</p>
  <p v-else-if="error" class="hint error">{{ error }}</p>
  <p v-else-if="!rows.length" class="hint">
    {{ auth.teacher ? '还没有任务，点击右上角新建一个吧。' : '当前没有分配给本班的任务。' }}
  </p>

  <div v-else class="task-grid">
    <RouterLink
      v-for="task in rows"
      :key="task.id"
      class="task-card"
      :to="auth.teacher ? `/teacher/tasks/${task.id}` : `/tasks/${task.id}`">
      <div class="task-card-head">
        <span class="tag" :class="task.statusClass">{{ task.statusLabel }}</span>
        <span class="tag tag-project">{{ task.project === 'TICKET' ? '校园抢票' : '校园报修' }}</span>
      </div>
      <h2>{{ privacyText(task.title) }}</h2>
      <div class="task-card-body" v-html="task.requirementHtml"></div>
      <dl class="task-meta">
        <div><dt>截止</dt><dd>{{ task.deadlineText }}</dd></div>
        <div><dt>满分</dt><dd>{{ task.fullScore }}</dd></div>
        <div><dt>逾期</dt><dd>{{ task.allowLate ? '允许' : '不允许' }}</dd></div>
      </dl>
      <p v-if="!auth.teacher" class="task-state">
        我的状态：{{ task.myStatus === undefined || task.myStatus === null ? '未提交'
          : task.myVersionNo ? `第 ${task.myVersionNo} 版 · ${task.myDecision === 'PASS' ? `已通过 ${task.myScore} 分` : task.myDecision === 'REVISE' ? '需修改' : '待评价'}` : '草稿' }}
        <span v-if="task.expired" class="expired">已过截止</span>
      </p>
      <span class="link-text">{{ auth.teacher ? '查看提交情况 →' : '查看任务详情 →' }}</span>
    </RouterLink>
  </div>
</template>
