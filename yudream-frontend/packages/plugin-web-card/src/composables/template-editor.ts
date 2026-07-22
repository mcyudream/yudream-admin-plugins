import type { TemplateMode } from '../types'

export const DEFAULT_STRUCTURED_LAYOUT = `{
  "variant": "editorial",
  "accentColor": "#39725d",
  "showImage": true,
  "showSource": true,
  "showSummary": true,
  "showUrl": true,
  "extraFields": "auto"
}`

export function normalizeStructuredLayout(value?: string) {
  if (!value || value === 'default') return DEFAULT_STRUCTURED_LAYOUT
  try { return JSON.stringify(JSON.parse(value), null, 2) }
  catch { return value }
}

export function validateTemplateCode(mode: TemplateMode, layout: string, html: string) {
  if (mode === 'STRUCTURED') {
    try { JSON.parse(layout) }
    catch { return '结构化布局 JSON 格式无效' }
  }
  if (mode === 'ADVANCED' && !/id=["']web-card["']/.test(html)) return 'HTML 模板需要包含 id="web-card" 的根元素'
  return ''
}
