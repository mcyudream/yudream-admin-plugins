/**
 * 真正的 2D 地图：直接展示渲染产物里的栅格图（首选地表栅格 `surface.png`，1px = 1 方块；
 * 旧渲染没有它时退回 LOD 航拍图集页）。
 *
 * <p>平移/缩放作用于图片元素的 CSS transform（浏览器合成器执行，主线程零负担）；
 * 解码与渲染全部由浏览器完成。</p>
 *
 * <p><b>几何一致性</b>：底图用 {@code image-rendering: pixelated} 展示，浏览器会把图像内容
 * 按**设备像素**栅格化，于是「看到哪一格」与「取到哪一格」必须落在同一个设备像素网格上。
 * 实测（无头浏览器逐像素比对）结论：</p>
 * <ul>
 *   <li>格宽为**整数设备像素**时，各 dpr（含 1.1 / 0.9 / 0.8 这类非二进制页面缩放比例）渲染出的
 *       方块边界都严格等距，只有 ≤0.5px 的常数原点偏移，且原点取整为 {@code round(offset×dpr)}；</li>
 *   <li>格宽为**小数设备像素**时，浏览器会调整自己的栅格化比例，内容相对换算坐标产生 2%~3% 的
 *       漂移，方向还随比例变化——JS 侧无从得知，任何公式都会取错格
 *       （实测误判率 7%~30%），这正是「框选/取点随缩放偏移」的根因。</li>
 * </ul>
 * <p>因此本模块把缩放对齐到整数设备像素（见 snapScale），并把取点与高亮都锚在同一条
 * {@code round(offset×dpr) + n×格宽} 网格上：三者由构造保证一致，不依赖浏览器行为。</p>
 *
 * <p>状态只有三态：加载中 / 就绪 / 失败——失败仅由图片加载错误触发，
 * 不存在任何可能无限停留的中间态（没有候选链、没有分片、没有超时矩阵）。</p>
 */
import { computed, reactive, ref } from 'vue'

export interface Map2DBounds {
  minX: number
  minZ: number
  maxX: number
  maxZ: number
}

/**
 * 设备像素比。
 *
 * <p>浏览器页面缩放会改变它（dpr = 页面缩放 × 系统缩放），而 pixelated 底图的内容按
 * 设备像素栅格化——所有几何换算都要经它换算到设备像素网格（见文件头说明）。</p>
 */
function deviceRatio(): number {
  return typeof window === 'undefined' ? 1 : (window.devicePixelRatio || 1)
}

/**
 * 把缩放对齐到「整数设备像素/图像像素」。
 *
 * <p>整数格宽是浏览器严格遵循的那一档（见文件头实测），小数格宽会被它悄悄改掉栅格化比例。
 * 缩小时（一格不足 1 设备像素）不强行拉大：此时一格本就不可见，对齐没有意义，保持原比例。</p>
 *
 * @param ideal 理想缩放（CSS px / 图像像素）
 * @param fit   true = 适应视口（向下取整，保证整图装得下）；false = 交互缩放（就近取整）
 */
function snapScale(ideal: number, fit: boolean): number {
  const ratio = deviceRatio()
  const device = ideal * ratio
  if (!(device >= 1)) return ideal
  const snapped = fit ? Math.floor(device) : Math.round(device)
  return Math.max(1, snapped) / ratio
}

