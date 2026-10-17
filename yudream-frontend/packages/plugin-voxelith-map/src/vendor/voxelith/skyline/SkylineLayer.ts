// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 天际线平面 LOD：远景不再加载并解析粗层 glb，而是把该层的 `lod-atlas.png`
 * 按槽位贴到**与瓦片同footprint 的水平面片**上，抬到该瓦片最高点之上一点。
 *
 * 为什么这样省：粗层瓦片离相机很远、在屏幕上只占几十像素，却仍然要下载几 KB～几十 KB
 * 的 glb 并解析成网格。图集页本来就已经为每一层准备好了（全量渲染时拼好的共享页），
 * 一片面片只要能拿到「页 + UV 矩形」就能画出同样的远景色块——零额外网络请求。
 *
 * 与 `TileManager` 的分工：近处（`detailDistance` 以内）完全交给真实的 hires/LOD 网格；
 * 本层只负责 `detailDistance → farDistance` 的地平线带，并按
 * {@link levelForFarDistance} 选**一层**来画（默认让远景带约 8 片铺满），
 * 其余层级不挂面片——同一片区域画两层只会互相盖住。
 *
 * 想改成「多层各画一圈」的消费方可以用 {@link planSkyline}/{@link skylineBands}
 * 自己决定层级，本层提供 `uvFor` 与 {@link applyUvRect} 做换算。
 *
 * 边缘处理：面片材质 `fog = true`，配合 {@link SkylineHaze} 在 detailDistance 处开始淡入背景色，
 * 所以「地毯」的近端接缝与地图外缘都不会留下硬边。
 */
import * as THREE from "three";
import type { MapManifest, ManifestTile } from "../core/index";
import { lodAtlasUvRect, levelTilesWithUv, type LodAtlasUv } from "./lodAtlas.js";
import { levelForFarDistance, skylineBands, type SkylineBand, type SkylinePolicyOptions } from "./policy.js";

export interface SkylineLayerOptions {
  manifest: MapManifest;
  /** level → 该层 `lod-atlas.png` 纹理；缺该层时用 `fallbackColor` 纯色面片。 */
  atlasTextures?: ReadonlyMap<number, THREE.Texture>;
  /** 细节视距（方块）：以内交给真实几何。 */
  detailDistance: number;
  /** 远景最大距离（方块）。 */
  farDistance: number;
  /** 参与远景的最高层级；默认 `lodCount - 1`。 */
  maxLevel?: number;
  /** 缺纹理时的兜底色。 */
  fallbackColor?: THREE.ColorRepresentation;
  /** 面片不透明度（远景压低存在感，避免喧宾夺主）。 */
  opacity?: number;
  /** 抬升高度（方块），默认 0.05，避免与地形 z-fighting。 */
  lift?: number;
  /** 层带重叠比例（传给策略）。 */
  overlap?: number;
  /** 退出滞回比例（面片进出地平线带时用，默认 0.15）。 */
  hysteresis?: number;
  /** 远景带铺满的瓦片片数（选层用，默认 8）。 */
  tilesAcross?: number;
}

interface TileQuad {
  tile: ManifestTile;
  mesh: THREE.Mesh;
  /** 世界 XZ 包围盒（距离剔除用，避免每帧算包围球）。 */
  minX: number;
  maxX: number;
  minZ: number;
  maxZ: number;
}

export class SkylineLayer {
  readonly object3d = new THREE.Group();

  private readonly options: SkylineLayerOptions;
  private readonly materials = new Map<number | "fallback", THREE.MeshBasicMaterial>();
  private readonly quads: TileQuad[] = [];
  private readonly activeLevelValue: number | null;
  /** 每帧复用：避免 update() 每次分配（远景每帧都跑）。 */
  private readonly cameraPosition = new THREE.Vector3();

  constructor(options: SkylineLayerOptions) {
    this.options = options;
    this.object3d.name = "voxelith-skyline";
    this.activeLevelValue = this.chooseLevel();
    this.build();
  }

  /**
   * 各层层带的静态区间（`near`/`far`/边长），供面板展示或外部策略复用。
   * 本层只画一层，所以不维护 `active` 状态——需要多层调度请自行用 {@link planSkyline}。
   */
  levelBands(): ReadonlyArray<Omit<SkylineBand, "active">> {
    return skylineBands(this.policyOptions());
  }

