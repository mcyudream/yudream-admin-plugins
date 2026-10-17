/**
 * MC 面板实例世界文件的浏览器侧打包与下载。
 *
 * 流程：节点打包（file.zip）→ 分块拉回 → 组装为 Blob/File → 交给宿主上传通道入库。
 * 与 mcpanel 保持零代码耦合：只调用其既有 HTTP 端点，管理员会话自带鉴权
 * （sa-token 令牌读宿主登录态落盘的 localStorage 'token'，无 Bearer 前缀）。
 *
 * 为什么走浏览器而不是插件服务端：跨插件 Java 契约在软依赖下不可靠——宿主按 Class 实例身份
 * 查询插件服务，而插件的依赖类加载器委派链是加载那一刻的快照、宿主只对硬依赖做递归预启用，
 * 于是「softdepend + 调对方 Java 契约」在加载顺序不利时静默失效。改成浏览器经面板既有 HTTP
 * 端点搬运后，mcpanel 只需 softdepend，也不受加载顺序影响（即 1.0.75 的集成方式）。
 *
 * 两个刻意的取舍：
 * 1) 并行分窗下载：节点侧 download 是「面板中转节点」的单请求往返，逐块 96KiB 串行拉 GB 级存档时
 *    绝大部分时间花在往返延迟上。这里按已知总大小切独立窗口、多路并行，单窗内顺序补齐；
 *    请求长度按节点实际返回量自适应（节点若有更小上限，只是把窗口拆细，不会截断数据）。
 * 2) 返回 File，不拼 base64：整包转 base64 会让浏览器为 GB 级文件同时持有数份等量字符串而崩页；
 *    Blob 由浏览器托管（大对象可落盘），交给宿主的 multipart 上传通道原样二进制出网。
 */

const MCP = '/api/plugins/mcpanel/admin'
/** 单次向节点请求的窗口上限；节点若有更小上限，会自动按实际返回量收敛 */
const WINDOW_BYTES = 2 * 1024 * 1024
/** 并行窗口数：节点侧是面板→节点的中转，多路并行主要用来掩盖往返延迟 */
const LANES = 4
const MAX_ZIP_BYTES = 2 ** 31
/**
 * 面板元数据请求（列目录、清理临时包）的超时上限。这类调用应当秒回，一旦面板卡住就会一直
 * 占着浏览器连接：列目录挂住会让导入停在无反馈的等待里，清理挂住会让「取消/失败」的处理
 * 永远走不完。打包与下载的耗时和体积成正比，不设上限，只由调用方的 signal 中止。
 */
const MCP_META_TIMEOUT_MS = 15_000

function tokenHeader(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: token } : {}
}

/**
 * 调 mcpanel 的既有 HTTP 端点。
 *
 * <p>{@code timeoutMs > 0} 时给请求叠加超时中断：面板进程卡死或节点不可达时，
 * 请求会一直占着浏览器的一条同源连接（HTTP/1.1 下同源只有约 6 条），插件自己的 API 请求
 * 全得排队等待——用户看到的就是「刷新后页面无响应」。元数据读取（实例列表、目录列表）
 * 一律带超时；打包/下载这类耗时与体积成正比的操作不设上限，交给调用方的 signal 中止。</p>
 *
 * @param timeoutMs 毫秒；0 或省略 = 不限时（打包、下载等长操作）
 */
