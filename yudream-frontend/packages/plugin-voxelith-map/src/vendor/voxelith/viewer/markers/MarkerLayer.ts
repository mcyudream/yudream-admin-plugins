// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 标注渲染层：把 MarkerSet（与 voxelith-core 的 zod schema 同构）变成 three.js 对象。
 *
 * 支持五种标注：
 * - **poi**：世界坐标上的图钉（Sprite，永远朝向相机）+ 可选文字标签（Canvas 纹理）；
 * - **line**：折线（Line）；
 * - **shape**：XZ 平面多边形（可带洞），在 shapeY 高度铺一层半透明面；
 * - **extrude**：多边形沿 Y 拉伸出棱柱（shapeMinY → shapeMaxY）；
 * - **box**：轴对齐盒子。
 *
 * 关键约定：
 * - 标注坐标是**世界坐标**。层对象直接挂在场景根下，因此和瓦片一起被浮点原点重定基平移
 *   （FloatingOrigin 平移的是 scene.children），不需要自己减原点——否则会平移两次；
 * - minDistance / maxDistance 按**方块距离**解释（schema 里写明由前端渲染策略决定），
 *   每帧按相机位置开关可见性：远处的小 POI 与近处的区域轮廓各自有用；
 * - 标注层不参与碰撞，depthTest:false 的样式用于「穿透地形可见」的 POI。
 */
import * as THREE from "three";
import type { Marker, MarkerSet, Vec3 } from "../../core/index";

const DEFAULT_FILL = "#2f6fd0";
const DEFAULT_LINE = "#e6ebf4";

/** POI 图钉/标签纹理缓存上限（超过就整体清空重建，避免缓存本身变成泄漏）。 */
const MAX_TEXTURE_CACHE = 256;

/** 每个标记渲染出来的对象与其元数据。 */
interface MarkerEntry {
  marker: Marker;
  object: THREE.Object3D;
  setId: string;
  /** POI 图钉对象（用于拾取）；其余类型为 null。 */
  pin: THREE.Object3D | null;
  /** 世界坐标锚点（供「飞到标记」用）。 */
  anchor: THREE.Vector3;
  distanceFrom: number;
  distanceTo: number;
}

export interface MarkerPickResult {
  marker: Marker;
  setId: string;
  anchor: THREE.Vector3;
}

/** POI 图钉/标签的纹理来源（可注入：无 DOM 环境或测试里换掉 Canvas 实现）。 */
export interface MarkerTextureFactory {
  pin(color: string, icon?: string): THREE.Texture;
  label(text: string, color: string, opacity: number): THREE.Texture;
}

/** 默认实现：Canvas 画图钉与文字标签（浏览器环境）。 */
export const canvasTextureFactory: MarkerTextureFactory = {
  pin: (color, icon) => createPinTexture(color, icon),
  label: (text, color, opacity) => createLabelTexture(text, color, opacity),
};

export class MarkerLayer {
  /** 挂到场景根下的容器（跟随浮点原点重定基）。 */
  readonly object3d = new THREE.Group();

  private readonly entries: MarkerEntry[] = [];
  private readonly bySet = new Map<string, MarkerEntry[]>();
  private readonly hiddenSets = new Set<string>();
  private readonly labelScale: number;
  private readonly textures: MarkerTextureFactory;
  private readonly pickables: THREE.Object3D[] = [];
  private readonly cameraWorld = new THREE.Vector3();
  /**
   * 纹理缓存：同一套「颜色 + 图标」的图钉、同一段标签文字只生成一张 canvas 纹理。
   *
   * <p>不缓存的话，一张图 5000 个 POI 就是 1 万张纹理（每张 64² canvas），
   * 显存与解码开销都随标注数线性增长。上限 {@link MAX_TEXTURE_CACHE} 防止
   * 「每个标注都不同文字」时缓存自己变成泄漏源。</p>
   */
  private readonly textureCache = new Map<string, THREE.Texture>();

  constructor(options: { labelScale?: number; textureFactory?: MarkerTextureFactory } = {}) {
    this.object3d.name = "voxelith-markers";
    this.labelScale = options.labelScale ?? 1;
    this.textures = options.textureFactory ?? canvasTextureFactory;
  }

