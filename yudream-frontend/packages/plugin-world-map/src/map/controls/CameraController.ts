import type { Vector3 } from 'three'

/** 相机控制器统一接口（轨道 / 自由飞行） */
export interface CameraController {
  /** 相机关注锚点：轨道模式为目标点，飞行模式为相机自身位置 */
  readonly target: Vector3
  setActive: (active: boolean) => void
  update: (dt: number) => void
  dispose: () => void
}
