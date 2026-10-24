import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import DatasetPage from './pages/DatasetPage.vue'
import FinancePage from './pages/FinancePage.vue'
import GeneratePage from './pages/GeneratePage.vue'
import MyFinancePage from './pages/MyFinancePage.vue'
import PromptsPage from './pages/PromptsPage.vue'
import SchemasPage from './pages/SchemasPage.vue'
import SettlementPage from './pages/SettlementPage.vue'
import SettingsPage from './pages/SettingsPage.vue'
import TemplatesPage from './pages/TemplatesPage.vue'

// 每个路由导出真实页面组件（新契约：不再用 shell 按 meta 猜页面）
export const Templates = TemplatesPage
export const Prompts = PromptsPage
export const Schemas = SchemasPage
export const Generate = GeneratePage
export const Finance = FinancePage
export const Settlement = SettlementPage
export const Dataset = DatasetPage
export const Settings = SettingsPage
export const MyFinance = MyFinancePage

export const routes = {
  Templates,
  Prompts,
  Schemas,
  Generate,
  Finance,
  Settlement,
  Dataset,
  Settings,
  MyFinance,
  'ai-office/Templates': TemplatesPage,
  'ai-office/Prompts': PromptsPage,
  'ai-office/Schemas': SchemasPage,
  'ai-office/Generate': GeneratePage,
  'ai-office/Finance': FinancePage,
  'ai-office/Settlement': SettlementPage,
  'ai-office/Dataset': DatasetPage,
  'ai-office/Settings': SettingsPage,
  'ai-office/MyFinance': MyFinancePage,
}

export default defineYuDreamPlugin({
  routes,
  default: Generate,
})
