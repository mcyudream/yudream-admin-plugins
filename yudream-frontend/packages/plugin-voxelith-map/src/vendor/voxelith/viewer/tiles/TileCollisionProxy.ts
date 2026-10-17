// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 瓦片碰撞代理：把瓦片几何按 XZ 切片、按 Y 分层做出一套只用于射线碰撞的副本
 * （顶点缓冲与渲染网格共享，只有索引各一份，每片带紧凑包围球）。
 *
 * 为什么不能直接打渲染网格：
 * 1. three 的 Mesh.raycast 只用整片瓦片的包围球做剔除 —— 密集瓦片（9 万面）单条射线
 *    要 3.7 ms，第一人称每帧 7 条就是 25 ms，必须按片切细；
 * 2. 渲染几何里混着**不该有碰撞**的东西：
 *    - 植物（草、花、树苗、作物）是绕 Y 轴 45° 的交叉面片，人应该能直接走过去；
 *    - 水是独立的 translucent primitive，应该是能游泳的介质而不是墙/地面。
 *    这两类靠几何本身区分：植物面片的三个顶点在 X/Y/Z 上都不共面（斜跨 0.9 格），
 *    而所有实体方块面都是轴平行的（水平面 Y 相同、竖直面 X 或 Z 相同）。
 *    水面用材质 `transparent`（glb 的 alphaMode BLEND）识别，单独归入 water 代理。
 *
 * 代理挂在瓦片组下面且 `visible = false`：不参与渲染，但会跟着瓦片一起被浮点原点重定基
 * 平移；three 的 Raycaster 只看 layers、不看 visible，所以照样能打它。
 */
import * as THREE from "three";

/** XZ 每边切成几块（8×8，hires 瓦片下约 4 方块一块） */
const DEFAULT_GRID = 8;
/** Y 方向每片厚度目标（方块）：水平射线只需扫自己所在的那一片 */
const SLICE_HEIGHT = 4;
/** Y 方向最多分几片 */
const MAX_Y_SLICES = 16;
/** 判定"轴平行面"的容差（方块）：三个顶点在某个轴上几乎共面即视为实体面 */
const PLANAR_EPSILON = 0.02;

export interface TileCollisionProxies {
  /** 实体几何（地面 / 墙 / 台阶）：已剔除植物与水面 */
  solid: THREE.Group;
  /** 水面几何：游泳、沉水、判断"人在水里"用 */
  water: THREE.Group;
}

function regionIndex(value: number, min: number, span: number, grid: number): number {
  const raw = Math.floor(((value - min) / span) * grid);
  return Math.min(grid - 1, Math.max(0, raw));
}

/**
 * 从一批顶点算出紧凑包围球（three 只会按整个 position 属性算，不认索引子集，必须自己给）。
 * 顶点读取走传入的访问器：float32 直接读底层数组，量化属性走 getX 还原。
 */
function subsetSphere(
  indices: Uint32Array,
  vx: (i: number) => number,
  vy: (i: number) => number,
  vz: (i: number) => number,
): THREE.Sphere {
  let minX = Infinity;
  let minY = Infinity;
  let minZ = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  let maxZ = -Infinity;
  for (const i of indices) {
    const x = vx(i);
    const y = vy(i);
    const z = vz(i);
    if (x < minX) minX = x;
    if (y < minY) minY = y;
    if (z < minZ) minZ = z;
    if (x > maxX) maxX = x;
    if (y > maxY) maxY = y;
    if (z > maxZ) maxZ = z;
  }
  const center = new THREE.Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
  let radiusSq = 0;
  for (const i of indices) {
    const dx = vx(i) - center.x;
    const dy = vy(i) - center.y;
    const dz = vz(i) - center.z;
    const distanceSq = dx * dx + dy * dy + dz * dz;
    if (distanceSq > radiusSq) {
      radiusSq = distanceSq;
    }
  }
  return new THREE.Sphere(center, Math.sqrt(radiusSq));
}

