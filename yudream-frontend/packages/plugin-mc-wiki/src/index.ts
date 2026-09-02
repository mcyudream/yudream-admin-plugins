import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import mcWikiStyles from './styles.css?inline'
import McWikiPlugin from './McWikiPlugin.vue'

export const CraftingRecipes = McWikiPlugin
export const Items = McWikiPlugin
export const Jobs = McWikiPlugin
export const Versions = McWikiPlugin

export const routes = {
  CraftingRecipes,
  Items,
  Jobs,
  Versions,
  'mc-wiki/CraftingRecipes': CraftingRecipes,
  'mc-wiki/Items': Items,
  'mc-wiki/Jobs': Jobs,
  'mc-wiki/Versions': Versions,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-mc-wiki-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = mcWikiStyles
}

export default defineYuDreamPlugin({
  routes,
  default: CraftingRecipes,
  install,
})
