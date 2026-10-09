<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { aiTutor, teaching, errorText } from '../../api/teaching'
import { markdown } from '../../composables/markdown'
import { privacyText, privacyRef } from '../../utils/privacy'

const route = useRoute()
const conversations = ref([])
const activeId = ref(null)
const messages = ref([])
const loading = ref(false)
const asking = ref(false)
const error = ref('')
const notice = ref('')
const thread = ref(null)

const form = ref({
  question: '',
  codeSnippet: '',
  errorText: ''
})

const project = ref((route.query.project || 'TICKET').toString().toUpperCase())
const taskId = ref(route.query.taskId ? Number(route.query.taskId) : null)
const tasks = ref([])
const switching = ref(false)

// 本人可见任务（后端已按班级与发布状态过滤），用于在页面内选择任务
const myTasks = computed(() => tasks.value.filter(t => t.project === project.value))

const taskError = ref('')
const tasksLoading = ref(false)
// 任务列表是否已经成功加载过：用于区分「真的没有任务」与「加载失败」
const tasksLoaded = ref(false)

async function loadTasks() {
  tasksLoading.value = true
  taskError.value = ''
  try {
    tasks.value = await teaching.listTasks() || []
    tasksLoaded.value = true
  } catch (e) {
    // 不能把加载失败伪装成「没有任务」：清空并给出错误与重试入口
    tasks.value = []
    tasksLoaded.value = false
    taskError.value = errorText(e)
  } finally {
    tasksLoading.value = false
  }
}

// 程序内同步（openConversation 时回填 project/taskId）不应触发「转入新会话」
let syncingFromConversation = false

/**
 * 转入新会话草稿：
 * 用户更换项目或任务后，原来的 activeId 指向的会话已与新的 project/taskId 不匹配，
 * 若继续沿用会被后端按边界校验拒绝，因此这里显式脱离旧会话，不污染它的历史。
 */
function resetToNewSession() {
  if (!activeId.value) return
  // 不做任何确认框：切换项目/任务只是进入新会话草稿，旧会话完整保留在左侧列表，
  // 不存在不可逆动作，因此直接切换并用提示条说明即可（也避免原生弹窗阻塞验收）。
  activeId.value = null
  messages.value = []
  pendingKey.value = ''          // 新会话使用新的幂等键
  notice.value = '已切换到新会话草稿；发送提问时会自动创建会话，原会话仍保留在左侧列表。'
}

function onProjectChange() {
  // 换项目时清空不匹配的任务选择，避免把 TICKET 的会话指向 REPAIR 的任务
  if (!myTasks.value.some(t => t.id === taskId.value)) taskId.value = null
  if (!syncingFromConversation) resetToNewSession()
}

function onTaskChange() {
  if (!syncingFromConversation) resetToNewSession()
}


const activeConversation = computed(() =>
  conversations.value.find(item => item.id === activeId.value) || null)

function newRequestKey() {
  const random = crypto.getRandomValues(new Uint8Array(12))
  return 'ai-' + Array.from(random, b => b.toString(16).padStart(2, '0')).join('')
}

// 同一条提问固定使用同一个 requestKey。
// 实际保证范围：① 成功结果在进程内按 requestKey 缓存；② 同一 requestKey 的在途请求复用首个结果。
// 若请求失败（超时/上游异常），后端会移除该幂等键，因此失败后重试**可能再次调用模型**，
// 不保证供应商侧未计费；提交前请先查看会话记录确认上一次是否已经产生回答。
const pendingKey = ref('')

// 进入页面时只列出会话：
//   * 带 project/taskId 从任务或接口文档跳进来时，不自动打开无关的旧会话（避免项目/任务不匹配被后端拒绝）；
//   * 不带参数时也不自动打开，避免误开出上一个项目的会话。
async function loadConversations() {
  error.value = ''
  try {
    conversations.value = await aiTutor.conversations() || []
  } catch (e) {
    error.value = errorText(e)
  }
}