/** 把一网格里"通过筛选"的三角面按 (XZ 区域, Y 层) 计数排序摊成索引数组。 */
function bucketMesh(
  node: THREE.Mesh,
  grid: number,
  bounds: { minX: number; maxX: number; minY: number; maxY: number; minZ: number; maxZ: number },
  keep: (minX: number, maxX: number, minY: number, maxY: number, minZ: number, maxZ: number) => boolean,
  out: { proxy: THREE.Group; toTileLocal: THREE.Matrix4 },
): void {
  const geometry = node.geometry as THREE.BufferGeometry;
  const position = geometry.getAttribute("position") as THREE.BufferAttribute | undefined;
  if (!position) {
    return;
  }
  const spanX = Math.max(bounds.maxX - bounds.minX, 1e-6);
  const spanZ = Math.max(bounds.maxZ - bounds.minZ, 1e-6);
  const spanY = Math.max(bounds.maxY - bounds.minY, 1e-6);
  const ySlices = Math.min(MAX_Y_SLICES, Math.max(1, Math.round(spanY / SLICE_HEIGHT)));
  const bucketCount = grid * grid * ySlices;
  const index = geometry.getIndex();
  const triangles = index ? Math.floor(index.count / 3) : Math.floor(position.count / 3);
  // 顶点若带归一化标志（量化 glb）必须走 getX 还原；float32 则直接读底层数组快得多
  const raw = position.normalized ? null : position.array as ArrayLike<number>;
  const vx = (i: number): number => (raw ? raw[i * 3]! : position.getX(i));
  const vy = (i: number): number => (raw ? raw[i * 3 + 1]! : position.getY(i));
  const vz = (i: number): number => (raw ? raw[i * 3 + 2]! : position.getZ(i));
  // 第一遍：数每个桶里有多少三角面（计数排序，避免 Map + 逐面 push 的分配开销）
  const counts = new Uint32Array(bucketCount);
  const triangleIndex = new Uint32Array(triangles * 3);
  let kept = 0;
  for (let t = 0; t < triangles; t++) {
    const a = index ? index.getX(t * 3) : t * 3;
    const b = index ? index.getX(t * 3 + 1) : t * 3 + 1;
    const c = index ? index.getX(t * 3 + 2) : t * 3 + 2;
    const ax = vx(a);
    const ay = vy(a);
    const az = vz(a);
    const bx = vx(b);
    const by = vy(b);
    const bz = vz(b);
    const cx = vx(c);
    const cy = vy(c);
    const cz = vz(c);
    const minX = Math.min(ax, bx, cx);
    const maxX = Math.max(ax, bx, cx);
    const minY = Math.min(ay, by, cy);
    const maxY = Math.max(ay, by, cy);
    const minZ = Math.min(az, bz, cz);
    const maxZ = Math.max(az, bz, cz);
    if (!keep(minX, maxX, minY, maxY, minZ, maxZ)) {
      continue;
    }
    triangleIndex[kept * 3] = a;
    triangleIndex[kept * 3 + 1] = b;
    triangleIndex[kept * 3 + 2] = c;
    kept++;
    const cx0 = regionIndex(minX, bounds.minX, spanX, grid);
    const cx1 = regionIndex(maxX, bounds.minX, spanX, grid);
    const cz0 = regionIndex(minZ, bounds.minZ, spanZ, grid);
    const cz1 = regionIndex(maxZ, bounds.minZ, spanZ, grid);
    const cy0 = regionIndex(minY, bounds.minY, spanY, ySlices);
    const cy1 = regionIndex(maxY, bounds.minY, spanY, ySlices);
    // 跨区域的三角形进多个桶（宁多勿漏）
    for (let cy = cy0; cy <= cy1; cy++) {
      for (let cz = cz0; cz <= cz1; cz++) {
        for (let cx = cx0; cx <= cx1; cx++) {
          const key = (cz * grid + cx) * ySlices + cy;
          counts[key] = (counts[key] ?? 0) + 1;
        }
      }
    }
  }
  if (kept === 0) {
    return;
  }
  const starts = new Uint32Array(bucketCount + 1);
  for (let b = 0; b < bucketCount; b++) {
    starts[b + 1] = starts[b]! + counts[b]!;
  }
  // 注意：flat 存的是索引（每面 3 个），starts 记的是三角面数
  const flat = new Uint32Array(starts[bucketCount]! * 3);
  const cursor = starts.slice(0, bucketCount);
  for (let t = 0; t < kept; t++) {
    const a = triangleIndex[t * 3]!;
    const b = triangleIndex[t * 3 + 1]!;
    const c = triangleIndex[t * 3 + 2]!;
    const minX = Math.min(vx(a), vx(b), vx(c));
    const maxX = Math.max(vx(a), vx(b), vx(c));
    const minY = Math.min(vy(a), vy(b), vy(c));
    const maxY = Math.max(vy(a), vy(b), vy(c));
    const minZ = Math.min(vz(a), vz(b), vz(c));
    const maxZ = Math.max(vz(a), vz(b), vz(c));
    const cx0 = regionIndex(minX, bounds.minX, spanX, grid);
    const cx1 = regionIndex(maxX, bounds.minX, spanX, grid);
    const cz0 = regionIndex(minZ, bounds.minZ, spanZ, grid);
    const cz1 = regionIndex(maxZ, bounds.minZ, spanZ, grid);
    const cy0 = regionIndex(minY, bounds.minY, spanY, ySlices);
    const cy1 = regionIndex(maxY, bounds.minY, spanY, ySlices);
    for (let cy = cy0; cy <= cy1; cy++) {
      for (let cz = cz0; cz <= cz1; cz++) {
        for (let cx = cx0; cx <= cx1; cx++) {
          const key = (cz * grid + cx) * ySlices + cy;
          const at = cursor[key]! * 3;
          flat[at] = a;
          flat[at + 1] = b;
          flat[at + 2] = c;
          cursor[key] = cursor[key]! + 1;
        }
      }
    }
  }
  for (let bucket = 0; bucket < bucketCount; bucket++) {
    const count = counts[bucket]!;
    if (count === 0) {
      continue;
    }
    const start = starts[bucket]! * 3;
    const indices = flat.subarray(start, start + count * 3);
    const sub = new THREE.BufferGeometry();
    // 顶点缓冲共享（省内存），索引各一份
    sub.setAttribute("position", position);
    sub.setIndex(new THREE.BufferAttribute(new Uint32Array(indices), 1));
    sub.boundingSphere = subsetSphere(indices, vx, vy, vz);
    const mesh = new THREE.Mesh(sub, node.material as THREE.Material);
    mesh.matrixAutoUpdate = false;
    mesh.matrix.multiplyMatrices(out.toTileLocal, node.matrixWorld);
    out.proxy.add(mesh);
  }
}

