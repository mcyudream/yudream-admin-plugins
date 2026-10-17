// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * Yudream Voxelith Map Core（VMC）传输协议定义。
 *
 * 与后端 tile-context 的 FileManifestPublisher 产物及 docs/protocol/ 文档保持同步。
 * 任何协议变更必须三方（本文档 / 后端 schema / 文档）同步提交。
 */
import { z } from "zod";

// ---------------------------------------------------------------------------
// 清单（manifest）
// ---------------------------------------------------------------------------

/** 协议格式版本，破坏性变更时递增。 */
export const MANIFEST_FORMAT_VERSION = 1;

const vec3Array = z.tuple([z.number(), z.number(), z.number()]);

export const mapSettingsSchema = z.object({
  /** hires 瓦片边长（方块） */
  hiresTileSize: z.number().int().positive(),
  /** LOD 层级数（1 = 仅 hires 层；N>1 = hires + lod 金字塔层 1..N-1） */
  lodCount: z.number().int().min(1),
});
export type MapSettings = z.infer<typeof mapSettingsSchema>;

export const atlasRefSchema = z.object({
  /** 相对地图根的图集地址 */
  url: z.string().min(1),
  /** 图集宽（像素） */
  size: z.number().int().positive(),
  /**
   * 图集高（像素）。缺省 = 等于宽度（正方形）。
   * 增量扩图集向下加行后高度会大于宽度：老单元格 UV 不动，新贴图落在新增的行里。
   */
  height: z.number().int().positive().optional(),
  /** 入集贴图数（含兜底格） */
  textureCount: z.number().int().min(1),
});
export type AtlasRef = z.infer<typeof atlasRefSchema>;

/**
 * LOD 层级共享图集页：该层全部瓦片的航拍色图拼成一页，瓦片 glb 只带指向本页的 UV。
 * 前端每层加载一张纹理即可，无需逐瓦片解码色图。
 */
export const lodAtlasRefSchema = z.object({
  /** LOD 层级（≥ 1） */
  level: z.number().int().min(1),
  /** 相对地图根的图集页地址 */
  url: z.string().min(1),
  /** 槽位边长（像素） */
  slotSize: z.number().int().positive(),
  /** 页内容哈希：拼瓦片 URL 的缓存版本戳 */
  sha1: z.string().min(1),
});
export type LodAtlasRef = z.infer<typeof lodAtlasRefSchema>;

/** 单个瓦片的索引项。sha1 为内容哈希，用于前端缓存失效与增量更新比对。 */
export const manifestTileSchema = z.object({
  level: z.number().int().min(0),
  x: z.number().int(),
  z: z.number().int(),
  /** 相对地图根的瓦片地址 */
  url: z.string().min(1),
  sha1: z.string().min(1),
  bytes: z.number().int().min(0),
  quads: z.number().int().min(0),
  min: vec3Array,
  max: vec3Array,
});
export type ManifestTile = z.infer<typeof manifestTileSchema>;

export const mapManifestSchema = z.object({
  formatVersion: z.number().int().min(1),
  mapId: z.string().min(1),
  name: z.string(),
  /** 内容版本：全部瓦片 sha1 的聚合哈希，任一瓦片变化则变化 */
  version: z.string().min(1),
  generatedAt: z.string(),
  settings: mapSettingsSchema,
  boundsMin: vec3Array,
  boundsMax: vec3Array,
  atlas: atlasRefSchema,
  /** LOD 每层共享图集页；缺省/空数组 = 该层瓦片各自内嵌色图（老清单与增量瓦片） */
  lodAtlases: z.array(lodAtlasRefSchema).default([]),
  tiles: z.array(manifestTileSchema),
});
export type MapManifest = z.infer<typeof mapManifestSchema>;

export function parseManifest(raw: unknown): MapManifest {
  const manifest = mapManifestSchema.parse(raw);
  if (manifest.formatVersion > MANIFEST_FORMAT_VERSION) {
    throw new Error(
      `清单格式版本 ${manifest.formatVersion} 高于前端支持的 ${MANIFEST_FORMAT_VERSION}`,
    );
  }
  return manifest;
}

