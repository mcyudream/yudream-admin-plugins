import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import PublicVerifyForm from './components/PublicVerifyForm.vue'
import AdminReviewPage from './pages/AdminReviewPage.vue'
import DomainsPage from './pages/DomainsPage.vue'
import PublicVerifyPage from './pages/PublicVerifyPage.vue'
import SettingsPage from './pages/SettingsPage.vue'

export const Public = PublicVerifyPage
export const RegisterForm = PublicVerifyForm
export const Admin = AdminReviewPage
export const Domains = DomainsPage
export const Settings = SettingsPage

export const routes = {
  Public,
  RegisterForm,
  Admin,
  Domains,
  Settings,
  'edu-verify/Public': Public,
  'edu-verify/RegisterForm': RegisterForm,
  'edu-verify/Admin': Admin,
  'edu-verify/Domains': Domains,
  'edu-verify/Settings': Settings,
}

export default defineYuDreamPlugin({
  routes,
  default: Public,
})
