// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 引擎核心：渲染循环 + 相机 + 视口自适应 + 帧更新钩子（瓦片调度/控制器挂接点）。
 */
import * as THREE from "three";
import { setP3OutputTransform, supportsDisplayP3, tryEnableP3DrawingBuffer } from "./wideGamut.js";

export interface MapEngineOptions {
  canvas: HTMLCanvasElement;
  background?: number;
  /**
   * 广色域输出：Display P3。基准始终为 sRGB（创作与输出的默认色彩空间），
   * 仅在屏幕与 WebGL 实现都支持 P3 时生效（见 {@link supportsDisplayP3}），否则静默回退 sRGB。
   */
  displayP3?: boolean;
}

export { supportsDisplayP3 };

/** 每帧更新钩子：dtSeconds 已截断（移动积分），rawDtSeconds 为真实间隔（FPS 统计）。 */
export type FrameHook = (dtSeconds: number, rawDtSeconds: number) => void;

export class MapEngine {
  readonly renderer: THREE.WebGLRenderer;
  readonly scene: THREE.Scene;
  readonly camera: THREE.PerspectiveCamera;

  private readonly resizeObserver: ResizeObserver;
  private readonly frameHooks = new Set<FrameHook>();
  private lastFrameTime = performance.now();
  private disposed = false;
  private displayP3: boolean;

  constructor(options: MapEngineOptions) {
    this.renderer = new THREE.WebGLRenderer({
      canvas: options.canvas,
      antialias: true,
      powerPreference: "high-performance",
    });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    // 输出基准 sRGB（three 默认 outputColorSpace=SRGBColorSpace）；
    // 广色域设备可选 P3：后备缓冲切 display-p3 + 片元输出插入基色矩阵（见 wideGamut.ts）
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.displayP3 = false;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(options.background ?? 0x87ceeb);

    this.camera = new THREE.PerspectiveCamera(75, 1, 0.1, 100000);
    this.camera.position.set(0, 90, 24);
    this.camera.lookAt(0, 64, 0);

    this.resizeObserver = new ResizeObserver(() => this.resizeToCanvas());
    this.resizeObserver.observe(options.canvas);
    this.resizeToCanvas();

    this.setDisplayP3(options.displayP3 === true);

    this.renderer.setAnimationLoop(() => {
      if (!this.disposed) {
        this.render();
      }
    });
  }

  /**
   * 注册每帧更新钩子（如 FreeFlightControls.update、TileManager.update）。
   *
   * @param hook 收到两个时间步：`dtSeconds` 已把超长帧截到 0.1s（供相机/移动积分，
   *   避免切回标签页时一帧瞬移），`rawDtSeconds` 是真实间隔（供 FPS 统计，
   *   否则低帧率下 elapsed 被人为压缩、测得的帧率会偏高）。
   */
  addFrameHook(hook: (dtSeconds: number, rawDtSeconds: number) => void): void {
    this.frameHooks.add(hook);
  }

  removeFrameHook(hook: (dtSeconds: number, rawDtSeconds: number) => void): void {
    this.frameHooks.delete(hook);
  }

  /**
   * 切换 Display P3 广色域输出。片元输出变换参与着色器程序缓存键，
   * 切换后全部材质重编译；不支持的设备调用无效（保持 sRGB）。
   */
  setDisplayP3(enabled: boolean): void {
    const next = enabled && supportsDisplayP3() && tryEnableP3DrawingBuffer(this.renderer);
    if (next === this.displayP3) {
      return;
    }
    this.displayP3 = next;
    setP3OutputTransform(next);
    this.scene.traverse((node) => {
      if (node instanceof THREE.Mesh) {
        (node.material as THREE.Material).needsUpdate = true;
      }
    });
  }

  get isDisplayP3(): boolean {
    return this.displayP3;
  }

  render(): void {
    const now = performance.now();
    const rawDt = (now - this.lastFrameTime) / 1000;
    const dt = Math.min(rawDt, 0.1);
    this.lastFrameTime = now;
    for (const hook of this.frameHooks) {
      hook(dt, rawDt);
    }
    this.renderer.render(this.scene, this.camera);
  }

  resizeToCanvas(): void {
    const canvas = this.renderer.domElement;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (width === 0 || height === 0) {
      return;
    }
    this.renderer.setSize(width, height, false);
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
  }

  dispose(): void {
    this.disposed = true;
    if (this.displayP3) {
      // 输出 chunk 补丁是全局的，引擎销毁时还原以免影响其他实例
      setP3OutputTransform(false);
      this.displayP3 = false;
    }
    this.resizeObserver.disconnect();
    this.renderer.setAnimationLoop(null);
    this.renderer.dispose();
  }
}