/** 瓦片世界原点（瓦片局部坐标的偏移量）。层级 L 瓦片边长 = hiresTileSize × 2^L。 */
export function tileWorldOrigin(manifest: MapManifest, tile: ManifestTile): [number, number, number] {
  const size = manifest.settings.hiresTileSize * 2 ** tile.level;
  return [tile.x * size, 0, tile.z * size];
}

/** 瓦片 URL 模板（相对地图根）：hires 与 lod 共用。 */
export function tileUrl(mapBaseUrl: string, level: number, x: number, z: number): string {
  return level === 0
    ? `${mapBaseUrl}/tiles/hires/${x}/${z}.glb`
    : `${mapBaseUrl}/tiles/lod/${level}/${x}/${z}.glb`;
}

// ---------------------------------------------------------------------------
// 标注（marker）
// ---------------------------------------------------------------------------

export const vec3Schema = z.object({ x: z.number(), y: z.number(), z: z.number() });
export type Vec3 = z.infer<typeof vec3Schema>;

export const markerStyleSchema = z.object({
  fillColor: z.string().optional(),
  lineColor: z.string().optional(),
  lineWidth: z.number().positive().optional(),
  opacity: z.number().min(0).max(1).optional(),
  icon: z.string().optional(),
  depthTest: z.boolean().optional(),
});
export type MarkerStyle = z.infer<typeof markerStyleSchema>;

const markerBase = z.object({
  id: z.string().min(1),
  label: z.string(),
  /** 低于/高于该视距时隐藏（像素或方块距离，由前端渲染策略解释） */
  minDistance: z.number().min(0).default(0),
  maxDistance: z.number().positive().default(Number.MAX_SAFE_INTEGER),
  style: markerStyleSchema.default({}),
});

export const poiMarkerSchema = markerBase.extend({
  type: z.literal("poi"),
  position: vec3Schema,
  /**
   * 详情内容（可选）。**属于不可信输入**（标注文件可以由任何人写入）：
   * 渲染时必须按纯文本处理或先做 HTML 消毒，**不要**直接 `v-html`/`innerHTML`，
   * 否则标注文件就是一个存储型 XSS 的入口。
   */
  detailHtml: z.string().optional(),
});
export const lineMarkerSchema = markerBase.extend({
  type: z.literal("line"),
  points: z.array(vec3Schema).min(2),
});
export const shapeMarkerSchema = markerBase.extend({
  type: z.literal("shape"),
  /** XZ 平面多边形（外环） */
  shape: z.array(z.object({ x: z.number(), z: z.number() })).min(3),
  holes: z.array(z.array(z.object({ x: z.number(), z: z.number() })).min(3)).default([]),
  shapeY: z.number(),
});
export const extrudeMarkerSchema = markerBase.extend({
  type: z.literal("extrude"),
  shape: z.array(z.object({ x: z.number(), z: z.number() })).min(3),
  holes: z.array(z.array(z.object({ x: z.number(), z: z.number() })).min(3)).default([]),
  shapeMinY: z.number(),
  shapeMaxY: z.number(),
});
export const boxMarkerSchema = markerBase.extend({
  type: z.literal("box"),
  min: vec3Schema,
  max: vec3Schema,
});

export const markerSchema = z.discriminatedUnion("type", [
  poiMarkerSchema,
  lineMarkerSchema,
  shapeMarkerSchema,
  extrudeMarkerSchema,
  boxMarkerSchema,
]);
export type Marker = z.infer<typeof markerSchema>;

export const markerSetSchema = z.object({
  id: z.string().min(1),
  label: z.string(),
  toggleable: z.boolean().default(true),
  defaultHidden: z.boolean().default(false),
  sorting: z.number().int().default(0),
  markers: z.array(markerSchema),
});
export type MarkerSet = z.infer<typeof markerSetSchema>;
