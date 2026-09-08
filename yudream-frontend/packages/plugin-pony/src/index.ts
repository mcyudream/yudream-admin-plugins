import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
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

export default defineYuDreamPlugin({
  routes,
  default: Overview,
})
