import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import Home from './pages/Home.vue'
import Catalog from './pages/Catalog.vue'
import Admin from './pages/Admin.vue'
import Settings from './pages/Settings.vue'
import 'virtual:uno.css'
import './style.css'
export { Home, Catalog, Admin, Settings }
export const routes = { Home, Catalog, Admin, Settings, 'ysm-models/Home': Home, 'ysm-models/Catalog': Catalog, 'ysm-models/Admin': Admin, 'ysm-models/Settings': Settings }
export default defineYuDreamPlugin({ routes, default: Home })
