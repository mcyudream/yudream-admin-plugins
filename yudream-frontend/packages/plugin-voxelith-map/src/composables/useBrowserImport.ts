/**
 * 浏览器搬运（面板插件没有实例文件访问契约时的导入方式）的**应用级**状态。
 *
 * <p>为什么放在模块单例而不是页面组件里：搬运由页面发起，但它是「进行中的工作」，不该随组件卸载
 * 而中止。此前状态挂在管理页组件上，组件卸载（在宿主 SPA 里切到别的插件页也算）就顺手 abort 并把
 * 任务判失败——用户看到的就是「离开页面任务就终止」「导入失败：Failed to fetch」。现在状态提到模块级：
 * 在 SPA 内跳转发货照常继续，回到管理页浮层又接上；只有真正的页面关闭/刷新（pagehide）才会中断，
 * 那时用 keepalive 请求把任务落为失败并写明原因。</p>
 *
 * <p>任务本身仍由服务端持有（创建、进度、日志、取消），所以即使本模块的状态丢失（例如整页刷新），
 * 任务中心里依然看得到发生了什么。</p>
 */
import { reactive } from 'vue'
import { useYdToast } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createVoxelithMapAdminApi } from '../api/voxelith-map-admin-api'
import {
  detectInstanceDimensions,
  downloadInstancePackage,
  packInstancePaths,
  packModsJars,
} from './mcpanel-world'

export interface BrowserImportPayload {
  instanceId: string
  instanceName: string
  name: string
  version: string
  withMods: boolean
  /** 要导入的维度目录；留空由搬运前现场探测（见 {@link resolveWorldDirs}） */
  worldDirs?: string[]
}

/** 进行中的搬运：页面组件只读它来渲染浮层，不拥有它。 */
const state = reactive({
  active: false,
  /** 展示名（实例名），浮层与提示用 */
  label: '',
  stage: '',
  /** >= 0 为确定百分比，-1 表示不确定（扫掠动画） */
  percent: -1,
  detail: '',
  taskId: '',
})

export function browserImportState(): typeof state {
  return state
}

let abort: AbortController | null = null
/** 本次搬运是否已由我们主动收尾（用户取消 / 页面卸载）：是则不再由异常分支去改任务结局 */
let superseded = false
/** 进度回报节流：阶段变化立即上报，同一阶段内最多每 2 秒一次 */
let lastReportAt = 0
let lastReportedStage = ''
let unloadBound = false

/** 把在途任务落为失败：页面即将卸载，搬运无法继续（keepalive 请求见下）。 */
function failOnUnload(message: string): void {
  const taskId = state.taskId
  if (!taskId) {
    return
  }
  superseded = true
  state.taskId = ''
  abort?.abort()
  // 用 keepalive 而不是 sendBeacon：宿主鉴权只认 Authorization 头，beacon 带不上头必然 401
  const token = localStorage.getItem('token')
  void fetch(`/api/plugins/voxelith-map/admin/import-instance-jobs/${encodeURIComponent(taskId)}/fail`, {
    method: 'POST',
    keepalive: true,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: token } : {}) },
    body: JSON.stringify({ message }),
  }).catch(() => {})
}

/** 只在真正卸载页面时收尾；SPA 内跳转不触发此事件。 */
function bindUnloadOnce(): void {
  if (unloadBound) {
    return
  }
  unloadBound = true
  window.addEventListener('pagehide', () => failOnUnload('页面已关闭或刷新，浏览器搬运中断'))
}

let api: ReturnType<typeof createVoxelithMapAdminApi> | null = null

async function reportStage(taskId: string, stage: string, percent: number, detail: string): Promise<void> {
  const now = Date.now()
  if (stage === lastReportedStage && now - lastReportAt < 2000) {
    return
  }
  lastReportedStage = stage
  lastReportAt = now
  await api?.reportImportProgress(taskId, { stage, percent, detail }).catch(() => {})
}

/** 当前是否有搬运在进行（管理页据此恢复浮层，而不是重新发起）。 */
export function isBrowserImportActive(): boolean {
  return state.active
}

