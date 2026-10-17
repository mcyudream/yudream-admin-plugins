// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * LOD 图集页的槽位换算：瓦片 (level, x, z) → 该层 `lod-atlas.png` 上的 UV 矩形。
 *
 * 与后端 `LodAtlasPacker` **同一套规则**（改一处必须同步另一处）：
 * - 槽位 = `(tileX - 该层最小 tileX, tileZ - 该层最小 tileZ)`，行主序；
 * - 该层网格 = `(maxTileX - minTileX + 1) × (maxTileZ - minTileZ + 1)`；
 * - 页宽高 = 网格 × `slotSize`；
 * - UV 做**半纹素内缩**：线性过滤下采样点落在槽位边界会与相邻槽位插值出串色条纹。
 *
 * 有了它，远景就能直接用「图集页 + 平面地毯」画出天际线，
 * 不必为几十上百片远处瓦片各自下载并解析 glb。
 */
import type { MapManifest, ManifestTile } from "../core/index";

export interface LodAtlasUv {
  level: number;
  /** 该层图集页地址（相对地图根）。 */
  url: string;
  u0: number;
  v0: number;
  u1: number;
  v1: number;
  /** 槽位边长（像素），供需要对齐纹素时用。 */
  slotSize: number;
  /** 页宽高（像素）。 */
  pageWidth: number;
  pageHeight: number;
}

/** 某一层在清单里的瓦片范围（用于定位槽位）；该层没有瓦片时返回 null。 */
export function levelTileBounds(
  manifest: MapManifest,
  level: number,
): { minX: number; maxX: number; minZ: number; maxZ: number } | null {
  let minX = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let minZ = Number.POSITIVE_INFINITY;
  let maxZ = Number.NEGATIVE_INFINITY;
  for (const tile of manifest.tiles) {
    if (tile.level !== level) {
      continue;
    }
    minX = Math.min(minX, tile.x);
    maxX = Math.max(maxX, tile.x);
    minZ = Math.min(minZ, tile.z);
    maxZ = Math.max(maxZ, tile.z);
  }
  if (!Number.isFinite(minX)) {
    return null;
  }
  return { minX, maxX, minZ, maxZ };
}

/**
 * 瓦片在层图集页上的 UV 矩形；该层没有声明图集页（增量瓦片逐片内嵌色图）
 * 或该层没有瓦片时返回 null——调用方据此退回「不画远景」或直接加载 glb。
 */
export function lodAtlasUvRect(
  manifest: MapManifest,
  level: number,
  tileX: number,
  tileZ: number,
): LodAtlasUv | null {
  const page = manifest.lodAtlases.find((entry) => entry.level === level);
  if (!page) {
    return null;
  }
  const bounds = levelTileBounds(manifest, level);
  if (!bounds) {
    return null;
  }
  const col = tileX - bounds.minX;
  const row = tileZ - bounds.minZ;
  const gridWidth = bounds.maxX - bounds.minX + 1;
  const gridHeight = bounds.maxZ - bounds.minZ + 1;
  if (col < 0 || row < 0 || col >= gridWidth || row >= gridHeight) {
    return null;
  }
  const slot = page.slotSize;
  const pageWidth = gridWidth * slot;
  const pageHeight = gridHeight * slot;
  const inset = 0.5;
  return {
    level,
    url: page.url,
    slotSize: slot,
    pageWidth,
    pageHeight,
    u0: (col * slot + inset) / pageWidth,
    v0: (row * slot + inset) / pageHeight,
    u1: ((col + 1) * slot - inset) / pageWidth,
    v1: ((row + 1) * slot - inset) / pageHeight,
  };
}

/** 某层所有瓦片及其 UV（按 level 过滤，顺序稳定：先 z 后 x）。 */
export function levelTilesWithUv(manifest: MapManifest, level: number): Array<{
  tile: ManifestTile;
  uv: LodAtlasUv;
}> {
  const out: Array<{ tile: ManifestTile; uv: LodAtlasUv }> = [];
  for (const tile of manifest.tiles) {
    if (tile.level !== level) {
      continue;
    }
    const uv = lodAtlasUvRect(manifest, level, tile.x, tile.z);
    if (uv) {
      out.push({ tile, uv });
    }
  }
  return out.sort((a, b) => a.tile.z - b.tile.z || a.tile.x - b.tile.x);
}