/**
 * 为一个瓦片组构建碰撞代理（不修改原网格，只新增不可见的子节点）。
 *
 * @param source 瓦片组（hires；LOD 柱顶是 2^L 方块内的最高表面，只作兜底，见 TileManager）
 * @param grid   XZ 每边切块数
 */
export function buildTileCollisionProxy(source: THREE.Object3D, grid = DEFAULT_GRID): TileCollisionProxies {
  const solid = new THREE.Group();
  solid.name = "collision-proxy-solid";
  const water = new THREE.Group();
  water.name = "collision-proxy-water";
  for (const group of [solid, water]) {
    // 不渲染：相机在 layer 0，这里也不进渲染列表（visible=false 对 Raycaster 无影响）
    group.visible = false;
  }
  // 网格可能嵌在 glb 的节点层级里：先刷新世界矩阵，待会儿换算成"相对瓦片组"的局部矩阵
  source.updateMatrixWorld(true);
  const toTileLocal = new THREE.Matrix4().copy(source.matrixWorld).invert();
  const meshes: THREE.Mesh[] = [];
  source.traverse((node) => {
    if (!(node instanceof THREE.Mesh)) {
      return;
    }
    const geometry = node.geometry as THREE.BufferGeometry;
    if (!geometry.getAttribute("position")) {
      return;
    }
    geometry.computeBoundingBox();
    if (!geometry.boundingBox) {
      return;
    }
    meshes.push(node);
  });
  if (meshes.length === 0) {
    return { solid, water };
  }
  // 整片瓦片共用一套分区边界：不同网格的子片在空间上对齐，射线剔除更有效
  const bounds = { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity, minZ: Infinity, maxZ: -Infinity };
  for (const mesh of meshes) {
    const box = (mesh.geometry as THREE.BufferGeometry).boundingBox!;
    bounds.minX = Math.min(bounds.minX, box.min.x);
    bounds.maxX = Math.max(bounds.maxX, box.max.x);
    bounds.minY = Math.min(bounds.minY, box.min.y);
    bounds.maxY = Math.max(bounds.maxY, box.max.y);
    bounds.minZ = Math.min(bounds.minZ, box.min.z);
    bounds.maxZ = Math.max(bounds.maxZ, box.max.z);
  }
  for (const node of meshes) {
    // 水面在瓦片里是独立的 translucent primitive（alphaMode BLEND）
    const isWater = (node.material as THREE.Material).transparent === true;
    bucketMesh(
      node,
      grid,
      bounds,
      (minX, maxX, minY, maxY, minZ, maxZ) => {
        const dx = maxX - minX;
        const dy = maxY - minY;
        const dz = maxZ - minZ;
        // 只要轴平行面：植物是 45° 交叉面片，三个方向都会跨开，这里就滤掉了
        return Math.min(dx, dy, dz) <= PLANAR_EPSILON;
      },
      { proxy: isWater ? water : solid, toTileLocal },
    );
  }
  return { solid, water };
}
