import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
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

export default defineYuDreamPlugin({
  routes,
  default: Overview,
})
