import { system, errorText } from './client'

// 教学与 AI 辅导接口都走系统登录凭证（与实训访问码无关）。
// 所有数据归属由后端按登录身份判断，前端不提交学号。
//
// 注意：这里没有复用 client.unwrap，因为该函数只接受 body.code === 200，
// 而新建任务、上传截图、正式提交按 REST 语义返回 201，会被误判为失败。
// 本模块接受 200 与 201，其它 code 一律抛出后端 message。
const OK_CODES = new Set([200, 201])

function unwrapAccepting(response) {
  const body = response?.data
  if (body && typeof body === 'object' && 'code' in body) {
    if (!OK_CODES.has(body.code)) throw new Error(body.message || '请求未成功')
    return body.data
  }
  return body
}

const call = async (method, url, data, config = {}) =>
  unwrapAccepting(await system.request({ method, url, data, ...config }))

export const teaching = {
  listTasks: () => call('get', '/api/teaching/tasks'),
  taskDetail: id => call('get', `/api/teaching/tasks/${encodeURIComponent(id)}`),
  createTask: payload => call('post', '/api/teaching/tasks', payload),
  updateTask: (id, payload) => call('put', `/api/teaching/tasks/${encodeURIComponent(id)}`, payload),
  changeTaskStatus: (id, action) =>
    call('post', `/api/teaching/tasks/${encodeURIComponent(id)}/status`, { action }),
  taskStats: id => call('get', `/api/teaching/tasks/${encodeURIComponent(id)}/stats`),
  taskSubmissions: (id, classId) =>
    call('get', `/api/teaching/tasks/${encodeURIComponent(id)}/submissions`, null,
      { params: classId ? { classId } : {} }),
  submissionDetail: id => call('get', `/api/teaching/submissions/${encodeURIComponent(id)}`),
  evaluate: payload => call('post', '/api/teaching/evaluations', payload),

  mySubmission: taskId => call('get', `/api/teaching/tasks/${encodeURIComponent(taskId)}/submission`),
  saveDraft: (taskId, payload) =>
    call('put', `/api/teaching/tasks/${encodeURIComponent(taskId)}/draft`, payload),
  submit: (taskId, payload) =>
    call('post', `/api/teaching/tasks/${encodeURIComponent(taskId)}/submissions`, payload),
  myAttachments: taskId => call('get', `/api/teaching/tasks/${encodeURIComponent(taskId)}/attachments`),
  deleteAttachment: id => call('delete', `/api/teaching/attachments/${encodeURIComponent(id)}`),

  async uploadAttachment(taskId, file) {
    const form = new FormData()
    form.append('file', file)
    // 不显式设置 Content-Type，交给浏览器带上 multipart 边界
    return call('post', `/api/teaching/tasks/${encodeURIComponent(taskId)}/attachments`, form)
  },

  async attachmentBlob(id) {
    const response = await system.get(
      `/api/teaching/attachments/${encodeURIComponent(id)}/content`,
      { responseType: 'blob', validateStatus: () => true })
    if (response.status >= 400 || response.data?.type?.includes('json')) {
      let message = '截图读取失败'
      try {
        message = JSON.parse(await response.data.text()).message || message
      } catch {
        // 响应体不是 JSON 时保留默认提示
      }
      throw new Error(message)
    }
    return URL.createObjectURL(response.data)
  }
}

// AI 辅导：后端整条链路的读取超时是 30s（可配置到 60s），axios 默认 30s 会先超时，
// 造成「前端报超时、后端其实还在算」的重复请求。
// 因此这里单独把客户端超时放宽到 90s，减少无谓的前端提前超时。
// 注意：放宽超时并不等于重复调用被完全避免——失败（含超时）后后端会移除幂等键，
// 此时重试可能真的再次调用模型，无法保证供应商侧未计费。
const AI_TIMEOUT_MS = 90_000

export const aiTutor = {
  conversations: () => call('get', '/api/ai-tutor/conversations'),
  createConversation: payload => call('post', '/api/ai-tutor/conversations', payload),
  deleteConversation: id => call('delete', `/api/ai-tutor/conversations/${encodeURIComponent(id)}`),
  messages: id =>
    call('get', `/api/ai-tutor/conversations/${encodeURIComponent(id)}/messages`),
  /**
   * payload.requestKey 由调用方生成并在重试时保持不变。
   * 实际保证范围：成功的回答在进程内按该键缓存，同一键的在途请求复用首个结果；
   * 请求失败后后端会移除该键，重试可能再次调用模型，无法保证供应商未计费。
   */
  ask: payload => call('post', '/api/ai-tutor/ask', payload, { timeout: AI_TIMEOUT_MS })
}

export { errorText }
