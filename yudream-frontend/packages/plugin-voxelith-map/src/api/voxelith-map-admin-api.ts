import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type {
  ClientJarEntry,
  MapAdmin,
  CreateMapPayload,
  PutClientJarPayload,
  SetDefaultViewPayload,
  EngineStatus,
  MarkersResponse,
  ModPackListing,
  PanoramaStatus,
  PanoramaTriggerPayload,
  Preview,
  RenderTask,
  ShardWorkerStartPayload,
  ShardWorkerStatus,
  UpdateMapPayload,
} from '../types'

/**
 * 管理接口薄封装（需要 plugin:voxelith-map:manage 权限）。
 * sdk.http 已自动加前缀 /api/plugins/voxelith-map。
 *
 * 错误穿透：宿主把业务错误包成 { code, data, message } 的 Axios 风格错误，
 * 这里统一拆出可读 message（data.response?.data?.message || data.message || fallback），
 * 让上层 toast / 状态栏直接展示。
 */
function normalizeError(e: unknown, fallback: string): Error {
  const anyErr = e as {
    response?: { data?: { message?: string } }
    message?: string
  }
  const message =
    anyErr?.response?.data?.message
    || anyErr?.message
    || fallback
  return new Error(message)
}

/**
 * 把「带权限的图片端点」取成 object URL。
 *
 * 管理端图片（preview.png / window.png）挂在 plugin:voxelith-map:manage 权限下，
 * 而浏览器的 `<img src>` 带不上 Bearer 令牌 —— 直连必定拿到
 * `400 {"code":1000,"message":"无此插件权限：plugin:voxelith-map:manage"}`，页面只会显示破图。
 * 因此图片必须经 sdk.http.blob()（它会补上鉴权）取回二进制，再转成 object URL 喂给 `<img>`。
 *
 * 注意 sdk.http.blob() 返回的是 { data, headers } 包装对象，不是裸 Blob：
 * 直接把它传给 URL.createObjectURL 会抛 TypeError（这里已兼容两种形态）。
 */
export async function fetchImageObjectUrl(sdk: YuDreamPluginSdk, path: string): Promise<string> {
  const response = await sdk.http.blob(path) as unknown
  const data = response instanceof Blob
    ? response
    : (response as { data?: unknown } | null | undefined)?.data
  if (!(data instanceof Blob)) {
    throw new Error('图片端点未返回二进制内容')
  }
  return URL.createObjectURL(data)
}

/**
 * 拉取任务的**完整日志**并触发另存为。
 *
 * <p>任务文档里只有日志尾部（4000 字），完整输出由服务端单独保留；直连地址带不上鉴权令牌，
 * 因此经 SDK 取回二进制、转成 object URL 再交给浏览器保存。任务中心与地图详情页共用这一份实现。</p>
 */
