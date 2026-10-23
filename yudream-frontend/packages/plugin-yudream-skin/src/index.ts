import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PropType } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { defineComponent, h } from 'vue'
import 'virtual:uno.css'
import './styles.css'
import DashboardCurrentPlayerCard from './components/DashboardCurrentPlayerCard.vue'
import DashboardLegacyActionCard from './components/DashboardLegacyActionCard.vue'
import DashboardSkinPreviewCard from './components/DashboardSkinPreviewCard.vue'
import DashboardStatsCard from './components/DashboardStatsCard.vue'
import SkinPlugin from './SkinPlugin.vue'
import SkinTexturePeekCard from './components/SkinTexturePeekCard.vue'
import { bindSkinTexturePeek, resetSkinTexturePeek } from './peek-runtime'
import type { SkinPage } from './types'

function skinRoutePage(name: string, page: SkinPage, scope: 'user' | 'admin') {
  return defineComponent({
    name,
    props: {
      sdk: { type: Object as PropType<YuDreamPluginSdk>, required: true },
      route: { type: Object as PropType<RouteLocationNormalizedLoaded> },
    },
    setup(props) {
      return () => h(SkinPlugin, { sdk: props.sdk, page, scope })
    },
  })
}

export const Dashboard = skinRoutePage('SkinDashboardRoute', 'dashboard', 'user')
export const Closet = skinRoutePage('SkinClosetRoute', 'closet', 'user')
export const Settings = skinRoutePage('SkinSettingsRoute', 'settings', 'admin')
export const Players = skinRoutePage('SkinPlayersRoute', 'players', 'user')
export const Textures = skinRoutePage('SkinTexturesRoute', 'textures', 'user')
export const PlayerManagement = skinRoutePage('SkinPlayerManagementRoute', 'playerManagement', 'admin')
export const TextureManagement = skinRoutePage('SkinTextureManagementRoute', 'textureManagement', 'admin')
export const ClosetManagement = skinRoutePage('SkinClosetManagementRoute', 'closetManagement', 'admin')
export const ACTION_CARD = DashboardLegacyActionCard

export const routes = {
  Dashboard,
  Closet,
  Settings,
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

let disposeTexturePeek: (() => void) | undefined

export default defineYuDreamPlugin({
  routes,
  default: Dashboard,
  install(sdk) {
    // 实体卡：登记本插件材质类型（键 yudream-skin:texture），宿主与其他插件页面可经 YdRelatedEntity 复用
    bindSkinTexturePeek(sdk)
    disposeTexturePeek = sdk.relatedEntity?.register('texture', SkinTexturePeekCard)
  },
  dispose() {
    disposeTexturePeek?.()
    disposeTexturePeek = undefined
    resetSkinTexturePeek()
  },
})
