import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import McWikiPlugin from './McWikiPlugin.vue'
import PublicEncyclopedia from './pages/PublicEncyclopedia.vue'

export const CraftingRecipes = McWikiPlugin
export const Items = McWikiPlugin
export const Jobs = McWikiPlugin
export const Versions = McWikiPlugin
export const PublicEncyclopediaPage = PublicEncyclopedia

export const routes = {
  CraftingRecipes,
  Items,
  Jobs,
  Versions,
  'mc-wiki/CraftingRecipes': CraftingRecipes,
  'mc-wiki/Items': Items,
  'mc-wiki/Jobs': Jobs,
  'mc-wiki/Versions': Versions,
  'mc-wiki/PublicEncyclopedia': PublicEncyclopedia,
}

export default defineYuDreamPlugin({
  routes,
  default: CraftingRecipes,
})
