import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import { disposeAccent, installAccent } from './accent'
import { disposeSounds, installSounds } from './sounds'
import About from './theme/About.vue'
import Activities from './theme/Activities.vue'
import Chrome from './theme/Chrome.vue'
import Home from './theme/Home.vue'
import News from './theme/News.vue'
import Servers from './theme/Servers.vue'
import './theme.css'
import 'virtual:uno.css'

/**
 * NECO 像素风主题：theme.css 由宿主 theme-runtime 按 SITE scope 注入/启停；
 * routes 承载 Vue 原生主题页（theme/Chrome 接管公开站页头页脚、theme/Home
 * 经 homeComponent 接管首页，服务器/活动经 @PluginRoute(publicAccess, siteNav)
 * 进导航）；install/dispose 在主题激活期间由宿主调用，负责 MC 点击音效与
 * 6 色强调色切换器。导航滑块改由 Chrome.vue 自己渲染。
 */
export default defineYuDreamPlugin({
  routes: {
    'theme/Chrome': Chrome,
    'theme/Home': Home,
    'theme/Servers': Servers,
    'theme/Activities': Activities,
    'theme/News': News,
    'theme/About': About,
  },
  install() {
    installSounds()
    installAccent()
  },
  dispose() {
    disposeAccent()
    disposeSounds()
  },
})