export function useMap2D() {
  /** 展示用图片地址（地表栅格：1px = 1 方块） */
  const resolvedUrl = ref('')
  /** 主图加载失败时的回退地址（旧渲染没有地表栅格时退用 LOD 图集页） */
  const fallbackUrl = ref('')
  /** 图像的世界范围（1px = 1 方块时与包围盒一致） */
  const bounds = ref<Map2DBounds | null>(null)
  const failed = ref(false)
  const loaded = ref(false)
  /** 平移/缩放所依据的视口元素（函数 ref 挂载，测尺寸用） */
  const viewportEl = ref<HTMLElement | null>(null)

  const view = reactive({
    scale: 1,
    offsetX: 0,
    offsetY: 0,
  })

  /**
   * 连续缩放的「理想」比例（CSS px / 图像像素）。
   *
   * <p>滚轮在它上面按比例累加，实际渲染比例由 snapScale 对齐到整数设备像素：缩放手感保持连续
   * （不会因为对齐而出现滚轮空转），渲染又始终落在浏览器严格遵循的那条网格上。</p>
   */
  let idealScale = 1

  /** 图像的原始像素尺寸（加载后可读）。 */
  const imageSize = reactive({ width: 0, height: 0 })

  /** 平移/缩放全部作用在这一个 transform 上。 */
  const imgStyle = computed(() => ({
    transform: `translate(${view.offsetX}px, ${view.offsetY}px) scale(${view.scale})`,
  }))

  const status = computed(() => {
    if (!resolvedUrl.value) return 'empty'
    if (failed.value) return 'error'
    if (!loaded.value) return 'loading'
    return 'ready'
  })

  /**
   * 视口像素 → 方块坐标（**所见即所得**）。
   *
   * <p>底图内容所在的网格是 {@code round(offset×dpr) + n×(格宽×dpr)}（格宽为整数设备像素，
   * 见文件头实测），所以直接在这条网格上定位：光标所在的那个设备像素落在第几格，就取第几格。
   * 用连续数学坐标反推会在边界附近取到相邻格（实测非整数缩放下 7%~30% 的位置会偏一格）。</p>
   */
  function screenToWorld(screenX: number, screenY: number): { x: number, z: number } | null {
    const src = bounds.value
    if (!src || !imageSize.width || !imageSize.height) return null
    const worldPerImageX = (src.maxX - src.minX) / imageSize.width
    const worldPerImageZ = (src.maxZ - src.minZ) / imageSize.height
    const ratio = deviceRatio()
    const cell = view.scale * ratio
    if (!(cell > 0)) return null
    const pxX = Math.floor((screenX * ratio + 0.5 - Math.round(view.offsetX * ratio)) / cell)
    const pxZ = Math.floor((screenY * ratio + 0.5 - Math.round(view.offsetY * ratio)) / cell)
    return {
      x: src.minX + pxX * worldPerImageX,
      z: src.minZ + pxZ * worldPerImageZ,
    }
  }

  /**
   * 图像像素矩形 → 视口 CSS 矩形，贴齐底图内容所在的设备像素网格。
   *
   * <p>与 screenToWorld 共用同一条网格，所以「高亮框」与「取到的格」由构造一致；底图内容也
   * 落在这条网格上（整数格宽时浏览器严格遵循），三者重合。</p>
   *
   * @param px 矩形左边缘（图像像素）
   * @param pz 矩形上边缘（图像像素）
   * @param wPx 矩形宽（图像像素，1px = 1 方块时即一格）
   * @param hPx 矩形高（图像像素）
   */
  function imageRect(
    px: number, pz: number, wPx = 1, hPx = 1,
  ): { left: number, top: number, width: number, height: number } {
    const ratio = deviceRatio()
    const cell = view.scale * ratio
    const originX = Math.round(view.offsetX * ratio)
    const originY = Math.round(view.offsetY * ratio)
    const x0 = originX + Math.round(px * cell)
    const x1 = originX + Math.round((px + wPx) * cell)
    const y0 = originY + Math.round(pz * cell)
    const y1 = originY + Math.round((pz + hPx) * cell)
    return {
      left: x0 / ratio,
      top: y0 / ratio,
      width: Math.max(x1 - x0, 1) / ratio,
      height: Math.max(y1 - y0, 1) / ratio,
    }
  }

  /** 把指定世界坐标平移到视口中心（坐标传送用）；未就绪返回 false。 */
  function centerOn(x: number, z: number): boolean {
    const el = viewportEl.value
    const src = bounds.value
    if (!el || !src || !imageSize.width || !imageSize.height) return false
    const worldPerImageX = (src.maxX - src.minX) / imageSize.width
    const worldPerImageZ = (src.maxZ - src.minZ) / imageSize.height
    if (!(worldPerImageX > 0) || !(worldPerImageZ > 0)) return false
    const rect = el.getBoundingClientRect()
    view.offsetX = rect.width / 2 - (x - src.minX) / worldPerImageX * view.scale
    view.offsetY = rect.height / 2 - (z - src.minZ) / worldPerImageZ * view.scale
    return true
  }

  /** 载入底图地址（地表栅格优先，附回退地址与世界范围）。 */
  function load(url: string, opts?: { fallbackUrl?: string, bounds?: Map2DBounds }): void {
    if (resolvedUrl.value === url) {
      fitToView()
      return
    }
    resolvedUrl.value = url
    fallbackUrl.value = opts?.fallbackUrl ?? ''
    bounds.value = opts?.bounds ?? null
    loaded.value = false
    failed.value = false
  }

  function reset(): void {
    resolvedUrl.value = ''
    fallbackUrl.value = ''
    bounds.value = null
    loaded.value = false
    failed.value = false
  }

  /** 整图装进视口（图片加载完成时自动调用）。比例向下对齐到整数设备像素，保证整图不溢出。 */
  function fitToView(): void {
    const el = viewportEl.value
    if (!el || !imageSize.width) return
    const rect = el.getBoundingClientRect()
    if (rect.width < 1 || rect.height < 1) return
    idealScale = Math.min(rect.width / imageSize.width, rect.height / imageSize.height)
    const scale = snapScale(idealScale, true)
    view.scale = scale
    // 居中按**对齐后**的比例算：对齐最多改半格，用理想比例居中会让图偏离中心
    view.offsetX = (rect.width - imageSize.width * scale) / 2
    view.offsetY = (rect.height - imageSize.height * scale) / 2
  }

  /** 滚轮缩放（以鼠标位置为锚：缩放前后指针下的图像点不动）。 */
  function zoomAt(screenX: number, screenY: number, factor: number): void {
    const nextIdeal = Math.min(64, Math.max(idealScale * factor, 0.005))
    const next = snapScale(nextIdeal, false)
    idealScale = nextIdeal
    // 对齐后可能与当前比例相同（同一档内微调）：此时不动，等累加跨档
    if (next === view.scale) return
    const applied = next / view.scale
    view.offsetX = screenX - (screenX - view.offsetX) * applied
    view.offsetY = screenY - (screenY - view.offsetY) * applied
    view.scale = next
  }

  /** 拖拽平移。 */
  function panBy(dx: number, dy: number): void {
    view.offsetX += dx
    view.offsetY += dy
  }

  /** 展示元素加载完成：记录原始尺寸并自动整图适配视口。 */
  function onImgLoad(e: Event): void {
    const img = e.target as HTMLImageElement
    imageSize.width = img.naturalWidth
    imageSize.height = img.naturalHeight
    loaded.value = true
    fitToView()
  }

  /** 展示元素加载失败：有回退地址（LOD 图集页）就换上重试一次。 */
  function onImgError(): void {
    if (fallbackUrl.value) {
      resolvedUrl.value = fallbackUrl.value
      fallbackUrl.value = ''
      failed.value = false
      return
    }
    failed.value = true
  }

  return {
    resolvedUrl,
    status,
    failed,
    loaded,
    fallbackUrl,
    bounds,
    viewportEl,
    imageSize,
    view,
    imgStyle,
    load,
    reset,
    fitToView,
    zoomAt,
    panBy,
    screenToWorld,
    imageRect,
    centerOn,
    onImgLoad,
    onImgError,
  }
}
