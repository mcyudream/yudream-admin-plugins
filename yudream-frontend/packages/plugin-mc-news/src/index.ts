import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import NewsListPage from './pages/NewsListPage.vue'
import MySubscriptionPage from './pages/MySubscriptionPage.vue'
import PushLogPage from './pages/PushLogPage.vue'
import SettingsPage from './pages/SettingsPage.vue'
import SourcesPage from './pages/SourcesPage.vue'
import TargetsPage from './pages/TargetsPage.vue'

export const NewsList = NewsListPage
export const Sources = SourcesPage
export const Targets = TargetsPage
export const Settings = SettingsPage
export const PushLog = PushLogPage
export const MySubscription = MySubscriptionPage

export const routes = {
  NewsList,
  Sources,
  Targets,
  Settings,
  PushLog,
  MySubscription,
  'mc-news/NewsList': NewsList,
  'mc-news/Sources': Sources,
  'mc-news/Targets': Targets,
  'mc-news/Settings': Settings,
  'mc-news/PushLog': PushLog,
  'mc-news/MySubscription': MySubscription,
}

export default defineYuDreamPlugin({
  routes,
  default: NewsList,
})
