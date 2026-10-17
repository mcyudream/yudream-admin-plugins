<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { YdAction, YdButton, YdCheckbox, YdIcon, YdInput, YdModal, YdNumberField, YdSelect, YdSlider, YdSwitch, YdTag, useYdToast } from '@yudream/components'
import type { Marker, MarkerSet } from '../vendor/voxelith/core/index'
import { createVoxelithMapApi, extractMarkerSets } from '../api/voxelith-map-api'
import { createVoxelithMapAdminApi } from '../api/voxelith-map-admin-api'
// 三维栈按需加载：静态 import 会把 three.js 与 vendored 渲染核并进 remoteEntry.js，
// 使其它插件页面刷新时也白白解析求值整份 3D 栈（见 src/viewer/stack.ts）
import { loadViewerStack } from '../viewer/stack'
import type { CameraState, ViewerMode, VoxelithViewer } from '../viewer/VoxelithViewer'
import { describeKey, useKeyBindings } from '../composables/useKeyBindings'
import { buildDimensionChoices, needsDimensionSwitch } from '../composables/dimensionSwitch'
import type { DimensionChoice } from '../composables/dimensionSwitch'
import { useMap2D } from '../composables/useMap2D'
import type { MapDefaultView, MapSummary } from '../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const api = createVoxelithMapApi(props.sdk)

// 管理员能力：把当前机位与画面保存为该地图的默认初始视角与列表封面
const adminApi = createVoxelithMapAdminApi(props.sdk)
// 与 material / skin 插件同款判断：超管持有通配权限「*」，只认具体权限码会让超管看不到管理入口
const isAdmin = props.sdk.account.permissions.includes('*')
  || props.sdk.account.permissions.includes('plugin:voxelith-map:manage')
/** 标注类动作：仅管理员可用（入口、快捷键、按键表一致地隐藏/失效） */
const ADMIN_ONLY_ACTIONS = new Set([
  'markers', 'togglePoiPick', 'toggleRangePick', 'annotateConfirm', 'annotateUndo', 'annotateCancel',
])
const savingView = ref(false)

const maps = ref<MapSummary[]>([])
const mapOptions = computed(() => maps.value.map(m => ({ label: m.name, value: m.id })))
const currentMapId = ref('')
/**
 * 同一世界的其它维度（含当前这张）。
 *
 * <p>各维度是各自独立的地图（各自渲染、各自瓦片），所以切换维度＝切到兄弟地图。只有一个维度时
 * 不显示切换器：没什么可切的。</p>
 */
const dimensionChoices = ref<DimensionChoice[]>([])
const hasDimensionSwitch = computed(() => needsDimensionSwitch(dimensionChoices.value))
/** 设置面板「切换维度」下拉：值是当前地图 id，改动即切换（不驻留副本，避免出现第二份真相） */
const dimensionOptions = computed(() => dimensionChoices.value.map(c => ({ label: c.label, value: c.id })))
const dimensionSelectValue = computed({
  get: () => currentMapId.value,
  set: (id: string) => switchDimension(id),
})
/** 只有一个维度时下拉没有意义，退回展示当前维度名 */
const currentDimensionLabel = computed(
  () => dimensionChoices.value.find(c => c.current)?.label ?? '主世界',
)
const loading = ref(false)
const error = ref('')
const container = ref<HTMLCanvasElement | null>(null)
/** 打开地图时的初始机位（保存的默认视角，未保存则为此时的内置机位），「重置视角」回到这里 */
const initialView = ref<CameraState | null>(null)
/** 该地图是否已保存过默认视角（再次保存属于覆盖，需弹窗确认） */
const hasSavedView = ref(false)
// 必须用 shallowRef：ref 会对实例做深层 reactive 代理，THREE 的相机/场景对象带
// 不可配置属性（modelViewMatrix 等），渲染时经代理访问触发 Proxy 不变量报错
const viewer = shallowRef<VoxelithViewer | null>(null)

// HUD
const cameraPos = ref({ x: 0, y: 0, z: 0 })
const loaded = ref(0)
const failed = ref(0)
const distance = ref(0)

// 控制面板
const mode = ref<ViewerMode>('flight')
// 设置面板默认收起：进页面先看到完整地图，设置按需展开（大面板常驻会盖住半张图）
const panelOpen = ref(false)
/** 菜单显隐：隐藏时收起地图切换/功能条/设置面板，仅留展开按钮与 HUD */
const menuVisible = ref(true)
/** 全屏展示：插件根节点整体全屏；全屏时宿主页签不在屏幕内，上下偏移归零（见 applyChromeOffsets） */
const fullscreen = ref(false)
/** 查看页根节点：主题适配的偏移变量挂在它上面 */
const viewerRoot = ref<HTMLDivElement | null>(null)

/**
 * 平台查看页（/platform/…）还是公开站点页（/map）。
 *
 * <p>同一个组件两种形态：平台页是「纯地图」——宿主不套页眉页脚（路由声明 fullscreen），偏移归零、
 * 功能条横排顶部；站点页是「内容页」——保留页眉页脚，偏移按量测回填、功能条保持纵向。</p>
 */
const topMenuLayout = computed(() => (props.route?.path ?? '').startsWith('/platform/'))

/**
 * 沉浸模式（默认 `\`）：隐藏全部界面组件，只留地图。
 *
 * <p>比「呼出界面」键（{@code toggleUi}）更彻底：连 HUD、准星、徽标、2D 操作提示一起收掉，
 * 站点页还会让宿主页眉页脚一起退场（用与量测偏移同一套主题类名；匹配不上就只是留着页眉，
 * 不会出错）。取点进行中的提示与方块高亮保留——那是地图交互本身，收掉就没法标注了。</p>
 */
const immersive = ref(false)

// ---------------------------------------------------------------------------
// 主题适配（参照 neco-pixel 像素主题的结构：.site-layout-header 吸顶页签 +
// .footer-area 底部页签）：运行时量测两者的实际高度，地图展示区独立占据
// 两者之间的整段视口，不被主题页签压缩或推挤出屏幕。
// ---------------------------------------------------------------------------

let chromeObserver: ResizeObserver | null = null

function applyChromeOffsets(): void {
  const root = viewerRoot.value
  if (!root) return
  // 偏移归零的三种情形：平台查看页（宿主不套页眉页脚）、全屏展示（宿主页签不在屏幕内）、
  // 沉浸模式（页眉页脚已退场）——不归零就会白留一条页签高度
  if (topMenuLayout.value || fullscreen.value || immersive.value) {
    root.style.setProperty('--voxelith-top-offset', '0px')
    root.style.setProperty('--voxelith-bottom-offset', '0px')
    return
  }
  // 主题类名优先，退回通用 header/footer（其他主题也能兜底）
  const header = (document.querySelector('.site-layout-header')
    ?? document.querySelector('header')) as HTMLElement | null
  const footer = (document.querySelector('.footer-area')
    ?? document.querySelector('footer')) as HTMLElement | null
  const top = header?.offsetHeight ?? 0
  const bottom = footer?.offsetHeight ?? 0
  root.style.setProperty('--voxelith-top-offset', `${top || 64}px`)
  root.style.setProperty('--voxelith-bottom-offset', `${bottom}px`)
}

function watchChrome(): void {
  applyChromeOffsets()
  chromeObserver?.disconnect()
  chromeObserver = null
  // 平台页没有需要避让的宿主页签：不装观测器（量到的也只是管理端自己的 header）
  if (topMenuLayout.value) return
  const header = document.querySelector('.site-layout-header')
    ?? document.querySelector('header')
  const footer = document.querySelector('.footer-area') ?? document.querySelector('footer')
  if (!header && !footer) return
  chromeObserver = new ResizeObserver(() => applyChromeOffsets())
  if (header) chromeObserver.observe(header)
  if (footer) chromeObserver.observe(footer)
}
/** 地图切换胶囊显隐（可独立折叠） */
const mapSwitchVisible = ref(true)

/**
 * 左侧菜单按功能折叠：组头是一个小按钮，点开展开该组工具，再点收起。
 * 相比把所有按钮一直铺开（矮屏要滚动、按钮淹没在长列里），折叠让常用组保持展开、
 * 其余一键收起，菜单高度可控。
 */
type SideGroup = 'view' | 'markers'
/** 视角模式默认展开（常用）；标注工具默认折叠（用时再展开，避免菜单过长） */
const expandedGroups = reactive<Record<SideGroup, boolean>>({
  view: true,
  markers: false,
})

function toggleSideGroup(group: SideGroup): void {
  expandedGroups[group] = !expandedGroups[group]
}

/**
 * 打开弹层前把焦点从原元素上移走。
 *
 * <p>模态弹层打开时，组件库会把页面原内容标成 {@code aria-hidden}；若焦点仍留在原内容里
 * （例如刚点过的下拉框），浏览器会报「aria-hidden 元素内保留焦点」的可访问性告警。
 * 主动 blur 一次让弹层接管焦点，告警随之消失。</p>
 */
function blurActiveForOverlay(): void {
  const active = document.activeElement
  if (active instanceof HTMLElement && active !== document.body) {
    active.blur()
  }
}

/**
 * 组头上的「有工具在生效」小圆点提示：只给标注工具组。
 *
 * <p>视角模式组不加：模式按钮本身就在组内高亮，折叠时再亮一个小圆点纯属噪声
 * （线上反馈「俯视倾斜时会多出小蓝点」）。标注工具是「正在进行的操作」，折叠时值得提示。</p>
 */
const showGroupDot = computed<Record<SideGroup, boolean>>(() => ({
  view: false,
  markers: markersOpen.value || savingMarker.value || rangePick.value || poiPick.value,
}))

/** 2D 地图开启时菜单收拢：只留「地图切换」与「全屏」，避免一堆按键压在平面地图上 */
const sidebarMinimal = computed(() => map2dActive.value)

/** 2D 地图上是否处于取点模式（范围或点位） */
const is2dPicking = computed(() => rangePick.value || poiPick.value)
const markersOpen = ref(false)

/**
 * 浏览器缩放比是否为「25% 的整数倍」（100%/125%/150%…）。
 *
 * <p>2D 底图按整数设备像素对齐后，这些比例下渲染完全精确；其它比例（110%/90%/80%…）浏览器仍会
 * 在个别位置挪动 1 个设备像素的相位——属浏览器内部行为，JS 侧拿不到。取点与坐标始终以实际方块
 * 为准、不受影响，但值得提示一句。</p>
 */
function isQuarterStepZoom(): boolean {
  if (typeof window === 'undefined') return true
  const percent = (window.devicePixelRatio || 1) * 100
  return Math.abs(percent - Math.round(percent / 25) * 25) <= 0.5
}

/** 只在会出现像素相位抖动的缩放比例下提示，避免常态噪声（页面缩放会触发 resize，随之刷新） */
const subPixelCaveat = ref(!isQuarterStepZoom())

function refreshSubPixelCaveat(): void {
  subPixelCaveat.value = !isQuarterStepZoom()
}

// 标注图层
const markerSets = ref<MarkerSet[]>([])
const markerVisibility = reactive<Record<string, boolean>>({})
const markerStatus = ref('')

// Y 轴切片
const ySliceEnabled = ref(false)
const ySliceMin = ref(-64)
const ySliceMax = ref(320)
const boundsMin = ref(-64)
const boundsMax = ref(320)

// 视距 / 显示
const layerFilter = ref<'all' | 'hires' | 'lod'>('all')
const detailChunks = ref(16)
const autoDistance = ref(true)
const displayP3 = ref(false)
const skyline = ref(false)

const MODE_LABELS: Record<ViewerMode, string> = {
  flight: '自由飞行',
  firstPerson: '第一人称',
  tiltOrbit: '俯视倾斜',
}

/** 顶部横向功能条用的短视角名（不超过 4 字；纵向形态的 title 仍用上面的完整名） */
const VIEW_MODE_SHORT: Record<ViewerMode, string> = {
  flight: '飞行',
  firstPerson: '第一人称',
  tiltOrbit: '俯视',
}

// ---------------------------------------------------------------------------
// 沉浸模式与按键自定义
//
// 公开页默认不显示任何界面元素（顶栏/设置/标注/角标全部收起，纯看图），
// 按下「呼出界面」键（默认 H，可自定义）才显示；再按一次回到沉浸态。
// 移动键（WASD 等）可在设置面板里重新绑定：vendor 控制器只认标准键位，
// 重映射由 useKeyBindings 在捕获阶段合成标准键事件实现，vendor 零改动。
// ---------------------------------------------------------------------------

const toast = useYdToast()
/** 查看页提示统一放底部居中：顶部弹出会盖住工具栏的操作按钮（范围标注时会连续提示） */
function notify(kind: 'success' | 'error' | 'warning' | 'info', message: string): void {
  toast[kind](message, { position: 'bottom-center' })
}

// ---------------------------------------------------------------------------
// 全屏展示
//
// 全屏的是插件根节点本身而非画布：左侧功能条、设置面板、标注窗口等浮层都在根节点
// 内定位，跟着一起全屏才能继续操作；宿主页签在根节点之外，全屏后自然不在屏幕内。
// 进出全屏不必手动 resize 画布——引擎对 canvas 挂了 ResizeObserver，尺寸变化自己跟。
// ---------------------------------------------------------------------------

