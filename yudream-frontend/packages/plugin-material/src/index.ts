import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import MaterialPlugin from './MaterialPlugin.vue'

export const Admin = MaterialPlugin
export const Categories = MaterialPlugin
export const Detail = MaterialPlugin
export const Library = MaterialPlugin

export const routes = {
  Admin,
  Categories,
  Detail,
  Library,
  'material/Admin': Admin,
  'material/Categories': Categories,
  'material/Detail': Detail,
  'material/Library': Library,
}

export default defineYuDreamPlugin({
  routes,
  default: Library,
})
