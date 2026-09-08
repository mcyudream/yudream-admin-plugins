import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
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

export default defineYuDreamPlugin({
  routes,
  default: Overview,
})
