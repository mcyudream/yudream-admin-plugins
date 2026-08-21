import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import ponyStyles from './styles.css?inline'
import PonyPlugin from './PonyPlugin.vue'

export const Overview = PonyPlugin
export const Games = PonyPlugin
export const Players = PonyPlugin
export const MyStats = PonyPlugin

export const routes = {
  Overview,
  Games,
  Players,
  MyStats,
  'pony/Overview': Overview,
  'pony/Games': Games,
  'pony/Players': Players,
  'pony/MyStats': MyStats,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-pony-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = ponyStyles
}

export default defineYuDreamPlugin({
  routes,
  default: Overview,
  install,
})
