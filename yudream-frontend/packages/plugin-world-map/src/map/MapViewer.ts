import * as THREE from 'three'
import { FlyController } from './controls/FlyController'
import { OrbitController } from './controls/OrbitController'
import { applyDayFactor, computeDayFactor, createTerrainMaterial, createTranslucentTerrainMaterial } from './material'
import { MarkerLayer } from './MarkerLayer'
import type { MarkerPickResult } from './MarkerLayer'
import { TileManager } from './TileManager'
import type { CameraMode, WorldMapSource } from './types'

export interface MapViewerOptions {
  /** 相机位置变化回调（节流至约 10Hz），用于坐标显示 */
  onCameraChanged?: (position: THREE.Vector3, target: THREE.Vector3) => void
  hiresRadius?: number
  maxConcurrent?: number
}

const DAY_SKY = new THREE.Color(0x87a9d6)
const NIGHT_SKY = new THREE.Color(0x070a12)
/** 初始相机到 spawn 的水平距离 */
const SPAWN_VIEW_DISTANCE = 120

/**
 * 3D 地图查看器引擎（视觉对齐 BlueMap 5.x）：
 * WebGLRenderer + 场景雾 + 昼夜天空 + 双模式相机 + tile 调度 + 标注层。
 * 与 Vue 无关，由 composable 驱动生命周期，dispose() 完整释放资源。
 */
export class MapViewer {
  private readonly renderer: THREE.WebGLRenderer
  private readonly scene = new THREE.Scene()
  private readonly camera: THREE.PerspectiveCamera
  private readonly fog: THREE.Fog
  private readonly skyColor = new THREE.Color()
  private readonly orbit: OrbitController
  private readonly fly: FlyController
  private readonly markerLayer: MarkerLayer
  private readonly resizeObserver: ResizeObserver
  private mode: CameraMode = 'orbit'
  private tileManager: TileManager | null = null
  private material: THREE.ShaderMaterial | null = null
  private translucentMaterial: THREE.ShaderMaterial | null = null
  private atlas: THREE.Texture | null = null
  private source: WorldMapSource | null = null
  private rafId = 0
  private lastTime = performance.now()
  private frameCounter = 0
  private timeOfDay = 0.5
  private disposed = false

  constructor(
    private readonly container: HTMLElement,
    private readonly options: MapViewerOptions = {},
  ) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    this.renderer.setSize(Math.max(container.clientWidth, 1), Math.max(container.clientHeight, 1))
    container.appendChild(this.renderer.domElement)

    this.camera = new THREE.PerspectiveCamera(60, 1, 0.1, 4000)
    this.camera.position.set(0, 200, 0)

    this.fog = new THREE.Fog(0x000000, 200, 900)
    this.scene.fog = this.fog

    this.orbit = new OrbitController(this.camera, this.renderer.domElement)
    this.fly = new FlyController(this.camera, this.renderer.domElement)
    this.setCameraMode('orbit')

    this.markerLayer = new MarkerLayer(this.scene)

    this.resizeObserver = new ResizeObserver(() => this.resize())
    this.resizeObserver.observe(container)
    this.resize()

    this.applyTimeOfDay()
    this.rafId = requestAnimationFrame(this.loop)
  }

  /** 切换地图数据源：加载 settings + atlas，重建 tile 调度，相机定位到 spawn 上方斜视 45° */
  async setSource(source: WorldMapSource): Promise<void> {
    this.source = source
    this.tileManager?.dispose()
    this.tileManager = null
    this.material?.dispose()
    this.material = null
    this.translucentMaterial?.dispose()
    this.translucentMaterial = null
    this.atlas?.dispose()
    this.atlas = null

    const settings = await source.loadSettings()
    if (this.disposed || this.source !== source) {
      return
    }
    const atlas = await source.loadAtlas()
    if (this.disposed || this.source !== source) {
      atlas.dispose()
      return
    }
    this.atlas = atlas
    this.material = createTerrainMaterial(atlas)
    this.translucentMaterial = createTranslucentTerrainMaterial(atlas)
    this.applyTimeOfDay()
    this.tileManager = new TileManager(source, settings, this.material, this.translucentMaterial, this.scene, {
      hiresRadius: this.options.hiresRadius,
      maxConcurrent: this.options.maxConcurrent,
    })
    void this.markerLayer.load(source)

    // 初始相机：spawn 上方，方位 45°、俯仰 45° 斜视
    const { x, y, z } = settings.spawn
    const horizontal = SPAWN_VIEW_DISTANCE * Math.cos(Math.PI / 4)
    this.camera.position.set(
      x + horizontal * Math.sin(Math.PI / 4),
      y + SPAWN_VIEW_DISTANCE * Math.sin(Math.PI / 4),
      z + horizontal * Math.cos(Math.PI / 4),
    )
    this.camera.lookAt(x, y, z)
    this.orbit.controls.target.set(x, y, z)
    this.orbit.controls.update()
  }

  setCameraMode(mode: CameraMode): void {
    this.mode = mode
    this.orbit.setActive(mode === 'orbit')
    this.fly.setActive(mode === 'fly')
    if (mode === 'orbit') {
      // 飞行 → 轨道：把轨道目标放到视线前方，保持视角连续
      const direction = new THREE.Vector3()
      this.camera.getWorldDirection(direction)
      this.orbit.controls.target.copy(this.camera.position).addScaledVector(direction, 60)
    }
  }

  getCameraMode(): CameraMode {
    return this.mode
  }

  /** t ∈ [0,1]，0 = 午夜，0.5 = 正午 */
  setTimeOfDay(t: number): void {
    this.timeOfDay = THREE.MathUtils.clamp(t, 0, 1)
    this.applyTimeOfDay()
  }

  /** 读取当前视角（相机位置 + 当前控制器目标点） */
  getView(): { position: THREE.Vector3, target: THREE.Vector3 } {
    const controller = this.mode === 'orbit' ? this.orbit : this.fly
    return { position: this.camera.position.clone(), target: controller.target.clone() }
  }

  /** 恢复指定视角（用于 URL 视角分享还原） */
  setView(position: { x: number, y: number, z: number }, target: { x: number, y: number, z: number }): void {
    this.camera.position.set(position.x, position.y, position.z)
    this.orbit.controls.target.set(target.x, target.y, target.z)
    this.camera.lookAt(target.x, target.y, target.z)
    this.orbit.controls.update()
  }

  /** 正在加载/排队中的 hires tile 数（加载进度指示用） */
  get pendingTiles(): number {
    return this.tileManager?.pendingCount ?? 0
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
  }

  private loop = (): void => {
    if (this.disposed) {
      return
    }
    this.rafId = requestAnimationFrame(this.loop)
    const now = performance.now()
    const dt = Math.min((now - this.lastTime) / 1000, 0.1)
    this.lastTime = now

    const controller = this.mode === 'orbit' ? this.orbit : this.fly
    controller.update(dt)
    this.tileManager?.update(this.camera, controller.target)
    this.renderer.render(this.scene, this.camera)

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
    this.camera.aspect = width / height
    this.camera.updateProjectionMatrix()
    this.renderer.setSize(width, height)
  }

  dispose(): void {
    if (this.disposed) {
      return
    }
    this.disposed = true
    cancelAnimationFrame(this.rafId)
    this.resizeObserver.disconnect()
    this.orbit.dispose()
    this.fly.dispose()
    this.tileManager?.dispose()
    this.markerLayer.dispose()
    this.material?.dispose()
    this.translucentMaterial?.dispose()
    this.atlas?.dispose()
    this.renderer.dispose()
    this.renderer.domElement.remove()
  }
}