async function toggleFullscreen(): Promise<void> {
  const root = viewerRoot.value
  if (!root) return
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    }
    else {
      // 必须由用户手势触发（点击按钮或按键都算），否则浏览器会拒绝
      await root.requestFullscreen()
    }
  }
  catch {
    notify('error', '无法切换全屏：浏览器拒绝了全屏请求（可能缺少用户手势或被权限策略拦截）')
  }
}

/** 浏览器全屏状态变化（含用户按 Esc 原生退出）：同步状态并重算页签偏移 */
function onFullscreenChange(): void {
  fullscreen.value = document.fullscreenElement === viewerRoot.value
  applyChromeOffsets()
}

const bindings = useKeyBindings()
const { keymap } = bindings

/** 按键设置里列出的动作：普通用户不显示标注类（那几项对他们本来就无效） */
const visibleKeyActions = computed(() => bindings.actions.filter(
  a => isAdmin || !ADMIN_ONLY_ACTIONS.has(a.action),
))

/** 界面元素是否可见（默认显示菜单栏；按「呼出界面」键可切换沉浸看图） */
const chromeVisible = ref(true)
/** 底部按键提示（呼出/隐藏时各提示一次，数秒后淡出） */
const hintVisible = ref(false)
const hintSeq = ref(0)
let hintTimer = 0

/** 正在等待改键的动作 */
const listeningAction = ref<string | null>(null)

function flashHint(): void {
  hintVisible.value = false
  hintSeq.value++
  // 下一帧再置 true，重挂 DOM 重播淡出动画
  requestAnimationFrame(() => {
    hintVisible.value = true
  })
  if (hintTimer) window.clearTimeout(hintTimer)
  hintTimer = window.setTimeout(() => {
    hintVisible.value = false
  }, 6500)
}

function toggleChrome(): void {
  // 沉浸模式下按「呼出界面」＝退出沉浸：否则按钮状态与画面不一致（界面被沉浸样式压着不显示）
  if (immersive.value) {
    setImmersive(false)
    return
  }
  chromeVisible.value = !chromeVisible.value
  if (!chromeVisible.value) flashHint()
}

/** 切换沉浸模式：隐藏/恢复全部界面组件，并同步宿主页眉页脚的避让偏移。 */
function setImmersive(next: boolean): void {
  immersive.value = next
  // 退出时把功能条一并恢复：`\` 是「隐藏 / 恢复全部组件」的开关，不该只退还一半
  chromeVisible.value = !next
  // 宿主页眉页脚由样式表在 html.voxelith-immersive 下隐藏；离开本页/退出沉浸必须摘掉，
  // 否则宿主导航会一直消失
  document.documentElement.classList.toggle('voxelith-immersive', next)
  applyChromeOffsets()
  flashHint()
}

function toggleImmersive(): void {
  setImmersive(!immersive.value)
}

/**
 * 平台查看页：把「本页要隐藏宿主控制台页签条」告诉样式表。
 *
 * <p>宿主把带组件的插件路由统一包进控制台布局（子路由 + Layout），而路由的 {@code fullscreen}
 * 只被站点页消费，于是平台查看页上会留下一条只写着页面标题的页签条——地图已占满视口，
 * 那一条纯属多余。宿主未提供「平台插件页不套布局」的路由能力，这里沿用与沉浸模式同一套
 * 类名适配层：类名对不上只是页签条留着，不会出错。</p>
 */
function syncHostChromeClass(): void {
  if (typeof document === 'undefined') return
  document.documentElement.classList.toggle('voxelith-fullbleed', topMenuLayout.value)
}

/** 页内返回：宿主页签条已隐藏，留一个出口（没有历史记录时回地图管理页）。 */
function leaveViewer(): void {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/platform/plugins/voxelith-map/maps')
}

const MODE_ORDER: ViewerMode[] = ['flight', 'firstPerson', 'tiltOrbit']

const MODE_ICONS: Record<ViewerMode, string> = {
  flight: 'i-mdi:flight',
  firstPerson: 'i-mdi:eye-outline',
  tiltOrbit: 'i-mdi:map',
}

function cycleMode(): void {
  const next = MODE_ORDER[(MODE_ORDER.indexOf(mode.value) + 1) % MODE_ORDER.length]
  setMode(next)
}

/**
 * 设置窗口 / 标注图层窗口：两个都是大面板，同时展开会连成一整片盖住地图，因此互斥。
 *
 * <p>互斥之后窗口区才可以改成自然堆叠（见 .voxelith-menu-panels）：同一时刻只有一个大窗口，
 * 不会出现「开一个把另一个挤下去」的位置跳动，同时省掉原固定槽位为未展开窗口留的空白。</p>
 */
function toggleSettingsPanel(): void {
  panelOpen.value = !panelOpen.value
  if (panelOpen.value) markersOpen.value = false
}

function toggleLayersPanel(): void {
  if (!isAdmin) return
  markersOpen.value = !markersOpen.value
  if (markersOpen.value) panelOpen.value = false
}

/** 标注图层：标注属管理能力，普通用户不显示面板也不响应快捷键（按钮侧另有 v-if）。 */
function toggleMarkers(): void {
  if (!isAdmin) return
  chromeVisible.value = true
  toggleLayersPanel()
}

function startBind(action: string, e: MouseEvent): void {
  // 改键捕获会打断当前按键：先把按住的移动键全部释放，避免松键事件丢失后持续移动
  bindings.releaseAllKeys()
  listeningAction.value = action
  // 移除按钮焦点：捕获态下按 Space/Enter 不会再触发按钮 click
  ;(e.currentTarget as HTMLElement | null)?.blur()
  bindings.startListening(action, (code, conflict) => {
    listeningAction.value = null
    if (conflict) notify('warning', `「${describeKey(code ?? '')}」已被「${conflict}」占用，请换一个键`)
  })
}


/** 宽色域支持探测结果：依赖三维栈，故等 chunk 到位后在 openMap 里回填 */
const p3Supported = ref(false)

function setMode(m: ViewerMode): void {
  // 「俯视倾斜」位＝上一版 2D 地图的展示效果（正俯视相机）：进入即开机位，切走即恢复三维相机；
  // 其余模式照常。viewer 内部 mode 不写 tiltOrbit，退出时由 setTopDown(false) 按原模式重建控制器。
  mode.value = m
  applyModeCamera(m)
}

async function loadMarkers(id: string, target: VoxelithViewer): Promise<void> {
  markerSets.value = []
  for (const key of Object.keys(markerVisibility)) delete markerVisibility[key]
  markerStatus.value = ''
  try {
    const { markerSetSchema } = await loadViewerStack()
    const url = api.markersJsonUrl(id)
    const res = await fetch(url)
    if (!res.ok) return
    const raw = extractMarkerSets(await res.json())
    const valid: MarkerSet[] = []
    let invalid = 0
    for (const item of raw) {
      const parsed = markerSetSchema.safeParse(item)
      if (!parsed.success) {
        invalid++
        continue
      }
      valid.push(parsed.data)
    }
    // 图层面板默认只显示「最新」一组标注（sorting 最大，平级保持文件顺序），
    // 其余组默认收起，需要时在面板里手动勾选展开，避免多组图层互相糊脸
    const latestSet = [...valid].sort((a, b) => (b.sorting ?? 0) - (a.sorting ?? 0))[0]
    for (const set of valid) {
      markerVisibility[set.id] = set.id === latestSet?.id && !set.defaultHidden
    }
    markerSets.value = valid
    // 挂到本次打开的 viewer 上而不是 viewer.value：await 期间若用户切换了地图，
    // 迟到的标注不能污染新 viewer
    target.setMarkerSets(valid)
    markerStatus.value = invalid > 0 ? `${invalid} 组标注格式不合法，已跳过` : ''
  }
  catch {
    markerStatus.value = '标注加载失败'
  }
}

/** 读取并应用该地图保存的默认视角；未设置/加载失败时保持内置机位。 */
async function applyDefaultView(id: string, target: VoxelithViewer): Promise<void> {
  try {
    const res = await fetch(api.viewJsonUrl(id))
    if (!res.ok) return
    const view = (await res.json()) as Partial<MapDefaultView>
    if (typeof view.x === 'number' && typeof view.tx === 'number') {
      target.applyCameraState(view as MapDefaultView)
      initialView.value = view as CameraState
      hasSavedView.value = true
    }
  }
  catch {
    // 没有保存过默认视角：静默走内置机位
  }
}

/** 截图先缩到长边 1280 再转 base64：全画布 PNG 在高分屏下很容易超出后端 8MB 限制 */
function coverPngBase64(dataUrl: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const scale = Math.min(1, 1280 / Math.max(img.width, img.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(img.width * scale))
      canvas.height = Math.max(1, Math.round(img.height * scale))
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        reject(new Error('截图处理失败'))
        return
      }
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
      const out = canvas.toDataURL('image/png')
      resolve(out.slice(out.indexOf(',') + 1))
    }
    img.onerror = () => reject(new Error('截图处理失败'))
    img.src = dataUrl
  })
}

/** 把当前机位与画面保存为该地图的默认初始视角与列表封面（仅管理员可见）。 */
async function saveDefaultView(): Promise<void> {
  const v = viewer.value
  if (!v || !currentMapId.value || savingView.value) return
  try {
    const state = v.getCameraState()
    if (!state) throw new Error('查看器尚未就绪')
    // snapshot 失败会带具体原因抛错（画布未渲染 / 上下文丢失 / 浏览器拒绝读取）
    const dataUrl = v.snapshot()
    pendingViewSave.state = state
    pendingViewSave.coverBase64 = await coverPngBase64(dataUrl)
    // 非第一次保存：先弹窗确认覆盖
    if (hasSavedView.value) {
      overwriteViewConfirm.value = true
      return
    }
    await doSaveDefaultView()
  }
  catch (e) {
    notify('error', e instanceof Error ? e.message : '保存默认视角失败')
  }
}

/** 待写入的默认视角（覆盖确认期间暂存） */
const pendingViewSave = reactive<{
  state: CameraState | null
  coverBase64: string | null
}>({ state: null, coverBase64: null })

const overwriteViewConfirm = ref(false)

async function doSaveDefaultView(): Promise<void> {
  const v = viewer.value
  const state = pendingViewSave.state
  const coverBase64 = pendingViewSave.coverBase64
  if (!v || !currentMapId.value || !state || !coverBase64) return
  overwriteViewConfirm.value = false
  savingView.value = true
  try {
    await adminApi.setDefaultView(currentMapId.value, { ...state, coverPngBase64: coverBase64 })
    hasSavedView.value = true
    pendingViewSave.state = null
    pendingViewSave.coverBase64 = null
    notify('success', '已保存默认视角与列表封面')
  }
  catch (e) {
    notify('error', e instanceof Error ? e.message : '保存默认视角失败')
  }
  finally {
    savingView.value = false
  }
}

// ---------------------------------------------------------------------------
// 标注编辑（仅管理员）：点位标注打在视线落点，范围标注以两次视线落点为对角
// 画一片矩形；保存进「管理标注」集（与其他标注集同库，图层开关通用）。
// ---------------------------------------------------------------------------

const savingMarker = ref(false)
/** 按键设置独立弹窗 */
const keybindsOpen = ref(false)

// ---------------------------------------------------------------------------
// 二维（正俯视）视图 / 准星瞄准高亮 / 坐标传送
// ---------------------------------------------------------------------------

/**
 * 两条「平面查看」路径，各自独立：
 * ①「俯视倾斜」模式＝上一版 2D 地图的展示效果：正俯视相机（拖拽平移、滚轮缩放），纯机位、必可用；
 * ②「2D 地图」按钮＝渲染任务烘焙的 LOD 航拍图集页（纯图片，打开即时）。没有图集的旧地图
 *   给不出图片，提示改用①，按钮不再悄悄去动机位——两个入口各管各的，状态不会互相污染。
 */
const map2d = useMap2D()
/** 纯图片 2D 地图是否打开 */
const map2dActive = ref(false)
/** 「俯视倾斜」模式选中中＝正俯视机位生效（相机拖拽平移/滚轮缩放） */
const isTopDownCamera = computed(() => mode.value === 'tiltOrbit')

/** 准星当前对准的方块（非俯视视角取点时的反馈） */
const aimPoint = ref<{ x: number, y: number, z: number } | null>(null)
let aimFrame = 0

/** 取点中且不在任何俯视/2D 视图时，才需要「屏幕中心准星」——俯视与 2D 都是点击取点，不看视线 */
function aimNeeded(): boolean {
  return rangePick.value && !map2dActive.value && !isTopDownCamera.value
}

/** 刷新准星高亮与坐标显示（取点中按帧刷新，跟着相机走） */
function updateAim(): void {
  const v = viewer.value
  if (!v || !aimNeeded()) {
    if (aimPoint.value) {
      aimPoint.value = null
      v?.clearAimHighlight()
    }
    return
  }
  const point = v.getCameraFocusPoint()
  if (!point) {
    aimPoint.value = null
    v.clearAimHighlight()
    return
  }
  const block = { x: Math.floor(point.x), y: Math.floor(point.y), z: Math.floor(point.z) }
  aimPoint.value = block
  v.setAimHighlight(block.x, block.y, block.z)
}

function aimLoop(): void {
  aimFrame = 0
  updateAim()
  if (aimNeeded()) {
    aimFrame = requestAnimationFrame(aimLoop)
  }
}

