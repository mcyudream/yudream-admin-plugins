// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 远景雾化：把地平线一带的几何连续淡入背景色。
 *
 * 为什么用 three 自带的 `Fog` 而不是自写着色器补丁：
 * 1. 雾是按**片元到相机的距离**连续插值的，天然不产生边界（正是远端 LOD 需要的效果）；
 * 2. 内置材质默认 `fog = true`，VMC 前端那些 `onBeforeCompile` 过的材质也照样吃雾，
 *    不需要为每个材质再写一遍 shader 补丁；
 * 3. `scene.fog` 是渲染器状态，切换/销毁都能干净还原。
 *
 * 与既有「无雾效遮掩 LOD 边界」的关系：那条说的是**中距离**的层级切换不靠雾遮掩
 * （层级本身逐级过渡）；这里的雾只作用在 `detailDistance → farDistance` 的地平线带，
 * 用于让远景地毯与真实几何的接缝、以及地图边缘自然消失。
 */
import * as THREE from "three";
import type { SkylinePolicyOptions } from "./policy.js";
import { hazeBand } from "./policy.js";

export interface SkylineHazeOptions {
  /** 雾色（一般取场景背景色，才看不出边界）。 */
  color: THREE.ColorRepresentation;
  /** 雾起点（方块，距相机）；只影响这段之外 */
  near: number;
  /** 完全变成雾色的距离（方块） */
  far: number;
}

export class SkylineHaze {
  readonly fog: THREE.Fog;
  private previous: THREE.Fog | THREE.FogExp2 | null = null;
  private appliedTo: THREE.Scene | null = null;

  constructor(options: SkylineHazeOptions) {
    this.fog = new THREE.Fog(options.color, Math.max(0, options.near), Math.max(options.near + 1, options.far));
  }

  /** 按层带策略生成雾（细节视距之外 → 最远距离）。 */
  static fromPolicy(options: SkylinePolicyOptions, color: THREE.ColorRepresentation): SkylineHaze {
    const band = hazeBand(options);
    return new SkylineHaze({ color, near: band.near, far: band.far });
  }

  /** 更新雾带（相机移动/视距变化时调用；不重建雾对象，避免材质重编译）。 */
  setBand(near: number, far: number): void {
    this.fog.near = Math.max(0, near);
    this.fog.far = Math.max(this.fog.near + 1, far);
  }

  setColor(color: THREE.ColorRepresentation): void {
    this.fog.color.set(color);
  }

  /** 挂到场景（记录原雾，便于 dispose 还原）。 */
  apply(scene: THREE.Scene): void {
    if (this.appliedTo === scene) {
      return;
    }
    if (this.appliedTo) {
      this.dispose();
    }
    this.previous = scene.fog ?? null;
    scene.fog = this.fog;
    this.appliedTo = scene;
  }

  dispose(scene?: THREE.Scene): void {
    const target = scene ?? this.appliedTo;
    if (target && this.appliedTo === target) {
      target.fog = this.previous;
    }
    this.appliedTo = null;
    this.previous = null;
  }
}
