/**
 * Voxelith 三维地图查看器核心：把 vendored 渲染核心（@yudream/voxelith-*）包成一个
 * 可复用、可彻底销毁的查看器类。
 *
 * 接线顺序与 voxelith-app 的 App.vue 完全一致：
 *   - 构造 MapEngine + MarkerLayer（挂场景根，随浮点原点重定基平移）；
 *   - 探测设备档位 → TileManager（缓存上限按档位，detailDistanceChunks 接自适应视距）；
 *   - 自适应视距 AdaptiveDistance；
 *   - 设置相机初始机位 → 建控制器；
 *   - 可选天际线；
 *   - 每帧钩子顺序：控制器 update → markerLayer update → 天际线 update →
 *     浮点原点重定基（并 tileManager.setWorldOffset）→ tileManager.update → adaptive.update。
 */
import * as THREE from 'three'
import {
  AdaptiveDistance,
  cacheLimitsForTier,
  detectDeviceProfile,
  FirstPersonControls,
  FloatingOrigin,
  FreeFlightControls,
  initialViewDistanceChunks,
  loadManifest,
  MapEngine,
  MarkerLayer,
  setYSlice,
  sliceRaycastTargets,
  supportsDisplayP3,
  TileManager,
  TiltOrbitControls,
  type CameraControls,
  type CameraMode,
  type DeviceProfile,
  type TerrainMedium,
  type TerrainProbe,
} from '../vendor/voxelith/viewer/index'
import type { MapManifest, MarkerSet } from '../vendor/voxelith/core/index'
import { SkylineHaze, SkylineLayer } from '../vendor/voxelith/skyline/index'
import { TopDownControls } from './TopDownControls'

/** 每帧钩子签名（与 vendored MapEngine.addFrameHook 一致）。 */
type FrameHook = (dtSeconds: number, rawDtSeconds: number) => void

export type ViewerMode = 'flight' | 'firstPerson' | 'tiltOrbit'

/** 事件是否发生在可输入元素上：在输入框里打字不该驱动相机、也不该触发快捷键。 */
function isEditableKeyEvent(e: KeyboardEvent): boolean {
  const target = e.target as HTMLElement | null
  if (!target) return false
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable
}
export type LayerFilter = 'all' | 'hires' | 'lod'

/** 保存/恢复用的机位：相机位置 + 注视点（世界方块坐标）。 */
export interface CameraState {
  x: number
  y: number
  z: number
  tx: number
  ty: number
  tz: number
}

export interface VoxelithViewerOptions {
  mapBaseUrl: string
  manifest: MapManifest
  deviceProfile?: DeviceProfile
  displayP3?: boolean
  skyline?: boolean
  mode?: ViewerMode
  /** 鼠标灵敏度（弧度/像素） */
  sensitivity?: number
  /** 飞行速度（方块/秒） */
  flySpeed?: number
  /** 第一人称默认地面高度 */
  groundY?: number
}

const TERRAIN_SKY_MARGIN = 32
const FIRST_PERSON_EDGE_MARGIN = 1

export interface CameraWorldPosition {
  x: number
  y: number
  z: number
}

export class VoxelithViewer {
  private readonly canvas: HTMLCanvasElement
  private readonly mapBaseUrl: string
  private manifest: MapManifest
  private mode: ViewerMode

  private readonly floatingOrigin = new FloatingOrigin()
  private engine: MapEngine | null = null
  private controls: CameraControls | null = null
  private tileManager: TileManager | null = null
  private adaptive: AdaptiveDistance | null = null
  private markerLayer: MarkerLayer | null = null
  private skylineLayer: SkylineLayer | null = null
  private skylineHaze: SkylineHaze | null = null
  private deviceProfile: DeviceProfile | null = null

  private readonly frameHooks: FrameHook[] = []
  private posTimer = 0
  private disposed = false
  /** 渲染循环是否已被暂停（见 setPaused） */
  private paused = false

  private currentDistance = 0

  private readonly sensitivity: number
  private readonly flySpeed: number
  private readonly groundY: number
  private skylineEnabled: boolean

  /** 二维（正俯视）模式：控制器换成 TopDownControls，切模式/传送后仍保持 */
  private topDown = false