  /** 用一组标注集替换当前内容（旧的几何与纹理一并释放）。 */
  setMarkerSets(sets: readonly MarkerSet[]): void {
    this.clear();
    for (const set of sets) {
      const entries: MarkerEntry[] = [];
      for (const marker of set.markers) {
        const entry = this.build(marker, set.id);
        if (!entry) {
          continue;
        }
        entries.push(entry);
        this.entries.push(entry);
        this.object3d.add(entry.object);
        if (entry.pin) {
          this.pickables.push(entry.pin);
        }
      }
      this.bySet.set(set.id, entries);
      if (set.defaultHidden) {
        this.hiddenSets.add(set.id);
      }
      this.applyVisibility(set.id);
    }
  }

  /** 标注集整体显隐（默认由 MarkerSet.defaultHidden 决定）。 */
  setSetVisible(setId: string, visible: boolean): void {
    if (visible) {
      this.hiddenSets.delete(setId);
    } else {
      this.hiddenSets.add(setId);
    }
    this.applyVisibility(setId);
  }

  isSetVisible(setId: string): boolean {
    return !this.hiddenSets.has(setId);
  }

  /** 当前加载的标注集 id（按出现顺序）。 */
  setIds(): string[] {
    return [...this.bySet.keys()];
  }

  /** 某标注集里的 POI（供 UI 列表与「飞过去」使用）。 */
  pointsOfInterest(setId: string): MarkerPickResult[] {
    return (this.bySet.get(setId) ?? [])
      .filter((entry) => entry.marker.type === "poi")
      .map((entry) => ({
        marker: entry.marker,
        setId,
        anchor: entry.anchor.clone(),
      }));
  }

  /**
   * 每帧更新：按与相机的距离做 min/max 剔除，并让 POI 保持屏幕尺寸。
   */
  update(camera: THREE.Camera): void {
    camera.getWorldPosition(this.cameraWorld);
    for (const entry of this.entries) {
      if (this.hiddenSets.has(entry.setId)) {
        entry.object.visible = false;
        continue;
      }
      const distance = this.cameraWorld.distanceTo(entry.anchor);
      const visible = distance >= entry.distanceFrom && distance <= entry.distanceTo;
      entry.object.visible = visible;
      if (visible && entry.marker.type === "poi") {
        // 标签固定屏幕尺寸：距离越远放大越多，抵消透视缩小
        entry.object.scale.setScalar(Math.max(1, distance * 0.02 * this.labelScale));
      }
    }
  }

  /**
   * 射线拾取 POI（返回最近的一个）。
   *
   * @param camera Sprite 拾取需要相机（three 的 Sprite.raycast 要用它做面向相机的矩阵）
   */
  pick(raycaster: THREE.Raycaster, camera: THREE.Camera): MarkerPickResult | null {
    if (this.pickables.length === 0) {
      return null;
    }
    raycaster.camera = camera;
    const hits = raycaster.intersectObjects(this.pickables, false);
    for (const hit of hits) {
      const entry = this.entries.find((candidate) => candidate.pin === hit.object);
      if (entry && entry.object.visible) {
        return { marker: entry.marker, setId: entry.setId, anchor: entry.anchor.clone() };
      }
    }
    return null;
  }

  /** 释放全部几何/材质/纹理。 */
  dispose(): void {
    this.clear();
    this.object3d.removeFromParent();
  }

  private clear(): void {
    for (const entry of this.entries) {
      entry.object.removeFromParent();
      disposeObject(entry.object);
    }
    for (const texture of this.textureCache.values()) {
      texture.dispose();
    }
    this.textureCache.clear();
    this.entries.length = 0;
    this.bySet.clear();
    this.hiddenSets.clear();
    this.pickables.length = 0;
  }

  private applyVisibility(setId: string): void {
    const visible = !this.hiddenSets.has(setId);
    for (const entry of this.bySet.get(setId) ?? []) {
      entry.object.visible = visible;
    }
  }

  private build(marker: Marker, setId: string): MarkerEntry | null {
    switch (marker.type) {
      case "poi":
        return this.buildPoi(marker, setId);
      case "line":
        return this.buildLine(marker, setId);
      case "shape":
        return this.buildShape(marker, setId);
      case "extrude":
        return this.buildExtrude(marker, setId);
      case "box":
        return this.buildBox(marker, setId);
      default:
        return null;
    }
  }