function startAimLoop(): void {
  if (aimFrame === 0 && aimNeeded()) {
    aimFrame = requestAnimationFrame(aimLoop)
  }
}

function stopAimLoop(): void {
  if (aimFrame !== 0) {
    cancelAnimationFrame(aimFrame)
    aimFrame = 0
  }
  aimPoint.value = null
  viewer.value?.clearAimHighlight()
}

/** 按「俯视倾斜」模式进入正俯视机位；切到其它模式时恢复该模式的三维相机。 */
function applyModeCamera(m: ViewerMode): void {
  if (m === 'tiltOrbit') {
    viewer.value?.setTopDown(true)
    stopAimLoop()
    return
  }
  viewer.value?.setTopDown(false)
  viewer.value?.setMode(m)
}

/** 打开/关闭真正的 2D 地图（航拍图集纯图片）。没有图集时提示改用「俯视倾斜」模式。 */
function toggleAtlas2D(): void {
  if (map2dActive.value) {
    map2dActive.value = false
    map2d.reset()
    viewer.value?.setPaused(false)
    return
  }
  if (openAtlasImage(viewer.value)) {
    // 暂停三维渲染：纹理缺失的地图上每帧都在刷空贴图警告，主线程被压死后
    // 2D 地图的加载回调会被饿到几乎不动（「一直加载中、切图才报错」的根因）
    viewer.value?.setPaused(true)
    notify('info', '已打开 2D 地图：拖拽平移，滚轮缩放')
  }
}

/** 打开 2D 地图：直接展示发布目录里航拍图集页（取瓦片数最多的层级，通常即 level 1）。 */
function openAtlasImage(v: VoxelithViewer | null): boolean {
  if (!v) return false
  const pages = [...(v.getManifest().lodAtlases ?? [])].sort((a, b) => a.level - b.level)
  if (pages.length === 0) {
    notify('warning', '这张地图的渲染产物中没有航拍图集（lod-atlas.png）：重新渲染一次即可生成')
    return false
  }
  // 统计各层级的瓦片数，取瓦片最多的一页（正常即 level 1，分辨率也最高）
  const tileCounts = new Map<number, number>()
  for (const tile of v.getManifest().tiles) {
    tileCounts.set(tile.level, (tileCounts.get(tile.level) ?? 0) + 1)
  }
  const best = [...pages].sort((a, b) => {
    const tilesDelta = (tileCounts.get(b.level) ?? 0) - (tileCounts.get(a.level) ?? 0)
    return tilesDelta !== 0 ? tilesDelta : a.level - b.level
  })[0]
  // 首选地表栅格（1px=1方块，精确坐标换算）；旧渲染没有时退用图集页
  map2dActive.value = true
  const bounds = {
    minX: v.getManifest().boundsMin[0],
    minZ: v.getManifest().boundsMin[2],
    maxX: v.getManifest().boundsMax[0],
    maxZ: v.getManifest().boundsMax[2],
  }
  map2d.load(api.surfaceUrl(currentMapId.value), {
    fallbackUrl: `${api.mapBaseUrl(currentMapId.value)}/${best.url}`,
    bounds,
  })
  return true
}

// 坐标传送：三维走 flyTo，二维把视点移到对应 XZ
const teleport = reactive({ x: 0, y: 64, z: 0 })
const teleportOpen = ref(false)

function teleportTo(): void {
  const v = viewer.value
  if (!v) return
  if (isTopDownCamera.value) {
    v.moveTopDownTo(teleport.x, teleport.z)
    notify('info', `已定位到 X=${teleport.x} Z=${teleport.z}`)
    return
  }
  v.flyTo(teleport.x, teleport.y, teleport.z)
  notify('info', `已传送到 ${teleport.x} / ${teleport.y} / ${teleport.z}`)
}



// ---------------------------------------------------------------------------
// 2D 地图（航拍图集）的指针交互：拖拽平移、滚轮缩放
// ---------------------------------------------------------------------------

const map2dDrag = reactive({ active: false, x: 0, y: 0, moved: false })

/** 2D 地图上的鼠标悬停位置（视口像素 + 世界坐标）：取点时给出精确实时反馈。 */
const hover2d = ref<{ sx: number, sy: number, x: number, z: number } | null>(null)

/**
 * 方块单元在视口中的像素方框（框选高亮用）。
 *
 * <p>地表栅格 1px = 1 方块，所以一个方块就是栅格上的一格。位置与尺寸统一交给 map2d.imageRect
 * 在**底图内容所在的设备像素网格**上计算：高亮框、光标取到的格、底图渲染出的格三者由构造重合
 * （网格与理由见 useMap2D 文件头）。</p>
 *
 * <p>注意不能再用「精确坐标 + 让浏览器自己取整」：格宽为小数设备像素时浏览器会改自己的栅格化
 * 比例（实测 2%~3% 漂移），高亮框会跟底图错开。</p>
 */
function blockSquare(
  bx: number, bz: number,
): { left: number, top: number, width: number, height: number } | null {
  const b = map2d.bounds.value
  const img = map2d.imageSize
  if (!b || !img.width || !img.height) return null
  const worldPerImageX = (b.maxX - b.minX) / img.width
  const worldPerImageZ = (b.maxZ - b.minZ) / img.height
  if (!(worldPerImageX > 0) || !(worldPerImageZ > 0)) return null
  return map2d.imageRect(
    (bx - b.minX) / worldPerImageX,
    (bz - b.minZ) / worldPerImageZ,
    1 / worldPerImageX,
    1 / worldPerImageZ,
  )
}

/** 预选方块：光标所在的那一格（取点前就能看清会选中哪个方块）。 */
const hoverSquare = computed(() => {
  if (!is2dPicking.value || !hover2d.value) return null
  return blockSquare(hover2d.value.x, hover2d.value.z)
})

/** 已选方块：每个已取点所在的那一格；最后取的一个标为当前。 */
const selectedSquares = computed(() => {
  if (!rangePick.value) return []
  const last = rangePoints.value.length - 1
  return rangePoints.value
    .map((point, index) => {
      const rect = blockSquare(point.x, point.z)
      return rect ? { ...rect, key: index, current: index === last } : null
    })
    .filter((rect): rect is { left: number, top: number, width: number, height: number, key: number, current: boolean } => rect !== null)
})

function on2dMouseDown(e: MouseEvent): void {
  // 取点模式下不启动拖拽平移（点击 = 取点），并阻止浏览器默认选择/拖拽
  if (rangePick.value || poiPick.value) {
    e.preventDefault()
    return
  }
  map2dDrag.active = true
  map2dDrag.moved = false
  map2dDrag.x = e.clientX
  map2dDrag.y = e.clientY
}

/**
 * 鼠标移动：**按动画帧节流**。
 *
 * <p>mousemove 可达数百 Hz，而每次处理都会更新悬停状态、进而重渲染整棵 2D 覆盖层
 * （含大图的 transform）——逐事件处理是明显的性能坑。这里只记录最新位置，
 * 每帧统一处理一次；同时把「同一格内移动」直接丢弃，避免无意义的响应式更新。</p>
 */
let hoverRaf = 0
let pendingMouse: { clientX: number, clientY: number, target: HTMLElement } | null = null

function on2dMouseMove(e: MouseEvent): void {
  pendingMouse = {
    clientX: e.clientX,
    clientY: e.clientY,
    target: e.currentTarget as HTMLElement,
  }
  if (hoverRaf !== 0) return
  hoverRaf = requestAnimationFrame(flush2dMouse)
}

function flush2dMouse(): void {
  hoverRaf = 0
  const pending = pendingMouse
  pendingMouse = null
  if (!pending) return
  const rect = pending.target.getBoundingClientRect()
  const localX = pending.clientX - rect.left
  const localY = pending.clientY - rect.top
  const world = map2d.screenToWorld(localX, localY)
  const next = world
    ? { sx: localX, sy: localY, x: Math.floor(world.x), z: Math.floor(world.z) }
    : null
  // 只在「跨格或像素位置变化」时更新：同一格内微动不触发重渲染
  const prev = hover2d.value
  if (!next) {
    if (prev) hover2d.value = null
  }
  else if (!prev || prev.x !== next.x || prev.z !== next.z
    || prev.sx !== next.sx || prev.sy !== next.sy) {
    hover2d.value = next
  }
  if (!map2dDrag.active) return
  const dx = pending.clientX - map2dDrag.x
  const dy = pending.clientY - map2dDrag.y
  map2d.panBy(dx, dy)
  map2dDrag.x = pending.clientX
  map2dDrag.y = pending.clientY
}

function on2dMouseLeave(): void {
  map2dDrag.active = false
  hover2d.value = null
}

function on2dMouseUp(): void {
  map2dDrag.active = false
}

function on2dWheel(e: WheelEvent): void {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  // 一格滚轮 ±15%：触控板的小 delta 也按比例起作用
  map2d.zoomAt(e.clientX - rect.left, e.clientY - rect.top, e.deltaY < 0 ? 1.15 : 1 / 1.15)
}

/** 世界坐标 → 2D 视口像素（标注点渲染与取点共用）。 */
/**
 * 世界坐标 → 2D 视口像素（标注点渲染与取点共用）。
 *
 * <p>刻意**不读** {@code getBoundingClientRect()}：视口坐标只由 view 的 offset/scale 决定，
 * 而该调用会强制同步布局；它此前在「每个标记点每次重算」的路径上被调用（markerDots 逐点算），
 * 鼠标一动就触发成百上千次重排，在放大图片上直接把渲染拖死——既卡顿又让新绘制的元素迟迟不上屏。</p>
 */
function worldTo2dScreen(x: number, z: number): { x: number, y: number } | null {
  const b = map2d.bounds.value
  if (!b || !map2d.imageSize.width) return null
  const worldPerImageX = (b.maxX - b.minX) / map2d.imageSize.width
  const worldPerImageZ = (b.maxZ - b.minZ) / map2d.imageSize.height
  return {
    x: map2d.view.offsetX + (x - b.minX) / worldPerImageX * map2d.view.scale,
    y: map2d.view.offsetY + (z - b.minZ) / worldPerImageZ * map2d.view.scale,
  }
}

/**
 * 范围多边形预览顶点（视口像素）。
 *
 * <p>取点记下的是方块坐标（即格子的角），这里统一 +0.5 平移到方块中心：
 * 编号徽标才会落在被框选的整格中央，连线也随之对齐格子中心。</p>
 */
const rangeSvgPoints = computed(() => {
  if (!rangePick.value) return []
  return rangePoints.value
    .map((p) => worldTo2dScreen(p.x + 0.5, p.z + 0.5))
    .filter((p): p is { x: number, y: number } => p !== null)
})

const rangeSvgLine = computed(() => rangeSvgPoints.value.map(p => `${p.x},${p.y}`).join(' '))

/** ≥3 个角时补一条虚线回到首点：提示「这就是将要闭合的多边形」 */
const rangeCloseLine = computed(() => {
  const points = rangeSvgPoints.value
  if (points.length < 3) return null
  const first = points[0]
  const last = points[points.length - 1]
  return { x1: last.x, y1: last.y, x2: first.x, y2: first.y }
})

/** 橡皮筋：从最后一个已选角到光标，预览「点下去会新增哪条边」（范围标注的核心反馈） */
const rangeRubberLine = computed(() => {
  if (!rangePick.value || !hover2d.value) return null
  const points = rangeSvgPoints.value
  if (points.length === 0) return null
  const last = points[points.length - 1]
  return { x1: last.x, y1: last.y, x2: hover2d.value.sx, y2: hover2d.value.sy }
})

/** 现有 POI 标注点（可见集）在 2D 地图上的位置。 */
const markerDots = computed(() => {
  const out: Array<{ key: string, left: number, top: number, label: string }> = []
  if (!map2dActive.value) return out
  for (const set of markerSets.value) {
    if (markerVisibility[set.id] === false) continue
    for (const marker of set.markers) {
      if (marker.type !== 'poi') continue
      const pos = worldTo2dScreen(marker.position.x, marker.position.z)
      if (pos) out.push({ key: set.id + '/' + marker.id, left: pos.x, top: pos.y, label: marker.label })
    }
  }
  return out
})

/**
 * 2D 地图上的点击：取点模式下记为多边形一角/POI 坐标。
 * 按下/抬起位移超过阈值视为拖拽平移，不算取点。
 */
function on2dClick(e: MouseEvent): void {
  if (map2dDrag.moved) return
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const world = map2d.screenToWorld(e.clientX - rect.left, e.clientY - rect.top)
  if (!world) return
  const blockX = Math.floor(world.x)
  const blockZ = Math.floor(world.z)
  if (rangePick.value) {
    rangePoints.value = [...rangePoints.value, { x: blockX, z: blockZ }]
    if (rangePoints.value.length >= 3) {
      notify('info', `已记录第 ${rangePoints.value.length} 个角（X ${blockX}、Z ${blockZ} 方块），`
        + '可继续点击或按 Enter 完成')
    }
    return
  }
  if (poiPick.value) {
    openPoiDialog(blockX, 64, blockZ, true)
    poiPick.value = false
  }
}

/**
 * 2D 地图键盘平移：方向键与 WASD，长按持续缓慢平移。
 *
 * <p>方向按「按键 = 视口移动方向」：按 ↑ 视口上移（画面内容相应下移，offset 增大）。
 * 长按由 requestAnimationFrame 逐帧推进（每帧 6px，约 360px/秒），比单次 Jump 更好控。</p>
 *
 * <p>绑在 window 上而不是视口元素：视口是普通 div，拿不到键盘焦点。</p>
 */
