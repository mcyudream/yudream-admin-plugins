import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import markdownEditorStyles from 'md-editor-v3/lib/style.css?inline'
import timelineStyles from './styles.css?inline'
import TimelinePlugin from './TimelinePlugin.vue'

export const Public = TimelinePlugin
export const Admin = TimelinePlugin

export const routes = {
  Public,
  Admin,
  'timeline/Public': Public,
  'timeline/Admin': Admin,
}

const STYLE_ID = 'yudream-plugin-timeline-style'

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  let style = document.getElementById(STYLE_ID) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = STYLE_ID
    document.head.appendChild(style)
  }
  // 已存在时也必须刷新内容，保证热重载/重复挂载后样式是最新的
  style.textContent = `${markdownEditorStyles}\n${timelineStyles}`
}

export function dispose() {
  if (typeof document === 'undefined') {
    return
  }
  document.getElementById(STYLE_ID)?.remove()
}

export default defineYuDreamPlugin({
  routes,
  default: Public,
  install,
  dispose,
})
