import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'

/**
 * 方块地形主题的前端入口包装：站点外观由 site-preset.json、theme.css 与
 * media/ 静态资产表达（随插件前端目录分发，经网站工作台导入应用），
 * 本 remote 不承载路由页面，仅为平台统一打包契约提供入口模块。
 */
export default defineYuDreamPlugin({})
