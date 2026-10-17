// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 天际线层带策略：决定**哪一层 LOD 在负责哪段距离**，以及什么时候切换。
 *
 * 现有前端机制（`TileManager` 的四叉树 + 细节视距）解决的是「近处要多细」，
 * 远景真正的问题是**边界抖动**：相机稍微前推，某个层级就整片出现/消失，
 * 地平线上会闪一下。这里把层级按距离分成带宽重叠的「层带」，并引入滞回：
 *
 * - 每条层带负责 `[near, far)` 的水平距离区间，`far` 取 `detail * 2^(level+1)`；
 * - 相邻层带之间有 `overlap` 重叠（默认 25%），切换发生在重叠区里；
 * - 已经启用的层要退出需要跨过 `far * (1 + hysteresis)`，避免在阈值上来回抖。
 *
 * 纯函数、无 three 依赖，所以可以直接单测边界行为。
 */

export interface SkylineBand {
  level: number;
  /** 该层负责的最近水平距离（方块）。 */
  near: number;
  /** 该层负责的最远水平距离（方块，已夹到 farDistance）。 */
  far: number;
  /** 该层瓦片的世界边长（方块）。 */
  sizeBlocks: number;
  /** 屏幕空间误差（方块）：等于边长，供 3D Tiles / 渲染器换算。 */
  geometricError: number;
  /** 这一层是否应该参与渲染（按当前相机距离 + 滞回判定）。 */
  active: boolean;
}

export interface SkylinePolicyOptions {
  /** hires 瓦片边长（方块），层级 L 的边长 = hiresTileSize × 2^L。 */
  hiresTileSize: number;
  /** 最粗层级（含）。 */
  maxLevel: number;
  /** 细节视距：这个距离内由 hires（level 0）负责。 */
  detailDistance: number;
  /** 远景最大距离：超过就不再画任何 LOD。 */
  farDistance: number;
  /** 层带重叠比例（0~0.5，默认 0.25）。 */
  overlap?: number;
  /** 退出滞回比例（默认 0.15）：已启用的层要跨过 far×(1+该值) 才关闭。 */
  hysteresis?: number;
}

/** 计算各层层带（不含 active，纯几何）。 */
export function skylineBands(options: SkylinePolicyOptions): Omit<SkylineBand, "active">[] {
  const overlap = clamp(options.overlap ?? 0.25, 0, 0.5);
  const bands: Omit<SkylineBand, "active">[] = [];
  for (let level = 0; level <= options.maxLevel; level++) {
    const size = options.hiresTileSize * 2 ** level;
    const near = level === 0 ? 0 : Math.max(0, options.detailDistance * 2 ** level * (1 - overlap));
    const far = Math.min(options.farDistance, options.detailDistance * 2 ** (level + 1));
    bands.push({ level, near, far, sizeBlocks: size, geometricError: size });
  }
  return bands;
}

/**
 * 按相机到地图中心的水平距离，决定各层是否参与渲染。
 *
 * @param distanceFromCenter 相机到图层中心的水平距离（方块）
 * @param previous 上一帧的层带（用于滞回）；不传则按纯阈值判定
 */
export function planSkyline(
  options: SkylinePolicyOptions,
  distanceFromCenter: number,
  previous?: readonly SkylineBand[],
): SkylineBand[] {
  const hysteresis = options.hysteresis ?? 0.15;
  const previousByLevel = new Map((previous ?? []).map((band) => [band.level, band]));
  return skylineBands(options).map((band) => {
    const wasActive = previousByLevel.get(band.level)?.active ?? false;
    if (band.far <= 0) {
      return { ...band, active: false };
    }
    // 进入：距离落在带宽内（含重叠）；退出：要越过 far×(1+滞回)
    const enter = distanceFromCenter >= band.near * (1 - hysteresis)
      && distanceFromCenter <= band.far;
    const stay = wasActive && distanceFromCenter <= band.far * (1 + hysteresis);
    return { ...band, active: enter || stay };
  });
}

/** 当前应该作为「远景主层」的那一层：active 里最粗的一层。 */
export function dominantLevel(bands: readonly SkylineBand[]): number | null {
  const active = bands.filter((band) => band.active);
  if (active.length === 0) {
    return null;
  }
  return active.reduce((best, band) => (band.level > best ? band.level : best), active[0]!.level);
}

/**
 * 地平线地毯选层：让最远距离大约由 `tilesAcross` 片瓦片铺满。
 *
 * 直觉：远景只需要「一眼看出哪里是城市、哪里是山」的色块密度。
 * 铺满远景带用 8 片左右最合适——太少（1~2 片）会糊成一整块，
 * 太多（几十片）就退化成逐瓦片渲染，白拿不回 draw call 的便宜。
 *
 * ```
 * 目标边长 = farDistance / tilesAcross
 * level   = ceil(log2(目标边长 / hiresTileSize))，夹到 [0, maxLevel]
 * ```
 */
export function levelForFarDistance(
  hiresTileSize: number,
  farDistance: number,
  maxLevel: number,
  tilesAcross = 8,
): number {
  if (farDistance <= 0 || hiresTileSize <= 0) {
    return 0;
  }
  const target = farDistance / Math.max(1, tilesAcross);
  const raw = Math.ceil(Math.log2(Math.max(1, target) / hiresTileSize));
  return Math.min(Math.max(0, raw), Math.max(0, maxLevel));
}

/** 远景雾化区间：只作用在「细节视距之外、最远距离之内」的地平线带。 */
export function hazeBand(options: SkylinePolicyOptions): { near: number; far: number } {
  const near = Math.max(1, options.detailDistance);
  return { near, far: Math.max(near + 1, options.farDistance) };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
