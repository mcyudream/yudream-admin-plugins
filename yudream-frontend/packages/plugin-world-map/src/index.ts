import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
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

export default defineYuDreamPlugin({
  routes,
  default: Viewer,
})
