import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'

/**
 * 方块地形主题的前端入口：站点外观主体由 site-preset.json、theme.css 与
 * media/ 静态资产表达（随插件分发，经网站工作台导入应用，已有站点不被覆盖）。
 *
 * 导航文字引导独立于站点文档：底部物品栏每个槽位底部常显 ≤3 字像素小字。
 * 样式在本模块 install 时按需注入到承载热栏的渲染根（Shadow DOM 或文档），
 * 因此已实装的旧站点升级插件后无需迁移文档即可获得；dispose 时全部回收。
 * 若站点文档已手工加入同类小字（hb-tag span），注入样式会将其隐藏避免双显。
 */

const HINT_STYLE_ID = 'mc-terrain-nav-hints'

const HINT_STYLE_CSS = `
/* 只作用于世界链接槽（a.hb）：登录/账号/音乐/音效等侧槽（hb-side-slot）保持原观感 */
a.hb:not(.hb-side-slot) img { margin-bottom: 10px; }
.hb .hb-tag { display: none; }
a.hb:not(.hb-side-slot)::after {
  content: attr(title);
  position: absolute;
  left: 2px;
  right: 2px;
  bottom: 2px;
  text-align: center;
  font-size: 8px;
  line-height: 1.1;
  color: #f0f0f0;
  text-shadow: 1px 1px 0 #000;
  opacity: .8;
  pointer-events: none;
  white-space: nowrap;
  overflow: hidden;
  font-family: 'Minecraft', monospace;
}
`

let scanTimer: number | undefined

/** 向「含热栏槽位」的渲染根注入小字样式；已存在或无热栏时不重复 */
function ensureHintStyle(root: ParentNode): boolean {
  const owner = root instanceof ShadowRoot || root instanceof Document ? root : null
  if (!owner || !owner.querySelector('.hotbar-slots')) {
    return false
  }
  if (owner.querySelector(`style#${HINT_STYLE_ID}`)) {
    return true
  }
  const style = document.createElement('style')
  style.id = HINT_STYLE_ID
  style.textContent = HINT_STYLE_CSS
  owner.appendChild(style)
  return true
}

/** 深度收集可见的渲染根：文档本体、全部 shadow root 与同源 iframe（草稿预览） */
function collectRoots(): ParentNode[] {
  const roots: ParentNode[] = [document]
  const walk = (element: Element) => {
    if (element.shadowRoot) {
      roots.push(element.shadowRoot)
      element.shadowRoot.querySelectorAll('*').forEach(walk)
    }
    if (element instanceof HTMLIFrameElement) {
      try {
        if (element.contentDocument) {
          roots.push(element.contentDocument)
        }
      }
      catch {
        // 跨源 iframe 不可访问，跳过
      }
    }
  }
  document.querySelectorAll('*').forEach(walk)
  return roots
}

function scan() {
  for (const root of collectRoots()) {
    ensureHintStyle(root)
  }
}

export default defineYuDreamPlugin({
  install() {
    scan()
    scanTimer = window.setInterval(scan, 1200)
  },
  dispose() {
    if (scanTimer !== undefined) {
      window.clearInterval(scanTimer)
      scanTimer = undefined
    }
    for (const root of collectRoots()) {
      root.querySelectorAll(`style#${HINT_STYLE_ID}`).forEach(style => style.remove())
    }
  },
})
