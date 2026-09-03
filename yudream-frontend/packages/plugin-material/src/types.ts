/** 宿主把 long（含 epoch 毫秒时间戳、字节大小）序列化为 JSON 字符串，统一用 number | string 承载。 */
export interface MaterialSummary {
  id: string
  name: string
  ext: string
  type: string
  typeLabel: string
  categoryId?: string | null
  categoryName?: string | null
  tags: string[]
  ownerId: string
  ownerName?: string | null
  currentVersion: number
  size: number | string
  contentType?: string | null
  status: string
  createdAt: number | string
  updatedAt: number | string
}

export interface VersionView {
  version: number
  originalName?: string | null
  size: number | string
  contentType?: string | null
  note?: string | null
  uploaderId: string
  uploaderName?: string | null
  createdAt: number | string
  current: boolean
}

export interface MaterialDetail {
  material: MaterialSummary
  currentVersionInfo?: VersionView | null
}

export interface CategoryView {
  id: string
  name: string
  sort: number
  materials: number | string
  createdAt: number | string
}

/** 预览信息：KKFILE=kkFileView iframe 绝对地址；DIRECT=平台签名公开路径（/api/**，用 sdk.files.assetUrl 解析）；NONE=不可预览。 */
export interface PreviewInfo {
  mode: 'KKFILE' | 'DIRECT' | 'NONE'
  url?: string | null
  message?: string | null
}

/** 分享外链。url 为插件相对路径（/public/share/{token}），expiresAt 为 0 表示永久有效。 */
export interface ShareView {
  id: string
  url: string
  note?: string | null
  expiresAt: number | string
  createdAt: number | string
  createdByName?: string | null
  expired: boolean
}

export interface Page<T> {
  records: T[]
  total: number | string
}

/** 标签云条目。 */
export interface TagView {
  name: string
  count: number | string
}

/** 封面签发结果：url 为平台签名公开路径（/api/**），需经 sdk.files.assetUrl() 转绝对地址。 */
export interface CoverView {
  id: string
  url: string
}

/** 卡片网格的类型图标兜底映射。 */
export const TYPE_ICONS: Record<string, string> = {
  IMAGE: 'i-ri:image-line',
  DESIGN: 'i-ri:palette-line',
  DOCUMENT: 'i-ri:file-text-line',
  SPREADSHEET: 'i-ri:file-excel-2-line',
  PRESENTATION: 'i-ri:file-ppt-2-line',
  VIDEO: 'i-ri:video-line',
  AUDIO: 'i-ri:music-2-line',
  ARCHIVE: 'i-ri:file-zip-line',
  OTHER: 'i-ri:file-line',
}

export const MATERIAL_TYPES = [
  { label: '全部类型', value: '' },
  { label: '图片', value: 'IMAGE' },
  { label: '设计稿', value: 'DESIGN' },
  { label: '文档', value: 'DOCUMENT' },
  { label: '表格', value: 'SPREADSHEET' },
  { label: '演示', value: 'PRESENTATION' },
  { label: '视频', value: 'VIDEO' },
  { label: '音频', value: 'AUDIO' },
  { label: '压缩包', value: 'ARCHIVE' },
  { label: '其他', value: 'OTHER' },
]

export function formatSize(value: number | string | undefined | null) {
  const size = Number(value)
  if (!value || !Number.isFinite(size) || size < 0) {
    return '-'
  }
  if (size < 1024) {
    return `${size} B`
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }
  if (size < 1024 * 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)} MB`
  }
  return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function formatTime(value: number | string | undefined | null) {
  const time = Number(value)
  if (!value || !Number.isFinite(time) || time <= 0) {
    return '-'
  }
  return new Date(time).toLocaleString('zh-CN', { hour12: false })
}
