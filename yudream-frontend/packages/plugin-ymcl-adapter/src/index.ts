import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import YmclConnectCard from './components/YmclConnectCard.vue'
import BundlesPage from './pages/BundlesPage.vue'
import ChromeHomePage from './pages/ChromeHomePage.vue'
import ChromeThemePage from './pages/ChromeThemePage.vue'
import NavigationPage from './pages/NavigationPage.vue'
import OverviewPage from './pages/OverviewPage.vue'
import PacksPage from './pages/PacksPage.vue'
import ServersPage from './pages/ServersPage.vue'

export const Overview = OverviewPage
export const Packs = PacksPage
export const Servers = ServersPage
export const ChromeHome = ChromeHomePage
export const ChromeTheme = ChromeThemePage
export const Navigation = NavigationPage
export const Bundles = BundlesPage
export { YmclConnectCard }

export const routes = {
  Overview,
  Packs,
  Servers,
  ChromeHome,
  ChromeTheme,
  Navigation,
  Bundles,
  'ymcl-adapter/Overview': OverviewPage,
  'ymcl-adapter/Packs': PacksPage,
  'ymcl-adapter/Servers': ServersPage,
  'ymcl-adapter/ChromeHome': ChromeHomePage,
  'ymcl-adapter/ChromeTheme': ChromeThemePage,
  'ymcl-adapter/Navigation': NavigationPage,
  'ymcl-adapter/Bundles': BundlesPage,
  'ymcl-adapter/YmclConnectCard': YmclConnectCard,
}

export default defineYuDreamPlugin({
  routes,
})
