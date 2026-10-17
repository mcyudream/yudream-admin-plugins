// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
  /**
   * 瓦片管理：LOD 四叉树遍历 + 加载调度（并发上限 + 优先级）+ 数量/字节双阈值 LRU 淘汰。
   *
   * 每帧从清单最粗层级向下遍历：距相机足够近（或俯视屏幕误差过大）且存在子瓦片时细分。
   * 期望瓦片未加载时以已加载祖先垫底，中间缺失层不入队（避免俯视被 L1 踏脚石占满带宽）。
   * lodCount=1 的清单退化为全量 hires 加载。
   */
import * as THREE from "three";
import { tileWorldOrigin, type MapManifest, type ManifestTile } from "../../core/index";
import { configureHiresAtlas, disposeTileGroup, GlbTileLoader } from "./GlbTileLoader.js";
import { buildTileCollisionProxy, type TileCollisionProxies } from "./TileCollisionProxy.js";

/**
 * 碰撞兜底允许用到的最粗 LOD 层级：LOD 柱顶取的是 2^level 方块内的**最高**表面，
 * 层级越粗误差越大（实测 L5 能比真实地面高 17 格）。取前 4 层（footprint ≤ 8 方块）
 * 既能在 hires 还没到时给出可用地面，又不会把人托到半空。
 */
const COLLISION_MAX_FALLBACK_LEVEL = 3;

export interface TileManagerOptions {
  scene: THREE.Scene;
  mapBaseUrl: string;
  manifest: MapManifest;
  /** 并发下载上限 */
  maxConcurrent?: number;
  /** 已加载瓦片数量上限（超出按最近最少使用淘汰） */
  maxLoaded?: number;
  /** 已加载瓦片字节上限（超出淘汰） */
  maxBytes?: number;
  /** 细分距离系数：boxDistance < 瓦片边长 × lodFactor 时细化（越大越精细） */
  lodFactor?: number;
  /** 细节视距（区块）：视距内正常细分到 hires；视距外不剔除，而是按水平距离
   *  每翻一倍允许的最细层级粗一级（逐级 LOD），最粗层封顶；缺省不限 */
  detailDistanceChunks?: number;
  /** 单个瓦片的加载尝试上限（含首次），超限后本次会话不再重试；默认 3 */
  maxTileAttempts?: number;
  /** 失败重试基础退避（毫秒，按尝试次数指数增长）；默认 1000 */
  retryBaseDelayMs?: number;
  /** 失败重试退避上限（毫秒）；默认 30000 */
  retryMaxDelayMs?: number;
  /** 单调时钟注入点（测试用）；缺省 performance.now */
  now?: () => number;
  onTileLoaded?: (tile: ManifestTile, loaded: number, total: number) => void;
  /** 加载器注入点（测试/自定义管线）；缺省用内置 GlbTileLoader（可带共享图集）。 */
  loader?: Pick<GlbTileLoader, "load">;
}

/** 失败瓦片的退避状态。 */
interface FailedTile {
  /** 已尝试次数（含首次失败） */
  attempts: number;
  /** 早于该时刻不重新入队（毫秒时间戳） */
  retryAt: number;
}

/**
 * 失败重试的指数退避：第 n 次失败后等 base × 2^(n-1) 毫秒，封顶 max。
 * 纯函数，便于单测。
 */
export function retryDelayMs(attempts: number, base: number, max: number): number {
  if (attempts <= 0) {
    return 0;
  }
  return Math.min(max, base * 2 ** (attempts - 1));
}

/**
 * LRU 淘汰打分：越大越先淘汰。帧龄按 ×1000 折算成方块距离，于是「上一帧用过」
 * 与「一千方块外」等价 —— 等效于 LRU 优先，距离只决定同期瓦片之间的先后。
 * 纯函数，便于单测。
 */
export function evictionScore(distance: number, ageFrames: number): number {
  return distance + ageFrames * 1000;
}

