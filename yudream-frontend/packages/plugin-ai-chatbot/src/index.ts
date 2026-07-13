import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import AiChatbotPlugin from './AiChatbotPlugin.vue'
import MemoryProfilesPage from './pages/MemoryProfilesPage.vue'
export const Settings = AiChatbotPlugin
export const MemoryProfiles = MemoryProfilesPage
export const routes = { Settings, MemoryProfiles, 'ai-chatbot/Settings': Settings, 'ai-chatbot/MemoryProfiles': MemoryProfiles }
export default defineYuDreamPlugin({ routes, default: Settings })
