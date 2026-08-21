import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import wordleStyles from './styles.css?inline'
import WordlePlugin from './WordlePlugin.vue'

export const Overview = WordlePlugin
export const Words = WordlePlugin
export const Games = WordlePlugin
export const Players = WordlePlugin
export const MyStats = WordlePlugin

export const routes = {
  Overview,
  Words,
  Games,
  Players,
  MyStats,
  'wordle/Overview': Overview,
  'wordle/Words': Words,
  'wordle/Games': Games,
  'wordle/Players': Players,
  'wordle/MyStats': MyStats,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-wordle-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = wordleStyles
}

export default defineYuDreamPlugin({
  routes,
  default: Overview,
  install,
})
