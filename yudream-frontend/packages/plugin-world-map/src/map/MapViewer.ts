import * as THREE from 'three'
import { FlyController } from './controls/FlyController'
import { OrbitController } from './controls/OrbitController'
import { applyDayFactor, computeDayFactor, createTerrainMaterial, createTranslucentTerrainMaterial } from './material'
import { applyBlueMapDayFactor, createBlueMapMaterials, disposeBlueMapMaterials } from '../bluemap-adapter/BlueMapMaterials'
import { applyBlueMapSettings } from '../bluemap-adapter/BlueMapSettings'
import { parseBlueMapLowresIndex } from '../bluemap-adapter/BlueMapLowresIndex'
import { canvasPointerToNdc } from './canvasCoordinates'
import { MarkerLayer, markerAnchor } from './MarkerLayer'
import type { MarkerPickResult } from './MarkerLayer'
import type { MapMarker, MapMarkerSet } from '../types'
import { TileManager } from './TileManager'
import type { CameraMode, MapViewMode, WorldMapSource } from './types'
import { fogForViewMode, PERSPECTIVE_FOG } from './viewMode'
import { BACKGROUND_FRAME_INTERVAL_MS, needsBackgroundRender } from './renderCadence'
import { renderPixelRatio } from './renderPixelRatio'
import { releaseLowresImage } from './lowresImageDecode'
import { EMPTY_TILE_LOAD_STATUS } from './tileLoadStatus'
import type { TileLoadStatus } from './tileLoadStatus'
import { FLAT_VIEW_MAX_DISTANCE, FLAT_VIEW_MIN_DISTANCE } from './flatViewPolicy'
import { FLAT_CAMERA_HEIGHT, flatSpawnPosition, perspectiveSpawnPosition } from './spawnView'

export interface MapViewerOptions {
  /** 相机位置变化回调（节流至约 10Hz），用于坐标显示 */
  onCameraChanged?: (position: THREE.Vector3, target: THREE.Vector3) => void
  hiresRadius?: number
  maxConcurrent?: number
  onMarkerSetsChanged?: (sets: readonly MapMarkerSet[]) => void
  onMarkerSelected?: (result: MarkerPickResult | null) => void
}

const DAY_SKY = new THREE.Color(0x87a9d6)
const NIGHT_SKY = new THREE.Color(0x070a12)
const FLAT_VIEW_HEIGHT = 1_200

/**
 * 3D 地图查看器引擎（视觉对齐 BlueMap 5.x）：
 * WebGLRenderer + 场景雾 + 昼夜天空 + 双模式相机 + tile 调度 + 标注层。
 * 与 Vue 无关，由 composable 驱动生命周期，dispose() 完整释放资源。
 */
export class MapViewer {
  private readonly renderer: THREE.WebGLRenderer
  private readonly scene = new THREE.Scene()
  private readonly perspectiveCamera: THREE.PerspectiveCamera
  private readonly flatCamera: THREE.OrthographicCamera
  private readonly fog: THREE.Fog
  private readonly skyColor = new THREE.Color()
  private readonly orbit: OrbitController
  private readonly flatOrbit: OrbitController
  private readonly fly: FlyController
  private readonly markerLayer: MarkerLayer
  private readonly resizeObserver: ResizeObserver
  private mode: CameraMode = 'orbit'
  private viewMode: MapViewMode = 'perspective'
  private readonly perspectiveOffset = new THREE.Vector3(60, 85, 60)
  private tileManager: TileManager | null = null
  private material: THREE.ShaderMaterial | null = null
  private translucentMaterial: THREE.ShaderMaterial | null = null
  private blueMapMaterials: THREE.ShaderMaterial[] | null = null
  private atlas: THREE.Texture | null = null
  private source: WorldMapSource | null = null
  private spawn: THREE.Vector3 | null = null
  private rafId: number | null = null
  private backgroundRenderTimer: number | null = null
  private lastTime = performance.now()
  private frameCounter = 0
  private timeOfDay = 0.5
  private disposed = false

  constructor(
    private readonly container: HTMLElement,
    private readonly options: MapViewerOptions = {},
  ) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
    this.renderer.setSize(Math.max(container.clientWidth, 1), Math.max(container.clientHeight, 1))
    container.appendChild(this.renderer.domElement)

