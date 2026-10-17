import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import AdminFeedbackDetail from './pages/AdminFeedbackDetail.vue'
import AdminFeedbacks from './pages/AdminFeedbacks.vue'
import FeedbackDetail from './pages/FeedbackDetail.vue'
import FeedbackEdit from './pages/FeedbackEdit.vue'
import MyFeedbacks from './pages/MyFeedbacks.vue'
import NewFeedback from './pages/NewFeedback.vue'

/**
 * 远程模块契约：路由名与后端 @PluginRoute(component = "...") 一一对应。
 *
 * <p>同时保留短名（MyFeedbacks 等）作为兼容 key，宿主按 code 前缀与短名两种约定都能取到组件；
 * default 指向用户端主页，宿主在未指定 component 时落到「我的反馈」。
 */
export const MyFeedbacksPage = MyFeedbacks
export const NewFeedbackPage = NewFeedback
export const FeedbackDetailPage = FeedbackDetail
export const FeedbackEditPage = FeedbackEdit
export const AdminFeedbacksPage = AdminFeedbacks
export const AdminFeedbackDetailPage = AdminFeedbackDetail

export const routes = {
  MyFeedbacks,
  NewFeedback,
  FeedbackDetail,
  FeedbackEdit,
  AdminFeedbacks,
  AdminFeedbackDetail,
  'feedback/MyFeedbacks': MyFeedbacks,
  'feedback/NewFeedback': NewFeedback,
  'feedback/FeedbackDetail': FeedbackDetail,
  'feedback/FeedbackEdit': FeedbackEdit,
  'feedback/AdminFeedbacks': AdminFeedbacks,
  'feedback/AdminFeedbackDetail': AdminFeedbackDetail,
}

export default defineYuDreamPlugin({
  routes,
  default: MyFeedbacks,
})
