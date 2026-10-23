import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import UnionCard from './components/UnionCard.vue'
import AdminSettingsPage from './pages/AdminSettingsPage.vue'
import AdminPeersPage from './pages/AdminPeersPage.vue'
import AdminFederationPage from './pages/AdminFederationPage.vue'
import AdminBansPage from './pages/AdminBansPage.vue'
import AdminAuditPage from './pages/AdminAuditPage.vue'

export const AdminSettings = AdminSettingsPage
export const AdminPeers = AdminPeersPage
export const AdminFederation = AdminFederationPage
export const AdminBans = AdminBansPage
export const AdminAudit = AdminAuditPage
export const UnionCardComponent = UnionCard

export const routes = {
  AdminSettings,
  AdminPeers,
  AdminFederation,
  AdminBans,
  AdminAudit,
  UnionCard: UnionCardComponent,
  'yudream-union/AdminSettings': AdminSettingsPage,
  'yudream-union/AdminPeers': AdminPeersPage,
  'yudream-union/AdminFederation': AdminFederationPage,
  'yudream-union/AdminBans': AdminBansPage,
  'yudream-union/AdminAudit': AdminAuditPage,
  'yudream-union/UnionCard': UnionCard,
}

export default defineYuDreamPlugin({
  routes,
  default: AdminSettingsPage,
})