  /**
   * 打字时吞掉按键。
   *
   * <p>vendor 的键盘控制器直接监听 window（只读，不可改），没有「输入框聚焦时不响应」的判断，
   * 于是在地图名称输入框里打字（WASD/空格/Shift）仍会驱动相机——已按下的键还会被插件的改键拦截器
   * 合成成标准移动键，双重放大。这里在**捕获阶段**拦住「发生在可输入元素上的 keydown」，同一节点上
   * 冒泡阶段的 vendor 监听就收不到了；keyup 不拦，长按状态能照常释放。</p>
   */
  private readonly swallowTypingKeys = (e: KeyboardEvent): void => {
    if (isEditableKeyEvent(e)) e.stopPropagation()
  }

  /** 第一人称地形射线复用对象（避免逐帧分配） */
  private readonly terrainRaycaster = new THREE.Raycaster()
  private readonly terrainRayOrigin = new THREE.Vector3()
  private readonly terrainRayDirection = new THREE.Vector3()

  constructor(canvas: HTMLCanvasElement, options: VoxelithViewerOptions) {
    this.canvas = canvas
    this.mapBaseUrl = options.mapBaseUrl
    this.manifest = options.manifest
    this.mode = options.mode ?? 'flight'
    this.sensitivity = options.sensitivity ?? 0.0011
    this.flySpeed = options.flySpeed ?? 20
    this.groundY = options.groundY ?? 4
    this.skylineEnabled = options.skyline ?? false

    this.engine = new MapEngine({ canvas, displayP3: options.displayP3 === true })
    this.deviceProfile = options.deviceProfile ?? detectDeviceProfile(this.engine.renderer.getContext())

    this.markerLayer = new MarkerLayer()
    this.engine.scene.add(this.markerLayer.object3d)

    const m = this.manifest
    const cx = (m.boundsMin[0] + m.boundsMax[0]) / 2
    const cz = (m.boundsMin[2] + m.boundsMax[2]) / 2
    const topY = m.boundsMax[1] + 60

    this.tileManager = new TileManager({
      scene: this.engine.scene,
      mapBaseUrl: this.mapBaseUrl,
      manifest: m,
      ...cacheLimitsForTier(this.deviceProfile.tier),
      // 瓦片并发压到 4：站点跑在 HTTP/1.1 上，同源只有约 6 条连接，而引擎默认 16 路并发
      // 会把连接全占满——页面其它请求（插件 API、任务 SSE、静态资源、宿主路由守卫）统统排队，
      // 刷新时表现为「页面无响应」，对象存储同时也要吃下十几个瓦片读。
      // 降并发只影响首屏铺满的速度，画质与视距不受影响；瓦片按代不可变、有长缓存，回访很快。
      maxConcurrent: this.deviceProfile.mobile ? 3 : 4,
    })

    const initialChunks = initialViewDistanceChunks(this.deviceProfile.tier)
    this.adaptive = new AdaptiveDistance({
      tileManager: this.tileManager,
      initialChunks,
      mobile: this.deviceProfile.mobile,
      onChange: (c) => { this.currentDistance = c },
    })
    this.currentDistance = Math.round(this.adaptive.chunks)

    // 设置相机初始机位（水平俯视，避免进场即俯冲）
    this.engine.camera.position.set(cx, topY, cz + 80)
    this.engine.camera.lookAt(cx, topY, cz)

    this.controls = this.createControls(this.mode)
    window.addEventListener('keydown', this.swallowTypingKeys, true)

    if (this.skylineEnabled) {
      this.applySkyline()
    }

    this.registerFrameHooks()

    this.posTimer = window.setInterval(() => {
      if (!this.engine) return
      const p = this.engine.camera.position
      this.cameraPos = {
        x: Math.round(p.x + this.floatingOrigin.origin.x),
        y: Math.round(p.y + this.floatingOrigin.origin.y),
        z: Math.round(p.z + this.floatingOrigin.origin.z),
      }
    }, 150)
  }

  private cameraPos: CameraWorldPosition = { x: 0, y: 0, z: 0 }

  // ---------------------------------------------------------------------------
  // 帧循环钩子（顺序与 App.vue 一致）
  // ---------------------------------------------------------------------------