export async function mcpanelJson(
  path: string,
  init?: RequestInit,
  timeoutMs = 0,
): Promise<Record<string, unknown>> {
  // 超时与调用方信号都要能中断同一个请求，故合并到一个内部 controller 上
  const controller = new AbortController()
  const upstream = init?.signal
  const relayAbort = (): void => controller.abort()
  if (upstream) {
    if (upstream.aborted) {
      controller.abort()
    }
    else {
      upstream.addEventListener('abort', relayAbort, { once: true })
    }
  }
  let timedOut = false
  const timer = timeoutMs > 0
    ? setTimeout(() => {
        timedOut = true
        controller.abort()
      }, timeoutMs)
    : null
  try {
    const res = await fetch(path, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        ...(tokenHeader()),
        ...(init?.headers ?? {}),
      },
      signal: controller.signal,
    })
    const payload = (await res.json()) as { code?: number, data?: Record<string, unknown>, message?: string }
    if (!res.ok || (payload.code !== undefined && payload.code !== 200)) {
      throw new Error(payload.message || `mcpanel 请求失败（HTTP ${res.status}）`)
    }
    return (payload.data ?? payload) as Record<string, unknown>
  }
  catch (e) {
    if (timedOut) {
      throw new Error(`mcpanel 请求超时（${Math.round(timeoutMs / 1000)} 秒未响应），面板可能正忙或节点不可达`)
    }
    throw e
  }
  finally {
    if (timer !== null) clearTimeout(timer)
    upstream?.removeEventListener('abort', relayAbort)
  }
}

export interface WorldPackProgress {
  stage: string
  percent: number
  detail: string
}

/**
 * 从节点文件列表里取目标包的字节数。
 *
 * <p>打包响应不一定带 size；拿不到总大小就只能顺序探测、进度也只能是不定态。文件列表的条目
 * 形态因节点版本而异，按字段名做防御式解析。</p>
 */
async function sizeFromList(instanceId: string, fileName: string, signal?: AbortSignal): Promise<number> {
  try {
    const data = await mcpanelJson(
      `${MCP}/instances/${encodeURIComponent(instanceId)}/files?path=${encodeURIComponent('.')}`,
      { signal },
      MCP_META_TIMEOUT_MS,
    )
    const entries = (data.entries ?? []) as unknown[]
    if (!Array.isArray(entries)) {
      return 0
    }
    for (const entry of entries) {
      if (typeof entry !== 'object' || entry === null) {
        continue
      }
      const record = entry as Record<string, unknown>
      const name = String(record.name ?? record.fileName ?? record.file ?? '')
      if (name !== fileName) {
        continue
      }
      const size = Number(record.size ?? record.length ?? record.bytes ?? 0)
      if (Number.isFinite(size) && size > 0) {
        return size
      }
    }
  }
  catch {
    // 列目录失败：退回顺序探测，不影响正确性
  }
  return 0
}

/** 读一段字节（offset 起、最多 length 字节）；返回实际读到的字节，空数组表示已到文件末尾。 */
async function readRange(
  instanceId: string,
  path: string,
  offset: number,
  length: number,
  signal?: AbortSignal,
): Promise<Uint8Array> {
  const res = await fetch(
    `${MCP}/instances/${encodeURIComponent(instanceId)}/files/download`
    + `?path=${encodeURIComponent(path)}&offset=${offset}&length=${length}`,
    { headers: tokenHeader(), signal },
  )
  const payload = (await res.json()) as { code?: number, data?: { content?: string }, message?: string }
  if (!res.ok || (payload.code !== undefined && payload.code !== 200)) {
    throw new Error(payload.message || `下载失败（HTTP ${res.status}）`)
  }
  const encoded = payload.data?.content ?? ''
  return encoded ? Uint8Array.from(atob(encoded), c => c.charCodeAt(0)) : new Uint8Array(0)
}

interface DownloadTarget {
  instanceId: string
  path: string
  /** 打包产物总字节；<=0 表示未知（退回顺序探测，进度不定态） */
  totalBytes: number
  stageLabel: string
  signal?: AbortSignal
  onProgress?: (progress: WorldPackProgress) => void
}

