import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import AiChatbotPlugin from './AiChatbotPlugin.vue'
import MemoryProfilesPage from './pages/MemoryProfilesPage.vue'
import UserProfileWorkbenchPage from './pages/UserProfileWorkbenchPage.vue'

export const Settings = AiChatbotPlugin
export const MemoryProfiles = MemoryProfilesPage
export const UserProfileWorkbench = UserProfileWorkbenchPage
export const routes = { Settings, MemoryProfiles, UserProfileWorkbench, 'ai-chatbot/Settings': Settings, 'ai-chatbot/MemoryProfiles': MemoryProfiles, 'ai-chatbot/UserProfileWorkbench': UserProfileWorkbench }

export default defineYuDreamPlugin({ routes, default: Settings })