  private registerFrameHooks(): void {
    if (!this.engine) return
    const hooks: FrameHook[] = [
      (dt) => this.controls?.update(dt),
      () => this.markerLayer?.update(this.engine!.camera),
      () => this.skylineLayer?.update(this.engine!.camera),
      () => {
        const delta = this.engine && this.floatingOrigin.maybeRebase(this.engine.camera, this.engine.scene)
        if (delta) {
          const targetHolder = this.controls as { target?: THREE.Vector3 } | null
          targetHolder?.target?.sub(delta)
          this.tileManager?.setWorldOffset(this.floatingOrigin.origin)
        }
      },
      () => this.tileManager?.update(this.engine!.camera),
      (dt, rawDt) => this.adaptive?.update(dt, rawDt),
    ]
    for (const h of hooks) {
      this.frameHooks.push(h)
      this.engine.addFrameHook(h)
    }
  }

  // ---------------------------------------------------------------------------
  // 控制器
  // ---------------------------------------------------------------------------

  private createControls(m: CameraMode, tiltTarget?: THREE.Vector3): CameraControls {
    if (this.topDown && this.engine) {
      // 二维：正俯视、北朝上；中心取相机当前的水平位置，高度取当前高度
      const controls = new TopDownControls(this.engine.camera, this.canvas, {
        centerX: this.engine.camera.position.x,
        centerZ: this.engine.camera.position.z,
        height: Math.max(24, this.engine.camera.position.y),
      })
      const surface = this.surfaceYAtWorld(this.engine.camera.position.x, this.engine.camera.position.z)
      if (surface !== null) controls.setFocusY(surface)
      return controls
    }
    if (!this.engine) {
      throw new Error('engine not ready')
    }
    const canvas = this.canvas
    if (m === 'flight') {
      return new FreeFlightControls(this.engine.camera, canvas, {
        sensitivity: this.sensitivity,
        speed: this.flySpeed,
      })
    }
    if (m === 'firstPerson') {
      return new FirstPersonControls(this.engine.camera, canvas, {
        sensitivity: this.sensitivity,
        groundY: this.groundY,
        probe: this.terrainProbe,
        clampXZ: (position) => this.clampToMapBounds(position),
      })
    }
    const forward = new THREE.Vector3()
    this.engine.camera.getWorldDirection(forward)
    const target = tiltTarget ?? this.engine.camera.position.clone().addScaledVector(forward, 60)
    if (!tiltTarget) {
      // 轨道中心的高度取「视线落点处的地表」，而不是固定的 groundY（默认 4）：
      // 地表在 y=64 以上的地图切到俯视倾斜时，轨道中心沉在地下、相机绕着地底转，
      // 表现出来就是「俯视倾斜视角不对」——这正是此前的症状。
      const surface = this.surfaceYAtWorld(target.x, target.z)
      target.y = surface ?? this.groundY
    }
    return new TiltOrbitControls(this.engine.camera, canvas, { target })
  }

  /** 切换相机模式（先 dispose 旧控制器再建新控制器）。 */
  setMode(mode: ViewerMode): void {
    if (mode === this.mode || !this.engine) return
    this.mode = mode
    this.controls?.dispose()
    this.controls = this.createControls(mode)
  }

  getMode(): ViewerMode {
    return this.mode
  }

  /** 是否处于二维（正俯视）模式。 */
  isTopDown(): boolean {
    return this.topDown
  }

  /**
   * 切到/退出二维（正俯视）模式。
   *
   * <p>相机与控制器同步切换，3D 模式（视角、视距、Y 轴切片）不受影响：退出时按当前 mode 重建原控制器。</p>
   */
  setTopDown(enabled: boolean): void {
    if (!this.engine || enabled === this.topDown) return
    this.topDown = enabled
    this.controls?.dispose()
    this.controls = this.createControls(this.mode)
  }

  /** 二维模式下把视点移到某处（坐标传送用）；3D 模式走 {@link flyTo}。 */
  moveTopDownTo(x: number, z: number): void {
    const controls = this.controls
    if (controls instanceof TopDownControls) {
      controls.moveTo(x, z)
      const surface = this.surfaceYAtWorld(x, z)
      if (surface !== null) controls.setFocusY(surface)
    }
  }