  private buildPoi(marker: Extract<Marker, { type: "poi" }>, setId: string): MarkerEntry {
    const group = new THREE.Group();
    group.position.set(marker.position.x, marker.position.y, marker.position.z);

    const pinColor = marker.style.fillColor ?? DEFAULT_FILL;
    const pinTexture = this.cachedTexture(`pin|${pinColor}|${marker.style.icon ?? ""}`,
      () => this.textures.pin(pinColor, marker.style.icon));
    const pin = new THREE.Sprite(
      new THREE.SpriteMaterial({
        map: pinTexture,
        transparent: true,
        depthTest: marker.style.depthTest ?? true,
        opacity: marker.style.opacity ?? 1,
      }),
    );
    pin.name = `marker:${setId}:${marker.id}`;
    pin.scale.set(1.2, 1.8, 1);
    pin.position.y = 0.9;
    group.add(pin);

    if (marker.label) {
      const opacity = marker.style.opacity ?? 1;
      const labelTexture = this.cachedTexture(
        `label|${marker.label}|${pinColor}|${opacity}`,
        () => this.textures.label(marker.label, pinColor, opacity),
      );
      const label = new THREE.Sprite(
        new THREE.SpriteMaterial({
          map: labelTexture,
          transparent: true,
          depthTest: marker.style.depthTest ?? true,
        }),
      );
      label.position.y = 2.2;
      label.scale.set(4, 1, 1);
      group.add(label);
    }

    return {
      marker,
      object: group,
      setId,
      pin,
      anchor: new THREE.Vector3(marker.position.x, marker.position.y, marker.position.z),
      distanceFrom: marker.minDistance,
      distanceTo: marker.maxDistance,
    };
  }