/** 拉回节点上的整包产物（并行分窗；总大小未知时顺序探测）。 */
async function downloadArchive(target: DownloadTarget): Promise<Blob> {
  const { instanceId, path, stageLabel, signal, onProgress } = target
  const total = target.totalBytes
  let received = 0
  /** 节点单请求实际可返回的上限（自适应收敛：返回量小于请求量即认为撞到上限） */
  let requestBytes = WINDOW_BYTES

  const report = () => {
    onProgress?.({
      stage: stageLabel,
      percent: total > 0 ? Math.min(99, Math.round((received / total) * 100)) : -1,
      detail: `${(received / 1048576).toFixed(1)} MiB`,
    })
  }

  /** 单窗内顺序补齐：直到窗口填满、到文件末尾、或触到总量上限 */
  const fillWindow = async (start: number, end: number): Promise<Uint8Array[]> => {
    const parts: Uint8Array[] = []
    let offset = start
    while (offset < end) {
      if (signal?.aborted) throw new Error('IMPORT_CANCELLED')
      const want = Math.min(requestBytes, end - offset)
      const bytes = await readRange(instanceId, path, offset, want, signal)
      if (bytes.length === 0) break
      if (bytes.length < want) requestBytes = Math.max(1, Math.min(requestBytes, bytes.length))
      parts.push(bytes)
      offset += bytes.length
      received += bytes.length
      if (received > MAX_ZIP_BYTES) throw new Error('打包产物超过 2GB 上限')
      report()
    }
    return parts
  }

  const windows: Uint8Array[][] = []

  if (total > 0) {
    const count = Math.ceil(total / WINDOW_BYTES)
    let next = 0
    const lane = async () => {
      while (true) {
        const index = next++
        if (index >= count) return
        const start = index * WINDOW_BYTES
        windows[index] = await fillWindow(start, Math.min(start + WINDOW_BYTES, total))
      }
    }
    await Promise.all(Array.from({ length: Math.min(LANES, count) }, () => lane()))
  }
  else {
    const parts: Uint8Array[] = []
    let offset = 0
    while (true) {
      if (signal?.aborted) throw new Error('IMPORT_CANCELLED')
      const want = requestBytes
      const bytes = await readRange(instanceId, path, offset, want, signal)
      if (bytes.length === 0) break
      if (bytes.length < want) requestBytes = Math.max(1, Math.min(requestBytes, bytes.length))
      parts.push(bytes)
      offset += bytes.length
      received += bytes.length
      if (received > MAX_ZIP_BYTES) throw new Error('打包产物超过 2GB 上限')
      report()
    }
    windows.push(parts)
  }

  if (received === 0) throw new Error('打包产物为空')
  if (total > 0 && received !== total) {
    throw new Error(`打包产物不完整（期望 ${total} 字节，实收 ${received} 字节），请重试`)
  }
  report()
  return new Blob(windows.flat() as BlobPart[], { type: 'application/zip' })
}

/**
 * 从节点 file.list 的条目里取文件名：条目可能是字符串，也可能是对象（字段名因节点版本而异）。
 * 面板对同一份列表在不同版本下返回的形态不同，这里统一归一化，避免调用方各写一套防御式解析。
 */
export function entryNameOf(entry: unknown): string {
  if (typeof entry === 'string') return entry
  // 条目可能是 null / 数字 / 数组（节点版本差异）：先挡住非对象，否则一次属性访问就把整段列表解析打断
  if (entry === null || typeof entry !== 'object') return ''
  const obj = entry as Record<string, unknown>
  const name = obj.name ?? obj.fileName ?? obj.file ?? obj.path
  return name === undefined || name === null ? '' : String(name)
}

/** 面板侧打包产物：先只打包（节点压缩），下载与清理留到后面单独做。 */export interface PanelPackage {
  /** 面板侧产物路径（下载后应删除） */
  dest: string
  /** 产物字节数；0 = 未知（退回顺序探测） */
  sizeBytes: number
  /** 展示名（进度文案用） */
  label: string
  /** 产出的 File 文件名 */
  fileName: string
}

