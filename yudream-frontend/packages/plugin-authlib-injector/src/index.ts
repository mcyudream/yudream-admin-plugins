import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import authlibStyles from './styles.css?inline'
import DashboardEndpointCard from './components/DashboardEndpointCard.vue'
import AuthlibPlugin from './AuthlibPlugin.vue'

export const EndpointCard = DashboardEndpointCard
export const AdminStatus = AuthlibPlugin

export const routes = {
  EndpointCard,
  AdminStatus,
  'authlib-injector/EndpointCard': EndpointCard,
  'authlib-injector/AdminStatus': AdminStatus,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-authlib-injector-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.id = id
  style.textContent = authlibStyles
}

export default defineYuDreamPlugin({
  routes,
  default: AdminStatus,
  install,
})
