// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 俯视倾斜轨道控制器（BlueMap 式键位）：无指针锁定。
 *
 * 鼠标：
 * - 左键拖拽：平移目标点（移动，随距离缩放）
 * - 右键/中键拖拽：旋转 / 倾斜（方位角 + 俯仰角）
 * - 滚轮：缩放（指数调节相机到目标距离）
 *
 * 键盘：
 * - WASD / 方向键：前后左右平移
 * - 小键盘 +/- 或 Insert / Home：缩放
 * - Alt + WASD / 方向键：旋转 / 倾斜
 * - Delete / End：左右旋转；PageUp / PageDown：俯仰倾斜
 *
 * 触屏：
 * - 单指拖动：移动
 * - 双指捏合：缩放；双指旋转：转向；双指上下滑动：倾斜
 */
import * as THREE from "three";
import type { CameraControls } from "./CameraControls.js";

export interface TiltOrbitOptions {
  /** 初始观察目标（世界坐标），默认取相机注视点在地面的投影 */
  target?: THREE.Vector3;
  /** 最小/最大距离 */
  minDistance?: number;
  maxDistance?: number;
}

const MIN_ELEVATION = 0.05;
const MAX_ELEVATION = Math.PI / 2 - 0.01;

export class TiltOrbitControls implements CameraControls {
  private readonly camera: THREE.PerspectiveCamera;
  private readonly canvas: HTMLCanvasElement;

  readonly target = new THREE.Vector3();
  private distance = 120;
  /** 方位角（绕 Y 轴） */
  private azimuth = 0;
  /** 俯仰角（相对地面，0 = 平视，π/2 = 正俯视） */
  private elevation = Math.PI / 3;
  private readonly minDistance: number;
  private readonly maxDistance: number;

  private dragging: "pan" | "rotate" | null = null;
  private lastX = 0;
  private lastY = 0;

  /** 按下的键（e.code），失焦时清空 */
  private readonly keys = new Set<string>();
  private altDown = false;

  /** 触屏状态 */
  private touchMode: "pan" | "pinch" | null = null;
  private lastTouches: { x: number; y: number }[] = [];

  private static isEditableTarget(e: Event): boolean {
    const t = e.target as HTMLElement | null;
    return !!t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
  }

  private readonly onMouseDown = (e: MouseEvent) => {
    if (e.button === 0) {
      this.dragging = "pan";
    } else if (e.button === 1 || e.button === 2) {
      this.dragging = "rotate";
      e.preventDefault();
    }
    this.lastX = e.clientX;
    this.lastY = e.clientY;
  };
  private readonly onMouseMove = (e: MouseEvent) => {
    if (this.dragging === null) {
      return;
    }
    const dx = e.clientX - this.lastX;
    const dy = e.clientY - this.lastY;
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    if (this.dragging === "rotate") {
      this.rotateBy(dx, dy);
    } else {
      this.panBy(dx, dy);
    }
  };
  private readonly onMouseUp = () => {
    this.dragging = null;
  };
  private readonly onWheel = (e: WheelEvent) => {
    e.preventDefault();
    this.zoomBy(e.deltaY > 0 ? 1.15 : 1 / 1.15);
  };
  private readonly onContextMenu = (e: Event) => e.preventDefault();

  private readonly onKeyDown = (e: KeyboardEvent) => {
    if (TiltOrbitControls.isEditableTarget(e)) {
      return;
    }
    if (e.code === "AltLeft" || e.code === "AltRight") {
      this.altDown = true;
      e.preventDefault();
      return;
    }
    if (this.isHandledKey(e.code)) {
      this.keys.add(e.code);
      e.preventDefault();
    }
  };
  private readonly onKeyUp = (e: KeyboardEvent) => {
    if (e.code === "AltLeft" || e.code === "AltRight") {
      this.altDown = false;
      return;
    }
    this.keys.delete(e.code);
  };
  private readonly onBlur = () => {
    this.keys.clear();
    this.altDown = false;
    this.dragging = null;
  };

  private isHandledKey(code: string): boolean {
    switch (code) {
      case "KeyW":
      case "KeyA":
      case "KeyS":
      case "KeyD":
      case "ArrowUp":
      case "ArrowDown":
      case "ArrowLeft":
      case "ArrowRight":
      case "NumpadAdd":
      case "NumpadSubtract":
      case "Equal":
      case "Minus":
      case "Insert":
      case "Home":
      case "Delete":
      case "End":
      case "PageUp":
      case "PageDown":
        return true;
      default:
        return false;
    }
  }

