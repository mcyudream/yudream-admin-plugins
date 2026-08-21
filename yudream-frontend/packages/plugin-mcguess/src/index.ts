import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import mcguessStyles from './styles.css?inline'
import McguessPlugin from './McguessPlugin.vue'

export const Overview = McguessPlugin
export const Games = McguessPlugin
export const Players = McguessPlugin
export const MyStats = McguessPlugin

export const routes = {
  Overview,
  Games,
  Players,
  MyStats,
  'mcguess/Overview': Overview,
  'mcguess/Games': Games,
  'mcguess/Players': Players,
  'mcguess/MyStats': MyStats,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-mcguess-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = mcguessStyles
}

export default defineYuDreamPlugin({
  routes,
  default: Overview,
  install,
})