  // ---------------------------------------------------------------------------
  // 第一人称地形探测（射线打 hires 瓦片碰撞代理）
  // ---------------------------------------------------------------------------

  private castTerrainRay(
    x: number,
    y: number,
    z: number,
    dx: number,
    dy: number,
    dz: number,
    maxDistance: number,
    medium: TerrainMedium = 'solid',
  ): number | null {
    if (!this.engine || !this.tileManager || maxDistance <= 0) {
      return null
    }
    const start = medium === 'water'
      ? this.tileManager.waterProxyAt(x, z)
      : this.tileManager.solidProxyAt(x, z)
    const end = medium === 'water'
      ? this.tileManager.waterProxyAt(x + dx * maxDistance, z + dz * maxDistance)
      : this.tileManager.solidProxyAt(x + dx * maxDistance, z + dz * maxDistance)
    const groups: THREE.Object3D[] = []
    if (start) groups.push(start)
    if (end && end !== start) groups.push(end)
    const targets = sliceRaycastTargets(groups)
    if (targets.length === 0) {
      return null
    }
    this.terrainRaycaster.set(
      this.terrainRayOrigin.set(x, y, z),
      this.terrainRayDirection.set(dx, dy, dz),
    )
    this.terrainRaycaster.near = 0
    this.terrainRaycaster.far = maxDistance
    const hits = this.terrainRaycaster.intersectObjects(targets, true)
    return hits.length > 0 ? hits[0]!.distance : null
  }

  private readonly terrainProbe: TerrainProbe = {
    topSurfaceY: (x, z, medium = 'solid') => {
      const m = this.manifest
      if (!m) return null
      const top = m.boundsMax[1] - this.floatingOrigin.origin.y + TERRAIN_SKY_MARGIN
      const bottom = m.boundsMin[1] - this.floatingOrigin.origin.y
      const hit = this.castTerrainRay(x, top, z, 0, -1, 0, top - bottom, medium)
      return hit === null ? null : top - hit
    },
    groundBelow: (x, z, fromY, maxDrop = Number.POSITIVE_INFINITY, medium = 'solid') => {
      const m = this.manifest
      const floor = m ? m.boundsMin[1] - this.floatingOrigin.origin.y : fromY - 4096
      const drop = Math.min(maxDrop, fromY - floor)
      if (drop <= 0) return null
      const hit = this.castTerrainRay(x, fromY, z, 0, -1, 0, drop, medium)
      return hit === null ? null : fromY - hit
    },
    castRay: (x, y, z, dx, dy, dz, maxDistance, medium = 'solid') =>
      this.castTerrainRay(x, y, z, dx, dy, dz, maxDistance, medium),
  }

  /** 把第一人称活动范围收进地图包围盒内（渲染空间就地修改）。 */
  private clampToMapBounds(position: THREE.Vector3): void {
    const m = this.manifest
    if (!m) return
    const origin = this.floatingOrigin.origin
    position.x = THREE.MathUtils.clamp(
      position.x,
      m.boundsMin[0] - origin.x + FIRST_PERSON_EDGE_MARGIN,
      m.boundsMax[0] - origin.x - FIRST_PERSON_EDGE_MARGIN,
    )
    position.z = THREE.MathUtils.clamp(
      position.z,
      m.boundsMin[2] - origin.z + FIRST_PERSON_EDGE_MARGIN,
      m.boundsMax[2] - origin.z - FIRST_PERSON_EDGE_MARGIN,
    )
  }

  // ---------------------------------------------------------------------------
  // 天际线（实验开关，默认关，零残留）
  // ---------------------------------------------------------------------------

  private skylineFarDistance(): number {
    const m = this.manifest
    if (!m) return 4096
    const dx = m.boundsMax[0] - m.boundsMin[0]
    const dz = m.boundsMax[2] - m.boundsMin[2]
    return Math.max(1024, Math.hypot(dx, dz) * 2)
  }