const panKeys = new Set<string>()
let panFrame = 0

function on2dPanKeyDown(e: KeyboardEvent): void {
  if (!map2dActive.value || !map2d.resolvedUrl.value) return
  // 输入框/下拉里打字不驱动地图
  const target = e.target as HTMLElement | null
  const tag = target?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) return
  const key = e.key.toLowerCase()
  if (!['arrowup', 'arrowdown', 'arrowleft', 'arrowright', 'w', 'a', 's', 'd'].includes(key)) return
  panKeys.add(key)
  e.preventDefault()
  if (panFrame === 0) panFrame = requestAnimationFrame(pan2d)
}

function on2dPanKeyUp(e: KeyboardEvent): void {
  panKeys.delete(e.key.toLowerCase())
  if (panKeys.size === 0 && panFrame !== 0) {
    cancelAnimationFrame(panFrame)
    panFrame = 0
  }
}

function pan2d(): void {
  panFrame = 0
  if (!map2dActive.value || panKeys.size === 0) return
  const step = 6
  let dx = 0
  let dy = 0
  // 按 ↑/W 视口上移 → 内容下移 → offsetY 增大
  if (panKeys.has('arrowup') || panKeys.has('w')) dy += step
  if (panKeys.has('arrowdown') || panKeys.has('s')) dy -= step
  if (panKeys.has('arrowleft') || panKeys.has('a')) dx += step
  if (panKeys.has('arrowright') || panKeys.has('d')) dx -= step
  map2d.panBy(dx, dy)
  panFrame = requestAnimationFrame(pan2d)
}

/** 用「当前视线落点」回填传送坐标：省掉手抄坐标 */
function fillTeleportFromFocus(): void {
  const v = viewer.value
  if (!v) return
  const point = v.getCameraFocusPoint() ?? v.getCameraWorldPosition()
  if (!point) {
    notify('warning', '当前视线没有落在地形上，无法取点')
    return
  }
  teleport.x = Math.floor(point.x)
  teleport.y = Math.floor(point.y)
  teleport.z = Math.floor(point.z)
}

/** 打开坐标传送弹窗（先移走焦点，避免 aria-hidden 告警）。 */
function toggleTeleport(): void {
  if (!teleportOpen.value) blurActiveForOverlay()
  teleportOpen.value = !teleportOpen.value
}

/** 打开按键设置弹窗（同上）。 */
function openKeybinds(): void {
  blurActiveForOverlay()
  keybindsOpen.value = true
}

/** 弹窗里的「传送」：传完就收窗（坐标已生效，留着弹窗只会挡住地图） */
function teleportFromDialog(): void {
  teleportTo()
  teleportOpen.value = false
}

/** 编辑期间强制显示目标标注集：新增/取点过程中标注必须肉眼可见 */
function ensureSetVisible(setId: string): void {
  markerVisibility[setId] = true
  viewer.value?.setSetVisible(setId, true)
}

/** 标注的锚点世界坐标（用于「飞到该标注」） */
function markerAnchor(marker: Marker): { x: number, y: number, z: number } {
  if (marker.type === 'poi') return marker.position
  if (marker.type === 'line') return marker.points[0] ?? { x: 0, y: 64, z: 0 }
  if (marker.type === 'box') {
    return {
      x: (marker.min.x + marker.max.x) / 2,
      y: (marker.min.y + marker.max.y) / 2,
      z: (marker.min.z + marker.max.z) / 2,
    }
  }
  if (marker.type === 'shape') {
    const p = marker.shape[0] ?? { x: 0, z: 0 }
    return { x: p.x, y: marker.shapeY, z: p.z }
  }
  const p = marker.shape[0] ?? { x: 0, z: 0 }
  return { x: p.x, y: (marker.shapeMinY + marker.shapeMaxY) / 2, z: p.z }
}

function flyToMarker(marker: Marker): void {
  viewer.value?.flyTo(markerAnchor(marker).x, markerAnchor(marker).y, markerAnchor(marker).z)
}

/**
 * 标注编辑弹窗：与标注管理页同款——名称、标注集（可快捷新建）、
 * 点位 XYZ（含忽略 Y）、范围多边形顶点与贴地高度；线/盒仅改名称与归属。
 */
const editMarkerDialog = reactive({
  open: false,
  originSetId: '',
  markerId: '',
  markerType: 'poi',
  label: '',
  setId: '',
  newSetName: '',
  x: 0,
  y: 64,
  z: 0,
  ignoreY: true,
  verts: [] as Array<{ x: number, z: number }>,
  shapeY: 64,
})

const editDialogIsPoi = computed(() => editMarkerDialog.markerType === 'poi')

function openEditMarkerDialog(set: MarkerSet, marker: Marker): void {
  bindings.releaseAllKeys()
  editMarkerDialog.originSetId = set.id
  editMarkerDialog.markerId = marker.id
  editMarkerDialog.markerType = marker.type
  editMarkerDialog.label = marker.label
  editMarkerDialog.setId = set.id
  editMarkerDialog.newSetName = ''
  if (marker.type === 'poi') {
    editMarkerDialog.x = marker.position.x
    editMarkerDialog.y = marker.position.y
    editMarkerDialog.z = marker.position.z
  }
  else {
    editMarkerDialog.x = 0
    editMarkerDialog.y = 64
    editMarkerDialog.z = 0
  }
  editMarkerDialog.ignoreY = true
  editMarkerDialog.verts = marker.type === 'shape' ? marker.shape.map(p => ({ ...p })) : []
  editMarkerDialog.shapeY = marker.type === 'shape' ? marker.shapeY : 64
  editMarkerDialog.open = true
}

function addEditDialogVertex(): void {
  const last = editMarkerDialog.verts[editMarkerDialog.verts.length - 1]
  editMarkerDialog.verts.push({ x: last?.x ?? 0, z: last?.z ?? 0 })
}

function deleteMarker(set: MarkerSet, marker: Marker): void {
  if (!currentMapId.value || savingMarker.value) return
  if (!window.confirm(`确认删除标注「${marker.label || marker.id}」吗？`)) return
  const copies = markerSets.value.map(s => ({ ...s, markers: s.markers.filter(m => m.id !== marker.id) }))
  const target = copies.find(s => s.id === set.id)
  if (!target) return
  savingMarker.value = true
  void persistMarkers(copies, `已删除「${marker.label || marker.id}」`)
    .catch((e: unknown) => notify('error', e instanceof Error ? e.message : '删除失败'))
    .finally(() => { savingMarker.value = false })
}

async function confirmEditMarkerDialog(): Promise<void> {
  const label = editMarkerDialog.label.trim()
  if (!label) {
    notify('warning', '请填写标注名称')
    return
  }
  if (editMarkerDialog.markerType === 'shape' && editMarkerDialog.verts.length < 3) {
    notify('warning', '多边形至少需要 3 个顶点')
    return
  }
  const v = viewer.value
  if (!v || !currentMapId.value) return
  const copies = markerSets.value.map(s => ({ ...s, markers: [...s.markers] }))
  const origin = copies.find(s => s.id === editMarkerDialog.originSetId)
  const marker = origin?.markers.find(m => m.id === editMarkerDialog.markerId)
  if (!origin || !marker) {
    notify('error', '标注不存在或已被删除')
    return
  }
  const target = resolveTargetSet(copies, editMarkerDialog.setId, editMarkerDialog.newSetName)
  if (!target) return
  marker.label = label
  if (marker.type === 'poi') {
    marker.position.x = editMarkerDialog.x
    marker.position.z = editMarkerDialog.z
    if (!editMarkerDialog.ignoreY) marker.position.y = editMarkerDialog.y
  }
  if (marker.type === 'shape') {
    marker.shape = editMarkerDialog.verts.map(p => ({ x: Math.round(p.x), z: Math.round(p.z) }))
    marker.shapeY = editMarkerDialog.shapeY
  }
  if (target.id !== origin.id) {
    origin.markers = origin.markers.filter(m => m.id !== marker.id)
    target.markers.push(marker)
  }
  editMarkerDialog.open = false
  ensureSetVisible(target.id)
  savingMarker.value = true
  try {
    await persistMarkers(copies, `已修改「${label}」`)
    notify('success', `已修改「${label}」`)
  }
  catch (e) {
    notify('error', e instanceof Error ? e.message : '修改失败')
  }
  finally {
    savingMarker.value = false
  }
}

async function persistMarkers(sets: MarkerSet[], note: string): Promise<void> {
  if (!currentMapId.value) return
  // 后端校验要求请求体是 { sets: [...] } 对象，传裸数组会被判「标注必须是 JSON 对象」
  await adminApi.saveMarkers(currentMapId.value, { sets })
  markerSets.value = sets
  viewer.value?.setMarkerSets(sets)
  markerStatus.value = note
}

/** 点位标注弹窗：预填取点坐标，可改名/微调 XYZ/忽略 Y */
const poiDialog = reactive({
  open: false,
  label: '',
  setId: '__new__',
  newSetName: '',
  x: 0,
  y: 64,
  z: 0,
  ignoreY: false,
})

/** 可选标注集（与图层面板同源）；新增标注不再固定进「管理标注」 */
const markerSetOptions = computed(() => markerSets.value.map(s => ({ label: s.label, value: s.id })))

/** 下拉选项 + 快捷新建项 */
const markerSetOptionsWithNew = computed(() => [
  ...markerSetOptions.value,
  { label: '＋ 新建标注集…', value: '__new__' },
])

function defaultMarkerSetId(): string {
  // 没有任何标注集时默认走快捷新建，不再悄悄落到「管理标注」
  return markerSets.value[0]?.id ?? '__new__'
}

function defaultNewSetName(): string {
  return markerSets.value.length ? '' : '我的标注'
}

/**
 * 解析弹窗选择的标注集：普通 id 直接查；'__new__' 按名称在副本中找/建。
 * @returns 目标集；快捷新建未填名称时返回 null（已 toast）
 */
function resolveTargetSet(sets: MarkerSet[], setId: string, newSetName: string): MarkerSet | null {
  if (setId !== '__new__') {
    return sets.find(s => s.id === setId) ?? null
  }
  const label = newSetName.trim()
  if (!label) {
    notify('warning', '请填写新标注集名称')
    return null
  }
  let created = sets.find(s => s.label === label)
  if (!created) {
    created = { id: `set-${Date.now()}`, label, toggleable: true, defaultHidden: false, sorting: 100, markers: [] }
    sets.push(created)
  }
  return created
}

/** 俯视视角下的点位取点态：点击方块确认坐标（高亮），其余视角直接取当前视线落点 */
const poiPick = ref(false)

function addPoiMarker(): void {
  const v = viewer.value
  if (!v || !currentMapId.value || savingMarker.value) return
  // 新增的标注要立刻可见：目标集若被收起则强制显示
  ensureSetVisible('admin-annotations')
  if (map2dActive.value || mode.value === 'tiltOrbit') {
    // 点位与范围互斥：开始点位标注前先收掉范围取点
    cancelRangePickSilently()
    poiPick.value = true
    notify('info', map2dActive.value
      ? '点位标注：在 2D 地图上点击位置（Enter 确定 / Esc 取消）'
      : '点位标注：点击一个方块确认坐标（Esc 取消）')
    return
  }
  const pos = v.getCameraFocusPoint() ?? v.getCameraWorldPosition()
  openPoiDialog(pos.x, pos.y, pos.z, false)
}

function openPoiDialog(x: number, y: number, z: number, ignoreY: boolean): void {
  bindings.releaseAllKeys()
  poiDialog.label = ''
  poiDialog.setId = defaultMarkerSetId()
  poiDialog.newSetName = defaultNewSetName()
  poiDialog.x = x
  poiDialog.y = y
  poiDialog.z = z
  poiDialog.ignoreY = ignoreY
  poiDialog.open = true
}

function cancelPoiPick(): void {
  poiPick.value = false
  viewer.value?.clearPickHighlights()
  notify('info', '已取消点位标注')
}

async function confirmPoiDialog(): Promise<void> {
  const label = poiDialog.label.trim()
  if (!label) {
    notify('warning', '请填写标注名称')
    return
  }
  const v = viewer.value
  if (!v || !currentMapId.value) return
  poiDialog.open = false
  v.clearPickHighlights()
  savingMarker.value = true
  try {
    const sets = markerSets.value.map(s => ({ ...s, markers: [...s.markers] }))
    const target = resolveTargetSet(sets, poiDialog.setId, poiDialog.newSetName)
    if (!target) return
    target.markers.push({
      id: `poi-${Date.now()}`,
      type: 'poi',
      label,
      position: {
        x: poiDialog.x,
        y: poiDialog.ignoreY ? 64 : poiDialog.y,
        z: poiDialog.z,
      },
      minDistance: 0,
      maxDistance: Number.MAX_SAFE_INTEGER,
      style: { fillColor: '#e67e22', icon: '★', depthTest: false },
    })
    ensureSetVisible(target.id)
    await persistMarkers(sets, `已添加「${label}」`)
    notify('success', `已添加点位标注「${label}」`)
  }
  catch (e) {
    notify('error', e instanceof Error ? e.message : '添加标注失败')
  }
  finally {
    savingMarker.value = false
  }
}

