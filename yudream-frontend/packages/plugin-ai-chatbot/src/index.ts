import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import AiChatbotPlugin from './AiChatbotPlugin.vue'
export const Settings = AiChatbotPlugin
export const routes = { Settings, 'ai-chatbot/Settings': Settings }
export default defineYuDreamPlugin({ routes, default: Settings })