  private applySkyline(): void {
    this.skylineLayer?.dispose()
    this.skylineLayer = null
    this.skylineHaze?.dispose()
    this.skylineHaze = null
    const m = this.manifest
    if (!this.skylineEnabled || !this.engine || !m || !this.tileManager) {
      return
    }
    const farDistance = this.skylineFarDistance()
    this.skylineLayer = new SkylineLayer({
      manifest: m,
      atlasTextures: this.tileManager.lodAtlasTextures(),
      detailDistance: this.tileManager.detailDistanceBlocks,
      farDistance,
      opacity: 0.85,
    })
    this.engine.scene.add(this.skylineLayer.object3d)
    if (this.skylineLayer.tileCount() === 0) {
      this.skylineLayer.dispose()
      this.skylineLayer = null
      return
    }
    this.skylineHaze = new SkylineHaze({
      color: 0x87ceeb,
      near: this.tileManager.detailDistanceBlocks,
      far: farDistance,
    })
    this.skylineHaze.apply(this.engine.scene)
    this.skylineLayer.update(this.engine.camera)
  }

  setSkyline(enabled: boolean): void {
    if (enabled === this.skylineEnabled) return
    this.skylineEnabled = enabled
    if (this.skylineEnabled) {
      this.applySkyline()
    }
    else {
      this.skylineLayer?.dispose()
      this.skylineLayer = null
      this.skylineHaze?.dispose()
      this.skylineHaze = null
    }
  }

  // ---------------------------------------------------------------------------
  // 标注
  // ---------------------------------------------------------------------------

  setMarkerSets(sets: MarkerSet[]): void {
    this.markerLayer?.setMarkerSets(sets)
  }

  setSetVisible(id: string, visible: boolean): void {
    this.markerLayer?.setSetVisible(id, visible)
  }

  // ---------------------------------------------------------------------------
  // Y 轴切片
  // ---------------------------------------------------------------------------

  setYSlice(min: number, max: number): void {
    setYSlice({ min, max })
  }

  clearYSlice(): void {
    setYSlice(null)
  }

  // ---------------------------------------------------------------------------
  // 图层 / 视距
  // ---------------------------------------------------------------------------

  setLayerFilter(filter: LayerFilter): void {
    this.tileManager?.setLayerFilter(filter)
  }

  setDetailDistanceChunks(n: number): void {
    this.adaptive?.setEnabled(false)
    this.adaptive?.setChunks(n)
  }

  setAutoDistance(enabled: boolean): void {
    this.adaptive?.setEnabled(enabled)
  }

  /** 运行时切换 Display P3 广色域输出（仅 P3 屏幕生效）。 */
  setDisplayP3(enabled: boolean): void {
    this.engine?.setDisplayP3(enabled)
  }

  // ---------------------------------------------------------------------------
  // 机位
  // ---------------------------------------------------------------------------

  /** 飞到世界坐标 (x,y,z) 斜上方并看向该点。 */
  flyTo(x: number, y: number, z: number): void {
    if (!this.engine) return
    const o = this.floatingOrigin.origin
    this.engine.camera.position.set(x - o.x + 24, y - o.y + 18, z - o.z + 24)
    this.engine.camera.lookAt(x - o.x, y - o.y, z - o.z)
    this.controls?.dispose()
    this.controls = this.createControls(this.mode)
  }

  /** 把相机摆到包围盒外的合理机位（整图俯瞰）。 */
  frameAll(): void {
    const m = this.manifest
    if (!this.engine || !m) return
    const cx = (m.boundsMin[0] + m.boundsMax[0]) / 2
    const cz = (m.boundsMin[2] + m.boundsMax[2]) / 2
    const topY = m.boundsMax[1] + 60
    const o = this.floatingOrigin.origin
    this.engine.camera.position.set(cx - o.x, topY - o.y, cz - o.z + 80)
    this.engine.camera.lookAt(cx - o.x, topY - o.y, cz - o.z)
    this.controls?.dispose()
    this.controls = this.createControls(this.mode)
  }

  // ---------------------------------------------------------------------------
  // 只读统计（供 HUD）
  // ---------------------------------------------------------------------------

  getCameraWorldPosition(): CameraWorldPosition {
    return this.cameraPos
  }

