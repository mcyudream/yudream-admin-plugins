import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import DashboardCurrentPlayerCard from './components/DashboardCurrentPlayerCard.vue'
import DashboardLegacyActionCard from './components/DashboardLegacyActionCard.vue'
import DashboardSkinPreviewCard from './components/DashboardSkinPreviewCard.vue'
import DashboardStatsCard from './components/DashboardStatsCard.vue'
import SkinPlugin from './SkinPlugin.vue'

export const Dashboard = SkinPlugin
export const Closet = SkinPlugin
export const Settings = SkinPlugin
export const Migration = SkinPlugin
export const Players = SkinPlugin
export const Textures = SkinPlugin
export const PlayerManagement = SkinPlugin
export const TextureManagement = SkinPlugin
export const ClosetManagement = SkinPlugin
export const ACTION_CARD = DashboardLegacyActionCard

export const routes = {
  Dashboard,
  Closet,
  Settings,
  Migration,
  Players,
  Textures,
  PlayerManagement,
  TextureManagement,
  ClosetManagement,
  ACTION_CARD,
  DashboardCurrentPlayerCard,
  DashboardSkinPreviewCard,
  DashboardStatsCard,
  'yudream-skin/DashboardCurrentPlayerCard': DashboardCurrentPlayerCard,
  'yudream-skin/DashboardSkinPreviewCard': DashboardSkinPreviewCard,
  'yudream-skin/DashboardStatsCard': DashboardStatsCard,
  'yudream-skin/Home': Dashboard,
  'yudream-skin/Dashboard': Dashboard,
  'yudream-skin/Closet': Closet,
  'yudream-skin/Settings': Settings,
  'yudream-skin/Migration': Migration,
  'yudream-skin/Players': Players,
  'yudream-skin/Textures': Textures,
  'yudream-skin/PlayerManagement': PlayerManagement,
  'yudream-skin/TextureManagement': TextureManagement,
  'yudream-skin/ClosetManagement': ClosetManagement,
  'yudream-skin/System': Settings,
  'blessing-skin/Home': Dashboard,
  'blessing-skin/Dashboard': Dashboard,
  'blessing-skin/Players': Players,
  'blessing-skin/Textures': Textures,
  'blessing-skin/Closet': Closet,
  'blessing-skin/System': Settings,
}

export default defineYuDreamPlugin({
  routes,
  default: Dashboard,
})
