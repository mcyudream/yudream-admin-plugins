import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
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

export default defineYuDreamPlugin({
  routes,
  default: AdminStatus,
})