  private buildLine(marker: Extract<Marker, { type: "line" }>, setId: string): MarkerEntry {
    const points = marker.points.map((point) => new THREE.Vector3(point.x, point.y, point.z));
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(points),
      new THREE.LineBasicMaterial({
        color: new THREE.Color(marker.style.lineColor ?? marker.style.fillColor ?? DEFAULT_LINE),
        transparent: (marker.style.opacity ?? 1) < 1,
        opacity: marker.style.opacity ?? 1,
        depthTest: marker.style.depthTest ?? true,
      }),
    );
    line.renderOrder = 2;
    return {
      marker,
      object: line,
      setId,
      pin: null,
      anchor: firstPoint(marker.points),
      distanceFrom: marker.minDistance,
      distanceTo: marker.maxDistance,
    };
  }

  private buildShape(marker: Extract<Marker, { type: "shape" }>, setId: string): MarkerEntry {
    const group = new THREE.Group();
    const mesh = new THREE.Mesh(
      flatPolygonGeometry(marker.shape, marker.holes, marker.shapeY),
      new THREE.MeshBasicMaterial({
        color: new THREE.Color(marker.style.fillColor ?? DEFAULT_FILL),
        transparent: true,
        opacity: marker.style.opacity ?? 0.45,
        side: THREE.DoubleSide,
        depthTest: marker.style.depthTest ?? true,
      }),
    );
    mesh.renderOrder = 1;
    group.add(mesh);
    if (marker.style.lineColor) {
      group.add(outline(marker.shape, marker.holes, marker.shapeY, marker.style));
    }
    return {
      marker,
      object: group,
      setId,
      pin: null,
      anchor: centroid(marker.shape, marker.shapeY),
      distanceFrom: marker.minDistance,
      distanceTo: marker.maxDistance,
    };
  }

  private buildExtrude(marker: Extract<Marker, { type: "extrude" }>, setId: string): MarkerEntry {
    const group = new THREE.Group();
    const height = Math.max(0.05, marker.shapeMaxY - marker.shapeMinY);
    const geometry = new THREE.ExtrudeGeometry(
      shapeFrom2d(marker.shape, marker.holes),
      { depth: height, bevelEnabled: false },
    );
    // ExtrudeGeometry 沿 +Z 拉伸：转到 +Y（世界垂直）并按 shapeMinY 抬升
    geometry.rotateX(-Math.PI / 2);
    geometry.translate(0, marker.shapeMinY, 0);
    const mesh = new THREE.Mesh(
      geometry,
      new THREE.MeshBasicMaterial({
        color: new THREE.Color(marker.style.fillColor ?? DEFAULT_FILL),
        transparent: true,
        opacity: marker.style.opacity ?? 0.35,
        side: THREE.DoubleSide,
        depthTest: marker.style.depthTest ?? true,
      }),
    );
    mesh.renderOrder = 1;
    group.add(mesh);
    return {
      marker,
      object: group,
      setId,
      pin: null,
      anchor: centroid(marker.shape, (marker.shapeMinY + marker.shapeMaxY) / 2),
      distanceFrom: marker.minDistance,
      distanceTo: marker.maxDistance,
    };
  }

  private buildBox(marker: Extract<Marker, { type: "box" }>, setId: string): MarkerEntry {
    const min = new THREE.Vector3(marker.min.x, marker.min.y, marker.min.z);
    const max = new THREE.Vector3(marker.max.x, marker.max.y, marker.max.z);
    const size = new THREE.Vector3()
      .subVectors(max, min)
      .max(new THREE.Vector3(0.05, 0.05, 0.05));
    const geometry = new THREE.BoxGeometry(size.x, size.y, size.z);
    const center = new THREE.Vector3().addVectors(min, max).multiplyScalar(0.5);
    const group = new THREE.Group();
    const mesh = new THREE.Mesh(
      geometry,
      new THREE.MeshBasicMaterial({
        color: new THREE.Color(marker.style.fillColor ?? DEFAULT_FILL),
        transparent: true,
        opacity: marker.style.opacity ?? 0.3,
        depthTest: marker.style.depthTest ?? true,
      }),
    );
    mesh.position.copy(center);
    group.add(mesh);
    if (marker.style.lineColor) {
      const edges = new THREE.LineSegments(
        new THREE.EdgesGeometry(geometry),
        new THREE.LineBasicMaterial({
          color: new THREE.Color(marker.style.lineColor),
          transparent: true,
          opacity: marker.style.opacity ?? 0.9,
          depthTest: marker.style.depthTest ?? true,
        }),
      );
      edges.position.copy(center);
      group.add(edges);
    }
    return {
      marker,
      object: group,
      setId,
      pin: null,
      anchor: center,
      distanceFrom: marker.minDistance,
      distanceTo: marker.maxDistance,
    };
  }

  /** 取缓存纹理；超过上限时先清空（宁可重建也不让缓存无界增长）。 */
  private cachedTexture(key: string, create: () => THREE.Texture): THREE.Texture {
    const cached = this.textureCache.get(key);
    if (cached) {
      return cached;
    }
    if (this.textureCache.size >= MAX_TEXTURE_CACHE) {
      for (const texture of this.textureCache.values()) {
        texture.dispose();
      }
      this.textureCache.clear();
    }
    const created = create();
    this.textureCache.set(key, created);
    return created;
  }
}

// ---------------------------------------------------------------------------
// 几何 / 纹理工具
// ---------------------------------------------------------------------------

function shapeFrom2d(
  outer: readonly { x: number; z: number }[],
  holes: readonly (readonly { x: number; z: number }[])[],
): THREE.Shape {
  const shape = new THREE.Shape();
  outer.forEach((point, index) => {
    if (index === 0) {
      shape.moveTo(point.x, point.z);
    } else {
      shape.lineTo(point.x, point.z);
    }
  });
  shape.closePath();
  for (const hole of holes) {
    const path = new THREE.Path();
    hole.forEach((point, index) => {
      if (index === 0) {
        path.moveTo(point.x, point.z);
      } else {
        path.lineTo(point.x, point.z);
      }
    });
    path.closePath();
    shape.holes.push(path);
  }
  return shape;
}

/**
 * XZ 平面多边形。
 *
 * ShapeGeometry 在 XY 平面上生成（shape 的 y 被当作 Z 用），绕 X 轴 -90° 后
 * 平面落到 XZ，再把整片按 shapeY 抬起。
 */
function flatPolygonGeometry(
  outer: readonly { x: number; z: number }[],
  holes: readonly (readonly { x: number; z: number }[])[],
  shapeY: number,
): THREE.BufferGeometry {
  const geometry = new THREE.ShapeGeometry(shapeFrom2d(outer, holes));
  geometry.rotateX(-Math.PI / 2);
  geometry.translate(0, shapeY, 0);
  return geometry;
}

