import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import markdownEditorStyles from 'md-editor-v3/lib/style.css?inline'
import eduVerifyStyles from './styles.css?inline'
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

const STYLE_ID = 'yudream-plugin-edu-verify-style'

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  let style = document.getElementById(STYLE_ID) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = STYLE_ID
    document.head.appendChild(style)
  }
  style.textContent = `${markdownEditorStyles}\n${eduVerifyStyles}`
}

export function dispose() {
  if (typeof document === 'undefined') {
    return
  }
  document.getElementById(STYLE_ID)?.remove()
}

export default defineYuDreamPlugin({
  routes,
  default: Public,
  install,
  dispose,
})
