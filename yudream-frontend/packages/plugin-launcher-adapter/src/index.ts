import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import YmclConnectCard from './components/YmclConnectCard.vue'
import ChromePage from './pages/ChromePage.vue'
import ConnectPage from './pages/ConnectPage.vue'
import PacksPage from './pages/PacksPage.vue'

export { YmclConnectCard }
export const Connect = ConnectPage
export const Packs = PacksPage
export const Chrome = ChromePage

export const routes = {
  YmclConnectCard,
  Connect,
  Packs,
  Chrome,
  'launcher-adapter/YmclConnectCard': YmclConnectCard,
  'launcher-adapter/Connect': ConnectPage,
  'launcher-adapter/Packs': PacksPage,
  'launcher-adapter/Chrome': ChromePage,
}

export default defineYuDreamPlugin({
  routes,
})
