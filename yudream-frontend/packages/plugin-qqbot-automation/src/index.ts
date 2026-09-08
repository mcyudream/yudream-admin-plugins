import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import automationStyles from './styles.css?inline'
import QqbotAutomationPlugin from './QqbotAutomationPlugin.vue'
import PoliciesPage from './pages/PoliciesPage.vue'
import MediaJobsPage from './pages/MediaJobsPage.vue'
export const Settings = QqbotAutomationPlugin
export const Policies = PoliciesPage
export const MediaJobs = MediaJobsPage
export const routes = { Settings, Policies, MediaJobs, 'qqbot-automation/Policies': Policies, 'qqbot-automation/MediaJobs': MediaJobs }

export function install() {
  const id = 'yudream-plugin-qqbot-automation-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = automationStyles
}

export default defineYuDreamPlugin({ routes, default: Settings, install })