/** 取消进行中的搬运：中止在途请求并把任务落为「已取消」。 */
export async function cancelBrowserImport(): Promise<void> {
  if (!state.active) {
    return
  }
  superseded = true
  const taskId = state.taskId
  state.taskId = ''
  state.active = false
  abort?.abort()
  if (taskId && api) {
    await api.cancelTask(taskId).catch(() => {})
  }
}

/**
 * 发起浏览器搬运：登记任务 → 面板侧打包/拉回 → 上传宿主文件存储 → 交服务端建图。
 *
 * @param sdk    插件 SDK（上传通道与接口封装用）
 * @param upload 上传一个文件并返回宿主文件 id（由调用方注入，避免本模块持有宿主上传实现）
 */
export async function runBrowserImport(
  sdk: YuDreamPluginSdk,
  payload: BrowserImportPayload,
  upload: (file: File) => Promise<string>,
): Promise<void> {
  if (state.active) {
    return
  }
  const toast = useYdToast()
  api = createVoxelithMapAdminApi(sdk)
  bindUnloadOnce()

  state.active = true
  state.label = payload.instanceName || payload.instanceId
  state.stage = '准备搬运'
  state.percent = -1
  state.detail = ''
  state.taskId = ''
  superseded = false
  lastReportAt = 0
  lastReportedStage = ''
  abort = new AbortController()
  const signal = abort.signal
  const worldRange = payload.withMods ? 40 : 78

  const show = (stage: string, percent: number, detail = ''): void => {
    state.stage = stage
    state.percent = percent
    state.detail = detail
  }

  /**
   * 给「节点压缩」这类量不出比例的长阶段加生命迹象：面板侧打包是一次同步调用，大存档可能压几分钟，
   * 只挂一个转圈会让人以为卡死。这里只刷新浮层明细（每秒一次），阶段文案不变，因此不会刷爆任务日志。
   */
  const withElapsed = async <T>(label: string, run: () => Promise<T>): Promise<T> => {
    const startedAt = Date.now()
    const timer = setInterval(() => {
      show(label, state.percent, `已等待 ${Math.round((Date.now() - startedAt) / 1000)} 秒`)
    }, 1000)
    try {
      return await run()
    }
    finally {
      clearInterval(timer)
    }
  }

  try {
    const task = await api.startBrowserImport(payload)
    state.taskId = task.id

    const stage = (text: string, percent: number, detail = ''): void => {
      show(text, percent, detail)
      void reportStage(task.id, text, percent, detail)
    }

    // 先探测维度：服务端在浏览器路径下读不到实例文件，包要打哪些目录、服务端据此建几张图，
    // 全靠这一份清单。老版存档的下界/末地在 world 里面（DIM-1/DIM1），探测会多探一层；
    // 打包用 packPath（老版是父目录 world），建图用 rootName（归一后的 world_nether/world_the_end）。
    stage(`「${state.label}」正在探测实例维度`, -1)
    const dimensions = await detectInstanceDimensions(payload.instanceId, signal)
    const packPaths = [...new Set(dimensions.map(dir => dir.packPath))]
    const worldDirs = dimensions.map(dir => dir.rootName)
    stage(`「${state.label}」将导入维度：${dimensions.map(dir => dir.rootName).join('、')}`, -1)

    stage(`「${state.label}」正在打包存档（${packPaths.length} 个目录，节点上压缩）`, -1)
    const worldPkg = await withElapsed(
      `「${state.label}」正在打包存档（节点上压缩）`,
      () => packInstancePaths(payload.instanceId, packPaths, 'voxelith-import.zip',
        packPaths.length > 1 ? ` 存档（${packPaths.length} 个维度目录）` : ' world',
        'voxelith-world.zip', (progress) => {
          show(progress.stage, scalePercent(progress.percent, 0, worldRange), progress.detail)
        }, signal))

    // 模组包的节点侧压缩与世界的下载/上传**重叠**：压缩吃节点 CPU/IO，下载上传吃网络，
    // 并行后总耗时接近两者较大者而不是相加。模组仍是可选增强，失败只记日志、不拖垮世界导入。
    const modsPack = payload.withMods
      ? withElapsed(`「${state.label}」正在打包 mods 目录（节点上压缩）`, async () => {
          stage(`「${state.label}」打包实例模组目录`, -1)
          return packModsJars(payload.instanceId, (progress) => {
            show(progress.stage, scalePercent(progress.percent, 45, 86), progress.detail)
          }, signal)
        }).then(
          file => ({ file }),
          (error: unknown) => ({ error }),
        )
      : null

    stage(`「${state.label}」正在下载存档打包产物`, -1)
    const worldFile = await downloadInstancePackage(payload.instanceId, worldPkg, (progress) => {
      show(progress.stage, scalePercent(progress.percent, 0, worldRange), progress.detail)
    }, signal)
    stage(`「${state.label}」上传存档到文件存储`, -1)
    const worldFileId = await upload(worldFile)

    // ---- 模组：下载 → 上传（打包已在世界传输期间完成）----
    let modsFileId = ''
    let modsPackName = ''
    if (modsPack) {
      const packed = await modsPack
      if ('error' in packed) {
        // 用户取消 / 页面卸载：照常走收尾流程
        if (superseded) {
          throw packed.error
        }
        // 模组是可选增强：打包超时、节点拒绝读取等都不该把整次导入拖垮——写进任务日志后
        // 继续完成世界导入（与服务端作业同一策略），地图建好后可在详情页补绑模组。
        stage(`「${state.label}」模组导入失败，已跳过（世界存档继续）：${describeTransferError(packed.error)}`,
          state.percent)
      }
      else {
        stage(`「${state.label}」正在下载模组打包产物`, 86)
        const modsFile = await downloadInstancePackage(payload.instanceId, packed.file, (progress) => {
          show(progress.stage, scalePercent(progress.percent, 45, 86), progress.detail)
        }, signal)
        stage(`「${state.label}」上传模组包到文件存储`, 88)
        modsFileId = await upload(modsFile)
        modsPackName = modsFile.name
      }
    }

    stage(`「${state.label}」存档已就绪，正在提交服务端建图`, 92)
    await api.completeImportJob(task.id, {
      ...payload,
      worldFileId,
      modsFileId,
      modsPackName,
      worldDirs,
    })
    show('完成', 100)
    state.taskId = ''
    toast.success(`「${state.label}」的存档已上传，正在服务端创建 ${worldDirs.length} 张维度地图；`
      + '进度与日志见任务中心')
  }
  catch (e) {
    if (!superseded) {
      const message = describeTransferError(e)
      if (state.taskId && api) {
        await api.failImportJob(state.taskId, message).catch(() => {})
        state.taskId = ''
      }
      toast.error(message, { duration: 6000 })
    }
    // 不向上抛：搬运的失败已经 toast 过、也写进了任务，调用方不需要（也不应该）再报一次
  }
  finally {
    abort = null
    state.active = false
    state.percent = -1
    state.detail = ''
  }
}