  /**
   * 当前机位（世界坐标）：相机位置 + 注视点。
   * 供「设为默认视角」保存；注视点取相机朝向前方 100 格处。
   */
  getCameraState(): CameraState | null {
    if (!this.engine) return null
    const o = this.floatingOrigin.origin
    const p = this.engine.camera.position
    const forward = new THREE.Vector3()
    this.engine.camera.getWorldDirection(forward)
    const target = p.clone().addScaledVector(forward, 100)
    const round = (value: number) => Math.round(value * 100) / 100
    return {
      x: round(p.x + o.x),
      y: round(p.y + o.y),
      z: round(p.z + o.z),
      tx: round(target.x + o.x),
      ty: round(target.y + o.y),
      tz: round(target.z + o.z),
    }
  }

  /** 回到保存的默认机位（世界坐标），并按当前相机模式重建控制器。 */
  applyCameraState(state: CameraState): void {
    if (!this.engine) return
    const o = this.floatingOrigin.origin
    this.engine.camera.position.set(state.x - o.x, state.y - o.y, state.z - o.z)
    this.engine.camera.lookAt(state.tx - o.x, state.ty - o.y, state.tz - o.z)
    this.controls?.dispose()
    this.controls = this.createControls(this.mode)
  }

  /**
   * 相机视线落点：沿视线对地形做射线检测，返回命中的世界坐标（用于「在此处标注」）。
   * 视线没打到地形（望向天空/远处未加载）时返回 null。
   */
  getCameraFocusPoint(): CameraWorldPosition | null {
    if (!this.engine || !this.tileManager) return null
    const o = this.floatingOrigin.origin
    const p = this.engine.camera.position
    const forward = new THREE.Vector3()
    this.engine.camera.getWorldDirection(forward)
    const distance = this.castTerrainRay(p.x, p.y, p.z, forward.x, forward.y, forward.z, 4000)
    if (distance === null) return null
    const hit = p.clone().addScaledVector(forward, distance)
    // 标注坐标只需精确到整数方块
    return {
      x: Math.round(hit.x + o.x),
      y: Math.round(hit.y + o.y),
      z: Math.round(hit.z + o.z),
    }
  }

  /** 指定世界坐标处的地表高度（用于把范围标注铺在地形表面上）；探测不到返回 null。 */
  surfaceYAtWorld(x: number, z: number): number | null {
    const o = this.floatingOrigin.origin
    const y = this.terrainProbe.topSurfaceY(x - o.x, z - o.z)
    return y === null ? null : Math.round((y + o.y) * 100) / 100
  }

  /** 范围标注的屏幕拾取射线（与渲染循环的 terrainRaycaster 分开，互不干扰） */
  private readonly pickerRaycaster = new THREE.Raycaster()

  /**
   * 屏幕坐标（clientX/clientY）拾取视线下的地表方块，返回命中的世界坐标。
   * 用于俯视视角「点击方块」式的范围标注；跳过标注图层，未命中返回 null。
   */
  pickTerrainAtClient(clientX: number, clientY: number): CameraWorldPosition | null {
    if (!this.engine || !this.canvas) return null
    const rect = this.canvas.getBoundingClientRect()
    if (rect.width <= 0 || rect.height <= 0) return null
    const ndc = new THREE.Vector2(
      ((clientX - rect.left) / rect.width) * 2 - 1,
      -((clientY - rect.top) / rect.height) * 2 + 1,
    )
    this.pickerRaycaster.setFromCamera(ndc, this.engine.camera)
    const markerRoot = this.markerLayer?.object3d
    const hits = this.pickerRaycaster.intersectObjects(this.engine.scene.children, true).filter((hit) => {
      // 标注牌/范围面也是场景网格，必须排除，否则会在已有标注上反复取点
      let parent: THREE.Object3D | null = hit.object
      while (parent) {
        if (parent === markerRoot) return false
        parent = parent.parent
      }
      return true
    })
    const hit = hits.length > 0 ? hits[0] : null
    if (!hit) return null
    const o = this.floatingOrigin.origin
    const point = hit.point
    // 标注坐标只需精确到整数方块
    return { x: Math.round(point.x + o.x), y: Math.round(point.y + o.y), z: Math.round(point.z + o.z) }
  }

  /** 取点高亮线框（方块大小），用于标注取点反馈 */
  private pickHighlights: THREE.Object3D[] = []