/**
 * 范围标注：仅在俯视视角（俯视倾斜或 2D 地图）下、以「点击方块」的方式记录多边形各角。
 * 每次有效点击追加一个顶点；点满 3 个后可再点工具栏按钮（或再次点击「完成」）结束，
 * 弹窗内可继续增删顶点、改名后落盘。拖拽（平移/旋转）与取点共用鼠标：
 * 按下/抬起位移小于 5px 才算点击。
 */
const rangePick = ref(false)
/** 已记录的多边形顶点（世界 XZ，取点顺序即多边形顺序） */
const rangePoints = ref<Array<{ x: number, z: number }>>([])
let pickDownAt: { x: number, y: number } | null = null

/** 范围标注弹窗：名称 + 顶点列表（可增删、可改 X/Z） */
const rangeDialog = reactive({
  open: false,
  label: '',
  setId: '__new__',
  newSetName: '',
  verts: [] as Array<{ x: number, z: number }>,
})

function toggleRangePick(): void {
  if (!isAdmin) return
  if (rangePick.value) {
    // 俯视视角下再点一次 = 结束取点（其他视角有独立「完成」按钮）
    if (mode.value === 'tiltOrbit') finishRangePick()
    return
  }
  if (!viewer.value || !currentMapId.value) return
  // 范围与点位互斥：开始范围标注前先收掉点位取点
  if (poiPick.value) {
    poiPick.value = false
  }
  rangePick.value = true
  rangePoints.value = []
  viewer.value?.clearPickHighlights()
  ensureSetVisible('admin-annotations')
  if (map2dActive.value) {
    notify('info', '范围标注：在 2D 地图上依次点击记录各角（至少 3 点）')
    return
  }
  if (aimNeeded()) startAimLoop()
  notify('info', '范围标注：依次点击记录多边形各角（至少 3 点），点「完成」结束（Esc 取消）')
}

/** 静默收掉范围取点（点位/范围互斥切换时用，不弹提示）。 */
function cancelRangePickSilently(): void {
  if (!rangePick.value) return
  rangePick.value = false
  rangePoints.value = []
  viewer.value?.clearPickHighlights()
}

/** 点位标注快捷键：2D 下按一次进入、再按一次退出；三维下沿用原行为。 */
function togglePoiPickAction(): void {
  if (!isAdmin) return
  if (map2dActive.value && poiPick.value) {
    cancelPoiPick()
    return
  }
  addPoiMarker()
}

/** 非俯视视角：把当前视线落点（整数、保留真实 Y 语义）记为一个多边形角 */
function addRangeVertexAtFocus(): void {
  const v = viewer.value
  if (!v || !rangePick.value) return
  const point = v.getCameraFocusPoint()
  if (!point) {
    notify('warning', '视线没有落在地形上，请对准地面再加角')
    return
  }
  rangePoints.value.push({ x: point.x, z: point.z })
  v.addPickHighlight(point.x, point.y, point.z)
  notify('info', `已记录第 ${rangePoints.value.length} 个角（X ${Math.floor(point.x)}、`
    + `Z ${Math.floor(point.z)} 方块）`)
}

/** 撤回上一个多边形角（含其方块高亮） */
function undoRangeVertex(): void {
  const v = viewer.value
  if (!v || !rangePick.value || rangePoints.value.length === 0) return
  rangePoints.value.pop()
  v.undoLastPickHighlight()
  notify('info', `已撤回上一角，剩余 ${rangePoints.value.length} 个`)
}

function finishRangePick(): void {
  if (rangePoints.value.length < 3) {
    notify('warning', `多边形至少需要 3 个顶点，当前 ${rangePoints.value.length} 个`)
    return
  }
  rangePick.value = false
  stopAimLoop()
  bindings.releaseAllKeys()
  rangeDialog.label = ''
  rangeDialog.setId = defaultMarkerSetId()
  rangeDialog.newSetName = defaultNewSetName()
  rangeDialog.verts = rangePoints.value.map(p => ({ ...p }))
  rangeDialog.open = true
  viewer.value?.clearPickHighlights()
}

function addRangeVertex(): void {
  const last = rangeDialog.verts[rangeDialog.verts.length - 1]
  rangeDialog.verts.push({ x: last?.x ?? 0, z: last?.z ?? 0 })
}

function confirmRangeDialog(): void {
  const label = rangeDialog.label.trim()
  if (!label) {
    notify('warning', '请填写范围名称')
    return
  }
  if (rangeDialog.verts.length < 3) {
    notify('warning', '多边形至少需要 3 个顶点')
    return
  }
  const v = viewer.value
  if (!v || !currentMapId.value) return
  const verts = rangeDialog.verts.map(p => ({ x: Math.round(p.x), z: Math.round(p.z) }))
  const cx = verts.reduce((sum, p) => sum + p.x, 0) / verts.length
  const cz = verts.reduce((sum, p) => sum + p.z, 0) / verts.length
  rangeDialog.open = false
  v.clearPickHighlights()
  savingMarker.value = true
  void (async () => {
    try {
      const shapeY = v.surfaceYAtWorld(cx, cz) ?? 64
      const sets = markerSets.value.map(s => ({ ...s, markers: [...s.markers] }))
      const target = resolveTargetSet(sets, rangeDialog.setId, rangeDialog.newSetName)
      if (!target) return
      target.markers.push({
        id: `range-${Date.now()}`,
        type: 'shape',
        label,
        shape: verts,
        holes: [],
        shapeY,
        minDistance: 0,
        maxDistance: Number.MAX_SAFE_INTEGER,
        style: { fillColor: '#2f6fd0', lineColor: '#2f6fd0', opacity: 0.3 },
      })
      ensureSetVisible(target.id)
      await persistMarkers(sets, `已添加范围「${label}」`)
      notify('success', `已添加范围标注「${label}」`)
    }
    catch (e) {
      notify('error', e instanceof Error ? e.message : '添加范围标注失败')
    }
    finally {
      savingMarker.value = false
    }
  })()
}

function onCanvasMouseDown(e: MouseEvent): void {
  pickDownAt = { x: e.clientX, y: e.clientY }
}

function onCanvasMouseUp(e: MouseEvent): void {
  const down = pickDownAt
  pickDownAt = null
  if (!down || e.button !== 0) return
  // 位移超过阈值视为拖拽（平移/旋转），不算取点
  if (Math.hypot(e.clientX - down.x, e.clientY - down.y) > 5) return
  const v = viewer.value
  if (!v) return
  if (poiPick.value) {
    // 俯视点位取点：点击方块确认坐标并高亮
    const point = v.pickTerrainAtClient(e.clientX, e.clientY)
    if (!point) {
      notify('warning', '没有点中地形方块，请点在地块上')
      return
    }
    poiPick.value = false
    v.addPickHighlight(point.x, point.y, point.z)
    // 俯视默认无视 Y：对话框按 Y=64 落盘；取消勾选则用所点方块的地表高度
    openPoiDialog(point.x, point.y, point.z, true)
    return
  }
  if (rangePick.value && mode.value === 'tiltOrbit') {
    const point = v.pickTerrainAtClient(e.clientX, e.clientY)
    if (!point) {
      notify('warning', '没有点中地形方块，请点在地块上')
      return
    }
    // 追加多边形顶点（取点顺序即多边形顺序），并高亮所选方块
    rangePoints.value.push({ x: point.x, z: point.z })
    v.addPickHighlight(point.x, point.y, point.z)
    notify('info', `已记录第 ${rangePoints.value.length} 个角（${point.x}, ${point.z}）`)
  }
}

/**
 * 取消取点。
 *
 * <p>Esc 与「退出全屏」在浏览器里是同一个键：全屏下按 Esc 会被浏览器先用来退出全屏，
 * 取点状态却还留着，两个语义互相打架。这里按状态分派——**全屏时 Esc 归全屏**（取点改用
 * 可自定义的「取消取点」键，默认 Backspace），非全屏时 Esc 照旧取消取点。</p>
 */
/**
 * 「取消取点」按键处理。
 *
 * <p>只认可自定义的 {@code annotateCancel}（默认 Backspace），**不再接受 Esc**：
 * Esc 归浏览器（退出全屏）与弹窗组件（关闭对话框），把它再分给取点会让同一个键
 * 在三种语义间打架——这正是此前取消行为不可预期的原因。</p>
 */
function onAnnotateCancelKey(e: KeyboardEvent): void {
  const cancelKey = bindings.keymap.annotateCancel ?? 'Escape'
  if (!isAdmin || e.code !== cancelKey) return
  if (rangePick.value) {
    rangePick.value = false
    rangePoints.value = []
    viewer.value?.clearPickHighlights()
    notify('info', '已取消范围标注')
    return
  }
  if (poiPick.value) cancelPoiPick()
}

/** 标注管理页（新窗口）：量化编辑全部标注坐标 */
function openMarkersPage(): void {
  if (!currentMapId.value) return
  window.open(`/platform/plugins/voxelith-map/markers?id=${encodeURIComponent(currentMapId.value)}`, '_blank')
}



/** 打开序号：并发/重复触发时只让最后一次真正建 viewer（见 openMap 内的守卫）。 */
let openSeq = 0

/**
 * 载入同一世界的维度列表（切换维度用）。
 *
 * <p>失败不影响看图：这条请求只为多给一组切换按钮，拿不到就只是没有切换器——但**选择器必须清空**
 * （否则切到另一张没有兄弟维度的图时，会留着上一张图的按钮，点下去等于跳到别的世界）。</p>
 */
async function loadDimensions(id: string, seq: number): Promise<void> {
  try {
    const res = await api.dimensions(id)
    if (seq !== openSeq) return
    dimensionChoices.value = buildDimensionChoices(res.maps ?? [], id)
  }
  catch {
    if (seq === openSeq) dimensionChoices.value = []
  }
}

/**
 * 切换维度：切到同一世界另一维度的地图，并把 URL 的 map 参数同步过去。
 *
 * <p>同步 URL 是为了分享与刷新后仍停在同一维度上；只改 query 不换路由，不会重建本页。</p>
 */
function switchDimension(id: string): void {
  if (!id || id === currentMapId.value) return
  const query = { ...(props.route?.query ?? {}), map: id }
  void router.replace({ query }).catch(() => {})
  currentMapId.value = id
}

async function openMap(id: string): Promise<void> {
  if (!container.value) return
  const seq = ++openSeq
  error.value = ''
  loading.value = true
  viewer.value?.dispose()
  viewer.value = null
  // 维度列表与地图本体并行取：它只影响切换按钮，慢一点甚至失败都不该拖住看图
  void loadDimensions(id, seq)
  try {
    const baseUrl = api.mapBaseUrl(id)
    const { VoxelithViewer, loadManifest, supportsDisplayP3 } = await loadViewerStack()
    const manifest = await loadManifest(baseUrl)
    // onMounted 里 currentMapId 赋值会触发 watch 再调一次 openMap：两次调用在此汇合，
    // 只有最后一次能往下走。否则两个 MapEngine 挂同一 canvas 共享一个 WebGL 上下文，
    // 各自的 _currentProgram 状态互踩 → 每帧刷 uniformMatrix4fv GL 错误且前者泄漏。
    if (seq !== openSeq) return
    p3Supported.value = supportsDisplayP3()
    boundsMin.value = Math.floor(manifest.boundsMin[1])
    boundsMax.value = Math.ceil(manifest.boundsMax[1]) + 1
    ySliceMin.value = boundsMin.value
    ySliceMax.value = boundsMax.value

    const v = new VoxelithViewer(container.value, {
      mapBaseUrl: baseUrl,
      manifest,
      mode: mode.value,
      displayP3: displayP3.value,
      skyline: skyline.value,
    })
    viewer.value = v
    // 换图/切维度后保持打开中的视图：俯视倾斜模式恢复正俯视机位；2D 地图按新图清单重新载入
    if (mode.value === 'tiltOrbit') v.setTopDown(true)
    if (map2dActive.value) {
      v.setPaused(true)
      if (!openAtlasImage(v)) {
        map2dActive.value = false
        map2d.reset()
        v.setPaused(false)
      }
    }
    // 内置初始机位：默认视角异步加载成功后会覆盖这个值
    initialView.value = v.getCameraState()
    if (ySliceEnabled.value) v.setYSlice(ySliceMin.value, ySliceMax.value)
    v.setLayerFilter(layerFilter.value)
    if (!autoDistance.value) v.setDetailDistanceChunks(detailChunks.value)
    void applyDefaultView(id, v)
    await loadMarkers(id, v)
    if (seq !== openSeq) return
    loading.value = false
  }
  catch (e) {
    if (seq !== openSeq) return
    loading.value = false
    error.value = e instanceof Error ? e.message : '地图加载失败'
  }
}

function retry(): void {
  if (currentMapId.value) void openMap(currentMapId.value)
}

function toggleLayer(id: string, visible: boolean): void {
  markerVisibility[id] = visible
  viewer.value?.setSetVisible(id, visible)
}

function resetView(): void {
  const v = viewer.value
  if (!v) return
  // 回到初始视角（保存过默认视角即该机位），没有才退回整图俯瞰
  if (initialView.value) v.applyCameraState(initialView.value)
  else v.frameAll()
}

// HUD 轮询
let hudTimer = 0
function startHud(): void {
  hudTimer = window.setInterval(() => {
    const v = viewer.value
    if (!v) return
    cameraPos.value = v.getCameraWorldPosition()
    loaded.value = v.getLoadedCount()
    failed.value = v.getFailedCount()
    distance.value = v.getCurrentDistance()
  }, 150)
}

