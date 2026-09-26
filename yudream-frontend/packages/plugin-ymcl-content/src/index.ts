import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import ContentPage from './pages/ContentPage.vue'
import ReleasesPage from './pages/ReleasesPage.vue'

export const Releases = ReleasesPage
export const Content = ContentPage

export const routes = {
  Releases,
  Content,
  'ymcl-content/Releases': ReleasesPage,
  'ymcl-content/Content': ContentPage,
}

export default defineYuDreamPlugin({
  routes,
})
