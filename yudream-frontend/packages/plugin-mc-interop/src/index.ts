import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import AdminCatalogPage from './pages/AdminCatalogPage.vue'
import AdminSitePage from './pages/AdminSitePage.vue'

export const AdminCatalog = AdminCatalogPage
export const AdminSite = AdminSitePage

export const routes = {
  AdminCatalog,
  AdminSite,
  'mc-interop/AdminCatalog': AdminCatalogPage,
  'mc-interop/AdminSite': AdminSitePage,
}

export default defineYuDreamPlugin({
  routes,
  default: AdminCatalogPage,
})