/** 清理面板侧临时包；失败不影响主流程。 */
async function deletePanelBundle(instanceId: string, dest: string): Promise<void> {
  try {
    await mcpanelJson(`${MCP}/instances/${encodeURIComponent(instanceId)}/files/delete`, {
      method: 'POST',
      body: JSON.stringify({ path: dest }),
    }, MCP_META_TIMEOUT_MS)
  } catch {
    // 清理失败不影响错误上报（超时也算清理失败：临时包留在面板侧，下次同实例导入会覆盖）
  }
}

/**
 * 只让节点打包（压缩整段在面板/节点侧完成），不下载。
 *
 * <p>拆成「打包」与「下载」两步是为了把**节点压缩**（CPU/IO）与另一阶段的**浏览器下载/上传**（网络）
 * 重叠起来：世界在下载/上传时，节点可以同时压模组包，总耗时接近两者较大者而不是相加。</p>
 */
export async function packInstancePaths(
  instanceId: string,
  paths: string[],
  dest: string,
  label: string,
  fileName: string,
  onProgress?: (progress: WorldPackProgress) => void,
  signal?: AbortSignal,
): Promise<PanelPackage> {
  onProgress?.({ stage: `正在打包${label}（节点上压缩）`, percent: -1, detail: '' })
  try {
    const zipData = await mcpanelJson(`${MCP}/instances/${encodeURIComponent(instanceId)}/files/zip`, {
      method: 'POST',
      body: JSON.stringify({ paths, dest }),
      signal,
    })
    let totalBytes = Number(zipData.size ?? 0)
    if (!(totalBytes > 0)) {
      totalBytes = await sizeFromList(instanceId, dest, signal)
    }
    return { dest, sizeBytes: totalBytes, label, fileName }
  } catch (e) {
    // 打包没成功就没有可用产物，但仍尝试清掉半成品
    await deletePanelBundle(instanceId, dest)
    throw e
  }
}

/**
 * 下载上一阶段打好的包并清理面板侧产物，返回可交给宿主上传通道的 File。
 *
 * <p>成功或失败都清理面板侧产物：成功时数据已在浏览器手里，失败时本次导入已放弃。</p>
 */
export async function downloadInstancePackage(
  instanceId: string,
  pkg: PanelPackage,
  onProgress?: (progress: WorldPackProgress) => void,
  signal?: AbortSignal,
): Promise<File> {
  try {
    const blob = await downloadArchive({
      instanceId,
      path: pkg.dest,
      totalBytes: pkg.sizeBytes,
      stageLabel: `正在下载${pkg.label}`,
      signal,
      onProgress,
    })
    await requireCompleteZip(blob)
    return new File([blob], pkg.fileName, { type: 'application/zip' })
  } finally {
    await deletePanelBundle(instanceId, pkg.dest)
  }
}

/**
 * 兜底校验：下载到的是不是一个**完整的 zip**。
 *
 * <p>面板报不出产物大小时只能顺序探测到空块为止，无法按字节数核对；一次提前结束的读就会拿到
 * 「看起来正常、其实被截断」的包，直到渲染解包才炸（解包器报 Unexpected end of ZLIB input stream）。
 * 判据取两处：文件头是 zip 本地头，且尾部窗口里能找到中央目录结束记录（EOCD）——缺了它必然被截断。
 * 非 zip 载荷不做这项校验。</p>
 */
export async function requireCompleteZip(blob: Blob): Promise<void> {
  if (blob.size < 32) {
    return
  }
  const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
  if (!(head[0] === 0x50 && head[1] === 0x4b && head[2] === 0x03 && head[3] === 0x04)) {
    return
  }
  // EOCD 之前最多还有 64KiB 的 zip 注释，窗口按它取
  const window = Math.min(blob.size, 66_000)
  const tail = new Uint8Array(await blob.slice(blob.size - window).arrayBuffer())
  for (let i = tail.length - 4; i >= 0; i--) {
    if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 0x05 && tail[i + 3] === 0x06) {
      return
    }
  }
  throw new Error('打包产物不完整（缺少 zip 尾部记录）：传输可能被截断，请重试')
}