  /** 视线瞄准高亮（单独一份：与标注取点高亮互不干扰） */
  private aimHighlight: THREE.Object3D | null = null

  /** 在世界坐标处放一个方块线框高亮（x/y/z 为整数方块坐标） */
  addPickHighlight(x: number, y: number, z: number): void {
    if (!this.engine) return
    const line = this.createBlockWireframe(0x2f6fd0)
    const o = this.floatingOrigin.origin
    line.position.set(x - o.x + 0.5, y - o.y + 0.5, z - o.z + 0.5)
    this.engine.scene.add(line)
    this.pickHighlights.push(line)
  }

  /**
   * 设置「当前视线对准的方块」高亮（非俯视视角取点时的准星反馈）。
   *
   * <p>与标注取点高亮分开保存：对准是每帧刷新的，不能把已记录的角点冲掉。重复传同一方块时不重建，
   * 避免每帧都在场景里增删对象。</p>
   */
  setAimHighlight(x: number, y: number, z: number): void {
    if (!this.engine) return
    const o = this.floatingOrigin.origin
    const px = x - o.x + 0.5
    const py = y - o.y + 0.5
    const pz = z - o.z + 0.5
    if (this.aimHighlight && this.aimHighlight.position.x === px
      && this.aimHighlight.position.y === py && this.aimHighlight.position.z === pz) {
      return
    }
    this.clearAimHighlight()
    const line = this.createBlockWireframe(0x22c55e)
    line.position.set(px, py, pz)
    this.engine.scene.add(line)
    this.aimHighlight = line
  }

  /** 清除准星高亮。 */
  clearAimHighlight(): void {
    if (!this.aimHighlight) return
    this.removeHighlight(this.aimHighlight)
    this.aimHighlight = null
  }

  /** 方块线框（取点/准星共用）。 */
  private createBlockWireframe(color: number): THREE.LineSegments {
    const edges = new THREE.EdgesGeometry(new THREE.BoxGeometry(1.05, 1.05, 1.05))
    const line = new THREE.LineSegments(
      edges,
      new THREE.LineBasicMaterial({ color, depthTest: false, transparent: true }),
    )
    line.renderOrder = 999
    return line
  }

  /** 清除全部取点高亮（标注流程结束/取消时调用） */
  clearPickHighlights(): void {
    for (const line of this.pickHighlights) {
      this.removeHighlight(line)
    }
    this.pickHighlights = []
  }

  /** 撤销最后一个取点高亮（范围标注「撤销上一角」）；没有时返回 false */
  undoLastPickHighlight(): boolean {
    const line = this.pickHighlights.pop()
    if (!line) return false
    this.removeHighlight(line)
    return true
  }

  private removeHighlight(line: THREE.Object3D): void {
    this.engine?.scene.remove(line)
    line.traverse((child) => {
      const anyChild = child as THREE.LineSegments
      anyChild.geometry?.dispose?.()
      const material = anyChild.material
      if (Array.isArray(material)) material.forEach(m => m.dispose?.())
      else material?.dispose?.()
    })
  }

  /**
   * 截取当前画面为 PNG dataURL；失败时抛出带原因的错误（同步补渲染一帧后读取）。
   *
   * 渲染循环没有开 preserveDrawingBuffer，必须先同步渲染再立刻读取；
   * 若浏览器安全策略拒绝 toDataURL（如纹理被判定污染画布），退回 readPixels
   * 手工搬运像素到 2D 画布再导出。
   */
  snapshot(): string {
    if (!this.engine) throw new Error('查看器尚未初始化')
    const canvas = this.engine.renderer.domElement
    if (canvas.width === 0 || canvas.height === 0) {
      throw new Error('画布还没有完成渲染，请稍后再试')
    }
    const gl = this.engine.renderer.getContext()
    if (gl.isContextLost()) {
      throw new Error('WebGL 上下文已丢失，请刷新页面后重试')
    }
    try {
      this.engine.render()
      return canvas.toDataURL('image/png')
    }
    catch (primaryError) {
      try {
        this.engine.render()
        return this.readPixelsToDataUrl(gl)
      }
      catch {
        const reason = primaryError instanceof Error ? primaryError.message : String(primaryError)
        throw new Error(`截图失败：${reason}`)
      }
    }
  }

