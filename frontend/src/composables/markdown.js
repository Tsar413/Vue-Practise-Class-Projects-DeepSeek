import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { privacyText } from '../utils/privacy'

// 任务要求、AI 回答等都是不可信输入：渲染前必须清洗，避免 XSS。
// 顺序：先做展示脱敏（专有名称 -> 中性代称），再解析 Markdown，最后 DOMPurify 清洗。
// 注意不要调换顺序：脱敏必须在 HTML 生成之前完成，否则标记里的文本可能绕过替换。
export function markdown(text) {
  if (!text) return ''
  const html = marked.parse(privacyText(String(text)), { breaks: true, async: false })
  return DOMPurify.sanitize(html)
}

export function plain(text) {
  return text == null ? '' : String(text)
}
