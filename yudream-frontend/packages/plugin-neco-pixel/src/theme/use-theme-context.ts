import type { YuDreamPluginSdk, YuDreamPluginSiteContext, YuDreamPluginSiteContextQuery, YuDreamPluginSiteSeoInput } from '@yudream/plugin-sdk'
import { onMounted, ref, shallowRef } from 'vue'

/**
 * 主题页数据上下文：拉取公开主题上下文端点（主题配置 + 插件数据块 + CMS 最新文章）。
 * 端点匿名可用；块提供者插件未装载时对应键缺省，页面按静态配置回落。
 */
export function useThemeContext(sdk: YuDreamPluginSdk, query: YuDreamPluginSiteContextQuery = {}) {
  const context = shallowRef<YuDreamPluginSiteContext | null>(null)
  const loaded = ref(false)

  onMounted(async () => {
    try {
      context.value = await sdk.site.context(query)
    }
    catch {
      // 上下文拉取失败不阻塞页面：配置与块一律回落为空，页面渲染静态兜底
      context.value = null
    }
    finally {
      loaded.value = true
    }
  })

  return { context, loaded }
}

/** 写入公开页 SEO（title/og/canonical/结构化数据），随组件挂载生效 */
export function useThemeSeo(sdk: YuDreamPluginSdk, input: YuDreamPluginSiteSeoInput) {
  onMounted(() => sdk.site.applySeo(input))
}

/** 主题配置文本值：空串/缺省回落 fallback */
export function configText(config: Record<string, any> | undefined, key: string, fallback = ''): string {
  const value = config?.[key]
  return typeof value === 'string' && value.trim() ? value : fallback
}

/** 主题配置 list 字段：非数组回落空数组 */
export function configList<T = Record<string, any>>(config: Record<string, any> | undefined, key: string): T[] {
  const value = config?.[key]
  return Array.isArray(value) ? value as T[] : []
}

/** 主题配置数值字段 */
export function configNumber(config: Record<string, any> | undefined, key: string, fallback: number): number {
  const value = Number(config?.[key])
  return Number.isFinite(value) && value > 0 ? value : fallback
}

/** 主题配置开关字段（缺省视为 true） */
export function configSwitch(config: Record<string, any> | undefined, key: string): boolean {
  return config?.[key] !== false
}

/** 主题资产/配置图片路径转可访问 URL（dev 走代理前缀，生产同源不变） */
export function themeAsset(sdk: YuDreamPluginSdk, path: string | undefined | null): string {
  return path ? sdk.site.assetUrl(path) : ''
}

/** 插件打包资产的访问路径（public/ 目录随 dist 下发到 /api/platform/plugins/{code}/assets/**） */
export function pluginAsset(sdk: YuDreamPluginSdk, path: string): string {
  return sdk.site.assetUrl(`/api/platform/plugins/${sdk.pluginCode}/assets/${path}`)
}