export async function downloadTaskLog(sdk: YuDreamPluginSdk, taskId: string): Promise<void> {
  const url = await fetchImageObjectUrl(sdk, `/admin/tasks/${encodeURIComponent(taskId)}/log`)
  const link = document.createElement('a')
  link.href = url
  link.download = `${taskId}.log`
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 交给浏览器读取后再释放 object URL
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

export type VoxelithMapAdminApi = ReturnType<typeof createVoxelithMapAdminApi>

export function createVoxelithMapAdminApi(sdk: YuDreamPluginSdk) {
  const enc = encodeURIComponent
  const get = <T>(path: string, fallback = '请求失败'): Promise<T> =>
    sdk.http.get<T>(path).catch((e: unknown) => { throw normalizeError(e, fallback) })
  const post = <T>(path: string, body?: unknown, fallback = '请求失败'): Promise<T> =>
    sdk.http.post<T>(path, body).catch((e: unknown) => { throw normalizeError(e, fallback) })
  const put = <T>(path: string, body?: unknown, fallback = '请求失败'): Promise<T> =>
    sdk.http.request<T>(path, { method: 'PUT', data: body }).catch((e: unknown) => { throw normalizeError(e, fallback) })
  const del = (path: string, fallback = '请求失败'): Promise<unknown> =>
    sdk.http.request(path, { method: 'DELETE' }).catch((e: unknown) => { throw normalizeError(e, fallback) })
  const delJson = <T>(path: string, fallback = '请求失败'): Promise<T> =>
    sdk.http.request<T>(path, { method: 'DELETE' }).catch((e: unknown) => { throw normalizeError(e, fallback) })

  /**
   * 限时读取：只包「决定页面能否渲染」的读接口（列表、详情、任务、标注、状态）。
   *
   * <p>宿主 SDK 的 http 客户端没有超时，后端或对象存储一旦卡住，页面的加载态就会永远停在
   * 那里——用户看到的就是「刷新后无响应」，而且拿不到任何可诊断的信息。这里给读接口加个上限：
   * 超时后报出明确错误（页面因此能显示错误与重试），而不是无限等待。
   * 写操作（导入、渲染、上传、生成预览）都是长任务，绝不套这个上限。</p>
   */
  const READ_TIMEOUT_MS = 20_000
  const timedRead = <T>(what: string, run: () => Promise<T>): Promise<T> =>
    new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`${what}超时：${READ_TIMEOUT_MS / 1000} 秒内没有响应（后端或存储可能正忙）`))
      }, READ_TIMEOUT_MS)
      run().then(
        (value) => { clearTimeout(timer); resolve(value) },
        (error) => { clearTimeout(timer); reject(error) },
      )
    })

  return {
    maps: () => timedRead('地图列表加载', () => get<{ maps: MapAdmin[] }>('/admin/maps', '地图列表加载失败')),
    map: (id: string) => timedRead('地图详情加载', () => get<MapAdmin>(`/admin/maps/${enc(id)}`, '地图详情加载失败')),
    /**
     * 同一世界的各维度地图（渲染页的「切换维度」）。
     *
     * <p>含未渲染与已归档的维度：渲染页正是要看到「哪些维度还没渲染」，才能逐个切过去渲染。</p>
     */
    dimensions: (id: string) => timedRead('维度列表加载', () =>
      get<{ maps: MapAdmin[] }>(`/admin/maps/${enc(id)}/dimensions`, '维度列表加载失败')),
    createMap: (payload: CreateMapPayload) => post<MapAdmin>('/admin/maps', payload, '创建地图失败'),
    updateMap: (id: string, payload: UpdateMapPayload) =>
      post<MapAdmin>(`/admin/maps/${enc(id)}`, payload, '更新地图失败'),

    render: (id: string) => post<RenderTask>(`/admin/maps/${enc(id)}/render`, undefined, '触发渲染失败'),
    tasks: () => timedRead('任务列表加载', () => get<{ tasks: RenderTask[] }>('/admin/tasks', '任务列表加载失败')),
    mapTasks: (id: string) =>
      get<{ tasks: RenderTask[] }>(`/admin/maps/${enc(id)}/tasks`, '任务列表加载失败'),
    cancelTask: (taskId: string) =>
      post<{ canceled: boolean }>(`/admin/tasks/${enc(taskId)}/cancel`, undefined, '取消任务失败'),

    engine: () => timedRead('引擎状态加载', () => get<EngineStatus>('/admin/engine', '引擎状态加载失败')),
    health: () => get<{ ok: boolean }>('/health', '自检失败'),

    preview: (id: string) => get<Preview>(`/admin/maps/${enc(id)}/preview`, '预览状态加载失败'),
    triggerPreview: (id: string) => post<Preview>(`/admin/maps/${enc(id)}/preview`, undefined, '触发生成预览失败'),
    /**
     * 框选底图 PNG 的**直连地址**。仅作 `<img>` 的过渡 src；正式取图请用
     * {@link fetchImageObjectUrl}，否则必然因「无此插件权限」渲染成破图。
     */
    previewImageUrl: (id: string, step: number) =>
      sdk.http.url(`/admin/maps/${enc(id)}/preview.png?step=${encodeURIComponent(String(step))}`),
    /** 指定窗口区域 PNG 的直连地址；同理见 {@link fetchImageObjectUrl}。 */
    windowImageUrl: (id: string, params: { minX: number, minZ: number, size: number, step: number }) =>
      sdk.http.url(
        `/admin/maps/${enc(id)}/window.png?minX=${params.minX}&minZ=${params.minZ}`
        + `&size=${params.size}&step=${params.step}`,
      ),

    panorama: (id: string) =>
      get<PanoramaStatus>(`/admin/maps/${enc(id)}/panorama`, '全景图状态加载失败'),
    triggerPanorama: (id: string, payload: PanoramaTriggerPayload) =>
      post<PanoramaStatus>(`/admin/maps/${enc(id)}/panorama`, payload, '触发全景图烘焙失败'),

    shardWorker: (id: string) =>
      get<ShardWorkerStatus>(`/admin/maps/${enc(id)}/shard-worker`, '分片 worker 状态加载失败'),
    startShardWorker: (id: string, payload: ShardWorkerStartPayload) =>
      post<ShardWorkerStatus>(`/admin/maps/${enc(id)}/shard-worker`, payload, '启动分片 worker 失败'),
    stopShardWorker: (id: string) =>
      delJson<ShardWorkerStatus>(`/admin/maps/${enc(id)}/shard-worker`, '停止分片 worker 失败'),

    markers: (id: string) => timedRead('标注加载', () => get<MarkersResponse>(`/admin/maps/${enc(id)}/markers`, '标注加载失败')),
    /** 归档地图：从管理列表与公开查看器隐藏（数据保留） */
    archiveMap: (id: string) =>
      post<MapAdmin>(`/admin/maps/${enc(id)}/archive`, undefined, '归档地图失败'),
    /** 恢复已归档地图 */
    unarchiveMap: (id: string) =>
      post<MapAdmin>(`/admin/maps/${enc(id)}/unarchive`, undefined, '恢复地图失败'),
    /** 已归档地图列表 */
    archivedMaps: () => timedRead('归档列表加载', () => get<{ maps: MapAdmin[] }>('/admin/archived-maps', '归档列表加载失败')),
    /**
     * 实例导入能力探测：服务端能否直接读取面板实例文件。
     *
     * <p>true → 走 {@link startImportJob}（服务端作业，关页不中断）；false → 面板插件未装/未启用，
     * 或旧版面板没有实例文件访问契约，此时走浏览器搬运（{@link startBrowserImport} 起）。</p>
     */
    instanceImportCapability: () => timedRead('实例导入能力探测', () =>
      get<{ serverImport: boolean }>('/admin/instance-import-capability', '实例导入能力探测失败')),
    /**
     * 发起一次实例导入作业（服务端后台执行）：面板侧打包 world（与可选 mods）→ 分块拉回插件存储
     * → 建图。返回任务；进度、日志与取消都在任务中心，与页面是否打开无关。
     */
    startImportJob: (payload: {
      instanceId: string
      /** 实例显示名（任务主体与地图名兜底）；只用于展示 */
      instanceName?: string
      name?: string
      /** 存档版本；留空由服务端从实例根目录 jar 文件名判定 */
      version?: string
      withMods?: boolean
      /** 要导入的维度目录（world / world_nether / world_the_end），每个各建一张地图；留空由服务端自行探测 */
      worldDirs?: string[]
    }) => post<RenderTask>('/admin/import-instance-jobs', payload, '发起实例导入失败'),
    /**
     * 发起一次**浏览器搬运**的导入任务（面板插件没有实例文件访问契约时的路径）。
     *
     * <p>任务先在任务中心登记，页面搬运并把进展回报进来（{@link reportImportProgress}），存档上传
     * 完成后交给 {@link completeImportJob} 由服务端建图；中途失败用 {@link failImportJob} 落原因。</p>
     */
    startBrowserImport: (payload: {
      instanceId: string
      instanceName?: string
      name?: string
      version?: string
      withMods?: boolean
      /** 页面探测到的维度目录（见 {@link worldDirNames}）：服务端读不到实例文件，只能由页面告知 */
      worldDirs?: string[]
    }) => post<RenderTask>('/admin/import-instance-jobs/browser', payload, '发起实例导入任务失败'),
    /** 页面回报搬运进度：阶段 / 百分比 / 明细（只写任务，不影响搬运本身）。 */
    reportImportProgress: (taskId: string, payload: { stage: string, percent: number, detail?: string }) =>
      post<{ ok: boolean }>(`/admin/import-instance-jobs/${enc(taskId)}/progress`, payload, '回报导入进度失败'),
    /** 存档（与可选模组包）上传完成：交给服务端接管建图，任务进度与日志继续在任务中心。 */
    completeImportJob: (taskId: string, payload: {
      instanceId: string
      instanceName?: string
      name?: string
      version?: string
      withMods?: boolean
      worldFileId: string
      modsFileId?: string
      modsPackName?: string
      /** 已打包进存档包的维度目录（决定服务端把同一个包解成几张地图） */
      worldDirs?: string[]
    }) => post<{ ok: boolean }>(`/admin/import-instance-jobs/${enc(taskId)}/complete`, payload, '提交导入结果失败'),
    /** 页面报告导入失败（面板报错、上传失败、用户取消搬运等）。 */
    failImportJob: (taskId: string, message: string) =>
      post<{ ok: boolean }>(`/admin/import-instance-jobs/${enc(taskId)}/fail`, { message }, '记录导入失败原因失败'),

    /** 用已上传的新存档覆盖地图存档（实例同步最新文件用） */
    replaceInstanceWorld: (id: string, payload: { worldFileId: string }) =>
      put<unknown>(`/admin/maps/${enc(id)}/world.zip`, payload, '存档同步失败'),
    /** 发起删除作业（服务端后台执行）：回收产物、存档与地图记录 */
    startDeleteJob: (id: string) =>
      post<RenderTask>(`/admin/maps/${enc(id)}/delete-jobs`, undefined, '发起删除作业失败'),
    /** 下载地图当前存档 zip（base64） */
    downloadWorldZip: (id: string) =>
      get<{ zipBase64: string, name?: string }>(`/admin/maps/${enc(id)}/world.zip`, '存档下载失败'),
    /**
     * 解析模组包，列出包内所有模组 jar。
     *
     * <p>服务端要把包物化到本地才能读 zip 中央目录（一次完整对象读），因此**按需**调用、不套读超时：
     * 大包解析会慢，界面给加载态即可，别用 20 秒读超时把它掐掉。</p>
     */
    inspectModPack: (payload: { fileId: string, name?: string }) =>
      post<ModPackListing>('/admin/mod-packs/inspect', payload, '模组包解析失败'),

    /** 客户端 jar 库：列出全部预上传版本 */
    clientJars: () => timedRead('客户端 jar 库加载', () => get<{ jars: ClientJarEntry[] }>('/admin/client-jars', '客户端 jar 库加载失败')),
    /** 新增/覆盖一个版本（文件先经宿主上传，传平台文件 id） */
    addClientJar: (payload: PutClientJarPayload) =>
      put<ClientJarEntry>('/admin/client-jars', payload, '添加客户端 jar 失败'),
    /** 从库中删除一个版本（不影响已创建的地图） */
    removeClientJar: (version: string) =>
      del(`/admin/client-jars/${enc(version)}`, '删除客户端 jar 失败'),
    /** 保存默认初始视角（可携带封面截图 base64） */
    setDefaultView: (id: string, payload: SetDefaultViewPayload) =>
      put<unknown>(`/admin/maps/${enc(id)}/default-view`, payload, '保存默认视角失败'),
    /** 清除默认初始视角（回到内置机位；已保存的封面保留） */
    clearDefaultView: (id: string) =>
      del(`/admin/maps/${enc(id)}/default-view`, '清除默认视角失败'),
    saveMarkers: (id: string, body: unknown) =>
      put<unknown>(`/admin/maps/${enc(id)}/markers`, body, '保存标注失败'),

    /** SSE 事件流 URL（事件名 connected / task） */
    taskEventsUrl: () => sdk.http.url('/admin/tasks/events'),
  }
}
