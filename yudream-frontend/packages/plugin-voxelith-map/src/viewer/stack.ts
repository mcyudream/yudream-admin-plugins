/**
 * 三维渲染栈（three.js + vendored 渲染核心）的按需加载入口。
 *
 * <p>这一栈约 700 KB（three.js 占大头）。若被路由页面静态 import，它就会并进 remoteEntry.js，
 * 出现在**每个**插件页面的关键路径上：管理列表、画廊、标注页刷新时也要下载、解析并求值整份
 * 3D 栈，主线程被占住、可交互时间被拖后——用户看到的就是「刷新插件页面无响应」。</p>
 *
 * <p>改成动态 import 后，只有真正要建三维视图时才拉取这块 chunk：公开查看页打开地图、
 * 详情页点「加载三维预览」。Promise 做记忆化，多页面/多次调用只加载一次；失败时清掉缓存，
 * chunk 下载失败（断网、插件重载）后再次进入页面仍可重试。</p>
 */

export interface ViewerStack {
  VoxelithViewer: typeof import('./VoxelithViewer').VoxelithViewer
  loadManifest: typeof import('../vendor/voxelith/viewer/index').loadManifest
  supportsDisplayP3: typeof import('../vendor/voxelith/viewer/index').supportsDisplayP3
  markerSetSchema: typeof import('../vendor/voxelith/core/index').markerSetSchema
}

let stack: Promise<ViewerStack> | null = null

export function loadViewerStack(): Promise<ViewerStack> {
  stack ??= Promise.all([
    import('./VoxelithViewer'),
    import('../vendor/voxelith/viewer/index'),
    import('../vendor/voxelith/core/index'),
  ])
    .then(([viewer, viewerIndex, core]) => ({
      VoxelithViewer: viewer.VoxelithViewer,
      loadManifest: viewerIndex.loadManifest,
      supportsDisplayP3: viewerIndex.supportsDisplayP3,
      markerSetSchema: core.markerSetSchema,
    }))
    .catch((e: unknown) => {
      stack = null
      throw e
    })
  return stack
}
