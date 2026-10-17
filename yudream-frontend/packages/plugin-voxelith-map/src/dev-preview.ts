/**
 * 本地开发预览入口（不参与库构建，也不会打进插件 JAR）。
 * 真实 viewer 仍然复用同一套组件与 WebGL 渲染核心；需启动后端（/api/plugins/voxelith-map）
 * 才能看到真实地图。这里只提供一个最小可用的 fake SDK 把 Viewer 挂起来做结构调试。
 */
import { createApp } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import Viewer from './pages/Viewer.vue'

const sdk = {
  version: 'development',
  pluginCode: 'voxelith-map',
  account: { userId: 'development', username: 'development', permissions: [] },
  http: {
    get: async () => { throw new Error('Mock preview 不调用真实 HTTP，请启动后端') },
    post: async () => { throw new Error('Mock preview 不调用真实 HTTP') },
    put: async () => { throw new Error('Mock preview 不调用真实 HTTP') },
    request: async () => { throw new Error('Mock preview 不调用真实 HTTP') },
    blob: async () => { throw new Error('Mock preview 不调用真实 HTTP') },
    url: (path: string) => path,
  },
  files: {
    uploadImage: async () => { throw new Error('Mock preview 不上传文件') },
    assetUrl: (url?: string) => url ?? '',
  },
} as unknown as YuDreamPluginSdk

const query = Object.fromEntries(new URLSearchParams(window.location.search).entries())
const route = { query, params: {}, path: '', fullPath: '', name: undefined, matched: [], meta: {}, redirectedFrom: undefined } as unknown as RouteLocationNormalizedLoaded

createApp(Viewer, { sdk, route }).mount('#app')
