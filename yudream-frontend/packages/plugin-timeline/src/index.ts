import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import TimelinePlugin from './TimelinePlugin.vue'

export const Public = TimelinePlugin
export const Admin = TimelinePlugin

export const routes = {
  Public,
  Admin,
  'timeline/Public': Public,
  'timeline/Admin': Admin,
}

export default defineYuDreamPlugin({
  routes,
  default: Public,
})