watch(currentMapId, (id) => {
  if (id) void openMap(id)
})

// 站点页（/map）与平台页（/platform/…）共用本组件，路由切换会复用实例：两种形态的页眉页脚
// 策略不同（站点页避让、平台页归零），所以切换后必须重量一次偏移
watch(topMenuLayout, () => {
  syncHostChromeClass()
  void nextTick(watchChrome)
})

// 打开按键设置弹窗即打断当前按键状态：先释放，防止松键事件丢失导致持续移动
watch(keybindsOpen, (open) => {
  if (open) bindings.releaseAllKeys()
})

watch(ySliceEnabled, () => {
  if (!viewer.value) return
  if (ySliceEnabled.value) viewer.value.setYSlice(ySliceMin.value, ySliceMax.value)
  else viewer.value.clearYSlice()
})
watch([ySliceMin, ySliceMax], () => {
  if (ySliceEnabled.value && viewer.value) viewer.value.setYSlice(ySliceMin.value, ySliceMax.value)
})
watch(layerFilter, (v) => viewer.value?.setLayerFilter(v))
watch([autoDistance, detailChunks], () => {
  const v = viewer.value
  if (!v) return
  if (autoDistance.value) v.setAutoDistance(true)
  else {
    v.setAutoDistance(false)
    v.setDetailDistanceChunks(detailChunks.value)
  }
})
watch(displayP3, (v) => viewer.value?.setDisplayP3(v))
watch(skyline, (v) => viewer.value?.setSkyline(v))

onMounted(async () => {
  // 取点取消键要在捕获阶段先于各控制器处理，避免落在输入控件时被吞掉
  window.addEventListener('keydown', onAnnotateCancelKey, true)
  window.addEventListener('keydown', on2dPanKeyDown)
  window.addEventListener('keyup', on2dPanKeyUp)
  // 页面缩放会改变 devicePixelRatio（也就改变了 2D 底图的对齐情况）：跟着刷新提示
  window.addEventListener('resize', refreshSubPixelCaveat)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  // 主题挂载可能晚于本组件：下一帧先量一次页签高度，再用 ResizeObserver 跟随变化
  void nextTick(watchChrome)
  bindings.onAction('toggleUi', () => toggleChrome())
  bindings.onAction('toggleImmersion', () => toggleImmersive())
  bindings.onAction('cycleMode', () => cycleMode())
  bindings.onAction('resetView', () => resetView())
  bindings.onAction('markers', () => toggleMarkers())
  bindings.onAction('fullscreen', () => void toggleFullscreen())
  // Enter 确定：弹窗打开时提交，否则在范围取点满 3 角时完成（标注为管理能力，普通用户不响应）
  bindings.onAction('annotateConfirm', () => {
    if (!isAdmin) return
    if (poiDialog.open) {
      void confirmPoiDialog()
      return
    }
    if (rangeDialog.open) {
      void confirmRangeDialog()
      return
    }
    if (rangePick.value && rangePoints.value.length >= 3) {
      finishRangePick()
    }
  })
  // Backspace 撤回上一个已记录的标注点：取点时误触只丢一个角，不必整段重来。
  // 弹窗打开时让位给弹窗自己的删行按钮，避免「撤回」落到对话框内部状态上。
  bindings.onAction('annotateUndo', () => {
    if (!isAdmin) return
    if (poiDialog.open || rangeDialog.open) return
    if (rangePick.value && rangePoints.value.length > 0) undoRangeVertex()
  })
  bindings.onAction('toggleMap2d', () => toggleAtlas2D())
  bindings.onAction('togglePoiPick', () => togglePoiPickAction())
  bindings.onAction('toggleRangePick', () => toggleRangePick())
  bindings.onAction('toggleTeleport', () => toggleTeleport())
  syncHostChromeClass()
  // 首次进入提示按键（界面默认隐藏，用户需要知道怎么呼出）
  flashHint()
  try {
    const res = await api.maps()
    maps.value = (res.maps ?? []).filter(m => m.ready)
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '地图列表加载失败'
  }
  const q = props.route?.query?.map
  const queryId = typeof q === 'string' && q ? q : ''
  const initial = (queryId && maps.value.some(m => m.id === queryId))
    ? queryId
    : (maps.value[0]?.id ?? '')
  if (initial) {
    // 赋值触发 watch → openMap：只有这一条打开路径，别再显式调一次（并发双开是
    // WebGL uniformMatrix4fv 刷屏的根因）
    currentMapId.value = initial
  }
  else if (maps.value.length === 0 && !error.value) {
    error.value = '当前没有已就绪的地图'
  }
  startHud()
})

onBeforeUnmount(() => {
  chromeObserver?.disconnect()
  chromeObserver = null
  // 沉浸模式 / 平台查看页给 html 挂过类：离开本页必须摘掉，否则宿主导航会一直保持隐藏
  document.documentElement.classList.remove('voxelith-immersive')
  document.documentElement.classList.remove('voxelith-fullbleed')
  window.removeEventListener('keydown', onAnnotateCancelKey, true)
  window.removeEventListener('keydown', on2dPanKeyDown)
  window.removeEventListener('keyup', on2dPanKeyUp)
  window.removeEventListener('resize', refreshSubPixelCaveat)
  if (hoverRaf !== 0) {
    cancelAnimationFrame(hoverRaf)
    hoverRaf = 0
  }
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  stopAimLoop()
  window.clearInterval(hudTimer)
  if (hintTimer) window.clearTimeout(hintTimer)
  viewer.value?.dispose()
  viewer.value = null
})
</script>