    this.perspectiveCamera = new THREE.PerspectiveCamera(60, 1, 0.1, 4000)
    this.perspectiveCamera.position.set(0, 200, 0)
    this.flatCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 8_000)
    this.flatCamera.up.set(0, 0, -1)

    this.fog = new THREE.Fog(0x000000, PERSPECTIVE_FOG.near, PERSPECTIVE_FOG.far)
    this.scene.fog = this.fog

    this.orbit = new OrbitController(this.perspectiveCamera, this.renderer.domElement)
    this.orbit.controls.addEventListener('change', this.requestRender)
    this.flatOrbit = new OrbitController(this.flatCamera, this.renderer.domElement)
    this.flatOrbit.controls.enableRotate = false
    this.flatOrbit.controls.screenSpacePanning = true
    this.flatOrbit.controls.minDistance = FLAT_VIEW_MIN_DISTANCE
    this.flatOrbit.controls.maxDistance = FLAT_VIEW_MAX_DISTANCE
    this.flatOrbit.controls.minZoom = 0.25
    this.flatOrbit.controls.maxZoom = 8
    this.flatOrbit.controls.addEventListener('change', this.requestRender)
    this.fly = new FlyController(this.perspectiveCamera, this.renderer.domElement, this.requestRender)
    this.setCameraMode('orbit')

    this.markerLayer = new MarkerLayer(this.scene)
    this.renderer.domElement.addEventListener('click', this.onCanvasClick)
    document.addEventListener('visibilitychange', this.onVisibilityChange)

    this.resizeObserver = new ResizeObserver(() => this.resize())
    this.resizeObserver.observe(container)
    this.resize()

    this.applyTimeOfDay()
    this.forceRender()
  }

  /** 切换地图数据源：加载 settings + atlas，重建 tile 调度，相机定位到 spawn 上方斜视 45° */
  async setSource(source: WorldMapSource): Promise<void> {
    this.source?.dispose?.()
    this.source = source
    this.tileManager?.dispose()
    this.tileManager = null
    this.material?.dispose()
    this.material = null
    this.translucentMaterial?.dispose()
    this.translucentMaterial = null
    if (this.blueMapMaterials) disposeBlueMapMaterials(this.blueMapMaterials)
    this.blueMapMaterials = null
    this.disposeAtlas()

    const settings = await source.loadSettings()
    if (this.disposed || this.source !== source) {
      return
    }
    if (settings.renderer === 'BLUEMAP') {
      if (!source.loadBlueMapTextures || !source.loadBlueMapSettings) throw new Error('BlueMap generation metadata is not available from this source')
      const [blueMapSettings, lowresIndexPayload, texturePayload] = await Promise.all([
        source.loadBlueMapSettings(),
        source.loadBlueMapLowresIndex ? source.loadBlueMapLowresIndex() : Promise.resolve(null),
        source.loadBlueMapTextures(),
      ])
      if (this.disposed || this.source !== source) {
        return
      }
      applyBlueMapSettings(settings, blueMapSettings)
      if (lowresIndexPayload !== null) {
        settings.lowresTileIndex = parseBlueMapLowresIndex(lowresIndexPayload)
      }
      const materials = await createBlueMapMaterials(texturePayload)
      if (this.disposed || this.source !== source) {
        disposeBlueMapMaterials(materials)
        return
      }
      this.blueMapMaterials = materials
    } else {
      const atlas = await source.loadAtlas()
      if (this.disposed || this.source !== source) {
        this.disposeAtlasTexture(atlas)
        return
      }
      this.atlas = atlas
      this.material = createTerrainMaterial(atlas)
      this.translucentMaterial = createTranslucentTerrainMaterial(atlas)
    }
    this.applyTimeOfDay()
    this.tileManager = new TileManager(source, settings, this.blueMapMaterials ?? this.material!,
      this.translucentMaterial ?? this.blueMapMaterials![0]!, this.scene, {
      hiresRadius: this.options.hiresRadius,
      maxConcurrent: this.options.maxConcurrent,
      onChanged: this.requestRender,
    })
    void this.markerLayer.load(source).then(() => {
      if (!this.disposed && this.source === source) {
        this.options.onMarkerSetsChanged?.(this.markerLayer.sets)
        this.requestRender()
      }
    })

    const { x, y, z } = settings.spawn
    this.spawn = new THREE.Vector3(x, y, z)
    this.resetView()
    this.forceRender()
  }

  setCameraMode(mode: CameraMode): void {
    if (mode === 'fly' && this.viewMode === 'flat') {
      this.setViewMode('perspective')
    }
    this.mode = mode
    this.syncControllerActivation()
    if (mode === 'orbit' && this.viewMode === 'perspective') {
      // 飞行 → 轨道：把轨道目标放到视线前方，保持视角连续
      const direction = new THREE.Vector3()
      this.camera.getWorldDirection(direction)
      this.orbit.controls.target.copy(this.camera.position).addScaledVector(direction, 60)
    }
    this.requestRender()
  }

  getCameraMode(): CameraMode {
    return this.mode
  }

  setViewMode(mode: MapViewMode): void {
    if (mode === this.viewMode) {
      return
    }
    if (mode === 'flat') {
      const target = this.currentTarget()
      this.perspectiveOffset.copy(this.perspectiveCamera.position).sub(this.orbit.controls.target)
      if (this.perspectiveOffset.lengthSq() < 1) {
        this.perspectiveOffset.set(60, 85, 60)
      }
      this.mode = 'orbit'
      this.viewMode = 'flat'
      this.applyFogForViewMode()
      this.flatCamera.position.set(target.x, target.y + FLAT_CAMERA_HEIGHT, target.z)
      this.flatCamera.lookAt(target)
      this.flatOrbit.controls.target.copy(target)
      this.flatOrbit.controls.update()
    }
    else {
      const target = this.flatOrbit.controls.target
      this.viewMode = 'perspective'
      this.applyFogForViewMode()
      this.orbit.controls.target.copy(target)
      this.perspectiveCamera.position.copy(target).add(this.perspectiveOffset)
      this.perspectiveCamera.lookAt(target)
      this.orbit.controls.update()
    }
    this.syncControllerActivation()
    this.requestRender()
  }

  getViewMode(): MapViewMode {
    return this.viewMode
  }

  /** t ∈ [0,1]，0 = 午夜，0.5 = 正午 */
  setTimeOfDay(t: number): void {
    this.timeOfDay = THREE.MathUtils.clamp(t, 0, 1)
    this.applyTimeOfDay()
    this.requestRender()
  }

  /** 读取当前视角（相机位置 + 当前控制器目标点） */
  getView(): { position: THREE.Vector3, target: THREE.Vector3, zoom?: number } {
    return {
      position: this.camera.position.clone(),
      target: this.currentTarget(),
      zoom: this.viewMode === 'flat' ? this.flatCamera.zoom : undefined,
    }
  }

  /** 恢复指定视角（用于 URL 视角分享还原） */
  setView(position: { x: number, y: number, z: number }, target: { x: number, y: number, z: number }, zoom?: number): void {
    const controls = this.viewMode === 'flat' ? this.flatOrbit.controls : this.orbit.controls
    controls.target.set(target.x, target.y, target.z)
    if (this.viewMode === 'flat' && zoom && Number.isFinite(zoom)) {
      this.flatCamera.zoom = THREE.MathUtils.clamp(zoom, this.flatOrbit.controls.minZoom, this.flatOrbit.controls.maxZoom)
      this.flatCamera.updateProjectionMatrix()
    }
    this.camera.position.set(position.x, position.y, position.z)
    this.camera.lookAt(target.x, target.y, target.z)
    // OrbitControls internally recomputes spherical state on update. In orthographic overview
    // mode that previously clamped a shared 2 km camera height to the perspective 1.5 km limit.
    if (this.viewMode !== 'flat') {
      controls.update()
    }
    this.requestRender()
  }

  /** 正在加载/排队中的 hires tile 数（加载进度指示用） */
  get pendingTiles(): number {
    return this.tileManager?.pendingCount ?? 0
  }

  get tileLoadStatus(): TileLoadStatus {
    return this.tileManager?.loadStatus ?? EMPTY_TILE_LOAD_STATUS
  }

  /** 截取当前画面并下载 PNG */
  captureScreenshot(filename = 'world-map.png'): void {
    this.renderer.render(this.scene, this.camera)
    this.renderer.domElement.toBlob((blob) => {
      if (!blob) {
        return
      }
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      a.click()
      URL.revokeObjectURL(url)
    }, 'image/png')
  }

  /** 标注点击拾取（二期 UI 入口；一期标注集为空，恒返回 null） */
  pickMarker(ndcX: number, ndcY: number): MarkerPickResult | null {
    return this.markerLayer.pick(ndcX, ndcY, this.camera)
  }

  setLayerVisible(setId: string, visible: boolean): void {
    this.markerLayer.setVisible(setId, visible)
    this.requestRender()
  }

  /** Moves the active camera to a marker's point or geometry center. */
  focusMarker(marker: MapMarker): boolean {
    const anchor = markerAnchor(marker)
    if (!anchor) {
      return false
    }
    if (this.viewMode === 'flat') {
      const height = this.flatCamera.position.y - this.flatOrbit.controls.target.y
      this.flatOrbit.controls.target.copy(anchor)
      this.flatCamera.position.set(anchor.x, anchor.y + height, anchor.z)
      this.flatCamera.lookAt(anchor)
      this.flatOrbit.controls.update()
    }
    else {
      const offset = this.perspectiveCamera.position.clone().sub(this.orbit.controls.target)
      const distance = Math.max(offset.length(), 72)
      offset.normalize().multiplyScalar(distance)
      this.orbit.controls.target.copy(anchor)
      this.perspectiveCamera.position.copy(anchor).add(offset)
      this.perspectiveCamera.lookAt(anchor)
      this.orbit.controls.update()
    }
    this.requestRender()
    return true
  }

  /** Returns the active map view to its published spawn without reloading map assets. */
  resetView(): boolean {
    if (!this.spawn) return false
    const spawn = this.spawn
    const perspective = perspectiveSpawnPosition(spawn)
    const flat = flatSpawnPosition(spawn)
    this.perspectiveCamera.position.set(perspective.x, perspective.y, perspective.z)
    this.perspectiveCamera.lookAt(spawn)
    this.orbit.controls.target.copy(spawn)
    if (this.mode === 'orbit' && this.viewMode === 'perspective') this.orbit.controls.update()
    this.flatCamera.position.set(flat.x, flat.y, flat.z)
    this.flatCamera.zoom = 1
    this.flatCamera.updateProjectionMatrix()
    this.flatCamera.lookAt(spawn)
    this.flatOrbit.controls.target.copy(spawn)
    if (this.viewMode === 'flat') this.flatOrbit.controls.update()
    this.requestRender()
    return true
  }

  private applyTimeOfDay(): void {
    const dayFactor = computeDayFactor(this.timeOfDay)
    // 天空背景色与雾色随时间变化
    this.skyColor.copy(NIGHT_SKY).lerp(DAY_SKY, dayFactor)
    this.scene.background = this.skyColor
    this.fog.color.copy(this.skyColor)
    if (this.material) {
      applyDayFactor(this.material, dayFactor)
    }
    if (this.translucentMaterial) {
      applyDayFactor(this.translucentMaterial, dayFactor)
    }
    if (this.blueMapMaterials) {
      applyBlueMapDayFactor(this.blueMapMaterials, dayFactor)
    }
    this.tileManager?.setDayFactor(dayFactor)
  }

  private disposeAtlas(): void {
    if (this.atlas) {
      this.disposeAtlasTexture(this.atlas)
      this.atlas = null
    }
  }

  private disposeAtlasTexture(texture: THREE.Texture): void {
    releaseLowresImage(texture.image as ImageBitmap | HTMLImageElement | null)
    texture.dispose()
  }

  private requestRender = (): void => {
    if (this.backgroundRenderTimer !== null) {
      window.clearTimeout(this.backgroundRenderTimer)
      this.backgroundRenderTimer = null
    }
    if (!this.disposed && this.isPageVisible && this.rafId === null) {
      this.rafId = requestAnimationFrame(this.renderFrame)
    }
  }

  /** Source initialization must draw once even if a browser has temporarily hidden this tab. */
  private forceRender(): void {
    if (this.backgroundRenderTimer !== null) {
      window.clearTimeout(this.backgroundRenderTimer)
      this.backgroundRenderTimer = null
    }
    if (this.disposed || this.rafId !== null) {
      return
    }
    if (!this.isPageVisible) {
      // Browsers may suppress RAF entirely for a newly-created background tab. Run the one
      // initialization frame synchronously so tile requests are not stranded until activation.
      this.renderFrame()
      return
    }
    if (!this.disposed && this.rafId === null) {
      this.rafId = requestAnimationFrame(this.renderFrame)
    }
  }

  /** Keeps background work at Minecraft's 20 TPS while input-driven camera changes remain immediate. */
  private scheduleBackgroundRender(): void {
    if (this.disposed || !this.isPageVisible || this.rafId !== null || this.backgroundRenderTimer !== null) {
      return
    }
    this.backgroundRenderTimer = window.setTimeout(() => {
      this.backgroundRenderTimer = null
      this.requestRender()
    }, BACKGROUND_FRAME_INTERVAL_MS)
  }

  private onVisibilityChange = (): void => {
    if (!this.isPageVisible) {
      if (this.rafId !== null) {
        cancelAnimationFrame(this.rafId)
        this.rafId = null
      }
      if (this.backgroundRenderTimer !== null) {
        window.clearTimeout(this.backgroundRenderTimer)
        this.backgroundRenderTimer = null
      }
      return
    }
    // Do not apply a long background-tab delta to fly movement or texture interpolation.
    this.lastTime = performance.now()
    this.forceRender()
  }

  private onCanvasClick = (event: MouseEvent): void => {
    if (this.mode !== 'orbit' || !this.options.onMarkerSelected) return
    const bounds = this.renderer.domElement.getBoundingClientRect()
    if (!bounds.width || !bounds.height) return
    const ndc = canvasPointerToNdc(event.clientX, event.clientY, bounds)
    this.options.onMarkerSelected(this.pickMarker(ndc.x, ndc.y))
  }

  private renderFrame = (): void => {
    if (this.disposed) {
      return
    }
    this.rafId = null
    const now = performance.now()
    const dt = Math.min((now - this.lastTime) / 1000, 0.1)
    this.lastTime = now

    const controller = this.controller
    const controllerMoving = controller.update(dt)
    // A generation can declare thousands of materials; only visible PRBM groups need animation work.
    this.tileManager?.stepVisibleBlueMapAnimations(dt * 1000)
    this.tileManager?.update(this.camera, controller.target)
    this.renderer.render(this.scene, this.camera)

    if (controllerMoving) {
      this.requestRender()
    }
    else if (needsBackgroundRender({
      pendingTiles: this.tileManager?.pendingCount ?? 0,
      animatedMaterials: this.tileManager?.hasActiveAnimations ?? false,
      pageVisible: this.isPageVisible,
    })) {
      this.scheduleBackgroundRender()
    }

    this.frameCounter += 1
    if (this.options.onCameraChanged && this.frameCounter % 6 === 0) {
      this.options.onCameraChanged(this.camera.position, controller.target)
    }
  }

  private resize(): void {
    const width = this.container.clientWidth
    const height = this.container.clientHeight
    if (!width || !height) {
      return
    }
    this.renderer.setPixelRatio(renderPixelRatio(window.devicePixelRatio, width, height))
    this.perspectiveCamera.aspect = width / height
    this.perspectiveCamera.updateProjectionMatrix()
    const halfHeight = FLAT_VIEW_HEIGHT * 0.5
    const halfWidth = halfHeight * (width / height)
    this.flatCamera.left = -halfWidth
    this.flatCamera.right = halfWidth
    this.flatCamera.top = halfHeight
    this.flatCamera.bottom = -halfHeight
    this.flatCamera.updateProjectionMatrix()
    this.renderer.setSize(width, height)
    this.requestRender()
  }

  dispose(): void {
    if (this.disposed) {
      return
    }
    this.disposed = true
    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId)
      this.rafId = null
    }
    if (this.backgroundRenderTimer !== null) {
      window.clearTimeout(this.backgroundRenderTimer)
      this.backgroundRenderTimer = null
    }
    this.resizeObserver.disconnect()
    this.renderer.domElement.removeEventListener('click', this.onCanvasClick)
    document.removeEventListener('visibilitychange', this.onVisibilityChange)
    this.orbit.controls.removeEventListener('change', this.requestRender)
    this.flatOrbit.controls.removeEventListener('change', this.requestRender)
    this.orbit.dispose()
    this.flatOrbit.dispose()
    this.fly.dispose()
    this.tileManager?.dispose()
    this.source?.dispose?.()
    this.markerLayer.dispose()
    this.material?.dispose()
    this.translucentMaterial?.dispose()
    if (this.blueMapMaterials) disposeBlueMapMaterials(this.blueMapMaterials)
    this.disposeAtlas()
    this.renderer.dispose()
    this.renderer.domElement.remove()
  }

  private get camera(): THREE.PerspectiveCamera | THREE.OrthographicCamera {
    return this.viewMode === 'flat' ? this.flatCamera : this.perspectiveCamera
  }

  private get isPageVisible(): boolean {
    return document.visibilityState !== 'hidden'
  }

  private get controller(): FlyController | OrbitController {
    if (this.viewMode === 'flat') {
      return this.flatOrbit
    }
    return this.mode === 'orbit' ? this.orbit : this.fly
  }

  private currentTarget(): THREE.Vector3 {
    if (this.viewMode === 'flat') {
      return this.flatOrbit.controls.target.clone()
    }
    if (this.mode === 'orbit') {
      return this.orbit.controls.target.clone()
    }
    const direction = new THREE.Vector3()
    this.perspectiveCamera.getWorldDirection(direction)
    return this.perspectiveCamera.position.clone().addScaledVector(direction, 60)
  }

  private syncControllerActivation(): void {
    this.orbit.setActive(this.viewMode === 'perspective' && this.mode === 'orbit')
    this.flatOrbit.setActive(this.viewMode === 'flat')
    this.fly.setActive(this.viewMode === 'perspective' && this.mode === 'fly')
  }

  private applyFogForViewMode(): void {
    const fog = fogForViewMode(this.viewMode)
    this.fog.near = fog.near
    this.fog.far = fog.far
  }
}
