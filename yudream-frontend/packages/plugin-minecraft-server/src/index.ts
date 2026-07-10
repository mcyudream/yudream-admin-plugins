import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import markdownEditorStyles from 'md-editor-v3/lib/style.css?inline'
import minecraftStyles from './styles.css?inline'
import MinecraftServerPlugin from './MinecraftServerPlugin.vue'

export const List = MinecraftServerPlugin
export const Detail = MinecraftServerPlugin
export const Admin = MinecraftServerPlugin
export const Editor = MinecraftServerPlugin
export const Seasons = MinecraftServerPlugin
export const Operations = MinecraftServerPlugin
export const Players = MinecraftServerPlugin

export const routes = {
  List,
  Detail,
  Admin,
  Editor,
  Seasons,
  Operations,
  Players,
  'minecraft-server/List': List,
  'minecraft-server/Detail': Detail,
  'minecraft-server/Admin': Admin,
  'minecraft-server/Editor': Editor,
  'minecraft-server/Seasons': Seasons,
  'minecraft-server/Operations': Operations,
  'minecraft-server/Players': Players,
}

export {
  List as 'minecraft-server/List',
  Detail as 'minecraft-server/Detail',
  Admin as 'minecraft-server/Admin',
  Editor as 'minecraft-server/Editor',
  Seasons as 'minecraft-server/Seasons',
  Operations as 'minecraft-server/Operations',
  Players as 'minecraft-server/Players',
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-minecraft-server-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = `${markdownEditorStyles}\n${minecraftStyles}`
}

export default defineYuDreamPlugin({
  routes,
  default: List,
  install,
})
