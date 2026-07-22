import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import type { CameraController } from './CameraController'
import { MAX_ORBIT_POLAR_ANGLE, MIN_ORBIT_POLAR_ANGLE } from './cameraAnglePolicy'

/** 轨道模式：绕目标旋转 / 缩放 / 平移，带阻尼惯性 */
export class OrbitController implements CameraController {
  readonly controls: OrbitControls

  constructor(camera: THREE.Camera, dom: HTMLElement) {
    this.controls = new OrbitControls(camera, dom)
    this.controls.enableDamping = true
    this.controls.dampingFactor = 0.08
    this.controls.screenSpacePanning = false
    this.controls.minDistance = 16
    this.controls.maxDistance = 224
    this.controls.minPolarAngle = MIN_ORBIT_POLAR_ANGLE
    this.controls.maxPolarAngle = MAX_ORBIT_POLAR_ANGLE
    // 滚轮朝光标位置缩放（BlueMap 同款手感）
    this.controls.zoomToCursor = true
    this.controls.listenToKeyEvents(dom)
    this.controls.enabled = false
  }

  get target(): THREE.Vector3 {
    return this.controls.target
  }

  setActive(active: boolean): void {
    this.controls.enabled = active
  }

  update(_dt: number): boolean {
    return this.controls.update()
  }

  dispose(): void {
    this.controls.stopListenToKeyEvents()
    this.controls.dispose()
  }
}
