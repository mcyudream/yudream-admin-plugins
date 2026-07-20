import * as THREE from 'three'
import type { CameraController } from './CameraController'

const PITCH_LIMIT = Math.PI / 2 - 0.01
const MOUSE_SENSITIVITY = 0.0022
const BASE_SPEED = 24
const BOOST_SPEED = 90

/** 自由飞行模式：点击画面锁定指针，WASD + 鼠标视角，Shift 加速，Space/Q 升降，Esc 解锁 */
export class FlyController implements CameraController {
  private active = false
  private readonly keys = new Set<string>()
  private yaw = 0
  private pitch = 0
  private readonly euler = new THREE.Euler(0, 0, 0, 'YXZ')
  private readonly forward = new THREE.Vector3()
  private readonly right = new THREE.Vector3()
  private readonly move = new THREE.Vector3()
  private speedScale = 1

  constructor(
    private readonly camera: THREE.PerspectiveCamera,
    private readonly dom: HTMLElement,
    private readonly onChanged: () => void,
  ) {
    this.dom.addEventListener('click', this.onClick)
    document.addEventListener('mousemove', this.onMouseMove)
    document.addEventListener('pointerlockchange', this.onPointerLockChange)
    window.addEventListener('keydown', this.onKeyDown)
    window.addEventListener('keyup', this.onKeyUp)
  }

  get target(): THREE.Vector3 {
    return this.camera.position
  }

  setActive(active: boolean): void {
    this.active = active
    if (!active) {
      this.keys.clear()
      if (document.pointerLockElement === this.dom) {
        document.exitPointerLock()
      }
      return
    }
    // 从当前相机姿态同步 yaw/pitch，保证模式切换视角连续
    this.euler.setFromQuaternion(this.camera.quaternion, 'YXZ')
    this.yaw = this.euler.y
    this.pitch = this.euler.x
  }

  setSpeedScale(scale: number): void {
    this.speedScale = Number.isFinite(scale) ? THREE.MathUtils.clamp(scale, 1, 4) : 1
  }

  private get locked(): boolean {
    return document.pointerLockElement === this.dom
  }

  private onClick = (): void => {
    if (!this.active || this.locked) {
      return
    }
    try {
      const result = this.dom.requestPointerLock() as unknown
      if (result instanceof Promise) {
        result.catch(() => { /* 指针锁定被拒绝时忽略 */ })
      }
    }
    catch { /* 指针锁定不可用时忽略 */ }
  }

  private onPointerLockChange = (): void => {
    // 解锁瞬间清空按键，避免按键卡死
    this.keys.clear()
  }

  private onMouseMove = (event: MouseEvent): void => {
    if (!this.active || !this.locked) {
      return
    }
    this.yaw -= event.movementX * MOUSE_SENSITIVITY
    this.pitch = THREE.MathUtils.clamp(this.pitch - event.movementY * MOUSE_SENSITIVITY, -PITCH_LIMIT, PITCH_LIMIT)
    this.euler.set(this.pitch, this.yaw, 0, 'YXZ')
    this.camera.quaternion.setFromEuler(this.euler)
    this.onChanged()
  }

  private onKeyDown = (event: KeyboardEvent): void => {
    if (!this.active || !this.locked) {
      return
    }
    this.keys.add(event.code)
    if (event.code === 'Space') {
      event.preventDefault()
    }
  }

  private onKeyUp = (event: KeyboardEvent): void => {
    this.keys.delete(event.code)
  }

  update(dt: number): boolean {
    if (!this.active || !this.locked) {
      return false
    }
    const boost = this.keys.has('ShiftLeft') || this.keys.has('ShiftRight')
    const speed = (boost ? BOOST_SPEED : BASE_SPEED) * this.speedScale * dt
    this.camera.getWorldDirection(this.forward)
    this.right.crossVectors(this.forward, this.camera.up).normalize()
    this.move.set(0, 0, 0)
    if (this.keys.has('KeyW')) this.move.add(this.forward)
    if (this.keys.has('KeyS')) this.move.sub(this.forward)
    if (this.keys.has('KeyD')) this.move.add(this.right)
    if (this.keys.has('KeyA')) this.move.sub(this.right)
    if (this.keys.has('Space')) this.move.y += 1
    if (this.keys.has('KeyQ')) this.move.y -= 1
    if (this.move.lengthSq() > 0) {
      this.move.normalize().multiplyScalar(speed)
      this.camera.position.add(this.move)
      this.onChanged()
      return true
    }
    return false
  }

  dispose(): void {
    this.dom.removeEventListener('click', this.onClick)
    document.removeEventListener('mousemove', this.onMouseMove)
    document.removeEventListener('pointerlockchange', this.onPointerLockChange)
    window.removeEventListener('keydown', this.onKeyDown)
    window.removeEventListener('keyup', this.onKeyUp)
    this.keys.clear()
  }
}
