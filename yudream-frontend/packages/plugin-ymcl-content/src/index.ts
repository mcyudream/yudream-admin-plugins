import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import ReleasesPage from './pages/ReleasesPage.vue'

export const Releases = ReleasesPage

export const routes = {
  Releases,
  'ymcl-content/Releases': ReleasesPage,
}

export default defineYuDreamPlugin({
  routes,
})