interface LiveTile {
  group: THREE.Group;
  bytes: number;
  lastUsed: number;
  /** 世界空间包围盒中心（淘汰打分用；与 group.position 的渲染空间区分） */
  center: THREE.Vector3;
}

export class TileManager {
  private readonly scene: THREE.Scene;
  private readonly mapBaseUrl: string;
  private readonly manifest: MapManifest;
  private readonly maxConcurrent: number;
  private readonly maxLoaded: number;
  private readonly maxBytes: number;
  private readonly lodFactor: number;
  private readonly maxTileAttempts: number;
  private readonly retryBaseDelayMs: number;
  private readonly retryMaxDelayMs: number;
  private readonly now: () => number;
  private readonly onTileLoaded?: TileManagerOptions["onTileLoaded"];
  /** 细节视距（方块）；Infinity = 视距内外无差别。由 AdaptiveDistance 每帧平滑写入 */
  private detailDistance = Infinity;

  private readonly loader: Pick<GlbTileLoader, "load">;
  private readonly sharedAtlas: THREE.Texture | null = null;
  /**
   * LOD 每层共享图集页（level → 纹理）。全量生成的 LOD 瓦片不含内嵌色图，其 UV 已烘焙成
   * 图集坐标；由 GlbTileLoader 在 LOD 分支挂上对应层的页。所有权在本管理器，随 dispose 释放。
   */
  private readonly lodAtlases = new Map<number, THREE.Texture>();
  private readonly live = new Map<string, LiveTile>();
  private readonly byKey = new Map<string, ManifestTile>();
  private readonly topLevelTiles: ManifestTile[] = [];
  private readonly topLevel: number;
  private readonly loading = new Set<string>();
  /** 失败瓦片 → 退避状态。超过 maxTileAttempts 的条目保留为「永久失败」（仅计数与 HUD 用）。 */
  private readonly failed = new Map<string, FailedTile>();
  private queue: ManifestTile[] = [];
  private frame = 0;
  private loadedBytes = 0;
  /**
   * 代际戳：dispose/换图时递增。in-flight 的异步加载完成后比对代际，
   * 过期结果（旧地图的迟到瓦片）直接销毁，绝不进新场景 —— 防快速切图竞态。
   */
  private generation = 0;
  /**
   * 世界→渲染空间偏移（浮点原点）。默认零向量 = 渲染空间即世界空间；
   * 由 FloatingOrigin 在超远坐标（边疆量级）重定基时写入。
   */
  private readonly worldOffset = new THREE.Vector3();
  private viewPosition = new THREE.Vector3();
  private readonly frustum = new THREE.Frustum();
  private readonly frustumMatrix = new THREE.Matrix4();
  private readonly tileBox = new THREE.Box3();
  private readonly traversalStack: ManifestTile[] = [];
  /** 排障隔离：限制可见层级。默认 all。 */
  private layerFilter: "all" | "hires" | "lod" = "all";
  /**
   * 瓦片组 → 碰撞代理（懒建）。第一人称的射线只打代理：密集瓦片整片求交要几毫秒，
   * 代理按区域切成子网格后每条射线只扫穿过的 1~2 块。随瓦片组一起被 GC 回收。
   */
  private readonly collisionProxies = new WeakMap<THREE.Object3D, TileCollisionProxies>();