  /** readPixels 兜底：直接从 WebGL 后备缓冲搬像素（行序自下而上，需翻转） */
  private readPixelsToDataUrl(gl: WebGLRenderingContext): string {
    const width = gl.drawingBufferWidth
    const height = gl.drawingBufferHeight
    const pixels = new Uint8Array(width * height * 4)
    gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
    const out = document.createElement('canvas')
    out.width = width
    out.height = height
    const ctx = out.getContext('2d')
    if (!ctx) throw new Error('无法创建截图画布')
    const image = ctx.createImageData(width, height)
    for (let y = 0; y < height; y++) {
      const source = (height - 1 - y) * width * 4
      image.data.set(pixels.subarray(source, source + width * 4), y * width * 4)
    }
    ctx.putImageData(image, 0, 0)
    return out.toDataURL('image/png')
  }

  getCurrentDistance(): number {
    return this.currentDistance
  }

  getLoadedCount(): number {
    return this.tileManager?.loadedCount ?? 0
  }

  getFailedCount(): number {
    return this.tileManager?.failedCount ?? 0
  }

  getLoadedByLevel(): Record<number, number> {
    return this.tileManager?.loadedByLevel() ?? {}
  }

  getManifest(): MapManifest {
    return this.manifest
  }

  supportsP3(): boolean {
    return supportsDisplayP3()
  }

  /** 重新加载同一张图的 manifest（切图或后端版本更新后调用）。 */
  async reloadManifest(): Promise<void> {
    const m = await loadManifest(this.mapBaseUrl)
    this.manifest = m
  }

  // ---------------------------------------------------------------------------
  // 渲染暂停（2D 地图打开时用）
  // ---------------------------------------------------------------------------

  /**
   * 暂停/恢复三维渲染循环。
   *
   * <p>为什么需要：纹理缺失的地图上（宿主对带查询串的请求 400，渲染核自带的缓存戳正中此坑），
   * 每一帧都会尝试上传空贴图并刷出海量控制台警告——主线程被压死后，页面其它任务
   * （包括 2D 地图的加载回调）会被饿到几乎不动；表现为「2D 地图一直加载中，
   * 切换地图才集中报错」（切图会销毁 viewer、停掉循环，主线程腾空后积压任务才跑完）。</p>
   *
   * <p>实现：直接停掉引擎的 setAnimationLoop；恢复时用等价回调驱动——
   * {@code MapEngine.render()} 内部会先跑 frameHooks（相机/瓦片更新）再渲染，与内置循环等价。
   * 暂停期间画布停留在最后一帧（被 2D 覆盖层遮住，无感知）；销毁自身会停循环，无需额外处理。</p>
   */
  setPaused(paused: boolean): void {
    const engine = this.engine
    if (this.disposed || !engine || paused === this.paused) return
    this.paused = paused
    if (paused) {
      engine.renderer.setAnimationLoop(null)
    }
    else {
      // 捕获局部引用：回调里再读 this.engine 会被判为可能为 null（dispose 会置空）
      engine.renderer.setAnimationLoop(() => {
        if (!this.disposed) engine.render()
      })
    }
  }

  // ---------------------------------------------------------------------------
  // 销毁（彻底：定时器、帧钩子、控制器、瓦片、标注、天际线、引擎）
  // ---------------------------------------------------------------------------

  dispose(): void {
    if (this.disposed) return
    this.disposed = true

    window.removeEventListener('keydown', this.swallowTypingKeys, true)
    this.clearAimHighlight()

    if (this.posTimer !== 0) {
      window.clearInterval(this.posTimer)
      this.posTimer = 0
    }

    for (const hook of this.frameHooks) {
      this.engine?.removeFrameHook(hook)
    }
    this.frameHooks.length = 0

    this.skylineLayer?.dispose()
    this.skylineLayer = null
    this.skylineHaze?.dispose()
    this.skylineHaze = null

    this.markerLayer?.dispose()
    this.markerLayer = null

    this.tileManager?.dispose()
    this.tileManager = null

    this.controls?.dispose()
    this.controls = null

    this.adaptive = null

    this.engine?.dispose()
    this.engine = null
  }
}
