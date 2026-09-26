import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import MyPointsPage from './pages/MyPointsPage.vue'
import SettlementsPage from './pages/SettlementsPage.vue'
import SettingsPage from './pages/SettingsPage.vue'

export const My = MyPointsPage
export const Settlements = SettlementsPage
export const Settings = SettingsPage

export const routes = {
  My: MyPointsPage,
  Settlements: SettlementsPage,
  Settings: SettingsPage,
  'playtime-points/My': MyPointsPage,
  'playtime-points/Settlements': SettlementsPage,
  'playtime-points/Settings': SettingsPage,
}

export default defineYuDreamPlugin({
  routes,
  default: MyPointsPage,
})
