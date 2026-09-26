import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TimeValue } from '../types'

export function errorMessage(error: unknown, fallback = '操作失败') {
  if (error && typeof error === 'object') {
    const data = error as { response?: { data?: { message?: string } }, message?: string }
    return data.response?.data?.message || data.message || fallback
  }
  return fallback
}

export function normalizeTime(value: TimeValue) {
  if (value == null || value === '') {
    return 0
  }
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value
    return new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000)).getTime()
  }
  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    return numeric < 10000000000 ? numeric * 1000 : numeric
  }
  return Number.isFinite(Date.parse(String(value))) ? Date.parse(String(value)) : 0
}

export function formatTime(value: TimeValue) {
  const timestamp = normalizeTime(value)
  return timestamp ? new Date(timestamp).toLocaleString('zh-CN', { hour12: false }) : '-'
}

export function formatAmount(value: string | number | undefined | null) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) {
    return '-'
  }
  return numeric.toFixed(2).replace(/\.?0+$/, '')
}

// 上传结果在开发环境会带 /proxy 前缀或绝对 origin，落库前统一还原成 /api/files/... 相对路径
export function normalizeFileUrl(value: string | null | undefined) {
  const raw = (value || '').trim()
  if (!raw) {
    return ''
  }
  return raw
    .replace(/^https?:\/\/[^/]+(?=\/api\/files\/)/i, '')
    .replace(/^\/proxy(?=\/api\/files\/)/i, '')
}

// 渲染前把内容里的文件引用解析成当前环境可访问的地址
export function resolveMarkdownFileUrls(sdk: YuDreamPluginSdk, text: string | null | undefined) {
  const raw = (text || '')
    .replace(/https?:\/\/[^/\s)"]+(?=\/api\/files\/)/gi, '')
    .replace(/\/proxy(?=\/api\/files\/)/gi, '')
  if (!raw) {
    return ''
  }
  return raw.replace(
    /(\]\(|src=")(\/api\/files\/[^)\s"]+)(\)|")/gi,
    (_match, prefix: string, url: string, suffix: string) => `${prefix}${sdk.files.assetUrl(url)}${suffix}`,
  )
}

/** Markdown 编辑器图片上传：落库为 /api/files/... 相对路径。 */
export async function uploadMarkdownImage(sdk: YuDreamPluginSdk, file: File) {
  if (!file.type.startsWith('image/')) {
    throw new Error('请选择图片文件')
  }
  const uploaded = await sdk.files.uploadImage(file, { module: 'shop', publicAccess: true })
  return normalizeFileUrl(uploaded.assetUrl || uploaded.url)
}

export function displayUserName(user?: { nickname?: string, username?: string, id?: string } | null) {
  return user?.nickname || user?.username || (user?.id ? `用户 ${user.id}` : '未知用户')
}

/** 权限判断与宿主 SPI 对齐：超管角色下发的是字面量 "*"，不展开具体权限码 */
export function hasPermission(permissions: string[] | undefined | null, code: string) {
  if (!permissions?.length) {
    return false
  }
  return permissions.includes('*') || permissions.includes(code)
}

export function productStatusTag(status: string) {
  return status === 'ON_SHELF'
    ? { variant: 'default' as const, text: '上架中' }
    : { variant: 'secondary' as const, text: '已下架' }
}

export function orderStatusTag(status: string): { variant: 'default' | 'secondary' | 'destructive' | 'outline', text: string } {
  switch (status) {
    case 'DELIVERED':
      return { variant: 'default', text: '已发货' }
    case 'DELIVERING':
      return { variant: 'outline', text: '发货中' }
    case 'PAID':
      return { variant: 'outline', text: '已支付' }
    case 'DELIVERY_FAILED':
      return { variant: 'destructive', text: '发货失败' }
    case 'REFUNDED':
      return { variant: 'secondary', text: '已退款' }
    default:
      return { variant: 'secondary', text: status || '未知' }
  }
}
