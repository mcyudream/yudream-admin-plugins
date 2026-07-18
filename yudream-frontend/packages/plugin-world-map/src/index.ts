import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import worldMapStyles from './styles.css?inline'
import Viewer from './pages/Viewer.vue'
import MapList from './pages/admin/MapList.vue'
import MapDetail from './pages/admin/MapDetail.vue'

export const routes = {
  Viewer,
  MapList,
  MapDetail,
  'world-map/Viewer': Viewer,
  'world-map/admin/MapList': MapList,
  'world-map/admin/MapDetail': MapDetail,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-world-map-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = worldMapStyles
}

export default defineYuDreamPlugin({
  routes,
  default: Viewer,
  install,
})