  constructor(options: TileManagerOptions) {
    this.scene = options.scene;
    this.mapBaseUrl = options.mapBaseUrl;
    this.manifest = options.manifest;
    this.maxConcurrent = options.maxConcurrent ?? 16;
    this.maxLoaded = options.maxLoaded ?? 4096;
    this.maxBytes = options.maxBytes ?? 2 * 1024 * 1024 * 1024;
    this.lodFactor = options.lodFactor ?? 3;
    this.maxTileAttempts = options.maxTileAttempts ?? 3;
    this.retryBaseDelayMs = options.retryBaseDelayMs ?? 1000;
    this.retryMaxDelayMs = options.retryMaxDelayMs ?? 30000;
    this.now = options.now ?? (() => performance.now());
    if (options.detailDistanceChunks !== undefined) {
      this.detailDistance = options.detailDistanceChunks * 16;
    }
    this.onTileLoaded = options.onTileLoaded;
    this.topLevel = options.manifest.settings.lodCount - 1;
    // hires 共享图集：每个 hires glb 内嵌同一张图集 PNG，逐瓦片解码会把显存耗尽。
    // 按 glTF 约定配置（flipY=false + SRGBColorSpace），过滤参数由 GlbTileLoader 统一设置。
    // 无 DOM 环境（vitest/node）无法解码图片，跳过共享图集回退逐瓦片内嵌。
    if (!options.loader && options.manifest.atlas?.url && typeof document !== "undefined") {
      this.sharedAtlas = configureHiresAtlas(
        new THREE.TextureLoader().load(`${this.mapBaseUrl}/${options.manifest.atlas.url}`),
      );
      this.sharedAtlas.userData.voxelithShared = true;
    }
    // LOD 每层共享图集页：一层一张纹理，取代上千张逐瓦片色图。
    // 与 hires 同理，无 DOM 环境（vitest/node）解不了图，跳过并回退逐瓦片内嵌色图。
    if (!options.loader && typeof document !== "undefined") {
      for (const page of options.manifest.lodAtlases) {
        const texture = new THREE.TextureLoader().load(
          // 页内容哈希做版本戳：页路径固定，不带戳会被 7 天强缓存挡住更新
          `${this.mapBaseUrl}/${page.url}?sha=${encodeURIComponent(page.sha1)}`,
        );
        texture.userData.voxelithShared = true;
        this.lodAtlases.set(page.level, texture);
      }
    }
    this.loader =
      options.loader ??
      new GlbTileLoader({ sharedAtlas: this.sharedAtlas ?? undefined, lodAtlases: this.lodAtlases });
    for (const tile of options.manifest.tiles) {
      this.byKey.set(this.key(tile), tile);
      if (tile.level === this.topLevel) {
        this.topLevelTiles.push(tile);
      }
    }
  }

  private key(tile: ManifestTile): string {
    return `${tile.level}:${tile.x}:${tile.z}`;
  }

  /** 每帧调用：LOD 遍历决定渲染集合（祖先垫底）并调度缺失瓦片。 */
  update(camera: THREE.Camera): void {
    this.frame++;
    // 逻辑坐标一律世界空间：相机在渲染空间，加浮点原点偏移还原
    this.viewPosition.copy(camera.position).add(this.worldOffset);
    camera.updateMatrixWorld();
    this.frustumMatrix.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse);
    this.frustum.setFromProjectionMatrix(this.frustumMatrix);

    const desired = this.collectDesired();
    const renderKeys = new Set<string>();
    const candidates = new Map<string, ManifestTile>();
    for (const tile of desired) {
      const key = this.key(tile);
      if (this.markLive(renderKeys, key)) {
        continue;
      }
      candidates.set(key, tile);
      // 未加载：已加载祖先垫底；若整条祖先链都还没到，只入队最粗缺失祖先一次，
      // 不要把 L1..L(n-1) 全塞进队列（俯视会被踏脚石占满带宽）。
      let queuedAncestor = false;
      for (let level = tile.level + 1; level <= this.topLevel; level++) {
        const shift = level - tile.level;
        const ancestor = this.byKey.get(`${level}:${tile.x >> shift}:${tile.z >> shift}`);
        if (!ancestor) {
          continue;
        }
        const ancestorKey = this.key(ancestor);
        if (this.markLive(renderKeys, ancestorKey)) {
          break;
        }
        if (!queuedAncestor && level === this.topLevel) {
          candidates.set(ancestorKey, ancestor);
          queuedAncestor = true;
        }
      }
    }