  /** 当前用于地平线地毯的层级（null = 清单里没有可用的 LOD 图集页）。 */
  activeLevel(): number | null {
    return this.activeLevelValue;
  }

  /** 已挂出的面片数（每片远景瓦片一个）。 */
  tileCount(): number {
    return this.quads.length;
  }

  /** 当前可见（被距离与层级筛过）的面片数。 */
  visibleTileCount(): number {
    return this.quads.reduce((count, quad) => count + (quad.mesh.visible ? 1 : 0), 0);
  }

  /**
   * 每帧更新：按相机位置更新层带（滞回）与每片面片的可见性。
   *
   * 只画**最粗的 active 层**：层带之间的重叠是给切换用的，同时画两层会互相盖住。
   */
  update(camera: THREE.Camera): void {
    const position = camera.getWorldPosition(this.cameraPosition);
    const activeLevel = this.activeLevel();
    const hysteresis = this.options.hysteresis ?? 0.15;
    for (const quad of this.quads) {
      if (activeLevel === null || quad.tile.level !== activeLevel) {
        quad.mesh.visible = false;
        continue;
      }
      const distance = this.horizontalDistance(position, quad);
      // 进入：稍微提前一点接上（×0.9），避免与雾带之间露出空隙；
      // 退出：已经在画的面片要跨过 15% 滞回才撤掉，避免相机在地平线带边界上抖动
      const enter = distance >= this.options.detailDistance * 0.9
        && distance <= this.options.farDistance;
      const stay = quad.mesh.visible
        && distance >= this.options.detailDistance * 0.9 * (1 - hysteresis)
        && distance <= this.options.farDistance * (1 + hysteresis);
      quad.mesh.visible = enter || stay;
    }
  }

  /** 该层的图集页是否已就绪（未就绪时用兜底色，不必等纹理）。 */
  hasAtlasTexture(level: number): boolean {
    return this.options.atlasTextures?.has(level) ?? false;
  }

  dispose(): void {
    for (const quad of this.quads) {
      quad.mesh.geometry.dispose();
      quad.mesh.removeFromParent();
    }
    this.quads.length = 0;
    for (const material of this.materials.values()) {
      material.dispose();
    }
    this.materials.clear();
    this.object3d.removeFromParent();
  }

  // -------------------------------------------------------------------------
  // 构建
  // -------------------------------------------------------------------------

  private policyOptions(): SkylinePolicyOptions {
    return {
      hiresTileSize: this.options.manifest.settings.hiresTileSize,
      maxLevel: Math.max(0, this.options.maxLevel ?? this.options.manifest.settings.lodCount - 1),
      detailDistance: this.options.detailDistance,
      farDistance: this.options.farDistance,
      ...(this.options.overlap !== undefined ? { overlap: this.options.overlap } : {}),
      ...(this.options.hysteresis !== undefined ? { hysteresis: this.options.hysteresis } : {}),
    };
  }

  private build(): void {
    const manifest = this.options.manifest;
    const level = this.activeLevelValue;
    if (level === null) {
      return;
    }
    for (const entry of levelTilesWithUv(manifest, level)) {
      const mesh = this.buildQuad(entry.tile, entry.uv);
      this.object3d.add(mesh);
      this.quads.push({
        tile: entry.tile,
        mesh,
        minX: Math.min(entry.tile.min[0]!, entry.tile.max[0]!),
        maxX: Math.max(entry.tile.min[0]!, entry.tile.max[0]!),
        minZ: Math.min(entry.tile.min[2]!, entry.tile.max[2]!),
        maxZ: Math.max(entry.tile.min[2]!, entry.tile.max[2]!),
      });
    }
  }

  /**
   * 选层：先按「远景带铺满约 N 片」定一个理想层级，再回退到最近的有图集页的层级。
   *
   * 为什么需要回退：增量发布过的图可能只有部分层级带 `lod-atlas` 页
   * （增量瓦片是逐片内嵌色图的），选中的层没有页时只能往上找更粗的、或往下找更细的——
   * 找不到任何带页的层就干脆不画远景（返回 null）。
   */
  private chooseLevel(): number | null {
    const manifest = this.options.manifest;
    const maxLevel = Math.max(0, this.options.maxLevel ?? manifest.settings.lodCount - 1);
    const ideal = levelForFarDistance(
      manifest.settings.hiresTileSize,
      this.options.farDistance,
      maxLevel,
      this.options.tilesAcross ?? 8,
    );
    const withPage = new Set(manifest.lodAtlases.map((page) => page.level));
    if (withPage.size === 0) {
      return null;
    }
    for (let offset = 0; offset <= maxLevel; offset++) {
      const coarser = ideal + offset;
      if (coarser <= maxLevel && withPage.has(coarser)) {
        return coarser;
      }
      const finer = ideal - offset;
      if (finer >= 0 && withPage.has(finer)) {
        return finer;
      }
    }
    return null;
  }