async function openConversation(id) {
  if (asking.value || switching.value) return   // 在途提问时不允许切换，避免串显示
  const target = conversations.value.find(item => item.id === id)
  if (!target) return
  switching.value = true
  error.value = ''
  notice.value = ''
  try {
    // 会话的 project/taskId 以服务端记录为准：切换会话必须同步，否则后续提问会因边界不符被拒。
    // 这里回填不应被当成「用户切换项目/任务」，因此加同步标记。
    syncingFromConversation = true
    project.value = target.project || project.value
    taskId.value = target.taskId ?? null
    syncingFromConversation = false
    activeId.value = id
    pendingKey.value = ''       // 换了会话就换新的幂等键，避免沿用上一条提问的键
    messages.value = await aiTutor.messages(id) || []
    await scrollToEnd()
  } catch (e) {
    activeId.value = null
    messages.value = []
    error.value = errorText(e)
  } finally {
    switching.value = false
  }
}

async function createConversation() {
  if (asking.value || switching.value) return   // 模型在途或已有新建/切换在途时不允许再发
  switching.value = true                        // 在 await 之前置位，防止连续点击创建多个会话
  error.value = ''
  notice.value = ''
  try {
    const created = await aiTutor.createConversation({
      project: project.value,
      taskId: taskId.value,
      title: form.value.question ? form.value.question.slice(0, 40) : '新的辅导会话'
    })
    conversations.value = [created, ...conversations.value]
    activeId.value = created.id
    messages.value = []
    pendingKey.value = ''      // 新会话使用新的幂等键
  } catch (e) {
    error.value = errorText(e)
  } finally {
    switching.value = false
  }
}

async function removeConversation(id) {
  error.value = ''
  try {
    await aiTutor.deleteConversation(id)
    conversations.value = conversations.value.filter(item => item.id !== id)
    if (activeId.value === id) {
      activeId.value = null
      messages.value = []
      pendingKey.value = ''
    }
  } catch (e) {
    error.value = errorText(e)
  }
}

async function scrollToEnd() {
  await nextTick()
  thread.value?.scrollTo({ top: thread.value.scrollHeight, behavior: 'smooth' })
}

async function ask() {
  if (!form.value.question.trim()) {
    error.value = '请先描述你遇到的问题。'
    return
  }
  asking.value = true
  error.value = ''
  notice.value = ''
  if (!pendingKey.value) pendingKey.value = newRequestKey()
  try {
    const answer = await aiTutor.ask({
      conversationId: activeId.value,
      project: project.value,
      taskId: taskId.value,
      question: form.value.question,
      codeSnippet: form.value.codeSnippet,
      errorText: form.value.errorText,
      requestKey: pendingKey.value
    })
    pendingKey.value = ''
    if (!answer) return
    if (answer.conversationId && answer.conversationId !== activeId.value) {
      activeId.value = answer.conversationId
      await loadConversations()
    }
    if (!answer.configured) {
      notice.value = answer.notice || 'AI辅导暂未配置'
    }
    messages.value = await aiTutor.messages(activeId.value) || []
    form.value.question = ''
    form.value.codeSnippet = ''
    form.value.errorText = ''
    await scrollToEnd()
  } catch (e) {
    // 保留 requestKey 原样重试；失败时后端已移除该幂等键，
    // 因此这里不承诺「重试不会再次调用模型」，提示语如实说明需先核对会话记录。
    error.value = `${errorText(e)}（请求未完成，请先查看会话记录；超时后重试可能再次调用模型）`
  } finally {
    asking.value = false
  }
}

onMounted(async () => {
  await loadTasks()
  await loadConversations()
})
</script>

