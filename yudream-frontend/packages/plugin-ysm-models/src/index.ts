import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import Home from './pages/Home.vue'
import Catalog from './pages/Catalog.vue'
import Admin from './pages/Admin.vue'
import 'virtual:uno.css'
import './style.css'
export { Home, Catalog, Admin }
export const routes = { Home, Catalog, Admin, 'ysm-models/Home': Home, 'ysm-models/Catalog': Catalog, 'ysm-models/Admin': Admin }
export default defineYuDreamPlugin({ routes, default: Home })
