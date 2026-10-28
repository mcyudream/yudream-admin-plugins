/** Validate before queuing any file so a bad selection cannot partially upload. */
export function uploadRelativePath(value: string): string {
  if (!value || value.length > 1024 || /[\\\u0000-\u001f\u007f:]/.test(value)
    || value.split('/').some(part => !part || part === '.' || part === '..' || part.startsWith('.mcpanel'))) {
    throw new Error('上传路径必须是相对路径，不能包含空目录、..、反斜杠或面板保留目录')
  }
  return value
}

export function folderUploadPlan(files: Array<Pick<File, 'name' | 'size' | 'webkitRelativePath'>>) {
  if (!files.length || files.length > 10_000) throw new Error('请选择文件夹，每次最多上传 10000 个文件')
  const seen = new Set<string>()
  return files.map(file => {
    const path = uploadRelativePath(file.webkitRelativePath || file.name)
    const key = path.toLocaleLowerCase()
    if (seen.has(key)) throw new Error(`选择中存在重复路径：${path}`)
    if (file.size > 2 * 1024 ** 3) throw new Error(`${path} 超过单文件 2GiB 上限，请使用 SFTP`)
    seen.add(key)
    return { path, size: file.size }
  })
}
