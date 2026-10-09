import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 任务要求、AI 回答等都是不可信输入：渲染前必须清洗，避免 XSS。
export function markdown(text) {
  if (!text) return ''
  const html = marked.parse(String(text), { breaks: true, async: false })
  return DOMPurify.sanitize(html)
}

export function plain(text) {
  return text == null ? '' : String(text)
}
