// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 自由飞行控制器：点击画布进入指针锁定，鼠标转视角，WASD 平移，Space/Ctrl 升降，滚轮调速。
 *
 * 视角带指数平滑（目标角 → 当前角按帧率无关的插值逼近），鼠标晃动不再"瞬移"。
 */
import * as THREE from "three";
import type { CameraControls, LookOptions } from "./CameraControls.js";

export interface FreeFlightOptions extends LookOptions {
  /** 初始基础速度（方块/秒） */
  speed?: number;
}

export class FreeFlightControls implements CameraControls {
  private readonly camera: THREE.PerspectiveCamera;
  private readonly canvas: HTMLCanvasElement;

  /** 基础移动速度（方块/秒），滚轮指数调节 */
  speed: number;
  /** 鼠标灵敏度（弧度/像素），可在设置面板实时调整 */
  sensitivity: number;

  private readonly keys = new Set<string>();
  private yaw = 0;
  private pitch = -0.4;
  private targetYaw = 0;
  private targetPitch = -0.4;
  private readonly euler = new THREE.Euler(0, 0, 0, "YXZ");
  private readonly onKeyDown = (e: KeyboardEvent) => this.keys.add(e.code);
  private readonly onKeyUp = (e: KeyboardEvent) => this.keys.delete(e.code);
  private readonly onMouseMove = (e: MouseEvent) => {
    if (document.pointerLockElement !== this.canvas) {
      return;
    }
    this.targetYaw -= e.movementX * this.sensitivity;
    this.targetPitch = THREE.MathUtils.clamp(
      this.targetPitch - e.movementY * this.sensitivity,
      -Math.PI / 2,
      Math.PI / 2,
    );
  };
  private readonly onWheel = (e: WheelEvent) => {
    this.speed = THREE.MathUtils.clamp(this.speed * (e.deltaY < 0 ? 1.2 : 1 / 1.2), 1, 500);
  };
  private readonly onClick = () => {
    if (document.pointerLockElement !== this.canvas) {
      void this.canvas.requestPointerLock();
    }
  };

  constructor(camera: THREE.PerspectiveCamera, canvas: HTMLCanvasElement, options: FreeFlightOptions = {}) {
    this.camera = camera;
    this.canvas = canvas;
    this.sensitivity = options.sensitivity ?? 0.0011;
    this.speed = options.speed ?? 20;
    // 以相机当前朝向初始化，避免切换模式瞬间跳视角
    this.euler.setFromQuaternion(camera.quaternion, "YXZ");
    this.yaw = this.targetYaw = this.euler.y;
    this.pitch = this.targetPitch = this.euler.x;
    window.addEventListener("keydown", this.onKeyDown);
    window.addEventListener("keyup", this.onKeyUp);
    window.addEventListener("mousemove", this.onMouseMove);
    canvas.addEventListener("wheel", this.onWheel, { passive: true });
    canvas.addEventListener("click", this.onClick);
  }

  /** 每帧调用。 */
  update(dtSeconds: number): void {
    // 指数平滑：k=18 时 60fps 下单帧收敛约 26%，既消抖又不拖沓
    const k = 1 - Math.exp(-18 * dtSeconds);
    this.yaw += (this.targetYaw - this.yaw) * k;
    this.pitch += (this.targetPitch - this.pitch) * k;
    this.euler.set(this.pitch, this.yaw, 0);
    this.camera.quaternion.setFromEuler(this.euler);

    const move = new THREE.Vector3();
    if (this.keys.has("KeyW")) move.z -= 1;
    if (this.keys.has("KeyS")) move.z += 1;
    if (this.keys.has("KeyA")) move.x -= 1;
    if (this.keys.has("KeyD")) move.x += 1;
    if (this.keys.has("Space")) move.y += 1;
    if (this.keys.has("ControlLeft") || this.keys.has("KeyC")) move.y -= 1;
    if (move.lengthSq() === 0) {
      return;
    }
    move.normalize().multiplyScalar(this.speed * (this.keys.has("ShiftLeft") ? 4 : 1) * dtSeconds);
    move.applyQuaternion(this.camera.quaternion);
    this.camera.position.add(move);
  }

  dispose(): void {
    window.removeEventListener("keydown", this.onKeyDown);
    window.removeEventListener("keyup", this.onKeyUp);
    window.removeEventListener("mousemove", this.onMouseMove);
    this.canvas.removeEventListener("wheel", this.onWheel);
    this.canvas.removeEventListener("click", this.onClick);
    if (document.pointerLockElement === this.canvas) {
      document.exitPointerLock();
    }
  }
}
