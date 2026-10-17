import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import ViewerPage from './pages/Viewer.vue'
import MapListPage from './pages/admin/MapList.vue'
import MapGalleryPage from './pages/admin/MapGallery.vue'
import MapDetailPage from './pages/admin/MapDetail.vue'
import MapMarkersPage from './pages/admin/MapMarkers.vue'
import MapArchivedPage from './pages/admin/MapArchived.vue'
import MapTasksPage from './pages/admin/MapTasks.vue'

/**
 * 远程模块契约：路由名与后端 {@code @PluginRoute(component = "...")} 一一对应。
 *
 * <p>命名与其它插件一致：组件同时以短名（{@code MapList}）与 {@code code/PageName}
 * （{@code voxelith-map/MapList}）两种约定导出，宿主两种取法都能命中；这里**不带 admin 段**——
 * 管理页在宿主路由里位于 {@code /platform/plugins/voxelith-map/**}，与其它插件同构。</p>
 */
export const Viewer = ViewerPage
export const MapList = MapListPage
export const MapGallery = MapGalleryPage
export const MapDetail = MapDetailPage
export const MapMarkers = MapMarkersPage
export const MapArchived = MapArchivedPage
export const MapTasks = MapTasksPage

export const routes = {
  Viewer,
  MapList,
  MapGallery,
  MapDetail,
  MapMarkers,
  MapArchived,
  MapTasks,
  'voxelith-map/Viewer': Viewer,
  'voxelith-map/MapList': MapList,
  'voxelith-map/MapGallery': MapGallery,
  'voxelith-map/MapDetail': MapDetail,
  'voxelith-map/MapMarkers': MapMarkers,
  'voxelith-map/MapArchived': MapArchived,
  'voxelith-map/MapTasks': MapTasks,
}

export default defineYuDreamPlugin({
  routes,
  default: Viewer,
})
