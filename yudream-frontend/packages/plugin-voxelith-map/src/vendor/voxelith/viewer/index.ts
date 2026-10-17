// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * @yudream/voxelith-viewer —— VMC three.js 渲染核心。
 *
 * 当前：清单加载 + glb 瓦片流（TileManager）+ 三模式相机（自由飞行 / 第一人称 / 俯视倾斜）
 *      + Y 轴切片 + meshopt 压缩瓦片 + 标注渲染（MarkerLayer）。
 */
export { MapEngine, supportsDisplayP3, type MapEngineOptions } from "./engine/MapEngine.js";
export {
  bucketIntersectsSlice,
  currentYSlice,
  setYSlice,
  sliceContainsY,
  sliceRaycastTargets,
  YSliceUniforms,
  type YSlice,
} from "./engine/YSlice.js";
export {
  AdaptiveDistance,
  decideChunks,
  type AdaptiveDistanceOptions,
  type DecideResult,
} from "./engine/AdaptiveDistance.js";
export {
  cacheLimitsForTier,
  computeTier,
  detectDeviceProfile,
  initialViewDistanceChunks,
  scoreGpu,
  type DeviceProfile,
  type DeviceSignals,
  type DeviceTier,
} from "./engine/deviceTier.js";
export {
  evictionScore,
  retryDelayMs,
  TileManager,
  type TileManagerOptions,
} from "./tiles/TileManager.js";
export {
  configureHiresAtlas,
  configureLodColormap,
  disposeTileGroup,
  GlbTileLoader,
  LightingUniforms,
  resolveLodTexture,
  resolveTileTexture,
  TileGeometryError,
  validateTileGroup,
  type GlbTileLoaderOptions,
} from "./tiles/GlbTileLoader.js";
export { MarkerLayer, canvasTextureFactory, type MarkerPickResult, type MarkerTextureFactory } from "./markers/MarkerLayer.js";
export { FloatingOrigin } from "./engine/FloatingOrigin.js";
export { FreeFlightControls, type FreeFlightOptions } from "./controls/FreeFlightControls.js";
export {
  FirstPersonControls,
  type FirstPersonOptions,
  type TerrainMedium,
  type TerrainProbe,
} from "./controls/FirstPersonControls.js";
export { TiltOrbitControls, type TiltOrbitOptions } from "./controls/TiltOrbitControls.js";
export type { CameraControls, CameraMode, LookOptions } from "./controls/CameraControls.js";
export { loadManifest } from "./manifest/loadManifest.js";
