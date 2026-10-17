/**
 * 二维（正俯视）相机控制器：垂直向下、北朝上，拖拽平移、滚轮升降。
 *
 * <p>「二维地图展示」的实现方式：不另做一套贴图管线，而是把同一批瓦片放到正俯视机位下看——
 * 得到一张平面地图（贴图、光照与 3D 视图完全一致），且标注、坐标、点击取点这些能力全部复用，
 * 不需要额外的服务端产物（例如先烘焙全景图才有俯视底图那种前置条件）。</p>
 *
 * <p>实现 `CameraControls` 接口，因此能直接替换 `VoxelithViewer` 里的控制器：引擎的每帧循环照常
 * 调 {@code update()}，切模式、改视距、Y 轴切片等既有逻辑都不用改。</p>
 */
import * as THREE from 'three'
import type { CameraControls } from '../vendor/voxelith/viewer/controls/CameraControls'

export interface TopDownOptions {
  /** 视点中心（世界 XZ），默认取相机当前位置 */
  centerX?: number
  centerZ?: number
  /** 相机离地高度（世界 Y 之上再加视距），默认取相机当前高度 */
  height?: number
}

const MIN_HEIGHT = 8
const MAX_HEIGHT = 6000

export class TopDownControls implements CameraControls {
  private readonly camera: THREE.PerspectiveCamera
  private readonly canvas: HTMLCanvasElement
  private centerX: number
  private centerZ: number
  private height: number

  /** 视线焦点高度（地表附近），由调用方给出，决定 lookAt 的 Y */
  private focusY = 0

  private dragging = false
  private lastX = 0
  private lastY = 0

  constructor(camera: THREE.PerspectiveCamera, canvas: HTMLCanvasElement, options: TopDownOptions = {}) {
    this.camera = camera
    this.canvas = canvas
    this.centerX = options.centerX ?? camera.position.x
    this.centerZ = options.centerZ ?? camera.position.z
    this.height = options.height ?? Math.max(MIN_HEIGHT, camera.position.y)

    // 北朝上：相机 up 指向 -Z（Minecraft 的北），于是屏幕上方就是北，横向是东西
    camera.up.set(0, 0, -1)
    this.apply()

    canvas.addEventListener('pointerdown', this.onPointerDown)
    canvas.addEventListener('wheel', this.onWheel, { passive: false })
    window.addEventListener('pointermove', this.onPointerMove)
    window.addEventListener('pointerup', this.onPointerUp)
  }

  /** 视点中心的世界坐标（供 HUD 显示与传送用）。 */
  getCenter(): { x: number, z: number } {
    return { x: this.centerX, z: this.centerZ }
  }

  /** 把视点移到某处（传送/定位）。 */
  moveTo(x: number, z: number): void {
    this.centerX = x
    this.centerZ = z
    this.apply()
  }

  /** 设置焦点高度（地表 Y）：正俯视下它只影响相机的高度基准，不影响观感方向。 */
  setFocusY(y: number): void {
    this.focusY = y
    this.apply()
  }

  update(_dtSeconds: number): void {
    this.apply()
  }

  dispose(): void {
    this.canvas.removeEventListener('pointerdown', this.onPointerDown)
    this.canvas.removeEventListener('wheel', this.onWheel)
    window.removeEventListener('pointermove', this.onPointerMove)
    window.removeEventListener('pointerup', this.onPointerUp)
    // 交还给其它模式：相机 up 恢复竖直，否则切回飞行模式后整个世界都是歪的
    this.camera.up.set(0, 1, 0)
  }

  private apply(): void {
    this.camera.position.set(this.centerX, this.focusY + this.height, this.centerZ)
    this.camera.lookAt(this.centerX, this.focusY, this.centerZ)
  }

  private readonly onPointerDown = (e: PointerEvent): void => {
    if (e.button !== 0) return
    this.dragging = true
    this.lastX = e.clientX
    this.lastY = e.clientY
    this.canvas.setPointerCapture?.(e.pointerId)
    e.preventDefault()
  }

  private readonly onPointerMove = (e: PointerEvent): void => {
    if (!this.dragging) return
    const dx = e.clientX - this.lastX
    const dy = e.clientY - this.lastY
    this.lastX = e.clientX
    this.lastY = e.clientY
    // 屏幕位移换算到世界位移：按相机高度与竖直视场角推算每像素覆盖的世界长度
    const worldPerPixel = (2 * this.height * Math.tan(THREE.MathUtils.degToRad(this.camera.fov) / 2))
      / Math.max(1, this.canvas.clientHeight)
    this.centerX -= dx * worldPerPixel
    this.centerZ -= dy * worldPerPixel
    this.apply()
  }

  private readonly onPointerUp = (e: PointerEvent): void => {
    if (!this.dragging) return
    this.dragging = false
    this.canvas.releasePointerCapture?.(e.pointerId)
  }

  private readonly onWheel = (e: WheelEvent): void => {
    e.preventDefault()
    this.height = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, this.height * Math.exp(e.deltaY * 0.0012)))
    this.apply()
  }
}
