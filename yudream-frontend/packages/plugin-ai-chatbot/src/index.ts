import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import aiChatbotStyles from './styles.css?inline'
import AiChatbotPlugin from './AiChatbotPlugin.vue'
import MemoryProfilesPage from './pages/MemoryProfilesPage.vue'
import UserProfileWorkbenchPage from './pages/UserProfileWorkbenchPage.vue'

export const Settings = AiChatbotPlugin
export const MemoryProfiles = MemoryProfilesPage
export const UserProfileWorkbench = UserProfileWorkbenchPage
export const routes = { Settings, MemoryProfiles, UserProfileWorkbench, 'ai-chatbot/Settings': Settings, 'ai-chatbot/MemoryProfiles': MemoryProfiles, 'ai-chatbot/UserProfileWorkbench': UserProfileWorkbench }

export function install() {
  if (typeof document === 'undefined') return
  const id = 'yudream-plugin-ai-chatbot-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = aiChatbotStyles
}

export default defineYuDreamPlugin({ routes, default: Settings, install })
