// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 自适应视距：渲染循环中按窗口统计 FPS，低于目标帧率缩小视距、高于目标视情况放大，
 * 步长 1 个区块并做平滑插值过渡；插值后的视距作为 TileManager 的细节视距——
 * 视距内细分到 hires 标准渲染，视距外不剔除、按距离逐级使用更粗 LOD。
 *
 * 帧率窗口与步进决策为纯函数（{@link decideChunks}），便于单测。
 */
import type { TileManager } from "../tiles/TileManager.js";

export interface AdaptiveDistanceOptions {
  tileManager: TileManager;
  /** 初始视距（区块，来自设备分档或手动设置） */
  initialChunks: number;
  /** 是否移动端：目标帧率 30，否则 60 */
  mobile?: boolean;
  /** 目标帧率（显式指定时覆盖 mobile 推导） */
  targetFps?: number;
  /** 视距下限（区块），默认 8 */
  minChunks?: number;
  /** 视距上限（区块），默认 32 */
  maxChunks?: number;
  /** 每次调整步长（区块），默认 1 */
  stepChunks?: number;
  /** 统计窗口（毫秒），默认 3000 */
  windowMs?: number;
  /** 当前视距变化回调（设置面板读数） */
  onChange?: (chunks: number) => void;
}

/** 单窗口决策结果。 */
export interface DecideResult {
  chunks: number;
  /** 连续达标窗口计数（放大需要连续 2 个窗口达标，防抖） */
  goodStreak: number;
}

/**
 * 视距步进决策：
 * - fps < 目标 × 0.85 → 立即缩 1 档（保帧率优先）；
 * - fps > 目标 × 0.97 且连续 2 个窗口达标 → 放大 1 档；
 * - 其余保持。结果夹在 [min, max]。
 */
export function decideChunks(
  current: number,
  fps: number,
  targetFps: number,
  goodStreak: number,
  min: number,
  max: number,
  step: number,
): DecideResult {
  if (fps < targetFps * 0.85) {
    return { chunks: Math.max(min, current - step), goodStreak: 0 };
  }
  if (fps > targetFps * 0.97) {
    const streak = goodStreak + 1;
    if (streak >= 2) {
      return { chunks: Math.min(max, current + step), goodStreak: 0 };
    }
    return { chunks: current, goodStreak: streak };
  }
  return { chunks: current, goodStreak: 0 };
}

export class AdaptiveDistance {
  private readonly tileManager: TileManager;
  private readonly targetFps: number;
  private readonly minChunks: number;
  private readonly maxChunks: number;
  private readonly stepChunks: number;
  private readonly windowMs: number;
  private readonly onChange?: (chunks: number) => void;

  private targetChunks: number;
  /** 插值中的当前视距（区块，浮点） */
  private currentChunks: number;
  private enabled = true;
  private frames = 0;
  private elapsedMs = 0;
  private goodStreak = 0;
  private lastReported = -1;

  constructor(options: AdaptiveDistanceOptions) {
    this.tileManager = options.tileManager;
    this.targetFps = options.targetFps ?? (options.mobile ? 30 : 60);
    this.minChunks = options.minChunks ?? 8;
    this.maxChunks = options.maxChunks ?? 32;
    this.stepChunks = options.stepChunks ?? 1;
    this.windowMs = options.windowMs ?? 3000;
    this.onChange = options.onChange;
    this.targetChunks = this.clamp(options.initialChunks);
    this.currentChunks = this.targetChunks;
    this.apply();
  }

  private clamp(chunks: number): number {
    return Math.min(this.maxChunks, Math.max(this.minChunks, chunks));
  }

  /** 自动调节开关。关闭时视距固定在当前目标值（手动滑杆入口）。 */
  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    this.goodStreak = 0;
  }

  /** 手动设定视距（自动模式关闭时使用）；自动模式下为临时目标。 */
  setChunks(chunks: number): void {
    this.targetChunks = this.clamp(chunks);
  }

  get chunks(): number {
    return this.currentChunks;
  }

  /**
   * 每帧钩子：统计 FPS、按窗口步进、向目标插值并应用。
   *
   * @param dtSeconds    已截断的时间步（视距插值用）
   * @param rawDtSeconds 真实帧间隔（FPS 统计用）。传已截断的值会让低帧率
   *   下的 elapsed 被人为压缩：4fps 会算出约 10fps，视距读数与放大判据同时失真。
   */
  update(dtSeconds: number, rawDtSeconds: number = dtSeconds): void {
    this.frames++;
    this.elapsedMs += rawDtSeconds * 1000;
    if (this.elapsedMs >= this.windowMs) {
      const fps = (this.frames * 1000) / this.elapsedMs;
      this.frames = 0;
      this.elapsedMs = 0;
      if (this.enabled) {
        const next = decideChunks(
          this.targetChunks,
          fps,
          this.targetFps,
          this.goodStreak,
          this.minChunks,
          this.maxChunks,
          this.stepChunks,
        );
        this.targetChunks = next.chunks;
        this.goodStreak = next.goodStreak;
      }
    }

    // 平滑插值：约 0.4s 收敛，足够接近时吸附，避免视距永远抖动
    const diff = this.targetChunks - this.currentChunks;
    if (diff !== 0) {
      this.currentChunks += diff * Math.min(1, dtSeconds * 2.5);
      if (Math.abs(this.targetChunks - this.currentChunks) < 0.02) {
        this.currentChunks = this.targetChunks;
      }
      this.apply();
    }
    if (this.onChange && Math.round(this.currentChunks) !== this.lastReported) {
      this.lastReported = Math.round(this.currentChunks);
      this.onChange(this.lastReported);
    }
  }

  /** 视距 → 瓦片细节视距（视距外逐级 LOD，不剔除）。 */
  private apply(): void {
    this.tileManager.setDetailDistanceBlocks(this.currentChunks * 16);
  }
}