/** 打包 + 下载的便捷组合（不需要与其它阶段重叠时用，如「同步最新存档」）。 */
async function packAndFetch(
  instanceId: string,
  paths: string[],
  dest: string,
  label: string,
  fileName: string,
  onProgress?: (progress: WorldPackProgress) => void,
  signal?: AbortSignal,
): Promise<File> {
  const pkg = await packInstancePaths(instanceId, paths, dest, label, fileName, onProgress, signal)
  return downloadInstancePackage(instanceId, pkg, onProgress, signal)
}

/** 打包实例的世界存档目录并拉回，返回可交给宿主上传通道的 File。 */
export async function packWorldZip(
  instanceId: string,
  worldDirs: string[] = [],
  onProgress?: (progress: WorldPackProgress) => void,
  signal?: AbortSignal,
): Promise<File> {
  const dirs = worldDirs.length > 0 ? worldDirs : [OVERWORLD_DIR]
  const label = dirs.length > 1 ? ` 存档（${dirs.length} 个维度目录）` : ' world'
  return packAndFetch(instanceId, dirs, 'voxelith-import.zip', label, 'voxelith-world.zip',
    onProgress, signal)
}

/** 主世界目录名（默认维度，也是认不出维度目录时的兜底）。 */
const OVERWORLD_DIR = 'world'

/** 维度目录：规范键 → 目录名；顺序固定为主世界 → 下界 → 末地。 */
const DIMENSION_DIRS: Array<{ key: string, dir: string }> = [
  { key: 'minecraft:overworld', dir: OVERWORLD_DIR },
  { key: 'minecraft:nether', dir: 'world_nether' },
  { key: 'minecraft:the_end', dir: 'world_the_end' },
]

/** 老版存档把维度塞在 world 里的目录名（DIM-1 = 下界、DIM1 = 末地，且都没有 level.dat）。 */
const LEGACY_NESTED: Record<string, string> = {
  'dim-1': 'minecraft:nether',
  'dim_1': 'minecraft:nether',
  dim1: 'minecraft:the_end',
  dim_1_end: 'minecraft:the_end',
}

/**
 * 一个要导入的维度：**打包路径**与**存档根名**是两件事。
 *
 * <p>新版布局里两者相同（{@code world_nether} 本身就是一份完整存档）；老版布局里维度数据在
 * {@code world/DIM-1}，打包得连父目录 {@code world} 一起带（维度子目录没有 level.dat），
 * 而服务端归一后的存档根名仍是 {@code world_nether}——地图记的是根名，打包用的是打包路径。</p>
 */
export interface InstanceDimensionDir {
  /** 实例里要打包的路径（相对实例根） */
  packPath: string
  /** 归一后该维度的存档根目录名；也是地图的 worldName */
  rootName: string
  dimension: string
}

/**
 * 目录（或文件）名 → 规范维度键；不是维度目录返回空串。
 *
 * <p>与后端 {@code InstanceDimensionDir.dimensionOf} 保持同一份对照表（含旧版服务端的
 * {@code DIM-1}/{@code DIM1}）：两边对「哪些目录算维度」的判断一旦分叉，就会出现
 * 「服务端建了三张图、浏览器只打包了一个目录」这类对不上的结果。</p>
 */
export function dimensionKeyOfDirectory(name: string): string {
  const base = (name.trim().replace(/\\/g, '/').split('/').pop() ?? '').toLowerCase()
  if (base in LEGACY_NESTED) {
    return LEGACY_NESTED[base]
  }
  switch (base) {
    case 'world':
    case 'world_overworld':
      return 'minecraft:overworld'
    case 'world_nether':
    case 'dim-1':
    case 'dim_1':
      return 'minecraft:nether'
    case 'world_the_end':
    case 'dim1':
    case 'dim_1_end':
      return 'minecraft:the_end'
    default:
      return ''
  }
}

