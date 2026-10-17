// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * Y 轴切片：只显示（并只参与碰撞）某个高度区间内的几何。
 *
 * 用途是「同一张地图看不同高度」：地表视图之外还想看地下矿道/建筑剖面，
 * 或者只想看地表以上的建筑而把地形挖掉。实现走**片元丢弃**而不是 clippingPlanes：
 *
 * - 切片区间是**世界 Y**。浮点原点只平移 X/Z（{@link FloatingOrigin} 的 Y 恒为 0），
 *   所以场景空间 Y 就是世界 Y，着色器里 `(modelMatrix * vec4(position,1)).y` 直接可用；
 * - 切片 uniform 与 {@link LightingUniforms} 一样是**共享对象**：所有瓦片材质在
 *   onBeforeCompile 里 `Object.assign` 的是同一份引用，改 `.value` 即时全场生效，
 *   不需要重编译着色器（判别是否丢弃的代码始终存在，靠 `enabled` 分支跳过）；
 * - 碰撞代理按 Y 分桶（{@link buildTileCollisionProxy}），切片开启时只要过滤掉
 *   与区间不相交的桶，射线就不会打到已被裁掉的地形上——否则会出现「站在空气上」。
 */
import * as THREE from "three";

/** Y 轴切片区间（含端点，方块坐标）。 */
export interface YSlice {
  min: number;
  max: number;
}

/** 「未开启」用的哨兵范围：比任何真实高度都宽。 */
const UNBOUNDED = 1e30;

/**
 * 共享 uniform（材质直接引用这几个对象，改 value 即时生效）。
 * `ySliceEnabled` 用数值而不是布尔：GLSL 里 `float` 是最省事的开关。
 */
export const YSliceUniforms = {
  ySliceMin: { value: -UNBOUNDED },
  ySliceMax: { value: UNBOUNDED },
  ySliceEnabled: { value: 0 },
};

let active: YSlice | null = null;

/**
 * 设置当前切片；null / 空区间等价于关闭。
 *
 * @param slice 世界 Y 区间（含端点）；min > max 时自动交换
 */
export function setYSlice(slice: YSlice | null): YSlice | null {
  if (!slice || !Number.isFinite(slice.min) || !Number.isFinite(slice.max)) {
    active = null;
    YSliceUniforms.ySliceEnabled.value = 0;
    YSliceUniforms.ySliceMin.value = -UNBOUNDED;
    YSliceUniforms.ySliceMax.value = UNBOUNDED;
    return null;
  }
  const min = Math.min(slice.min, slice.max);
  const max = Math.max(slice.min, slice.max);
  if (max - min <= 0) {
    // 零厚度切片没有可显示的内容，按关闭处理（否则整张图会被裁成空）
    active = null;
    YSliceUniforms.ySliceEnabled.value = 0;
    YSliceUniforms.ySliceMin.value = -UNBOUNDED;
    YSliceUniforms.ySliceMax.value = UNBOUNDED;
    return null;
  }
  active = { min, max };
  YSliceUniforms.ySliceMin.value = min;
  YSliceUniforms.ySliceMax.value = max;
  YSliceUniforms.ySliceEnabled.value = 1;
  return active;
}

/** 当前切片（未开启为 null）。 */
export function currentYSlice(): YSlice | null {
  return active ? { ...active } : null;
}

/** 世界 Y 是否落在当前切片内（未开启恒为 true）。 */
export function sliceContainsY(y: number): boolean {
  return !active || (y >= active.min && y <= active.max);
}

/**
 * 碰撞代理子节点（按 Y 分桶）是否与切片相交；未开启切片时恒为 true。
 *
 * 用**世界空间包围球**判断：包围球在代理构建时按索引子集算好
 * （three 只会按整个 position 属性算，不认索引子集），这里把球心随对象矩阵
 * 变换到世界空间即可——矩阵只含平移（浮点原点重定基也是平移），半径不变。
 */
export function bucketIntersectsSlice(object: THREE.Object3D, slice: YSlice | null = active): boolean {
  if (!slice) {
    return true;
  }
  const mesh = firstMesh(object);
  if (!mesh) {
    return true;
  }
  mesh.geometry.computeBoundingSphere();
  const sphere = mesh.geometry.boundingSphere;
  if (!sphere) {
    return true;
  }
  mesh.updateWorldMatrix(true, false);
  const center = sphere.center.clone().applyMatrix4(mesh.matrixWorld);
  const radius = sphere.radius * worldScale(mesh.matrixWorld);
  return center.y + radius >= slice.min && center.y - radius <= slice.max;
}

/**
 * 射线求交的目标列表：把代理组摊成**与切片相交的 Y 分桶**。
 *
 * 为什么不复制/重新挂载节点：桶本身就在场景里，`matrixWorld` 已由渲染循环更新；
 * 克隆出来的副本不在场景图上，父级变换要手工补，容易在浮点原点重定基后错位。
 * 直接把原对象交给 `Raycaster` 最稳。
 *
 * @param groups 代理组（solid / water）；切片未开启时原样返回（不分配）
 */
export function sliceRaycastTargets(
  groups: readonly THREE.Object3D[],
  slice: YSlice | null = active,
): THREE.Object3D[] {
  if (!slice) {
    return groups as THREE.Object3D[];
  }
  const targets: THREE.Object3D[] = [];
  for (const group of groups) {
    if (group.children.length === 0) {
      if (bucketIntersectsSlice(group, slice)) {
        targets.push(group);
      }
      continue;
    }
    for (const child of group.children) {
      if (bucketIntersectsSlice(child, slice)) {
        targets.push(child);
      }
    }
  }
  return targets;
}

function firstMesh(object: THREE.Object3D): THREE.Mesh | null {
  if (object instanceof THREE.Mesh) {
    return object;
  }
  for (const child of object.children) {
    const mesh = firstMesh(child);
    if (mesh) {
      return mesh;
    }
  }
  return null;
}

function worldScale(matrix: THREE.Matrix4): number {
  return (
    Math.abs(matrix.elements[0]!) +
    Math.abs(matrix.elements[5]!) +
    Math.abs(matrix.elements[10]!)
  ) / 3;
}
