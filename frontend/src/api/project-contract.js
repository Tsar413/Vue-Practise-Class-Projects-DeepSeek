// 拒绝元数据不匹配的接口，而不是用 split('/' + project) 猜测地址
export function projectSuffix(project, path) {
  if (!['ticket', 'repair'].includes(project)) throw new Error('项目不存在')
  const prefix = `/api/practice/{accessCode}/${project}`
  if (!path.startsWith(prefix + '/')) throw new Error('接口路径与所属项目不一致，已阻止发送。')
  return path.slice(prefix.length)
}
