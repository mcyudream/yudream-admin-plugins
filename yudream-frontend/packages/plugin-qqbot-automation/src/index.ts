import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import QqbotAutomationPlugin from './QqbotAutomationPlugin.vue'
import PoliciesPage from './pages/PoliciesPage.vue'
import MediaJobsPage from './pages/MediaJobsPage.vue'
export const Settings = QqbotAutomationPlugin
export const Policies = PoliciesPage
export const MediaJobs = MediaJobsPage
export const routes = { Settings, Policies, MediaJobs, 'qqbot-automation/Policies': Policies, 'qqbot-automation/MediaJobs': MediaJobs }
export default defineYuDreamPlugin({ routes, default: Settings })
