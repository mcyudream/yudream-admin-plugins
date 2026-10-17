// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * `@yudream/voxelith-skyline` —— VMC 的远景 LOD / 天际线增强。
 *
 * 三件事：
 * 1. {@link planSkyline}：把层级按距离分成带重叠与滞回的「层带」，决定哪一层负责哪段距离；
 * 2. {@link SkylineLayer}：远景直接用 `lod-atlas.png` 的槽位做**平面 LOD 地毯**，
 *    不再为远处瓦片下载/解析 glb（UV 换算见 {@link lodAtlasUvRect}，与后端打包器同一套规则）；
 * 3. {@link SkylineHaze}：`detailDistance → farDistance` 的雾带，让地毯接缝与地图外缘连续淡出。
 *
 * 只依赖 three + core，不依赖 viewer：既能被应用壳直接用，也能被第三方查看器复用。
 */
export {
  dominantLevel,
  hazeBand,
  levelForFarDistance,
  planSkyline,
  skylineBands,
  type SkylineBand,
  type SkylinePolicyOptions,
} from "./policy.js";
export {
  levelTileBounds,
  levelTilesWithUv,
  lodAtlasUvRect,
  type LodAtlasUv,
} from "./lodAtlas.js";
export { SkylineHaze, type SkylineHazeOptions } from "./haze.js";
export { applyUvRect, SkylineLayer, type SkylineLayerOptions } from "./SkylineLayer.js";