  private readonly onTouchStart = (e: TouchEvent) => {
    e.preventDefault();
    this.touchMode = e.touches.length >= 2 ? "pinch" : "pan";
    this.lastTouches = this.snapshotTouches(e);
  };
  private readonly onTouchMove = (e: TouchEvent) => {
    e.preventDefault();
    if (this.touchMode === null) {
      return;
    }
    const touches = this.snapshotTouches(e);
    const c0 = touches[0];
    const c1 = touches[1];
    const p0 = this.lastTouches[0];
    const p1 = this.lastTouches[1];
    if (c0 && c1 && p0 && p1) {
      // 双指：捏合缩放 + 旋转转向 + 整体上下滑动倾斜
      const prevDist = Math.hypot(p0.x - p1.x, p0.y - p1.y);
      const curDist = Math.hypot(c0.x - c1.x, c0.y - c1.y);
      if (prevDist > 0 && curDist > 0) {
        this.zoomBy(prevDist / curDist);
      }
      const prevAngle = Math.atan2(p1.y - p0.y, p1.x - p0.x);
      const curAngle = Math.atan2(c1.y - c0.y, c1.x - c0.x);
      this.azimuth += curAngle - prevAngle;
      const avgDy = (c0.y + c1.y - p0.y - p1.y) / 2;
      this.elevation = THREE.MathUtils.clamp(this.elevation + avgDy * 0.006, MIN_ELEVATION, MAX_ELEVATION);
    } else if (c0 && p0 && !c1 && !p1) {
      this.panBy(c0.x - p0.x, c0.y - p0.y);
    }
    this.lastTouches = touches;
  };
  private readonly onTouchEnd = (e: TouchEvent) => {
    if (e.touches.length === 0) {
      this.touchMode = null;
      this.lastTouches = [];
    } else {
      this.touchMode = e.touches.length >= 2 ? "pinch" : "pan";
      this.lastTouches = this.snapshotTouches(e);
    }
  };

  private snapshotTouches(e: TouchEvent): { x: number; y: number }[] {
    const list: { x: number; y: number }[] = [];
    for (let i = 0; i < e.touches.length; i++) {
      const t = e.touches.item(i);
      if (t) {
        list.push({ x: t.clientX, y: t.clientY });
      }
    }
    return list;
  }

  /** 屏幕像素位移 → 地面平移（按当前方位角投影，随距离缩放）；0.0015 比初版慢约 1/3，拖拽更稳 */
  private panBy(dx: number, dy: number): void {
    const scale = this.distance * 0.0015;
    const forward = new THREE.Vector3(-Math.sin(this.azimuth), 0, -Math.cos(this.azimuth));
    const right = new THREE.Vector3(-forward.z, 0, forward.x);
    this.target.addScaledVector(right, -dx * scale);
    this.target.addScaledVector(forward, -dy * scale);
  }

  private rotateBy(dx: number, dy: number): void {
    this.azimuth -= dx * 0.006;
    this.elevation = THREE.MathUtils.clamp(this.elevation + dy * 0.006, MIN_ELEVATION, MAX_ELEVATION);
  }

  private zoomBy(factor: number): void {
    this.distance = THREE.MathUtils.clamp(this.distance * factor, this.minDistance, this.maxDistance);
  }

  constructor(camera: THREE.PerspectiveCamera, canvas: HTMLCanvasElement, options: TiltOrbitOptions = {}) {
    this.camera = camera;
    this.canvas = canvas;
    this.minDistance = options.minDistance ?? 4;
    this.maxDistance = options.maxDistance ?? 8000;
    if (options.target) {
      this.target.copy(options.target);
    } else {
      // 相机位置到地面的垂足作为默认目标
      this.target.set(camera.position.x, 0, camera.position.z);
    }
    const offset = new THREE.Vector3().subVectors(camera.position, this.target);
    const len = offset.length();
    if (len > 0.001) {
      this.distance = THREE.MathUtils.clamp(len, this.minDistance, this.maxDistance);
      this.elevation = THREE.MathUtils.clamp(Math.asin(offset.y / len), MIN_ELEVATION, MAX_ELEVATION);
      this.azimuth = Math.atan2(offset.x, offset.z);
    }
    canvas.addEventListener("mousedown", this.onMouseDown);
    window.addEventListener("mousemove", this.onMouseMove);
    window.addEventListener("mouseup", this.onMouseUp);
    canvas.addEventListener("wheel", this.onWheel, { passive: false });
    canvas.addEventListener("contextmenu", this.onContextMenu);
    window.addEventListener("keydown", this.onKeyDown);
    window.addEventListener("keyup", this.onKeyUp);
    window.addEventListener("blur", this.onBlur);
    canvas.addEventListener("touchstart", this.onTouchStart, { passive: false });
    canvas.addEventListener("touchmove", this.onTouchMove, { passive: false });
    canvas.addEventListener("touchend", this.onTouchEnd);
    canvas.addEventListener("touchcancel", this.onTouchEnd);
  }

