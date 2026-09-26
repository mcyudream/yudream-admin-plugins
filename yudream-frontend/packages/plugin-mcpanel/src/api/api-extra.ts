import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

/**
 * mcpanel 扩展 API：实例、节点镜像、镜像目录、核心下载、模板、设置。
 * 全部 /admin/**；可选字段一律经 options/表选择，禁止手输内部 ID。
 */
export function createMcPanelApi(sdk: YuDreamPluginSdk) {
  const inst = (id: string) => `admin/instances/${encodeURIComponent(id)}`
  const node = (id: string) => `admin/nodes/${encodeURIComponent(id)}`

  function query(params: Record<string, string | number | boolean | undefined>) {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') {
        search.set(key, String(value))
      }
    })
    const value = search.toString()
    return value ? `?${value}` : ''
  }

  return {
    pageInstances: (page: number, size: number, keyword?: string, status?: string, nodeId?: string) =>
      sdk.http.get(`admin/instances${query({ page, size, keyword, status, nodeId })}`),
    instanceDetail: (id: string) => sdk.http.get(inst(id)),
    createInstance: (data: Record<string, unknown>) => sdk.http.post('admin/instances', data),
    updateInstance: (id: string, data: Record<string, unknown>) =>
      sdk.http.request(inst(id), { method: 'PUT', data }),
    deleteInstance: (id: string, purge: boolean) =>
      sdk.http.request<void>(`${inst(id)}${query({ purge })}`, { method: 'DELETE' }),
    instanceAction: (id: string, action: 'start' | 'stop' | 'restart' | 'kill', timeoutSec?: number) =>
      sdk.http.post(`${inst(id)}/${action}${query({ timeoutSec })}`),
    instanceCommand: (id: string, command: string) =>
      sdk.http.post(`${inst(id)}/command`, { command }),
    /** TPS 探测（Paper 系）：节点代发 tps 控制台命令，输出经控制台流返回由前端解析。 */
    tpsProbe: (id: string) =>
      sdk.http.post(`${inst(id)}/tps-probe`, {}),
    instanceOutput: (id: string, since?: string, tail?: number) =>
      sdk.http.get(`${inst(id)}/output${query({ since, tail })}`),
    /** 控制台实时输出：订阅节点 attach 泵后经 SSE 事件流推送（300ms 聚合）。 */
    outputSubscribe: (id: string) => sdk.http.post(`${inst(id)}/output/subscribe`, {}),
    outputUnsubscribe: (id: string) => sdk.http.post(`${inst(id)}/output/unsubscribe`, {}),
    outputEventsUrl: (id: string) => sdk.http.url(`/${inst(id)}/output/events`),
    /** 在线玩家探测：面板侧对实例端口做 MC Java server list ping（服务端缓存）。 */
    instancePlayers: (id: string) => sdk.http.get(`${inst(id)}/players`),
    /** 目录列表：page/size 缺省 = 整目录返回（目录树用）；keyword 按名称过滤。 */
    listFiles: (id: string, path: string, page?: number, size?: number, keyword?: string) =>
      sdk.http.get(`${inst(id)}/files${query({ path, page, size, keyword })}`),
    readFile: (id: string, path: string) =>
      sdk.http.get(`${inst(id)}/files/content${query({ path })}`),
    writeFile: (id: string, path: string, content: string, encoding: string, charset?: string) =>
      sdk.http.post(`${inst(id)}/files`, { path, content, encoding, ...(charset ? { charset } : {}) }),
    mkdir: (id: string, path: string) => sdk.http.post(`${inst(id)}/files/mkdir`, { path }),
    renameFile: (id: string, from: string, to: string) =>
      sdk.http.post(`${inst(id)}/files/rename`, { from, to }),
    deleteFile: (id: string, path: string) => sdk.http.post(`${inst(id)}/files/delete`, { path }),
    downloadFile: (id: string, path: string) =>
      sdk.http.url(`/${inst(id)}/files/download${query({ path })}`),
    /** 分块读取（file.download.chunk）：大文件只读预览用，length ≤ 96KiB。 */
    readFileChunk: (id: string, path: string, offset: number, length: number) =>
      sdk.http.get(`${inst(id)}/files/download${query({ path, offset, length })}`),
    uploadFile: (id: string, path: string, file: File) => {
      const form = new FormData()
      form.append('file', file)
      return sdk.http.request(`${inst(id)}/files/upload${query({ path })}`, {
        method: 'POST',
        data: form,
      })
    },
    /** 大文件分片上传任务：begin 需浏览器预计算的整文件 sha256（节点 commit 强校验）。 */
    beginUploadTask: (id: string, path: string, size: number, sha256: string, name?: string) =>
      sdk.http.post(`${inst(id)}/upload-tasks`, { path, size, sha256, ...(name ? { name } : {}) }),
    uploadTaskChunk: (id: string, taskId: string, offset: number, blob: Blob) => {
      const form = new FormData()
      form.append('data', blob)
      return sdk.http.request(`${inst(id)}/upload-tasks/chunk${query({ taskId, offset })}`, {
        method: 'POST',
        data: form,
      })
    },
    commitUploadTask: (id: string, taskId: string) =>
      sdk.http.post(`${inst(id)}/upload-tasks/commit${query({ taskId })}`, {}),
    cancelUploadTask: (id: string, taskId: string) =>
      sdk.http.post(`${inst(id)}/upload-tasks/cancel${query({ taskId })}`, {}),
    /** 上传任务列表（running + 近期终态）：重进页面恢复进度显示。 */
    listUploadTasks: (id: string) => sdk.http.get(`${inst(id)}/upload-tasks`),
    listBackups: (id: string) => sdk.http.get(`${inst(id)}/backups`),
    /** 受理一次节点本机备份（异步打包）：立即返回 accepted，结果经 listBackups 轮询。 */
    createBackup: (id: string) => sdk.http.post(`${inst(id)}/backups`, {}),
    /** 手动异地备份：经宿主数据备份中心推送到指定目标（异步任务，返回 jobId）。 */
    triggerOffsiteBackup: (id: string, targetCode: string) =>
      sdk.http.post(`${inst(id)}/backups/trigger`, { targetCode }),
    restoreBackup: (id: string, file: string) => sdk.http.post(`${inst(id)}/backups/restore`, { file }),
    deleteBackup: (id: string, file: string) => sdk.http.post(`${inst(id)}/backups/delete`, { file }),
    /** 实例备份保留策略（keepCount/keepDays，0=不限）；保存即触发一次节点清理。 */
    backupPolicy: (id: string) => sdk.http.get(`${inst(id)}/backup-policy`),
    saveBackupPolicy: (id: string, keepCount: number, keepDays: number) =>
      sdk.http.request(`${inst(id)}/backup-policy`, { method: 'PUT', data: { keepCount, keepDays } }),
    /** 事件触发型任务（对标 MCSM eventTask）：自动重启/自动启动，运行中可随时保存。 */
    saveEventTask: (id: string, autoRestart: boolean, autoStart: boolean) =>
      sdk.http.request(`${inst(id)}/event-task`, { method: 'PUT', data: { autoRestart, autoStart } }),
    /** 临时 SFTP：网关开启时返回 mc-短别名四件套（host/port/user/password/expiresAt），否则节点直连凭据。 */
    ftpOpen: (id: string, ttlMinutes = 120) =>
      sdk.http.post(`${inst(id)}/ftp/open`, { ttlMinutes }),
    ftpClose: (id: string) => sdk.http.post(`${inst(id)}/ftp/close`, {}),

    /** 节点本机 Docker 镜像（MCSM 镜像管理）。 */
    listNodeImages: (nodeId: string) => sdk.http.get(`${node(nodeId)}/images`),
    pullNodeImage: (nodeId: string, image: string) =>
      sdk.http.post(`${node(nodeId)}/images/pull`, { image }),
    removeNodeImage: (nodeId: string, image: string, force = false) =>
      sdk.http.request(`${node(nodeId)}/images${query({ image, force })}`, { method: 'DELETE' }),
    nodeTask: (nodeId: string, taskId: string) =>
      sdk.http.get(`${node(nodeId)}/tasks${query({ taskId })}`),

    /** 镜像目录：内部名 + 多标签。 */
    pageDockerImages: (page: number, size: number, keyword?: string) =>
      sdk.http.get(`admin/docker-images${query({ page, size, keyword })}`),
    dockerImageOptions: () => sdk.http.get('admin/docker-images/options'),
    dockerImageDetail: (id: string) =>
      sdk.http.get(`admin/docker-images/${encodeURIComponent(id)}`),
    saveDockerImage: (data: Record<string, unknown>) =>
      sdk.http.post('admin/docker-images', data),
    deleteDockerImage: (id: string) =>
      sdk.http.request(`admin/docker-images/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    /** 核心类型 / 版本（YdTablePicker）/ 下载计划。 */
    listCores: () => sdk.http.get('admin/cores'),
    coreVersions: (kind: string, page: number, size: number, keyword?: string) =>
      sdk.http.get(`admin/cores/versions${query({ kind, page, size, keyword })}`),
    resolveCoreDownload: (kind: string, mcVersion: string) =>
      sdk.http.post('admin/cores/resolve', { kind, mcVersion }),

    pageTemplates: (page: number, size: number, kind?: string, keyword?: string) =>
      sdk.http.get(`admin/templates${query({ page, size, kind, keyword })}`),
    saveTemplate: (data: Record<string, unknown>) => sdk.http.post('admin/templates', data),
    templateDetail: (key: string) => sdk.http.get(`admin/templates/${encodeURIComponent(key)}`),
    deleteTemplate: (key: string) =>
      sdk.http.request(`admin/templates/${encodeURIComponent(key)}`, { method: 'DELETE' }),
    viewSettings: () => sdk.http.get('admin/settings'),
    /**
     * 保存面板设置。
     * secrets = 密钥更新（键名如 dnsAliyunAccessKeyId）：缺省 = 保持不变，空串 = 清除；
     * 真值只进宿主 SecretStore，接口只回显「已配置」布尔。
     */
    saveSettings: (data: Record<string, unknown>, secrets?: Record<string, string>) =>
      sdk.http.request('admin/settings', {
        method: 'PUT',
        data: { ...data, ...(secrets && Object.keys(secrets).length ? { secrets } : {}) },
      }),
    /** 注入制品库：上传 jar 到平台文件库，返回 { fileId, name, size, sha256 }。 */
    uploadArtifact: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return sdk.http.request<{ fileId: string, name: string, size: number, sha256: string }>(
        'admin/artifacts', { method: 'POST', data: form })
    },
    /** 制品下载地址（fileId 含 '/'，走 query 传递）。 */
    artifactDownloadUrl: (fileId: string) =>
      sdk.http.url(`/admin/artifacts/download?fileId=${encodeURIComponent(fileId)}`),
    linkOptions: () => sdk.http.get('admin/link/options'),
    /** 数据监控总览（MCSM PanelOverview）。 */
    overview: () => sdk.http.get('admin/overview'),
    /** 计划任务。 */
    pageSchedules: (instanceId: string, page: number, size: number) =>
      sdk.http.get(`admin/schedules${query({ instanceId, page, size })}`),
    saveSchedule: (data: Record<string, unknown>) => sdk.http.post('admin/schedules', data),
    deleteSchedule: (id: string) =>
      sdk.http.request(`admin/schedules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    runSchedule: (id: string) =>
      sdk.http.post(`admin/schedules/${encodeURIComponent(id)}/run`, {}),
    /** server.properties / yml / toml 结构化配置。 */
    serverConfig: (id: string, path?: string) =>
      sdk.http.get(`admin/instances/${encodeURIComponent(id)}/server-config${query({ path })}`),
    saveServerConfig: (id: string, path: string, properties: Record<string, string>, content: string, preferRaw: boolean) =>
      sdk.http.request(`admin/instances/${encodeURIComponent(id)}/server-config`, {
        method: 'PUT',
        data: { path, properties, content, preferRaw },
      }),
    /** 代理纳管（BungeeCord/Velocity）：识别（只读）、绑定保存、解除。 */
    proxyGroup: (id: string) =>
      sdk.http.get(`admin/instances/${encodeURIComponent(id)}/proxy-group`),
    detectProxyGroup: (id: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/proxy-group/detect`, {}),
    saveProxyGroup: (id: string, data: Record<string, unknown>) =>
      sdk.http.request(`admin/instances/${encodeURIComponent(id)}/proxy-group`, {
        method: 'PUT',
        data,
      }),
    deleteProxyGroup: (id: string) =>
      sdk.http.request(`admin/instances/${encodeURIComponent(id)}/proxy-group`, { method: 'DELETE' }),
    /** 实例性能历史（环形窗口，windowMs 服务端裁剪到 24h 内）。 */
    instanceMetrics: (id: string, windowMs: number) =>
      sdk.http.get(`admin/instances/${encodeURIComponent(id)}/metrics${query({ windowMs })}`),
    /** 整合包上传解析（mrpack / CurseForge zip）；CF manifest 外呼 cfwidget 可能耗时数十秒。 */
    inspectModpack: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return sdk.http.request('admin/modpacks/inspect', { method: 'POST', data: form })
    },
    /**
     * 整合包分片上传（实例创建场景，大包避免整份 multipart 超时）：
     * begin（浏览器预计算整文件 sha256）→ 4MiB 分片 → commit（面板校验后解析，返回 token 摘要）。
     */
    beginModpackUpload: (name: string, size: number, sha256: string) =>
      sdk.http.post('admin/modpacks/upload-tasks', { name, size, sha256 }),
    modpackUploadChunk: (taskId: string, offset: number, blob: Blob) => {
      const form = new FormData()
      form.append('data', blob)
      return sdk.http.request(`admin/modpacks/upload-tasks/chunk${query({ taskId, offset })}`, {
        method: 'POST',
        data: form,
      })
    },
    commitModpackUpload: (taskId: string) =>
      sdk.http.post('admin/modpacks/upload-tasks/commit', { taskId }),
    cancelModpackUpload: (taskId: string) =>
      sdk.http.request(`admin/modpacks/upload-tasks${query({ taskId })}`, { method: 'DELETE' }),
    /** 创建实例后应用整合包（核心 + 全部文件 + overrides），进度经实例页安装进度条展示。 */
    applyModpack: (id: string, token: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/modpack-apply`, { token }),
    /** 已纳管代理列表（创建向导「挂到代理」数据源）。 */
    listProxyGroups: () => sdk.http.get('admin/proxy-groups'),
    /** 子服自动挂到代理：组绑定 + 自动写入代理配置（velocity.toml / config.yml）。 */
    attachToProxy: (proxyId: string, instanceId: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(proxyId)}/proxy-group/attach`, { instanceId }),
    /** 子服软链接（独立/同步模式）：查询/建立/解除/立即同步。 */
    syncLink: (id: string) => sdk.http.get(`admin/instances/${encodeURIComponent(id)}/sync-link`),
    saveSyncLink: (id: string, sourceInstanceId: string, paths?: string[]) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/sync-link`,
        { sourceInstanceId, ...(paths?.length ? { paths } : {}) }),
    deleteSyncLink: (id: string) =>
      sdk.http.request(`admin/instances/${encodeURIComponent(id)}/sync-link`, { method: 'DELETE' }),
    runSyncLink: (id: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/sync-link/sync`, {}),
    /** authlib 注入（实例粒度）：视图 + 切换（停机才可切换，下次启动生效）。 */
    authlibInjection: (id: string) =>
      sdk.http.get(`admin/instances/${encodeURIComponent(id)}/authlib-injection`),
    applyAuthlibInjection: (id: string, enabled: boolean) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/authlib-injection`, { enabled }),
    /** 在线时长注入（实例粒度）：按制品矩阵匹配插件/模组放入实例目录。 */
    playtimeInjection: (id: string) =>
      sdk.http.get(`admin/instances/${encodeURIComponent(id)}/playtime-injection`),
    applyPlaytimeInjection: (id: string, enabled: boolean) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/playtime-injection`, { enabled }),
    /** PROXY protocol（单端口入口配套）：状态 + 一键开关（只改配置文件里已存在的键，重启生效）。 */
    instanceProxyProtocol: (id: string) =>
      sdk.http.get(`admin/instances/${encodeURIComponent(id)}/proxy-protocol`),
    setInstanceProxyProtocol: (id: string, enabled: boolean) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/proxy-protocol`, { enabled }),
    /** 单端口入口（mc-router）：状态（配置/连通/差异）+ 手动对账。 */
    entryStatus: () => sdk.http.get('admin/entry/status'),
    entryReconcile: () => sdk.http.post('admin/entry/reconcile', {}),
    /** 实例域名自动解析：视图（本地状态）/ 分配（幂等）/ 释放 / 实时校验（会外呼云商）。 */
    instanceDomain: (id: string) =>
      sdk.http.get(`admin/instances/${encodeURIComponent(id)}/domain`),
    assignInstanceDomain: (id: string, srv: boolean) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/domain`, { srv }),
    releaseInstanceDomain: (id: string) =>
      sdk.http.request(`admin/instances/${encodeURIComponent(id)}/domain`, { method: 'DELETE' }),
    verifyInstanceDomain: (id: string, srv: boolean) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/domain/verify`, { srv }),
    /** 批量实例操作 start/stop/restart/kill。 */
    batchInstanceAction: (action: string, ids: string[]) =>
      sdk.http.post('admin/instances/batch', { action, ids }),
    /** 文件压缩 / 解压（节点 file.zip / file.unzip）；zipFiles 支持多选批量打包为同一 zip。 */
    zipFile: (id: string, path: string, dest?: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/files/zip`, { path, dest }),
    zipFiles: (id: string, paths: string[], dest: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/files/zip`, { paths, dest }),
    unzipFile: (id: string, path: string, dest?: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/files/unzip`, { path, dest }),
    /** Modrinth 模组/插件（对标 MCSM ModManager）。 */
    searchModrinth: (q: string, type: string, page = 1, size = 12) => {
      const search = new URLSearchParams()
      if (q) {
        search.set('query', q)
      }
      if (type) {
        search.set('type', type)
      }
      search.set('page', String(page))
      search.set('size', String(size))
      return sdk.http.get(`admin/modrinth/search?${search.toString()}`)
    },
    resolveModrinth: (data: Record<string, unknown>) =>
      sdk.http.post('admin/modrinth/resolve', data),
    installInstanceFiles: (id: string, files: Array<{ url: string, path: string }>) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/install-files`, { files }),
    /** 重试安装：按最近一次落库的安装计划原样重建 install.run（失败/卡死恢复）。 */
    retryInstanceInstall: (id: string) =>
      sdk.http.post(`admin/instances/${encodeURIComponent(id)}/install-retry`, {}),
    /** 节点全部容器。 */
    listNodeContainers: (nodeId: string) =>
      sdk.http.get(`admin/nodes/${encodeURIComponent(nodeId)}/containers`),
    /** 审计日志。 */
    pageAudit: (page: number, size: number, action?: string, actor?: string, targetType?: string, targetId?: string) =>
      sdk.http.get(`admin/audit${query({ page, size, action, actor, targetType, targetId })}`),
    exportAudit: (action?: string, actor?: string, targetType?: string, targetId?: string) =>
      sdk.http.get(`admin/audit/export${query({ action, actor, targetType, targetId })}`),
    /** 删除单条审计记录（plugin:mcpanel:delete）。 */
    deleteAudit: (logId: string) =>
      sdk.http.request(`admin/audit/${encodeURIComponent(logId)}`, { method: 'DELETE' }),
    /** 一键清理：删除当前筛选条件下的全部审计记录（无筛选 = 清空）。 */
    clearAudit: (action?: string, actor?: string, targetType?: string, targetId?: string) =>
      sdk.http.request(`admin/audit${query({ action, actor, targetType, targetId })}`, { method: 'DELETE' }),
    /** 快速开始向导包。 */
    pageQuickPackages: (page: number, size: number, keyword?: string) =>
      sdk.http.get(`admin/quickstart/packages${query({ page, size, keyword })}`),
    quickPackageOptions: () => sdk.http.get('admin/quickstart/packages/options'),
    quickPackageDetail: (id: string) =>
      sdk.http.get(`admin/quickstart/packages/${encodeURIComponent(id)}`),
    saveQuickPackage: (data: Record<string, unknown>) =>
      sdk.http.post('admin/quickstart/packages', data),
    deleteQuickPackage: (id: string) =>
      sdk.http.request(`admin/quickstart/packages/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /** 回收站：实例删除后的数据目录快照（保留期内可找回/下载，超期节点自动清除）。 */
    pageTrash: (page: number, size: number, keyword?: string) =>
      sdk.http.get(`admin/trash${query({ page, size, keyword })}`),
    /** 找回 = 以快照规格 + 原实例 id 重建实例（节点把目录改名回 instances/<id>）。 */
    restoreTrash: (trashId: string, name?: string) =>
      sdk.http.post(`admin/trash/${encodeURIComponent(trashId)}/restore`, name ? { name } : {}),
    /** 立即永久删除回收目录（节点 RemoveAll）；节点离线时返回 error 且记录保留。 */
    removeTrash: (trashId: string) =>
      sdk.http.request(`admin/trash/${encodeURIComponent(trashId)}`, { method: 'DELETE' }),
  }
}

export type McPanelExtraApi = ReturnType<typeof createMcPanelApi>

export const createMcPanelExtra = createMcPanelApi
