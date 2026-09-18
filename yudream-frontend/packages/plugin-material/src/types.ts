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
  /** 可见性：PRIVATE 仅自己 / DEPT 仅部门（deptIds 为显式选择的部门，deptNames 为其名称）/ PUBLIC 公开。 */
  visibility: string
  deptIds?: string[]
  deptNames?: string[]
  currentVersion: number
  size: number | string
  contentType?: string | null
  status: string
  /** 子物料数量。 */
  itemCount: number | string
  /**
   * 是否已有主文件。false 表示这是「组合物料」——本身不带文件，
   * 文件全部来自子物料，此时 ext/size 为空、type 为 OTHER，卡片应按组合物料渲染。
   */
  mainFilePresent: boolean
  /**
   * 组合物料的「预览主文件」：所指定的子物料 id（未指定时为 null）。
   * 详情页默认预览目标、库页缩略图与「下载当前版本」都跟随该子物料的当前版本。
   */
  previewItemId?: string | null
  createdAt: number | string
  updatedAt: number | string
}

/** 组合物料：没有主文件、靠子物料承载文件。 */
export function isBundle(material: Pick<MaterialSummary, 'mainFilePresent'> | undefined | null) {
  return !!material && !material.mainFilePresent
}

/** 组合物料在列表卡片上的展示名（类型标签位置使用）。 */
export const BUNDLE_LABEL = '组合物料'

/** 子物料：父物料下具名文件槽，拥有完全独立的版本链。 */
export interface MaterialItemView {
  id: string
  materialId: string
  name: string
  sort: number | string
  ext: string
  type: string
  typeLabel: string
  size: number | string
  contentType?: string | null
  currentVersion: number
  createdAt: number | string
  updatedAt: number | string
}

/** 子物料版本，字段与 VersionView 对齐，可复用同一套版本表格渲染。 */
export interface MaterialItemVersionView {
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

export interface MaterialItemDetail {
  item: MaterialItemView
  currentVersionInfo?: MaterialItemVersionView | null
}

export const VISIBILITY_OPTIONS = [
  { label: '仅自己可见', value: 'PRIVATE' },
  { label: '仅部门可见', value: 'DEPT' },
  { label: '公开（全站成员可见）', value: 'PUBLIC' },
]

const VISIBILITY_LABELS: Record<string, string> = {
  PRIVATE: '仅自己',
  DEPT: '仅部门',
  PUBLIC: '公开',
}

export function visibilityLabel(value: string | undefined | null) {
  return VISIBILITY_LABELS[value || 'PRIVATE'] || '仅自己'
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

/** 部门选项：label 带父级路径（如「技术部 / 平台组」），用户端只返回自己加入的部门。 */
export interface DeptOption {
  id: string
  name: string
  label: string
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

/** 封面签发结果：url 为缩略图的平台签名公开路径（/api/**），需经 sdk.files.assetUrl() 转绝对地址。 */
export interface CoverView {
  id: string
  url: string
}

/** 文件夹批量导入的单个文件条目：fileId 来自平台文件上传，name 留空时后端按目录名与文件名拼名。 */
export interface FolderImportItem {
  fileId: string
  filename: string
  /** 显式名称；留空时后端按「中间子目录-文件名」拼接（如 海报/横版/封面.png →「横版-封面」）。 */
  name?: string
  /**
   * 所在中间子文件夹名列表（由外到内，根文件夹不计）。FILES 模式下与批次 tags 合并
   * （去重后上限 8 个、单签 20 字）；BUNDLE 模式下写进该子物料首个版本的备注；
   * 两种形态都用它把目录名拼进物料名/子物料名。
   */
  tags?: string[]
}

/** 文件夹导入形态：FILES 每个文件一个单文件物料；BUNDLE 整批合并为一个组合物料，每个文件一个子物料。 */
export type FolderImportMode = 'FILES' | 'BUNDLE'

/** 文件夹批量导入请求：批次 categoryName 与 categoryId 二选一，按名查找（忽略大小写）不存在则自动创建。 */
export interface FolderImportPayload {
  /** 缺省 FILES。 */
  mode?: FolderImportMode
  /** BUNDLE 模式下组合物料的名称，留空取 categoryName（根文件夹名）。 */
  name?: string
  categoryId?: string
  categoryName?: string
  visibility?: string
  /** DEPT 可见性时显式选择的可见部门。 */
  deptIds?: string[]
  tags?: string[]
  items: FolderImportItem[]
}

/** 文件夹批量导入结果：单个文件失败不中断整批，failures 收集逐文件原因。 */
export interface FolderImportResult {
  mode?: FolderImportMode
  /** FILES 模式下是文件（物料）数，BUNDLE 模式下是文件（子物料）数。 */
  total: number
  created: number
  categoryId?: string
  categoryName?: string
  /** BUNDLE 模式下创建出的组合物料 id 与名称，FILES 模式下为空。 */
  materialId?: string
  materialName?: string
  failures: { filename: string, message: string }[]
}

/** 管理端批量操作结果：逐项容错不中断，failures 收集逐项原因。 */
export interface BatchResult {
  total: number
  succeeded: number
  failures: { id: string, name: string, message: string }[]
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