/**
 * 把搬运期的原始报错翻成能看懂的话。
 *
 * <p>这里必须区分两类「中断」，此前一律写成「页面关闭/刷新」是误诊：{@code Failed to fetch} 在页面
 * 好好的时候也会出现——大包的打包/下载/上传超过反向代理的读超时、或节点侧的连接被重置，浏览器给出的
 * 就是这句。文案要指向真正的排查方向，而不是让用户去怀疑自己没刷新页面。</p>
 */
function describeTransferError(e: unknown): string {
  const message = e instanceof Error ? e.message : String(e)
  const lower = message.toLowerCase()
  if (lower.includes('abort')) {
    return '搬运已中止'
  }
  if (lower.includes('failed to fetch') || lower.includes('networkerror') || lower.includes('load failed')) {
    return '与面板或节点的通讯中断/响应超时（大包打包、下载或上传可能超过反向代理超时）——可重试；'
      + '若只有模组阶段失败，可在导入弹窗里关闭「同步导入实例模组」，或先把实例的 mods 目录裁剪后再导入'
  }
  return message
}

/** 把阶段内部的 0–99 百分比映射到整条进度的 [from, to] 区间（保证只增不减）。 */
function scalePercent(percent: number, from: number, to: number): number {
  if (percent < 0) return -1
  return Math.round(from + (Math.min(100, percent) / 100) * (to - from))
}