function outline(
  outer: readonly { x: number; z: number }[],
  holes: readonly (readonly { x: number; z: number }[])[],
  shapeY: number,
  style: { lineColor?: string; opacity?: number; depthTest?: boolean },
): THREE.LineSegments {
  const points: THREE.Vector3[] = [];
  for (const ring of [outer, ...holes]) {
    for (let i = 0; i < ring.length; i++) {
      const a = ring[i]!;
      const b = ring[(i + 1) % ring.length]!;
      points.push(new THREE.Vector3(a.x, shapeY, a.z), new THREE.Vector3(b.x, shapeY, b.z));
    }
  }
  return new THREE.LineSegments(
    new THREE.BufferGeometry().setFromPoints(points),
    new THREE.LineBasicMaterial({
      color: new THREE.Color(style.lineColor ?? DEFAULT_LINE),
      transparent: true,
      opacity: style.opacity ?? 1,
      depthTest: style.depthTest ?? true,
    }),
  );
}

function centroid(outer: readonly { x: number; z: number }[], y: number): THREE.Vector3 {
  let x = 0;
  let z = 0;
  for (const point of outer) {
    x += point.x;
    z += point.z;
  }
  const count = Math.max(1, outer.length);
  return new THREE.Vector3(x / count, y, z / count);
}

function firstPoint(points: readonly Vec3[]): THREE.Vector3 {
  const first = points[0] ?? { x: 0, y: 0, z: 0 };
  return new THREE.Vector3(first.x, first.y, first.z);
}

/** 图钉纹理：水滴形（可选图标字符画在圆心）。 */
function createPinTexture(color: string, icon?: string): THREE.Texture {
  if (typeof document === "undefined") {
    return new THREE.Texture();
  }
  const size = 64;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    return new THREE.Texture();
  }
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(size / 2, size / 2 - 8, 16, Math.PI * 0.15, Math.PI * 0.85, true);
  ctx.lineTo(size / 2, size - 6);
  ctx.closePath();
  ctx.fill();
  ctx.strokeStyle = "rgba(255,255,255,0.85)";
  ctx.lineWidth = 3;
  ctx.stroke();
  if (icon) {
    ctx.fillStyle = "#ffffff";
    ctx.font = "18px sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(icon, size / 2, size / 2 - 8);
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

/** 文字标签纹理（POI 名称）。 */
function createLabelTexture(text: string, color: string, opacity: number): THREE.Texture {
  if (typeof document === "undefined") {
    return new THREE.Texture();
  }
  const font = 28;
  const padding = 12;
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    return new THREE.Texture();
  }
  ctx.font = `${font}px sans-serif`;
  canvas.width = Math.ceil(ctx.measureText(text).width + padding * 2);
  canvas.height = font + padding * 2;
  ctx.font = `${font}px sans-serif`;
  ctx.globalAlpha = Math.min(1, Math.max(0.2, opacity));
  ctx.fillStyle = "rgba(10,14,22,0.78)";
  roundRect(ctx, 0, 0, canvas.width, canvas.height, 10);
  ctx.fill();
  ctx.strokeStyle = color;
  ctx.lineWidth = 2;
  roundRect(ctx, 1, 1, canvas.width - 2, canvas.height - 2, 10);
  ctx.stroke();
  ctx.globalAlpha = 1;
  ctx.fillStyle = "#eef2f8";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(text, canvas.width / 2, canvas.height / 2);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
): void {
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.lineTo(x + width - radius, y);
  ctx.quadraticCurveTo(x + width, y, x + width, y + radius);
  ctx.lineTo(x + width, y + height - radius);
  ctx.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
  ctx.lineTo(x + radius, y + height);
  ctx.quadraticCurveTo(x, y + height, x, y + height - radius);
  ctx.lineTo(x, y + radius);
  ctx.quadraticCurveTo(x, y, x + radius, y);
  ctx.closePath();
}

function disposeObject(object: THREE.Object3D): void {
  object.traverse((node) => {
    const renderable = node as THREE.Mesh & { material?: THREE.Material | THREE.Material[] };
    renderable.geometry?.dispose?.();
    const material = renderable.material;
    if (Array.isArray(material)) {
      for (const entry of material) {
        entry.dispose();
      }
    } else {
      material?.dispose?.();
    }
  });
}
