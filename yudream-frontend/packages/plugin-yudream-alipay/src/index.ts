import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import alipayStyles from './styles.css?inline'
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

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-alipay-style'
  if (document.getElementById(id)) {
    return
  }
  const style = document.createElement('style')
  style.id = id
  style.textContent = alipayStyles
  document.head.appendChild(style)
}

export default defineYuDreamPlugin({
  routes,
  default: Settings,
  install,
})
