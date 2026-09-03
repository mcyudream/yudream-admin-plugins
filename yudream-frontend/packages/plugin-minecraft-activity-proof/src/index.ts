import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import markdownEditorStyles from 'md-editor-v3/lib/style.css?inline'
import activityProofStyles from './styles.css?inline'
import ActivityProofPlugin from './ActivityProofPlugin.vue'
import DashboardActivityCard from './components/DashboardActivityCard.vue'

export { DashboardActivityCard }

export const Square = ActivityProofPlugin
export const ActivityDetail = ActivityProofPlugin
export const MyActivities = ActivityProofPlugin
export const Mine = ActivityProofPlugin
export const Activities = ActivityProofPlugin
export const ActivityEdit = ActivityProofPlugin
export const ActivityAdminDetail = ActivityProofPlugin
export const Records = ActivityProofPlugin
export const Mappings = ActivityProofPlugin
export const Settings = ActivityProofPlugin

export const routes = {
  Square,
  ActivityDetail,
  MyActivities,
  Mine,
  Activities,
  ActivityEdit,
  ActivityAdminDetail,
  Records,
  Mappings,
  Settings,
  DashboardActivityCard,
  'minecraft-activity-proof/DashboardActivityCard': DashboardActivityCard,
  'minecraft-activity-proof/Square': Square,
  'minecraft-activity-proof/ActivityDetail': ActivityDetail,
  'minecraft-activity-proof/MyActivities': MyActivities,
  'minecraft-activity-proof/Mine': Mine,
  'minecraft-activity-proof/Activities': Activities,
  'minecraft-activity-proof/ActivityEdit': ActivityEdit,
  'minecraft-activity-proof/ActivityAdminDetail': ActivityAdminDetail,
  'minecraft-activity-proof/Records': Records,
  'minecraft-activity-proof/Mappings': Mappings,
  'minecraft-activity-proof/Settings': Settings,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-minecraft-activity-proof-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = `${markdownEditorStyles}\n${activityProofStyles}`
}

export default defineYuDreamPlugin({
  routes,
  default: Square,
  install,
})