<template>
  <div
    ref="viewerRoot"
    class="voxelith-viewer"
    :class="{
      'voxelith-viewer--topmenu': topMenuLayout,
      'voxelith-viewer--immersive': immersive,
    }"
  >
    <canvas
      ref="container"
      class="voxelith-canvas"
      :class="{ 'voxelith-picking': rangePick || poiPick }"
      @mousedown="onCanvasMouseDown"
      @mouseup="onCanvasMouseUp"
    />

    <!-- 真正的 2D 地图：渲染任务烘焙的 LOD 航拍图集页，纯图片展示（无 WebGL）。
         盖在三维画布上层，z-index 低于侧栏与窗口区；清单里没有图集页的地图走三维
         正俯视机位（此时不渲染本层），按钮行为永不落空。 -->
    <div v-if="map2dActive && map2d.resolvedUrl.value" class="voxelith-map2d">
      <div
        :ref="map2d.viewportEl"
        class="voxelith-map2d-viewport"
        :class="{
          'voxelith-map2d-panning': map2dDrag.active && !map2dDrag.moved,
          'voxelith-map2d-crosshair': is2dPicking,
        }"
        @mousedown="on2dMouseDown"
        @mousemove="on2dMouseMove"
        @mouseup="on2dMouseUp"
        @mouseleave="on2dMouseLeave"
        @wheel.prevent="on2dWheel"
      >
        <!-- 原生 img 展示：解码与渲染由浏览器完成；平移/缩放作用在 transform 上 -->
        <img
          v-if="map2d.resolvedUrl.value"
          :src="map2d.resolvedUrl.value"
          :style="map2d.imgStyle.value"
          class="voxelith-map2d-img"
          draggable="false"
          alt=""
          @click="on2dClick"
          @load="map2d.onImgLoad"
          @error="map2d.onImgError"
        />
        <!-- 已选方块框选：每个已取点所在格整格高亮（当前格更醒目） -->
        <div
          v-for="sq in selectedSquares"
          :key="'cell' + sq.key"
          class="voxelith-map2d-cell voxelith-map2d-cell-selected"
          :class="{ 'voxelith-map2d-cell-current': sq.current }"
          :style="{
            left: sq.left + 'px',
            top: sq.top + 'px',
            width: sq.width + 'px',
            height: sq.height + 'px',
          }"
        />
        <!-- 预选方块框选：光标所在格 -->
        <div
          v-if="hoverSquare"
          class="voxelith-map2d-cell voxelith-map2d-cell-hover"
          :style="{
            left: hoverSquare.left + 'px',
            top: hoverSquare.top + 'px',
            width: hoverSquare.width + 'px',
            height: hoverSquare.height + 'px',
          }"
        />
        <!-- 范围多边形：已选角连线 + 闭合提示虚线 + 跟随光标的橡皮筋虚线 -->
        <svg v-if="rangePick" class="voxelith-map2d-svg">
          <polyline
            v-if="rangeSvgPoints.length > 1"
            :points="rangeSvgLine"
            class="voxelith-map2d-range-line"
          />
          <line
            v-if="rangeCloseLine"
            :x1="rangeCloseLine.x1"
            :y1="rangeCloseLine.y1"
            :x2="rangeCloseLine.x2"
            :y2="rangeCloseLine.y2"
            class="voxelith-map2d-range-close"
          />
          <line
            v-if="rangeRubberLine"
            :x1="rangeRubberLine.x1"
            :y1="rangeRubberLine.y1"
            :x2="rangeRubberLine.x2"
            :y2="rangeRubberLine.y2"
            class="voxelith-map2d-range-rubber"
          />
        </svg>
        <!-- 已选角：编号徽标，最后选中的一个高亮（当前点） -->
        <div
          v-for="(p, i) in (rangePick ? rangeSvgPoints : [])"
          :key="'v' + i"
          class="voxelith-map2d-vertex"
          :class="{ 'voxelith-map2d-vertex-current': i === rangeSvgPoints.length - 1 }"
          :style="{ left: p.x + 'px', top: p.y + 'px' }"
        >{{ i + 1 }}</div>
        <div
          v-for="dot in markerDots"
          :key="dot.key"
          class="voxelith-map2d-marker"
          :style="{ left: dot.left + 'px', top: dot.top + 'px' }"
          :title="dot.label"
        >
          <YdIcon name="i-mdi:map-marker" />
        </div>
        <!-- 取点实时读数：贴在光标附近，显示这一格的世界坐标 -->
        <div
          v-if="is2dPicking && hover2d"
          class="voxelith-map2d-readout"
          :style="{ left: hover2d.sx + 'px', top: hover2d.sy + 'px' }"
        >X {{ hover2d.x }} · Z {{ hover2d.z }} 方块</div>
      </div>
      <div v-if="map2d.status.value === 'loading'" class="voxelith-map2d-status">航拍图加载中…</div>
      <div v-else-if="map2d.status.value === 'error'" class="voxelith-map2d-status">
        航拍图加载失败：请确认渲染产物包含航拍图集（重新渲染可生成），或稍后重试
      </div>
      <!-- 取点状态面板：当前模式与已选点数一目了然（标注时最需要的反馈） -->
      <div v-if="is2dPicking" class="voxelith-map2d-picking">
        <span class="voxelith-map2d-picking-dot" />
        <template v-if="rangePick">
          范围标注进行中 · 已选 <b>{{ rangePoints.length }}</b> 个角<template
            v-if="rangePoints.length < 3"
          >（至少 3 个）</template><template v-else>（可点「完成」结束）</template>
        </template>
        <template v-else>点位标注进行中 · 点击地图确定位置</template>
        <span
          v-if="rangePick && rangePoints.length > 0"
          class="voxelith-map2d-picking-key"
        >{{ describeKey(bindings.keymap.annotateUndo ?? 'Backspace') }} 撤回</span>
        <span
          v-if="rangePick && rangePoints.length >= 3"
          class="voxelith-map2d-picking-key"
        >{{ describeKey(bindings.keymap.annotateConfirm ?? 'Enter') }} 完成</span>
        <span class="voxelith-map2d-picking-key">{{ describeKey(bindings.keymap.annotateCancel ?? 'Escape') }} 取消</span>
      </div>
      <div class="voxelith-map2d-hud">
        2D 地图 · 滚轮缩放 / 拖拽平移 / WASD 或方向键移动<span
          v-if="map2d.imageSize.width"
        >· 原图 {{ map2d.imageSize.width }}×{{ map2d.imageSize.height }}px</span>
        <span v-if="subPixelCaveat" class="voxelith-map2d-hud-note">
          当前浏览器缩放比不是 25% 的整数倍（如 110%/90%），底图可能有个别像素的对齐误差；
          坐标与取点始终以实际方块为准，不受影响
        </span>
      </div>
    </div>



    <!-- 主题菜单占位：--voxelith-top-offset 预留宿主顶部菜单高度，避免控件与主题导航重叠 -->
    <div v-show="chromeVisible" class="voxelith-menu-toggle-row">
      <YdAction
        type="button"
        class="voxelith-menu-toggle"
        :title="menuVisible ? '收起菜单' : '展开菜单'"
        @click="menuVisible = !menuVisible"
      >
        <YdIcon :name="menuVisible ? 'i-mdi:menu-open' : 'i-mdi:menu'" />
      </YdAction>
    </div>

    <!-- 左侧纵向功能条 -->
    <div v-show="chromeVisible && menuVisible" class="voxelith-sidebar">

      <!-- 平台查看页占满视口（宿主页签条已隐藏）：功能条左端留一个页内返回口 -->
      <YdButton
        v-if="topMenuLayout"
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        title="返回上一页（无历史记录时回地图管理）"
        @click="leaveViewer"
      >
        <YdIcon name="i-mdi:arrow-left" />
        <span class="voxelith-side-label">返回</span>
      </YdButton>

      <YdButton
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        :class="{ 'voxelith-side-active': mapSwitchVisible }"
        :title="mapSwitchVisible ? '隐藏地图/维度切换' : '显示地图/维度切换'"
        @click="mapSwitchVisible = !mapSwitchVisible"
      >
        <YdIcon name="i-mdi:map" />
        <span class="voxelith-side-label">地图</span>
      </YdButton>

      <YdButton
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        :class="{ 'voxelith-side-active': map2dActive }"
        :title="map2dActive ? '关闭 2D 地图' : '2D 地图（渲染航拍图，纯图片：拖拽平移 / 滚轮缩放）'"
        @click="toggleAtlas2D"
      >
        <YdIcon name="i-mdi:image-outline" />
        <span class="voxelith-side-label">二维</span>
      </YdButton>
      <YdButton
        v-if="!sidebarMinimal"
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        :class="{ 'voxelith-side-active': teleportOpen }"
        title="坐标传送"
        @click="toggleTeleport"
      >
        <YdIcon name="i-mdi:crosshairs-gps" />
        <span class="voxelith-side-label">传送</span>
      </YdButton>
      <YdButton
        v-if="!sidebarMinimal"
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        title="回到初始视角"
        @click="resetView"
      >
        <YdIcon name="i-mdi:home-map-marker" />
        <span class="voxelith-side-label">复位</span>
      </YdButton>

      <div v-if="!sidebarMinimal" class="voxelith-side-divider" />

      <!-- 视角模式（可折叠）：自由飞行 / 第一人称 / 俯视倾斜 -->
      <YdAction
        v-if="!sidebarMinimal"
        type="button"
        class="voxelith-side-group"
        :class="{ 'voxelith-side-group-active': showGroupDot.view }"
        title="视角模式"
        @click="toggleSideGroup('view')"
      >
        <YdIcon name="i-mdi:rotate-3d-variant" class="voxelith-side-group-icon" />
        <span class="voxelith-side-label">视角</span>
        <YdIcon :name="expandedGroups.view ? 'i-mdi:chevron-up' : 'i-mdi:chevron-down'" class="voxelith-side-caret" />
      </YdAction>
      <template v-if="!sidebarMinimal && expandedGroups.view">
        <YdButton
          v-for="(label, key) in MODE_LABELS"
          :key="key"
          size="sm"
          variant="ghost"
          class="voxelith-side-btn"
          :class="{ 'voxelith-side-active': mode === key }"
          :title="label"
          @click="setMode(key as ViewerMode)"
        >
          <YdIcon :name="MODE_ICONS[key as ViewerMode]" />
          <span class="voxelith-side-label">{{ VIEW_MODE_SHORT[key as ViewerMode] }}</span>
        </YdButton>
      </template>

      <div v-if="isAdmin" class="voxelith-side-divider" />

      <!-- 标注工具（可折叠）：点位标注 / 范围标注 / 标注图层。
           仅管理员可用：普通用户看不到入口，相关快捷键也一并失效（各 handler 里有 isAdmin 守卫） -->
      <YdAction
        v-if="isAdmin"
        type="button"
        class="voxelith-side-group"
        :class="{ 'voxelith-side-group-active': showGroupDot.markers }"
        title="标注工具"
        @click="toggleSideGroup('markers')"
      >
        <YdIcon name="i-mdi:map-marker-plus" class="voxelith-side-group-icon" />
        <span class="voxelith-side-label">标注</span>
        <YdIcon :name="expandedGroups.markers ? 'i-mdi:chevron-up' : 'i-mdi:chevron-down'" class="voxelith-side-caret" />
      </YdAction>
      <template v-if="isAdmin && expandedGroups.markers">
        <YdButton
          v-if="isAdmin"
          size="sm"
          variant="ghost"
          class="voxelith-side-btn"
          :loading="savingMarker"
          title="点位标注：俯视视角点击方块确认点位；其他视角在视线落点处添加"
          @click="addPoiMarker"
        >
          <YdIcon name="i-mdi:map-marker-plus" />
        <span class="voxelith-side-label">点位</span>
        </YdButton>
        <YdButton
          v-if="isAdmin && !rangePick"
          size="sm"
          variant="ghost"
          class="voxelith-side-btn"
          title="范围标注：俯视视角下点击方块记录多边形各角；其他视角用「加角」记录视线落点"
          @click="toggleRangePick"
        >
          <YdIcon name="i-mdi:vector-square" />
        <span class="voxelith-side-label">范围</span>
        </YdButton>
        <template v-if="isAdmin && rangePick">
          <!-- 「加角」只对三维视角有意义：2D 地图是直接点图取点，此按钮隐藏 -->
          <YdButton
            v-if="!map2dActive"
            size="sm"
            variant="ghost"
            class="voxelith-side-btn voxelith-range-active"
            :title="mode !== 'tiltOrbit' ? '把当前视线落点记为多边形一角' : '完成取点并编辑多边形'"
            @click="mode !== 'tiltOrbit' ? addRangeVertexAtFocus() : finishRangePick()"
          >
            <YdIcon :name="mode !== 'tiltOrbit' ? 'i-mdi:plus' : 'i-mdi:check'" />
            <span class="voxelith-side-label">{{ mode !== 'tiltOrbit' ? '加角' : '完成' }}</span>
          </YdButton>
          <YdButton
            size="sm"
            variant="ghost"
            class="voxelith-side-btn"
            :disabled="rangePoints.length === 0"
            title="撤回上一个新增的角"
            @click="undoRangeVertex"
          >
            <YdIcon name="i-mdi:undo-variant" />
        <span class="voxelith-side-label">撤回</span>
          </YdButton>
          <!-- 完成：2D 与三维非俯视视角都需要（俯视倾斜下由上一个按钮兼任） -->
          <YdButton
            v-if="map2dActive || mode !== 'tiltOrbit'"
            size="sm"
            variant="ghost"
            class="voxelith-side-btn"
            :title="`已记录 ${rangePoints.length} 个角：完成并编辑多边形（至少 3 点）`"
            @click="finishRangePick"
          >
            <YdIcon name="i-mdi:check" />
        <span class="voxelith-side-label">完成</span>
          </YdButton>
        </template>
        <YdButton
          size="sm"
          variant="ghost"
          class="voxelith-side-btn"
          :class="{ 'voxelith-side-active': markersOpen }"
          title="标注图层"
          @click="toggleLayersPanel"
        >
          <YdIcon name="i-mdi:map-marker" />
          <span class="voxelith-side-label">图层</span>
        </YdButton>
      </template>

      <YdButton
        v-if="isAdmin && !sidebarMinimal"
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        :loading="savingView"
        title="把当前位置与朝向保存为该地图的默认进入视角，并截图作为列表封面"
        @click="saveDefaultView"
      >
        <YdIcon name="i-mdi:camera" />
        <span class="voxelith-side-label">存景</span>
      </YdButton>

      <div v-if="!sidebarMinimal" class="voxelith-side-divider" />

      <YdButton
        v-if="!sidebarMinimal"
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        :class="{ 'voxelith-side-active': panelOpen }"
        title="设置"
        @click="toggleSettingsPanel"
      >
        <YdIcon name="i-mdi:tune-variant" />
        <span class="voxelith-side-label">设置</span>
      </YdButton>
      <YdButton
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        :class="{ 'voxelith-side-active': fullscreen }"
        :title="fullscreen ? '退出全屏（Esc）' : '全屏展示'"
        @click="toggleFullscreen"
      >
        <YdIcon :name="fullscreen ? 'i-mdi:fullscreen-exit' : 'i-mdi:fullscreen'" />
        <span class="voxelith-side-label">{{ fullscreen ? '退出' : '全屏' }}</span>
      </YdButton>
      <YdButton
        v-if="topMenuLayout"
        size="sm"
        variant="ghost"
        class="voxelith-side-btn"
        :title="immersive ? '退出沉浸模式（恢复全部界面）' : '隐藏全部界面（沉浸模式，仅保留地图）'"
        @click="toggleImmersive"
      >
        <YdIcon :name="immersive ? 'i-mdi:eye-outline' : 'i-mdi:eye-off-outline'" />
        <span class="voxelith-side-label">{{ immersive ? '显示' : '隐藏' }}</span>
      </YdButton>
    </div>

    <!-- 菜单右侧窗口区：地图切换 / 设置 / 标注图层 三个窗口纵向占满 -->
    <div v-if="chromeVisible && menuVisible" class="voxelith-menu-panels">
      <div v-if="mapSwitchVisible" class="voxelith-map-switch">
        <YdSelect
          v-if="mapOptions.length > 1"
          v-model="currentMapId"
          :options="mapOptions"
          class="voxelith-map-switch-select"
        />
        <span v-else class="voxelith-map-switch-title">{{ maps[0]?.name || 'Voxelith 地图' }}</span>
      </div>

      <div v-if="panelOpen" class="voxelith-settings">
        <!-- 维度切换（设置面板首位）：同一世界各维度是各自独立的地图，这里直接切过去 -->
        <label class="voxelith-row">
          <span>切换维度</span>
          <YdSelect
            v-if="hasDimensionSwitch"
            v-model="dimensionSelectValue"
            :options="dimensionOptions"
            class="w-full"
          />
          <span v-else class="voxelith-dimension-single">{{ currentDimensionLabel }}</span>
        </label>

        <label class="voxelith-row">
          <span>自适应视距</span>
          <YdSwitch v-model="autoDistance" />
        </label>
        <label v-if="!autoDistance" class="voxelith-row voxelith-slider">
          <span>视距 {{ detailChunks }} 区块</span>
          <YdSlider :model-value="[detailChunks]" :min="8" :max="32" :step="1" :tooltip="false"
                    @update:model-value="v => detailChunks = v?.[0] ?? detailChunks" />
        </label>

        <label class="voxelith-row">
          <span>Y 轴切片</span>
          <YdSwitch v-model="ySliceEnabled" />
        </label>
        <template v-if="ySliceEnabled">
          <label class="voxelith-row voxelith-slider">
            <span>切片下限 {{ ySliceMin }}</span>
            <YdSlider :model-value="[ySliceMin]" :min="boundsMin" :max="boundsMax" :step="1" :tooltip="false" @update:model-value="v => ySliceMin = v?.[0] ?? ySliceMin" />
          </label>
          <label class="voxelith-row voxelith-slider">
            <span>切片上限 {{ ySliceMax }}</span>
            <YdSlider :model-value="[ySliceMax]" :min="boundsMin" :max="boundsMax" :step="1" :tooltip="false" @update:model-value="v => ySliceMax = v?.[0] ?? ySliceMax" />
          </label>
        </template>

        <label v-if="p3Supported" class="voxelith-row">
          <span>Display P3</span>
          <YdSwitch v-model="displayP3" />
        </label>

        <label class="voxelith-row">
          <span>远景天际线（实验）</span>
          <YdSwitch v-model="skyline" />
        </label>


        <!-- 按键自定义：列表较长，收进独立弹窗；设置面板只留入口，避免面板被撑高/挤出横向滚动 -->
        <div class="voxelith-row">
          <span>按键设置</span>
          <YdButton size="sm" variant="outline" @click="openKeybinds">
            自定义
          </YdButton>
        </div>
      </div>

        <div v-if="isAdmin && markersOpen" class="voxelith-layers">
        <h3 class="voxelith-layers-title">标注图层</h3>
        <template v-if="markerSets.length">
          <div v-for="set in markerSets" :key="set.id" class="voxelith-layer-group">
            <label class="voxelith-layer-item">
              <YdCheckbox
                :model-value="markerVisibility[set.id] !== false"
                @update:model-value="value => toggleLayer(set.id, Boolean(value))"
              />
              <span class="voxelith-layer-label">{{ set.label }} <b>{{ set.markers.length }}</b></span>
            </label>
            <div
              v-for="marker in set.markers"
              :key="marker.id"
              class="voxelith-marker-item"
            >
              <YdAction
                type="button"
                class="voxelith-marker-name"
                title="飞到该标注"
                @click="flyToMarker(marker)"
              >{{ marker.label || marker.id }}</YdAction>
              <template v-if="isAdmin">
                <YdAction
                  type="button"
                  class="voxelith-marker-action"
                  title="编辑"
                  @click="openEditMarkerDialog(set, marker)"
                >
                  <YdIcon name="i-mdi:pencil" />
                </YdAction>
                <YdAction
                  type="button"
                  class="voxelith-marker-action"
                  title="删除"
                  @click="deleteMarker(set, marker)"
                >
                  <YdIcon name="i-mdi:delete-outline" />
                </YdAction>
              </template>
            </div>
          </div>
        </template>
        <div v-else class="voxelith-layer-empty">
          这张地图还没有标注
        </div>
        <div v-if="isAdmin" class="voxelith-layer-hint">
          点击名称飞到标注；铅笔可编辑名称、坐标与归属。
        </div>
        <div v-if="markerStatus" class="voxelith-layer-hint">{{ markerStatus }}</div>
        <YdButton
          v-if="isAdmin"
          size="sm"
          variant="outline"
          class="mt-2 w-full"
          @click="openMarkersPage"
        >
          标注管理
        </YdButton>
      </div>

      <!-- HUD 信息：右下角显示，不与主题菜单/左侧功能条争夺空间 -->
    </div>

    <!-- HUD 信息：固定于屏幕右下角（根节点直挂，不受菜单容器定位/显隐影响） -->
    <!-- 屏幕中心准星：非俯视视角取点/点位取点时，指示「当前对准的方块」 -->
    <div v-if="aimNeeded()" class="voxelith-crosshair" aria-hidden="true">
      <span class="voxelith-crosshair-h" />
      <span class="voxelith-crosshair-v" />
    </div>

    <div class="voxelith-hud">
      <div v-if="rangePick || poiPick" class="voxelith-hud-pick">
        {{ rangePick
          ? `范围取点中：已记 ${rangePoints.length} 角（${describeKey(bindings.keymap.annotateUndo ?? 'Backspace')} 撤回 / ${describeKey(bindings.keymap.annotateCancel ?? 'Escape')} 取消）`
          : `点位取点中：点击方块确认坐标（${describeKey(bindings.keymap.annotateCancel ?? 'Escape')} 取消）` }}
        <span v-if="aimPoint" class="voxelith-hud-aim">
          对准方块 {{ aimPoint.x }} / {{ aimPoint.y }} / {{ aimPoint.z }}
        </span>
      </div>
      <div class="voxelith-hud-line">XYZ: {{ cameraPos.x }} / {{ cameraPos.y }} / {{ cameraPos.z }}</div>
      <div class="voxelith-hud-line">
        瓦片 {{ loaded }}<template v-if="failed">（失败 {{ failed }}）</template>
        · 视距 {{ distance }} 区块
      </div>
    </div>

    <!-- 坐标传送：独立弹窗（此前挤在设置面板里，三个数字框把面板撑得很高） -->
    <YdModal v-model="teleportOpen" title="坐标传送">
      <div class="voxelith-teleport-form">
        <div class="voxelith-teleport-grid">
          <label class="voxelith-teleport-cell">
            <span class="voxelith-teleport-label">X</span>
            <YdNumberField
              v-model="teleport.x"
              :step="1"
              hide-button
              class="voxelith-teleport-input"
            />
          </label>
          <label class="voxelith-teleport-cell">
            <span class="voxelith-teleport-label">Y</span>
            <YdNumberField
              v-model="teleport.y"
              :step="1"
              hide-button
              class="voxelith-teleport-input"
            />
          </label>
          <label class="voxelith-teleport-cell">
            <span class="voxelith-teleport-label">Z</span>
            <YdNumberField
              v-model="teleport.z"
              :step="1"
              hide-button
              class="voxelith-teleport-input"
            />
          </label>
        </div>
        <p class="voxelith-teleport-hint">
          二维俯视图按 X / Z 定位，三维视角连 Y 一起用；「取焦点」把当前视线落点填进来。
        </p>
      </div>
      <template #footer>
        <div class="voxelith-teleport-footer">
          <YdButton variant="outline" @click="fillTeleportFromFocus">
            取焦点
          </YdButton>
          <YdButton @click="teleportFromDialog">
            传送
          </YdButton>
        </div>
      </template>
    </YdModal>

    <YdModal v-model="keybindsOpen" title="按键设置">
      <div class="voxelith-keybinds">
        <div class="voxelith-keybinds-header">
          <span>点击键位后按下新键（Esc 取消）；同一键不能绑两个动作</span>
          <YdButton size="sm" variant="outline" @click="bindings.resetBindings()">
            恢复默认
          </YdButton>
        </div>
        <div v-for="a in visibleKeyActions" :key="a.action" class="voxelith-keybind-row">
          <span>{{ a.label }}</span>
          <YdAction
            type="button"
            class="voxelith-keybind-key"
            :class="{ listening: listeningAction === a.action }"
            @click="startBind(a.action, $event)"
          >
            {{ listeningAction === a.action ? '按下新键…' : describeKey(keymap[a.action]) }}
          </YdAction>
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" @click="keybindsOpen = false">
            完成
          </YdButton>
        </div>
      </template>
    </YdModal>

    <!-- 点位标注弹窗：预填视线落点坐标 -->
    <YdModal v-model="poiDialog.open" title="新增点位标注">
      <div class="grid gap-4">
        <label class="grid gap-1.5">
          <span class="text-sm">名称</span>
          <YdInput v-model="poiDialog.label" placeholder="例如：村庄入口" />
        </label>
        <label class="grid gap-1.5">
          <span class="text-sm">标注集</span>
          <YdSelect v-model="poiDialog.setId" :options="markerSetOptionsWithNew" />
        </label>
        <label v-if="poiDialog.setId === '__new__'" class="grid gap-1.5">
          <span class="text-sm">新标注集名称</span>
          <YdInput v-model="poiDialog.newSetName" placeholder="例如：建筑组" />
        </label>
        <!-- 忽略 Y 轴时只有 X/Z 两列。YdNumberField 内置 w-[200px]，且网格子项默认 min-width:auto
             会沿 min-content 链拒绝收缩，压到相邻列并撑出弹窗横向滚动条：逐层 min-w-0 断开 -->
        <div class="grid gap-1">
          <span class="text-xs text-muted-foreground">坐标为方块单位（1 格 = 1 方块）</span>
        </div>
        <div class="grid gap-3" :class="poiDialog.ignoreY ? 'grid-cols-2' : 'grid-cols-3'">
          <label class="grid min-w-0 gap-1.5">
            <span class="text-sm">X</span>
            <YdNumberField v-model="poiDialog.x" :step="1" class="w-full min-w-0" />
          </label>
          <label v-if="!poiDialog.ignoreY" class="grid min-w-0 gap-1.5">
            <span class="text-sm">Y</span>
            <YdNumberField v-model="poiDialog.y" :step="1" class="w-full min-w-0" />
          </label>
          <label class="grid min-w-0 gap-1.5">
            <span class="text-sm">Z</span>
            <YdNumberField v-model="poiDialog.z" :step="1" class="w-full min-w-0" />
          </label>
        </div>
        <label class="flex items-center gap-2 text-sm">
          <YdCheckbox v-model="poiDialog.ignoreY" />
          忽略 Y 轴（按 Y = 64 落盘）
        </label>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" @click="poiDialog.open = false; viewer?.clearPickHighlights()">
            取消
          </YdButton>
          <YdButton @click="confirmPoiDialog">
            确定
          </YdButton>
        </div>
      </template>
    </YdModal>

    <!-- 范围标注弹窗：多边形各角坐标可增删改 -->
    <YdModal v-model="rangeDialog.open" title="范围标注">
      <div class="grid gap-4">
        <label class="grid gap-1.5">
          <span class="text-sm">名称</span>
          <YdInput v-model="rangeDialog.label" placeholder="例如：村庄区域" />
        </label>
        <label class="grid gap-1.5">
          <span class="text-sm">标注集</span>
          <YdSelect v-model="rangeDialog.setId" :options="markerSetOptionsWithNew" />
        </label>
        <label v-if="rangeDialog.setId === '__new__'" class="grid gap-1.5">
          <span class="text-sm">新标注集名称</span>
          <YdInput v-model="rangeDialog.newSetName" placeholder="例如：建筑组" />
        </label>
        <div class="grid gap-2">
          <span class="text-sm">
            多边形顶点（{{ rangeDialog.verts.length }} 个，X / Z 为方块单位）
          </span>
          <div
            v-for="(vert, index) in rangeDialog.verts"
            :key="index"
            class="flex items-center gap-2"
          >
            <span class="w-8 shrink-0 text-xs text-muted-foreground">#{{ index + 1 }}</span>
            <YdNumberField v-model="vert.x" :step="1" class="w-full min-w-0" />
            <YdNumberField v-model="vert.z" :step="1" class="w-full min-w-0" />
            <YdButton
              size="sm"
              variant="ghost"
              title="删除该顶点"
              :disabled="rangeDialog.verts.length <= 3"
              @click="rangeDialog.verts.splice(index, 1)"
            >
              <YdIcon name="i-mdi:close" />
            </YdButton>
          </div>
          <YdButton size="sm" variant="outline" class="justify-self-start" @click="addRangeVertex">
            <YdIcon name="i-mdi:plus" />
            添加顶点
          </YdButton>
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" @click="rangeDialog.open = false">
            取消
          </YdButton>
          <YdButton @click="confirmRangeDialog">
            确定
          </YdButton>
        </div>
      </template>
    </YdModal>

    <!-- 标注编辑弹窗：与标注管理页同款 -->
    <YdModal v-model="editMarkerDialog.open" title="编辑标注">
      <div class="grid gap-4">
        <label class="grid gap-1.5">
          <span class="text-sm">名称</span>
          <YdInput v-model="editMarkerDialog.label" />
        </label>
        <label class="grid gap-1.5">
          <span class="text-sm">标注集</span>
          <YdSelect v-model="editMarkerDialog.setId" :options="markerSetOptionsWithNew" />
        </label>
        <label v-if="editMarkerDialog.setId === '__new__'" class="grid gap-1.5">
          <span class="text-sm">新标注集名称</span>
          <YdInput v-model="editMarkerDialog.newSetName" placeholder="例如：建筑组" />
        </label>

        <template v-if="editDialogIsPoi">
          <div class="grid gap-3" :class="editMarkerDialog.ignoreY ? 'grid-cols-2' : 'grid-cols-3'">
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">X</span>
              <YdNumberField v-model="editMarkerDialog.x" :step="1" class="w-full min-w-0" />
            </label>
            <label v-if="!editMarkerDialog.ignoreY" class="grid min-w-0 gap-1.5">
              <span class="text-sm">Y</span>
              <YdNumberField v-model="editMarkerDialog.y" :step="1" class="w-full min-w-0" />
            </label>
            <label class="grid min-w-0 gap-1.5">
              <span class="text-sm">Z</span>
              <YdNumberField v-model="editMarkerDialog.z" :step="1" class="w-full min-w-0" />
            </label>
          </div>
          <label class="flex items-center gap-2 text-sm">
            <YdCheckbox v-model="editMarkerDialog.ignoreY" />
            忽略 Y 轴（保留原 Y 值）
          </label>
        </template>
        <template v-else-if="editMarkerDialog.markerType === 'shape'">
          <div class="grid gap-2">
            <label class="grid gap-1.5">
              <span class="text-sm">贴地高度 Y</span>
              <YdNumberField v-model="editMarkerDialog.shapeY" :step="1" />
            </label>
            <span class="text-sm">多边形顶点（{{ editMarkerDialog.verts.length }} 个）</span>
            <div
              v-for="(vert, index) in editMarkerDialog.verts"
              :key="index"
              class="flex items-center gap-2"
            >
              <span class="w-8 shrink-0 text-xs text-muted-foreground">#{{ index + 1 }}</span>
              <YdNumberField v-model="vert.x" :step="1" class="w-full min-w-0" />
              <YdNumberField v-model="vert.z" :step="1" class="w-full min-w-0" />
              <YdButton
                size="sm"
                variant="ghost"
                title="删除该顶点"
                :disabled="editMarkerDialog.verts.length <= 3"
                @click="editMarkerDialog.verts.splice(index, 1)"
              >
                <YdIcon name="i-mdi:close" />
              </YdButton>
            </div>
            <YdButton size="sm" variant="outline" class="justify-self-start" @click="addEditDialogVertex">
              <YdIcon name="i-mdi:plus" />
              添加顶点
            </YdButton>
          </div>
        </template>
        <p v-else class="rounded-md border border-border bg-muted/40 p-2 text-xs text-muted-foreground">
          该标注为{{ editMarkerDialog.markerType }}类型，几何坐标在查看页绘制，这里只能改名称与标注集。
        </p>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" @click="editMarkerDialog.open = false">
            取消
          </YdButton>
          <YdButton :loading="savingMarker" @click="confirmEditMarkerDialog">
            确定
          </YdButton>
        </div>
      </template>
    </YdModal>

    <!-- 覆盖默认视角确认 -->
    <YdModal v-model="overwriteViewConfirm" title="覆盖默认视角">
      <p class="text-sm text-muted-foreground">
        该地图已保存过默认视角与列表封面，继续保存将覆盖原有内容。是否覆盖？
      </p>
      <template #footer>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" @click="overwriteViewConfirm = false">
            取消
          </YdButton>
          <YdButton :loading="savingView" @click="doSaveDefaultView">
            覆盖保存
          </YdButton>
        </div>
      </template>
    </YdModal>

    <div v-if="loading" class="voxelith-overlay">地图加载中……</div>
    <div v-else-if="error" class="voxelith-overlay voxelith-error">
      <span>{{ error }}</span>
      <YdButton v-if="currentMapId" size="sm" variant="outline" @click="retry">重试</YdButton>
    </div>
    <div v-else-if="!currentMapId" class="voxelith-overlay">没有可显示的地图</div>

    <YdTag v-if="chromeVisible && viewer && loaded > 0" class="voxelith-badge" variant="outline">
      渲染中 · {{ loaded }} 瓦片
    </YdTag>

    <!-- 沉浸模式提示：告知呼出界面的按键（数秒后自动淡出） -->
    <div
      v-if="hintVisible && !chromeVisible"
      :key="hintSeq"
      class="voxelith-hud-hint"
    >
      <template v-if="immersive">
        沉浸模式 · 按 {{ describeKey(keymap.toggleImmersion) }} 退出
      </template>
      <template v-else>
        按 {{ describeKey(keymap.toggleUi) }} 呼出界面 · 按 {{ describeKey(keymap.cycleMode) }} 切换视角
      </template>
    </div>
  </div>
</template>
