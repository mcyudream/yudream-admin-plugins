import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import { disposeAccent, installAccent } from './accent'
import { disposeNavSlider, installNavSlider } from './nav-slider'
import { disposeSounds, installSounds } from './sounds'
import './theme.css'
import 'virtual:uno.css'

/**
 * NECO 像素风主题：无路由与管理菜单。theme.css 由宿主 theme-runtime 按
 * SITE scope 注入/启停；install/dispose 在主题激活期间由宿主调用，负责
 * 8-bit 交互音效、6 色强调色切换器与导航活动项像素滑块。
 */
export default defineYuDreamPlugin({
  install() {
    installSounds()
    installAccent()
    installNavSlider()
  },
  dispose() {
    disposeNavSlider()
    disposeAccent()
    disposeSounds()
  },
})
