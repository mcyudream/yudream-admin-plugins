// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 相机控制器统一接口：每种模式一个实现，App 侧按模式切换实例。
 */
export interface CameraControls {
  /** 每帧调用（dt 单位秒）。 */
  update(dtSeconds: number): void;
  dispose(): void;
}

/** 相机模式标识（UI 切换用）。 */
export type CameraMode = "flight" | "firstPerson" | "tiltOrbit";

/** 视角相关公共可调参数。 */
export interface LookOptions {
  /** 鼠标灵敏度（弧度/像素），默认 0.0011 —— 约等于多数游戏的中等手感 */
  sensitivity?: number;
}