  private buildQuad(tile: ManifestTile, uv: LodAtlasUv): THREE.Mesh {
    const size = this.options.manifest.settings.hiresTileSize * 2 ** tile.level;
    const geometry = new THREE.PlaneGeometry(size, size);
    geometry.rotateX(-Math.PI / 2);
    applyUvRect(geometry, uv);
    const mesh = new THREE.Mesh(geometry, this.materialFor(tile.level));
    mesh.position.set(
      tile.x * size + size / 2,
      Math.max(tile.min[1]!, tile.max[1]!) + (this.options.lift ?? 0.05),
      tile.z * size + size / 2,
    );
    mesh.matrixAutoUpdate = false;
    mesh.updateMatrix();
    mesh.renderOrder = -1; // 远景先画，真实几何照常覆盖
    mesh.visible = false;
    return mesh;
  }

  private materialFor(level: number): THREE.MeshBasicMaterial {
    const texture = this.options.atlasTextures?.get(level);
    const key: number | "fallback" = texture ? level : "fallback";
    const cached = this.materials.get(key);
    if (cached) {
      return cached;
    }
    const material = new THREE.MeshBasicMaterial({
      map: texture ?? null,
      color: texture ? 0xffffff : (this.options.fallbackColor ?? 0x4a5568),
      transparent: (this.options.opacity ?? 1) < 1,
      opacity: this.options.opacity ?? 1,
      depthWrite: true,
      side: THREE.FrontSide,
      fog: true,
    });
    // 图集页是线性过滤的无 mip 纹理：近似的远景色块不需要 mip，也避免跨槽位串色。
    // 纹理通常由 TileManager 等外部持有，所以只在还没配好时才动它（避免无故改写别人的状态）
    if (texture && (texture.magFilter !== THREE.LinearFilter
      || texture.minFilter !== THREE.LinearFilter || texture.generateMipmaps)) {
      texture.magFilter = THREE.LinearFilter;
      texture.minFilter = THREE.LinearFilter;
      texture.generateMipmaps = false;
    }
    this.materials.set(key, material);
    return material;
  }

  /** 相机到面片包围盒的水平距离（盒内为 0）——与 TileManager 的判据保持一致。 */
  private horizontalDistance(position: THREE.Vector3, quad: TileQuad): number {
    const dx = Math.max(quad.minX - position.x, 0, position.x - quad.maxX);
    const dz = Math.max(quad.minZ - position.z, 0, position.z - quad.maxZ);
    return Math.hypot(dx, dz);
  }

  /** 供工具函数复用：某层某片的 UV（无图集页时 null）。 */
  uvFor(level: number, x: number, z: number): LodAtlasUv | null {
    return lodAtlasUvRect(this.options.manifest, level, x, z);
  }
}

/**
 * 把图集槽位矩形写进平面几何的 UV。
 *
 * `PlaneGeometry` 绕 X 轴 −90° 之后，局部 +Y 变成世界 **−Z**，而图集页的行序是
 * 「v 随世界 Z 增大」——所以 v 方向必须翻转：局部 uv.y = 0 的顶点落在世界最大 Z 上，
 * 取图集槽位的 `v1`。
 */
export function applyUvRect(geometry: THREE.BufferGeometry, uv: LodAtlasUv): void {
  const attribute = geometry.getAttribute("uv") as THREE.BufferAttribute | undefined;
  if (!attribute) {
    throw new Error("几何缺少 uv 属性");
  }
  for (let i = 0; i < attribute.count; i++) {
    const u = attribute.getX(i);
    const v = attribute.getY(i);
    attribute.setXY(i, uv.u0 + u * (uv.u1 - uv.u0), uv.v1 - v * (uv.v1 - uv.v0));
  }
  attribute.needsUpdate = true;
}
