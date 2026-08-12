export function zipValidationError(file: Pick<File, 'name' | 'type'>): string | null {
  const name = file.name.trim()
  if (!name.toLowerCase().endsWith('.zip')) {
    return '请选择 ZIP 格式的地图文件'
  }
  if (file.type && !['application/zip', 'application/x-zip-compressed', 'multipart/x-zip'].includes(file.type)) {
    return '请选择 ZIP 格式的地图文件'
  }
  return null
}
