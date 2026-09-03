import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import materialStyles from './styles.css?inline'
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

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-material-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = materialStyles
}

export function dispose() {
  document.getElementById('yudream-plugin-material-style')?.remove()
}

export default defineYuDreamPlugin({
  routes,
  default: Library,
  install,
  dispose,
})