<template>
  <section class="page-head">
    <div>
      <span class="eyebrow">学生 · AI 实训辅导</span>
      <h1>AI 实训辅导</h1>
      <p>回答只针对当前项目（{{ project === 'TICKET' ? '校园抢票' : '校园设备报修' }}）的接口与你的任务要求，并会列出实际参考来源。</p>
    </div>
    <RouterLink v-if="taskId" class="button secondary" :to="`/tasks/${taskId}`">返回任务</RouterLink>
  </section>

  <section class="panel">
    <div class="form-grid">
      <label>
        <span>辅导项目</span>
        <select v-model="project" :disabled="asking || switching" @change="onProjectChange">
          <option value="TICKET">校园抢票（TICKET）</option>
          <option value="REPAIR">校园设备报修（REPAIR）</option>
        </select>
      </label>
      <label>
        <span>关联任务（可选）</span>
        <select v-model="taskId" :disabled="asking || switching || tasksLoading || Boolean(taskError) || !tasksLoaded" @change="onTaskChange">
          <option :value="null">{{ tasksLoading ? '正在加载任务…' : '不关联具体任务' }}</option>
          <option v-for="item in myTasks" :key="item.id" :value="item.id">
            {{ privacyText(item.title) }}{{ item.status === 2 ? '（已关闭）' : '' }}
          </option>
        </select>
      </label>
    </div>
    <p v-if="taskError" class="hint error">
      任务列表加载失败，请重试
      <button class="text-button" :disabled="tasksLoading" @click="loadTasks">
        {{ tasksLoading ? '重试中…' : '重试' }}
      </button>
    </p>
    <p v-else-if="tasksLoading" class="hint">正在加载你的任务列表…</p>
    <p v-else-if="tasksLoaded && !myTasks.length" class="hint">
      当前项目下没有可关联的任务，可以不带任务直接提问。
    </p>
    <p v-else class="hint">
      选择的项目与任务决定 AI 参考哪些接口文档与任务要求；提问前请确认与你要解决的问题一致。
    </p>
  </section>

  <p v-if="notice" class="hint">{{ notice }}</p>
  <p v-if="error" class="hint error">{{ error }}</p>

  <div class="tutor-layout">
    <aside class="panel">
      <div class="section-heading">
        <h2>我的会话</h2>
        <button class="text-button" :disabled="asking || switching" @click="createConversation">
          {{ asking ? '提问中…' : '新建' }}
        </button>
      </div>
      <p v-if="!conversations.length" class="hint">还没有会话，发送第一条提问即可自动创建。</p>
      <ul class="conv-list">
        <li v-for="item in conversations" :key="item.id" :class="{ active: item.id === activeId }">
          <button class="text-button" :disabled="asking || switching" @click="openConversation(item.id)">
            {{ privacyText(item.title) }}（{{ item.messageCount }}）
            <span class="muted">{{ item.project === 'REPAIR' ? '报修' : '抢票' }}{{ item.taskId ? ` · 任务${item.taskId}` : '' }}</span>
          </button>
          <button class="text-button danger" :disabled="asking" @click="removeConversation(item.id)">删除</button>
        </li>
      </ul>
      <p class="hint">教师看不到你的私聊内容。</p>
    </aside>

    <section class="panel tutor-thread">
      <div ref="thread" class="thread">
        <p v-if="!messages.length" class="hint">
          描述你遇到的问题即可。建议写清：想实现什么、用了哪个接口、报错信息是什么。
        </p>
        <article v-for="message in messages" :key="message.id" class="bubble" :class="message.role.toLowerCase()">
          <header>{{ message.role === 'USER' ? '我' : 'AI 辅导' }}</header>
          <div class="rich" v-html="markdown(message.content)"></div>
          <div v-if="message.contextRefs?.length" class="refs">
            <strong>参考来源</strong>
            <ul>
              <li v-for="ref in message.contextRefs" :key="`${ref.type}-${ref.id}-${ref.path}`">
                {{ privacyRef(ref).title || ref.id }}
                <span v-if="ref.path" class="muted">{{ privacyRef(ref).path }}</span>
              </li>
            </ul>
          </div>
        </article>
      </div>

      <div class="form-grid">
        <label class="wide">
          <span>你的问题</span>
          <textarea v-model="form.question" rows="3" maxlength="2000" placeholder="例如：报名接口返回 409，怎么排查？"></textarea>
        </label>
        <label class="wide">
          <span>相关代码片段（可选）</span>
          <textarea v-model="form.codeSnippet" rows="4" maxlength="4000" placeholder="粘贴出现问题的少量代码"></textarea>
        </label>
        <label class="wide">
          <span>报错信息（可选）</span>
          <textarea v-model="form.errorText" rows="3" maxlength="2000" placeholder="粘贴控制台或响应中的报错"></textarea>
        </label>
        <p class="hint wide">请勿粘贴口令、访问码或 Token；系统会在发送前自动脱敏。</p>
        <div class="wide actions">
          <button class="button" :disabled="asking" @click="ask">
            {{ asking ? 'AI 正在思考…' : '发送提问' }}
          </button>
        </div>
      </div>
    </section>
  </div>
</template>