  update(dtSeconds: number): void {
    const dt = Math.min(dtSeconds, 0.1);
    this.applyKeyboard(dt);
    const horizontal = Math.cos(this.elevation) * this.distance;
    this.camera.position.set(
      this.target.x + Math.sin(this.azimuth) * horizontal,
      this.target.y + Math.sin(this.elevation) * this.distance,
      this.target.z + Math.cos(this.azimuth) * horizontal,
    );
    this.camera.lookAt(this.target);
  }

  private applyKeyboard(dt: number): void {
    const k = this.keys;
    if (k.size === 0) {
      return;
    }
    const forward = new THREE.Vector3(-Math.sin(this.azimuth), 0, -Math.cos(this.azimuth));
    const right = new THREE.Vector3(-forward.z, 0, forward.x);

    const fwdDown = k.has("KeyW") || k.has("ArrowUp");
    const backDown = k.has("KeyS") || k.has("ArrowDown");
    const leftDown = k.has("KeyA") || k.has("ArrowLeft");
    const rightDown = k.has("KeyD") || k.has("ArrowRight");

    if (this.altDown) {
      // Alt + WASD / 方向键：旋转 / 倾斜
      const rotSpeed = 1.6;
      if (leftDown) this.azimuth += rotSpeed * dt;
      if (rightDown) this.azimuth -= rotSpeed * dt;
      if (fwdDown) this.elevation = this.clampElevation(this.elevation + rotSpeed * 0.7 * dt);
      if (backDown) this.elevation = this.clampElevation(this.elevation - rotSpeed * 0.7 * dt);
    } else {
      // WASD / 方向键：前后左右平移（速度随距离缩放）
      const moveSpeed = Math.max(this.distance * 1.1, 8);
      if (fwdDown) this.target.addScaledVector(forward, moveSpeed * dt);
      if (backDown) this.target.addScaledVector(forward, -moveSpeed * dt);
      if (leftDown) this.target.addScaledVector(right, -moveSpeed * dt);
      if (rightDown) this.target.addScaledVector(right, moveSpeed * dt);
    }

    // 缩放：小键盘 +/- 或 Insert / Home（主键盘 +/- 也支持）
    if (k.has("NumpadAdd") || k.has("Equal") || k.has("Insert")) {
      this.zoomBy(Math.exp(-1.6 * dt));
    }
    if (k.has("NumpadSubtract") || k.has("Minus") || k.has("Home")) {
      this.zoomBy(Math.exp(1.6 * dt));
    }

    // 旋转 / 倾斜：Delete / End 左右转，PageUp / PageDown 俯仰
    const rotSpeed = 1.6;
    if (k.has("Delete")) this.azimuth += rotSpeed * dt;
    if (k.has("End")) this.azimuth -= rotSpeed * dt;
    if (k.has("PageUp")) this.elevation = this.clampElevation(this.elevation + rotSpeed * 0.7 * dt);
    if (k.has("PageDown")) this.elevation = this.clampElevation(this.elevation - rotSpeed * 0.7 * dt);
  }

  private clampElevation(value: number): number {
    return THREE.MathUtils.clamp(value, MIN_ELEVATION, MAX_ELEVATION);
  }

  dispose(): void {
    this.canvas.removeEventListener("mousedown", this.onMouseDown);
    window.removeEventListener("mousemove", this.onMouseMove);
    window.removeEventListener("mouseup", this.onMouseUp);
    this.canvas.removeEventListener("wheel", this.onWheel);
    this.canvas.removeEventListener("contextmenu", this.onContextMenu);
    window.removeEventListener("keydown", this.onKeyDown);
    window.removeEventListener("keyup", this.onKeyUp);
    window.removeEventListener("blur", this.onBlur);
    this.canvas.removeEventListener("touchstart", this.onTouchStart);
    this.canvas.removeEventListener("touchmove", this.onTouchMove);
    this.canvas.removeEventListener("touchend", this.onTouchEnd);
    this.canvas.removeEventListener("touchcancel", this.onTouchEnd);
  }
}
