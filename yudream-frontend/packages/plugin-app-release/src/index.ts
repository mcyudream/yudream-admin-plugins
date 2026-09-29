import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import ReleasesPage from './pages/ReleasesPage.vue'

export const Releases = ReleasesPage

export const routes = {
  Releases,
  'app-release/Releases': Releases,
}

export default defineYuDreamPlugin({
  routes,
  default: Releases,
})
