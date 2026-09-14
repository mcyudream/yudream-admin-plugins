export interface PagedResult<T> {
  records: T[]
  total: number
}

export interface LauncherPack {
  id: string
  name: string
  description: string
  icon: string
  recommendedVersionId: string
  retainedVersionIds: string[]
  createdAt: string
  updatedAt: string
}

export interface LauncherPackVersion {
  packId: string
  versionId: string
  indexHash: string
  overridesObjectKey: string
  downloads: string[]
  changelog: string
  publishedAt: string
  publisherUserId: string | null
  status: string
}

export interface LauncherPackDetail {
  pack: LauncherPack
  versions: LauncherPackVersion[]
}

export interface PackForm {
  packId: string
  name: string
  description: string
  icon: string
}

export interface YmclChromePage {
  code: string
  title: string
  icon: string
  type: string
  path: string
  providerCode: string
  navOrder: number
  dataSourceCode: string
}

export type YmclNavKind = 'tab' | 'menu'

export interface YmclNavNode {
  code: string
  kind: YmclNavKind
  title: string
  icon: string
  pageCode: string
  visible: boolean
  children: YmclNavNode[]
}

export interface YmclChromeForm {
  displayName: string
  logoUrl: string
  backgroundUrl: string
  navTree: YmclNavNode[]
}

export interface YmclChromeAdmin {
  chrome: YmclChromeForm
  pages: YmclChromePage[]
}