    // 不在渲染集合中的存活瓦片隐藏但保留（LRU 持有，视角回摆即无感恢复）
    for (const [key, tile] of this.live) {
      const inRender = renderKeys.has(key);
      if (!inRender) {
        tile.group.visible = false;
        continue;
      }
      if (this.layerFilter === "all") {
        tile.group.visible = true;
        continue;
      }
      const level = Number(key.split(":")[0]);
      tile.group.visible = this.layerFilter === "hires" ? level === 0 : level > 0;
    }

    this.queue = [...candidates.values()].filter(
      (tile) => !this.loading.has(this.key(tile)) && this.isSchedulable(this.key(tile)),
    );
    this.sortQueue();

    while (this.loading.size < this.maxConcurrent && this.queue.length > 0) {
      const tile = this.queue.shift()!;
      const key = this.key(tile);
      if (this.live.has(key) || !this.isSchedulable(key)) {
        continue;
      }
      this.loading.add(key);
      void this.loadTile(tile, key);
    }
  }

  /**
   * 是否可入队：无失败记录直接可；失败过则需未达尝试上限且已过退避时刻。
   * 瞬时网络错误不再导致该瓦片本次会话永久缺失。
   */
  private isSchedulable(key: string): boolean {
    const failure = this.failed.get(key);
    if (!failure) {
      return true;
    }
    return failure.attempts < this.maxTileAttempts && this.now() >= failure.retryAt;
  }

  /** LOD 四叉树遍历：从最粗层级向下，够近且有子瓦片则细分，返回期望渲染的瓦片集合。 */
  private collectDesired(): ManifestTile[] {
    const desired: ManifestTile[] = [];
    const stack = this.traversalStack;
    stack.push(...this.topLevelTiles);
    while (stack.length > 0) {
      const tile = stack.pop()!;
      if (tile.level > 0 && this.shouldRefine(tile) && this.pushChildren(tile, stack)) {
        continue;
      }
      desired.push(tile);
    }
    return desired;
  }

  /** 设置细节视距（方块）：视距外逐级使用更粗 LOD（不剔除）；传 Infinity 取消限制。 */
  setDetailDistanceBlocks(blocks: number): void {
    this.detailDistance = blocks;
  }

  /** 写入浮点原点偏移（世界→渲染空间）；新加载瓦片与视距逻辑随之对齐。 */
  setWorldOffset(offset: THREE.Vector3): void {
    this.worldOffset.copy(offset);
  }

  /**
   * 覆盖该渲染空间列的已加载瓦片组（level 0 = hires），没有则 null。
   *
   * 第一人称的脚下地表/碰撞探测要用它：LOD 层级的柱顶取的是 2^L 方块内的**最高**表面，
   * 拿粗层当地面会把人托在半空（大范围平台比真实地面高几十格）。所以碰撞只认 hires。
   */
  tileGroupAt(x: number, z: number, level = 0): THREE.Object3D | null {
    if (level < 0 || level > this.topLevel) {
      return null;
    }
    const size = this.manifest.settings.hiresTileSize * 2 ** level;
    const tileX = Math.floor((x + this.worldOffset.x) / size);
    const tileZ = Math.floor((z + this.worldOffset.z) / size);
    return this.live.get(`${level}:${tileX}:${tileZ}`)?.group ?? null;
  }

  /**
   * 该列可用于碰撞的瓦片：hires（level 0）优先，hires 还没流式到位时退到**已加载的最细
   * LOD**（不超过 {@link COLLISION_MAX_FALLBACK_LEVEL}，避免粗层柱顶把人托到半空）。
   */
  private collisionTileAt(x: number, z: number): THREE.Object3D | null {
    const maxLevel = Math.min(COLLISION_MAX_FALLBACK_LEVEL, this.topLevel);
    for (let level = 0; level <= maxLevel; level++) {
      const tile = this.tileGroupAt(x, z, level);
      if (tile) {
        return tile;
      }
    }
    return null;
  }

  /** 瓦片的碰撞代理（实体 + 水面），懒建一次后缓存在瓦片组上。 */
  private proxiesFor(tile: THREE.Object3D): TileCollisionProxies {
    const cached = this.collisionProxies.get(tile);
    if (cached) {
      return cached;
    }
    const proxies = buildTileCollisionProxy(tile);
    // `visible=false` 的子节点：不参与渲染，但会跟着瓦片一起被浮点原点重定基平移
    tile.add(proxies.solid);
    tile.add(proxies.water);
    proxies.solid.updateMatrixWorld(true);
    proxies.water.updateMatrixWorld(true);
    this.collisionProxies.set(tile, proxies);
    return proxies;
  }

  /**
   * 该渲染空间列的**实体**碰撞代理（地面 / 墙 / 台阶）：第一人称的下落、贴墙、撞头射线都打它。
   * 已剔除植物（45° 交叉面片）与水面；hires 未加载时用最细的可用 LOD 兜底。
   */
  solidProxyAt(x: number, z: number): THREE.Object3D | null {
    const tile = this.collisionTileAt(x, z);
    return tile ? this.proxiesFor(tile).solid : null;
  }

  /**
   * 该渲染空间列的**水面**碰撞代理：游泳 / 沉水 / 判断人在水里用。
   * 只有 hires 瓦片带水面 primitive（LOD 是高度场，水面颜色烘进色图），因此 LOD 兜底时为空。
   */
  waterProxyAt(x: number, z: number): THREE.Object3D | null {
    const tile = this.collisionTileAt(x, z);
    return tile ? this.proxiesFor(tile).water : null;
  }

  get detailDistanceBlocks(): number {
    return this.detailDistance;
  }

  /**
   * 各层 LOD 图集页纹理（level → texture）。
   *
   * 暴露给远景增强（`@yudream/voxelith-skyline`）：天际线平面地毯直接用这些页贴图，
   * 才能不为远处瓦片再下载/解析 glb。所有权仍在本管理器（换图时统一释放），
   * 调用方**不要** dispose 这些纹理。
   */
  lodAtlasTextures(): ReadonlyMap<number, THREE.Texture> {
    return this.lodAtlases;
  }

  /** 相机到瓦片包围盒的水平（XZ）距离（盒内为 0），高度差不计入细节视距。 */
  private horizontalBoxDistance(tile: ManifestTile): number {
    const px = this.viewPosition.x;
    const pz = this.viewPosition.z;
    const dx = Math.max(tile.min[0] - px, 0, px - tile.max[0]);
    const dz = Math.max(tile.min[2] - pz, 0, pz - tile.max[2]);
    return Math.sqrt(dx * dx + dz * dz);
  }

  private shouldRefine(tile: ManifestTile): boolean {
    const size = this.manifest.settings.hiresTileSize * 2 ** tile.level;
    const geometric = this.boxDistance(tile) < size * this.lodFactor;
    const sse = this.screenErrorExceeds(tile, size);
    if (!geometric && !sse) {
      return false;
    }
    const dxz = this.horizontalBoxDistance(tile);
    if (dxz < this.detailDistance) {
      return true;
    }
    const allowedFinest = Math.min(
      this.topLevel,
      1 + Math.floor(Math.log2(dxz / this.detailDistance)),
    );
    return tile.level > allowedFinest;
  }

  /**
   * 屏幕空间误差：俯视高空时几何距离会远大于瓦片边长，lodFactor 门限会把整图钉在最粗层。
   * 用「瓦片边长 / 相机高度」近似像素跨度，跨度过阈值则继续细分。
   */
  private screenErrorExceeds(tile: ManifestTile, size: number): boolean {
    const midY = (tile.min[1] + tile.max[1]) / 2;
    const height = Math.max(8, this.viewPosition.y - midY);
    if (this.horizontalBoxDistance(tile) > height * 4) {
      return false;
    }
    return size / height > 0.18;
  }

  /** 4 个子瓦片中存在于清单的压入栈；返回是否存在任何子瓦片。 */
  private pushChildren(tile: ManifestTile, stack: ManifestTile[]): boolean {
    const childLevel = tile.level - 1;
    const cx = tile.x * 2;
    const cz = tile.z * 2;
    let found = false;
    for (const [dx, dz] of [[0, 0], [1, 0], [0, 1], [1, 1]] as const) {
      const child = this.byKey.get(`${childLevel}:${cx + dx}:${cz + dz}`);
      if (child) {
        stack.push(child);
        found = true;
      }
    }
    return found;
  }

  /** 相机到瓦片世界包围盒的距离（盒内为 0）。 */
  private boxDistance(tile: ManifestTile): number {
    const px = this.viewPosition.x;
    const py = this.viewPosition.y;
    const pz = this.viewPosition.z;
    const dx = Math.max(tile.min[0] - px, 0, px - tile.max[0]);
    const dy = Math.max(tile.min[1] - py, 0, py - tile.max[1]);
    const dz = Math.max(tile.min[2] - pz, 0, pz - tile.max[2]);
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /** 瓦片已加载则加入渲染集合并刷新 LRU 时间戳。 */
  private markLive(renderKeys: Set<string>, key: string): boolean {
    const liveTile = this.live.get(key);
    if (!liveTile) {
      return false;
    }
    liveTile.lastUsed = this.frame;
    renderKeys.add(key);
    return true;
  }

  /** 加载优先级：视锥内优先；同屏先铺最粗背景，再填期望层。 */
  private sortQueue(): void {
    const inView = (t: ManifestTile) => {
      this.tileBox.min.set(t.min[0], t.min[1], t.min[2]);
      this.tileBox.max.set(t.max[0], t.max[1], t.max[2]);
      return this.frustum.intersectsBox(this.tileBox);
    };
    const distSq = (t: ManifestTile) => {
      const dx = (t.min[0] + t.max[0]) / 2 - this.viewPosition.x;
      const dy = (t.min[1] + t.max[1]) / 2 - this.viewPosition.y;
      const dz = (t.min[2] + t.max[2]) / 2 - this.viewPosition.z;
      return dx * dx + dy * dy + dz * dz;
    };
    const score = (t: ManifestTile) => {
      const viewBias = inView(t) ? 0.08 : 1;
      // 最粗层先到（铺底），同层按距离。中间踏脚石已不再入队。
      return distSq(t) * viewBias / 2 ** t.level;
    };
    this.queue.sort((a, b) => score(a) - score(b));
  }

  private async loadTile(tile: ManifestTile, key: string): Promise<void> {
    const generation = this.generation;
    try {
      // 版本戳用瓦片自身 sha1：内容未变的瓦片跨地图版本继续命中 7 天强缓存，
      // 而 manifest.version 是全图聚合哈希，任一瓦片变化都会让全部 URL 失效。
      const group = await this.loader.load(
        `${this.mapBaseUrl}/${tile.url}?sha=${encodeURIComponent(tile.sha1)}`,
        tile.level,
      );
      if (generation !== this.generation || this.live.has(key)) {
        // 迟到/重复结果：所属管理器已换图或该瓦片已被另一路径加载，直接销毁防泄漏
        disposeTileGroup(group);
        return;
      }
      const [ox, oy, oz] = tileWorldOrigin(this.manifest, tile);
      group.position.set(
        ox - this.worldOffset.x,
        oy - this.worldOffset.y,
        oz - this.worldOffset.z,
      );
      group.matrixAutoUpdate = false;
      group.updateMatrix();
      // 射线求交（第一人称脚下地表）直接读 matrixWorld，而世界矩阵平时只在渲染前刷新：
      // 这里就地把这一组算好，避免刚到的瓦片在下一帧用旧矩阵（原点）参与求交。
      group.updateMatrixWorld(true);
      if (tile.level > 0) {
        // LOD 背景在深度上让位：细层就位后与祖先重叠区域由细层绘制
        group.traverse((node) => {
          if (node instanceof THREE.Mesh) {
            const material = node.material as THREE.Material;
            material.polygonOffset = true;
            material.polygonOffsetFactor = tile.level;
            material.polygonOffsetUnits = tile.level * 2;
          }
        });
      }
      this.scene.add(group);
      this.live.set(key, {
        group,
        bytes: tile.bytes,
        lastUsed: this.frame,
        center: new THREE.Vector3(
          (tile.min[0] + tile.max[0]) / 2,
          (tile.min[1] + tile.max[1]) / 2,
          (tile.min[2] + tile.max[2]) / 2,
        ),
      });
      this.loadedBytes += tile.bytes;
      this.failed.delete(key);
      this.evictIfNeeded();
      this.onTileLoaded?.(tile, this.live.size, this.manifest.tiles.length);
    } catch (error) {
      const attempts = (this.failed.get(key)?.attempts ?? 0) + 1;
      this.failed.set(key, {
        attempts,
        retryAt: this.now() + retryDelayMs(attempts, this.retryBaseDelayMs, this.retryMaxDelayMs),
      });
      console.error(`瓦片加载失败 ${key}（第 ${attempts}/${this.maxTileAttempts} 次）:`, error);
    } finally {
      this.loading.delete(key);
    }
  }

  /**
   * 双阈值滞回淘汰：数量或字节超限，淘汰最久未用（优先远离视点）的瓦片。
   * 打分把「帧龄 × 1000」与「方块距离」相加，因此一帧的陈旧就压过千方块的距离——
   * 等价于 LRU 优先，刚加载且仍在视点附近的瓦片天然最后被淘汰。
   */
  private evictIfNeeded(): void {
    while (this.live.size > this.maxLoaded || this.loadedBytes > this.maxBytes) {
      let victimKey: string | null = null;
      let victimScore = -Infinity;
      for (const [key, tile] of this.live) {
        const distance = tile.center.distanceTo(this.viewPosition);
        const score = evictionScore(distance, this.frame - tile.lastUsed);
        if (score > victimScore) {
          victimScore = score;
          victimKey = key;
        }
      }
      if (victimKey === null) {
        return;
      }
      const victim = this.live.get(victimKey)!;
      this.scene.remove(victim.group);
      disposeTileGroup(victim.group);
      this.loadedBytes -= victim.bytes;
      this.live.delete(victimKey);
    }
  }

  get loadedCount(): number {
    return this.live.size;
  }

  get failedCount(): number {
    return this.failed.size;
  }

  /**
   * 排障隔离：按层级过滤可见性。
   * - `"hires"`：只显示 level 0
   * - `"lod"`：只显示 level &gt; 0
   * - `"all"`：恢复默认
   */
  setLayerFilter(filter: "all" | "hires" | "lod"): void {
    this.layerFilter = filter;
    for (const [key, tile] of this.live) {
      const level = Number(key.split(":")[0]);
      if (filter === "all") {
        continue;
      }
      tile.group.visible = filter === "hires" ? level === 0 : level > 0;
    }
  }

  get layerFilterMode(): "all" | "hires" | "lod" {
    return this.layerFilter;
  }

  /** 已加载瓦片按层级计数，控制台排障用。 */
  loadedByLevel(): Record<number, number> {
    const counts: Record<number, number> = {};
    for (const key of this.live.keys()) {
      const level = Number(key.split(":")[0]);
      counts[level] = (counts[level] ?? 0) + 1;
    }
    return counts;
  }

  dispose(): void {
    // 代际递增：此后所有 in-flight 加载完成即销毁，不再进场景
    this.generation++;
    for (const tile of this.live.values()) {
      this.scene.remove(tile.group);
      disposeTileGroup(tile.group);
    }
    this.live.clear();
    this.queue = [];
    this.loadedBytes = 0;
    this.sharedAtlas?.dispose();
    for (const atlas of this.lodAtlases.values()) {
      atlas.dispose();
    }
    this.lodAtlases.clear();
  }
}