/** 维度键 → 规范目录名。 */
function dirOfDimension(dimension: string): string {
  return DIMENSION_DIRS.find(entry => entry.key === dimension)?.dir ?? OVERWORLD_DIR
}

/**
 * 实例里存在哪些**维度**（新版同级目录与老版 {@code world/DIM-1}、{@code world/DIM1} 都认）。
 *
 * <p>老版存档只看根目录会漏掉下界与末地（它们藏在 world 里面），必须再探一层；两种布局同时存在时以
 * 新版为准。条目顺序固定为主世界 → 下界 → 末地。</p>
 *
 * @param rootNames 实例根目录条目（面板对字符串/对象两种形态都可能返回）
 * @param worldNames {@code world} 目录里的条目；没有 world 目录或列表失败时传空数组
 */
export function instanceDimensions(rootNames: unknown[], worldNames: unknown[] = []): InstanceDimensionDir[] {
  const found = new Map<string, InstanceDimensionDir>()
  const clean = (entry: unknown): string =>
    entryNameOf(entry).trim().replace(/\\/g, '/').replace(/^\.?\//, '')

  for (const entry of rootNames) {
    const name = clean(entry)
    if (!name) continue
    const dimension = dimensionKeyOfDirectory(name)
    // 新版：目录名自身就是该维度的存档根
    if (dimension && !found.has(dimension)) {
      found.set(dimension, { packPath: name, rootName: name, dimension })
    }
  }

  const overworld = found.get('minecraft:overworld')
  const hasModernNetherOrEnd = found.has('minecraft:nether') || found.has('minecraft:the_end')
  if (overworld && !hasModernNetherOrEnd) {
    for (const entry of worldNames) {
      const nested = clean(entry)
      if (!nested) continue
      const dimension = dimensionKeyOfDirectory(nested)
      // 只认老版嵌套目录：world 里的 region 之类不算维度
      if (!dimension || dimension === 'minecraft:overworld' || found.has(dimension)) continue
      found.set(dimension, {
        // 打包要带父目录 world：DIM 子目录没有 level.dat，单独打包出来不是一份完整存档
        packPath: overworld.packPath,
        rootName: dirOfDimension(dimension),
        dimension,
      })
    }
  }

  const ordered = DIMENSION_DIRS
    .map(entry => found.get(entry.key))
    .filter((dir): dir is InstanceDimensionDir => Boolean(dir))
  return ordered.length > 0 ? ordered : [{ packPath: OVERWORLD_DIR, rootName: OVERWORLD_DIR, dimension: 'minecraft:overworld' }]
}

/**
 * 列出实例里某个目录的条目（带超时上限：列目录应当秒回，面板卡住时不能一直占着同源连接）。
 *
 * @throws 面板不可达 / 无权限 / 超时
 */
export async function listInstanceDir(
  instanceId: string,
  path: string,
  signal?: AbortSignal,
): Promise<unknown[]> {
  const data = await mcpanelJson(
    `${MCP}/instances/${encodeURIComponent(instanceId)}/files?path=${encodeURIComponent(path)}`,
    { signal },
    MCP_META_TIMEOUT_MS,
  )
  const entries = data.entries ?? []
  return Array.isArray(entries) ? entries : []
}

/**
 * 探测实例里要导入的**维度**（含老版 world/DIM-1、world/DIM1）。
 *
 * <p>列目录失败（面板旧版没有目录端点、无权限、超时）时退回主世界：探测是为了多建几张图，
 * 失败不该把整次导入挡在门口——与「按主世界目录导入」的历史行为一致。</p>
 */
export async function detectInstanceDimensions(
  instanceId: string,
  signal?: AbortSignal,
): Promise<InstanceDimensionDir[]> {
  try {
    const rootNames = await listInstanceDir(instanceId, '.', signal)
    const modern = instanceDimensions(rootNames)
    const hasModernNetherOrEnd = modern.some(dir => dir.dimension !== 'minecraft:overworld')
    if (hasModernNetherOrEnd) {
      return modern
    }
    // 老版存档：下界/末地在 world 里面，再探一层（失败就当没有，不影响主世界导入）
    let worldNames: unknown[] = []
    try {
      worldNames = await listInstanceDir(instanceId, modern[0]?.packPath ?? OVERWORLD_DIR, signal)
    }
    catch {
      worldNames = []
    }
    return instanceDimensions(rootNames, worldNames)
  }
  catch (e) {
    // 用户取消/页面卸载触发的 abort 不能吞掉：调用方要靠它决定收尾方式
    if (signal?.aborted) {
      throw e
    }
    return [{ packPath: OVERWORLD_DIR, rootName: OVERWORLD_DIR, dimension: 'minecraft:overworld' }]
  }
}

/**
 * 从实例 mods 目录的条目里挑出**参与渲染的模组 jar**的相对路径。
 *
 * <p>只压 jar：模组目录里常混着缓存、配置、日志与 `.disabled` 的停用包，整目录打包会把
 * 「节点侧压缩」拖成整个导入最慢、最容易撞反向代理超时的一环；而渲染只吃 jar，其余文件带过去
 * 也没有用。保持相对实例根目录的路径（{@code mods/xxx.jar}），与整目录打包的条目布局一致。</p>
 *
 * @param names 目录条目（面板对字符串/对象两种形态都可能返回）
 * @returns jar 相对路径；一个都没有时返回空数组
 */
export function modJarPaths(names: unknown[]): string[] {
  const paths: string[] = []
  const seen = new Set<string>()
  for (const entry of names) {
    // 面板给的是实例根目录相对路径；反斜杠形态（个别节点）先归一化，后面只处理正斜杠
    const name = entryNameOf(entry).trim().replace(/\\/g, '/')
    if (!name) continue
    const base = name.split('/').pop() ?? name
    const lower = base.toLowerCase()
    // 停用包（xxx.jar.disabled）不参与渲染，直接跳过
    if (!lower.endsWith('.jar') || lower.endsWith('.jar.disabled')) continue
    const relative = name.replace(/^\.?\//, '')
    const path = relative.startsWith('mods/') ? relative : `mods/${base}`
    if (seen.has(path)) continue
    seen.add(path)
    paths.push(path)
  }
  return paths
}

/**
 * 只打包实例 mods 目录（节点侧压缩），不下载——交给调用方与世界的下载/上传重叠执行。
 *
 * <p>优先只打包 jar（见 {@link modJarPaths}）：模组目录里的缓存、配置与停用包对渲染毫无用处，
 * 却会把节点压缩这一步拖成整个导入最慢的一环。列目录失败或一个 jar 都没有时退回整目录打包，
 * 与旧版行为一致。</p>
 */
export async function packModsJars(
  instanceId: string,
  onProgress?: (progress: WorldPackProgress) => void,
  signal?: AbortSignal,
): Promise<PanelPackage> {
  let paths = ['mods']
  try {
    const data = await mcpanelJson(
      `${MCP}/instances/${encodeURIComponent(instanceId)}/files?path=${encodeURIComponent('mods')}`,
      { signal },
      MCP_META_TIMEOUT_MS,
    )
    const jars = modJarPaths((data.entries ?? []) as unknown[])
    if (jars.length > 0) {
      paths = jars
      onProgress?.({ stage: `已识别 ${jars.length} 个模组 jar`, percent: -1, detail: '' })
    }
  } catch {
    // 列目录失败：退回整目录打包，不让模组阶段因为一次列目录失败而改变结果
  }
  return packInstancePaths(instanceId, paths, 'voxelith-mods.zip', ' mods 目录', 'voxelith-mods.zip',
    onProgress, signal)
}
