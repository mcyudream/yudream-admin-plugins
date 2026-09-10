declare module '*.css'

/**
 * 本地桥接：SDK 1.7.0 的 site client 已随宿主运行时注入，但尚未发布 Nexus，
 * 插件仓 catalog 仍消费 1.6.0 类型。这里用模块增补补齐类型，1.7.0 发布并
 * 升级 catalog 后删除本文件。
 */
declare module '@yudream/plugin-sdk' {
  interface YuDreamPluginSiteContextQuery {
    /** 请求的主题块编码列表；空表示不需要块数据 */
    blocks?: string[]
    /** 单块数据量上限 */
    limit?: number
    /** CMS 最新文章数量；空或 0 表示不需要 */
    cmsLatest?: number
  }

  interface YuDreamPluginSiteContext {
    themeCode: string
    themeConfig: Record<string, any>
    /** 插件主题块提供者贡献的数据，键为块 code；插件未装载/不支持当前主题时缺省 */
    blocks: Record<string, any>
    cmsPagesLatest: Array<Record<string, any>>
  }

  interface YuDreamPluginSiteSeoInput {
    title: string
    description?: string
    canonicalPath?: string
    image?: string
    type?: 'article' | 'website'
    siteName?: string
    publishedAt?: string
    updatedAt?: string
    breadcrumbs?: Array<{ name: string, path: string }>
  }

  /** 公开站主题页能力：仅对 SITE 场景的公开页面有意义，匿名可用。 */
  interface YuDreamPluginSiteClient {
    context: (query?: YuDreamPluginSiteContextQuery) => Promise<YuDreamPluginSiteContext>
    applySeo: (input: YuDreamPluginSiteSeoInput) => void
    assetUrl: (path: string) => string
  }

  interface YuDreamPluginSdk {
    site: YuDreamPluginSiteClient
  }
}

export {}
