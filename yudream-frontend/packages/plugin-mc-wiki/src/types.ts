export interface WikiVersionRow {
  id: string
  type: string
  releaseTime: string | null
  url: string
  latest: boolean
  imported: boolean
  items: number
  recipes: number
  textures: number
  published: boolean
}

export interface WikiJob {
  jobId: string
  version: string
  phase: string
  done: number
  total: number
  status: string
  error?: string | null
  streamId: string
  updatedAt: number
}

export interface WikiJobEvent extends WikiJob {
  message: string
  level: string
}

export interface WikiMeta {
  published: boolean
  version: string | null
  versions: string[]
  items: number
  recipes: number
}

/** 共享渲染资产库元信息（mc-assets 一键更新） */
export interface WikiRenderMeta {
  updated: boolean
  commit?: string
  gameVersion?: string
  items?: number
  entitiesFlat?: number
  entitiesIsometric?: number
  updatedAt?: number | string
}

export interface WikiItem {
  version: string
  namespacedId: string
  kind: string
  nameEn: string
  nameZh: string
  tags: string[]
  textureKey: string | null
  diagnostic: string | null
}

export interface WikiRecipe {
  id: string
  version: string
  type: string
  resultId: string
  resultCount: number
  ingredients: string[]
  rawJson: string
  resultNameZh?: string | null
  resultNameEn?: string | null
  resultTextureKey?: string | null
  resultKind?: string | null
}

export interface WikiItemDetail {
  item: WikiItem
  producing: WikiRecipe[]
  using: WikiRecipe[]
}

export interface WikiItemTextures {
  id: string
  version: string
  kind: string
  /** 渲染资产库中的渲染名（大写），对应 renders 键 */
  renderName: string
  renderAvailable: boolean
  textureKey: string | null
  iconLayers: string[]
  faces: Record<string, string>
  blockstate?: string
}

export interface WikiPage<T> {
  records: T[]
  total: number
}

export const RECIPE_TYPE_LABELS: Record<string, string> = {
  'minecraft:crafting_shaped': '有序合成',
  'minecraft:crafting_shapeless': '无序合成',
  'minecraft:smelting': '熔炉烧炼',
  'minecraft:blasting': '高炉烧炼',
  'minecraft:smoking': '烟熏',
  'minecraft:campfire_cooking': '营火烹饪',
  'minecraft:stonecutting': '切石',
  'minecraft:smithing_transform': '锻造升级',
  'minecraft:smithing_trim': '盔甲纹饰',
}

export function recipeTypeLabel(type: string): string {
  return RECIPE_TYPE_LABELS[type] ?? type.replace(/^minecraft:/, '')
}

export const JOB_STATUS_LABELS: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  PAUSING: '暂停中',
  PAUSED: '已暂停',
  CANCELLING: '取消中',
  CANCELLED: '已取消',
  DONE: '已完成',
  FAILED: '失败',
}

export function jobStatusLabel(status: string): string {
  return JOB_STATUS_LABELS[status] ?? status ?? '-'
}

/** 任务版本展示名：渲染资产一键更新使用伪版本号占位 */
export function jobVersionLabel(version: string): string {
  return version === '__renders__' ? '渲染资产更新' : version
}

export function itemDisplayName(item: Pick<WikiItem, 'nameZh' | 'nameEn' | 'namespacedId'>): string {
  return item.nameZh || item.nameEn || item.namespacedId
}
