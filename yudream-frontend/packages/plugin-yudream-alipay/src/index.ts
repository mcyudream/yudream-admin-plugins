import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import AlipayPlugin from './AlipayPlugin.vue'

export const Settings = AlipayPlugin
export const Orders = AlipayPlugin
export const MyOrders = AlipayPlugin

export const routes = {
  Settings,
  'yudream-alipay/Settings': Settings,
  'yudream-alipay/Orders': Orders,
  'yudream-alipay/MyOrders': MyOrders,
}

export default defineYuDreamPlugin({
  routes,
  default: Settings,
})
