import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import MinecraftServerPlugin from './MinecraftServerPlugin.vue'

export const List = MinecraftServerPlugin
export const Closed = MinecraftServerPlugin
export const Detail = MinecraftServerPlugin
export const Admin = MinecraftServerPlugin
export const Editor = MinecraftServerPlugin
export const Seasons = MinecraftServerPlugin
export const Operations = MinecraftServerPlugin
export const Players = MinecraftServerPlugin

export const routes = {
  List, Closed, Detail, Admin, Editor, Seasons, Operations, Players,
  'minecraft-server/List': List,
  'minecraft-server/Closed': Closed,
  'minecraft-server/Detail': Detail,
  'minecraft-server/Admin': Admin,
  'minecraft-server/Editor': Editor,
  'minecraft-server/Seasons': Seasons,
  'minecraft-server/Operations': Operations,
  'minecraft-server/Players': Players,
}

export {
  List as 'minecraft-server/List',
  Closed as 'minecraft-server/Closed',
  Detail as 'minecraft-server/Detail',
  Admin as 'minecraft-server/Admin',
  Editor as 'minecraft-server/Editor',
  Seasons as 'minecraft-server/Seasons',
  Operations as 'minecraft-server/Operations',
  Players as 'minecraft-server/Players',
}

export default defineYuDreamPlugin({ routes, default: List })
