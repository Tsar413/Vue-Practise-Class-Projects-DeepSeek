// 冷却按「学生 + 用途」分别记录截止时间，两名学生之间互不影响。
export const deadlineKey = (userId, kind) => `practice:${encodeURIComponent(userId)}:${kind}:deadline`
export const remaining = (deadline, now = Date.now()) => Math.max(0, Math.ceil((Number(deadline) - now) / 1000))
export const formatRemaining = seconds =>
  `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
