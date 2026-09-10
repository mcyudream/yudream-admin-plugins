import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import { disposeSounds, installSounds } from './sounds'
import './theme.css'

/**
 * NECO 像素风主题：无路由与管理菜单。theme.css 由宿主 theme-runtime 按
 * SITE scope 注入/启停；install/dispose 在主题激活期间由宿主调用，负责
 * 8-bit 交互音效与音效开关。
 */
export default defineYuDreamPlugin({
  install: installSounds,
  dispose: disposeSounds,
})
