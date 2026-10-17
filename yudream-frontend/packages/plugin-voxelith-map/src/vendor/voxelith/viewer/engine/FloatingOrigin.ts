// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 浮点原点（camera-relative rendering / floating origin）。
 *
 * 精度模型：顶点缓冲永远是瓦片局部坐标（float32，最大跨度为瓦片边长），
 * three.js 在 CPU 侧以 float64 组合 modelViewMatrix 后按 float32 上传 ——
 * GPU 看到的始终是相机相对的小数值，因此世界坐标在 ±2^24 以内天然安全。
 * 本类是边疆量级（|x| 或 |z| 超过 threshold，默认 1M 方块）的保险：
 *
 * 标准范式：
 * - 世界坐标（瓦片包围盒、清单 bounds、玩家逻辑位置）始终保持 float64 语义；
 * - 渲染空间 = 世界空间 - origin；相机与瓦片 group.position 都存渲染空间坐标；
 * - 相机离开原点超过 threshold 时重定基：origin 按 snap 网格步进，相机与场景
 *   顶层节点同步平移相反量，逻辑层（TileManager）经 worldOffset 还原世界坐标。
 *
 * 一次重定基只移动顶层节点（O(瓦片数) 次向量减法），顶点缓冲与着色器完全不动。
 */
import * as THREE from "three";

export class FloatingOrigin {
  /** 当前渲染原点（世界空间）。渲染坐标 + origin = 世界坐标。 */
  readonly origin = new THREE.Vector3();

  constructor(
    /** 触发重定基的坐标阈值（方块）。默认 2^20 ≈ 105 万，远超常规活动范围。 */
    private readonly threshold = 1 << 20,
    /** 重定基步进网格：吸附到网格避免跨阈值抖动。 */
    private readonly snap = 1024,
  ) {}

  /**
   * 每帧在控制器更新后、瓦片调度前调用。
   * 超过阈值时执行重定基并返回本次平移量（世界空间增量），否则返回 null。
   * 调用方需把返回值同步给持有世界空间目标的组件（如俯视控制器的 target）。
   */
  maybeRebase(camera: THREE.Camera, scene: THREE.Scene): THREE.Vector3 | null {
    const p = camera.position;
    if (Math.max(Math.abs(p.x), Math.abs(p.z)) < this.threshold) {
      return null;
    }
    const delta = new THREE.Vector3(
      Math.round(p.x / this.snap) * this.snap,
      0,
      Math.round(p.z / this.snap) * this.snap,
    );
    for (const child of scene.children) {
      child.position.sub(delta);
      if (!child.matrixAutoUpdate) {
        child.updateMatrix();
      }
    }
    camera.position.sub(delta);
    this.origin.add(delta);
    return delta;
  }

  /** 换图/回出生点时归零（场景与相机随即按世界坐标重建）。 */
  reset(): void {
    this.origin.set(0, 0, 0);
  }
}
