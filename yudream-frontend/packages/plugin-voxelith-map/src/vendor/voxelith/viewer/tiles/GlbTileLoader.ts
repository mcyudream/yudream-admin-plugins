// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * glb 瓦片加载：GLTFLoader 包装 + 数据校验 + 材质归一化 + 资源释放。
 *
 * 瓦片几何的 POSITION 为瓦片局部坐标，加载后由 TileManager 平移到世界原点。
 * Phase 2 起瓦片携带烘焙光照（_LIGHT ubyte normalized：sky/block/ao）与染色（COLOR_0），
 * 统一转为带光照解码 shader 的 MeshBasicMaterial：texel × tint × 光照亮度 × AO，
 * 光照强度经全局共享 uniforms 参数化（昼夜调光），旧格式瓦片（无 _LIGHT）回退无光照渲染。
 *
 * 防御约定：
 * - 每个瓦片解析完成后先做 {@link validateTileGroup}：拦截 NaN/Infinity 顶点与越界索引，
 *   坏数据直接抛错进 TileManager 的 failed 集合，绝不允许带病进场景。
 * - hires 瓦片逐瓦片内嵌同一张图集 PNG；传入 sharedAtlas 时用共享纹理替换，
 *   内嵌副本立即 dispose —— 否则每帧几百张重复图集纹理会把显存耗尽。
 * - hires / LOD 由调用方显式传入的 level 区分，绝不从纹理过滤参数反推：
 *   着色器 sampler 状态会随 glb 编码细节漂移，一旦 LOD 被当成 hires，
 *   它的 0..1 全幅 UV 会去采样整张方块图集，整片变成红/青色块。
 */
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { MeshoptDecoder } from "three/addons/libs/meshopt_decoder.module.js";
import { YSliceUniforms } from "../engine/YSlice.js";

/**
 * 全局烘焙光照参数。所有瓦片材质共享同一组 uniform 引用，
 * 修改 value 即时全场景生效（设置面板的昼夜/调光入口）。
 */
export const LightingUniforms = {
  /** 天空光强度（0 = 夜晚，1 = 白天）。 */
  skyLightStrength: { value: 1.0 },
  /** 方块光强度（火把/岩浆等自发光源）。 */
  blockLightStrength: { value: 1.0 },
  /** 环境光遮蔽强度（0 = 关闭 AO）。 */
  aoStrength: { value: 1.0 },
  /** 环境光下限：完全无光处的保底亮度。 */
  ambientFloor: { value: 0.18 },
};

/** 瓦片几何数据校验失败（NaN/Infinity、索引越界等）。 */
export class TileGeometryError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TileGeometryError";
  }
}

/** 共享纹理标记：disposeTileGroup 不释放带此标记的 map（所有权在调用方）。 */
const SHARED_TEXTURE_KEY = "voxelithShared";

/**
 * 瓦片几何健全性校验：在 BufferGeometry 进场景前拦截脏数据。
 *
 * 检查项（发现即抛 {@link TileGeometryError}）：
 * 1. 缺少 position 属性；
 * 2. 任何 float32 属性（position/normal/uv 等）含 NaN 或 Infinity —— GPU 端表现为顶点爆炸；
 * 3. 索引最大值越界（>= position.count）—— GPU 端表现为随机三角形/条状拉伸。
 *
 * 成本：每瓦片数万次数组读，相对网络与解码耗时可忽略；Uint8 属性天然有限，跳过。
 */
export function validateTileGroup(group: THREE.Group, label = "tile"): void {
  group.traverse((node) => {
    if (!(node instanceof THREE.Mesh)) {
      return;
    }
    const geometry = node.geometry as THREE.BufferGeometry;
    const position = geometry.getAttribute("position");
    if (!position) {
      throw new TileGeometryError(`${label}: 缺少 position 属性`);
    }
    for (const [name, attribute] of Object.entries(geometry.attributes)) {
      const array = (attribute as THREE.BufferAttribute).array;
      if (!(array instanceof Float32Array)) {
        continue;
      }
      for (let i = 0; i < array.length; i++) {
        if (!Number.isFinite(array[i])) {
          throw new TileGeometryError(
            `${label}: 属性 ${name} 含 NaN/Infinity（元素 ${i}/${array.length}）`,
          );
        }
      }
    }
    const index = geometry.getIndex();
    if (index) {
      const array = index.array as unknown as ArrayLike<number>;
      let max = -1;
      for (let i = 0; i < array.length; i++) {
        const v = array[i] ?? -1;
        if (v > max) {
          max = v;
        }
      }
      if (max >= position.count) {
        throw new TileGeometryError(
          `${label}: 索引越界 max=${max}，顶点数=${position.count}`,
        );
      }
    }
  });
}

/**
 * 释放瓦片组的全部 GPU 资源：geometry + material + 纹理。
 * 共享图集（带 SHARED_TEXTURE_KEY 标记）不释放，所有权在 TileManager。
 */
export function disposeTileGroup(group: THREE.Group): void {
  group.traverse((node) => {
    if (!(node instanceof THREE.Mesh)) {
      return;
    }
    node.geometry.dispose();
    const material = node.material as THREE.MeshBasicMaterial;
    const map = material.map;
    if (map && !(map.userData && map.userData[SHARED_TEXTURE_KEY])) {
      map.dispose();
    }
    material.dispose();
  });
}

export interface GlbTileLoaderOptions {
  /**
   * hires 共享图集纹理：替换每瓦片内嵌的图集副本（每个 hires glb 都内嵌同一张
   * 图集 PNG，GLTFLoader 会逐瓦片解码出独立 GPU 纹理，显存随加载数线性增长）。
   * 必须按 {@link configureHiresAtlas} 配置；LOD 层不受影响。
   */
  sharedAtlas?: THREE.Texture;
  /**
   * LOD 每层共享图集页（level → 纹理）。全量生成的 LOD 瓦片不含内嵌色图，
   * 其 UV 已烘焙成该层的图集坐标，纹理只能由这里提供。
   */
  lodAtlases?: ReadonlyMap<number, THREE.Texture>;
}

/**
 * hires 图集过滤：禁止 mipmap。256² 图集里 69 个格子，任何带 mip 的 minFilter
 * 都会在格子边界串色，俯视时整片变成彩虹噪点。anisotropy 同样会跨格采样。
 */
export function configureHiresAtlas(texture: THREE.Texture): THREE.Texture {
  texture.flipY = false;
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.generateMipmaps = false;
  texture.anisotropy = 1;
  texture.wrapS = THREE.ClampToEdgeWrapping;
  texture.wrapT = THREE.ClampToEdgeWrapping;
  texture.needsUpdate = true;
  return texture;
}

/** LOD 航拍色图：与 glTF sampler（LINEAR / LINEAR / CLAMP）一致，不生成 mip。 */
export function configureLodColormap(texture: THREE.Texture): THREE.Texture {
  texture.flipY = false;
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.magFilter = THREE.LinearFilter;
  texture.minFilter = THREE.LinearFilter;
  texture.generateMipmaps = false;
  texture.anisotropy = 1;
  texture.wrapS = THREE.ClampToEdgeWrapping;
  texture.wrapT = THREE.ClampToEdgeWrapping;
  return texture;
}

/**
 * 按层级决定 hires 瓦片的漫反射贴图用哪张，并返回实际应挂到材质上的纹理。
 *
 * 判据只能是调用方传入的 `lod`，不能从纹理 sampler 状态反推：一旦 LOD 被判成 hires，
 * 它的 0..1 全幅 UV 会去采样整张方块图集，整片渲染成红/青色块。
 *
 * @param embedded    glb 内嵌纹理（共享图集模式下为 null）
 * @param lod         true = LOD 层（level > 0）
 * @param sharedAtlas hires 共享图集；为 undefined 时就地按图集参数配置内嵌副本
 * @returns 与原内嵌纹理不同时，调用方负责释放内嵌副本
 */
export function resolveTileTexture(
  embedded: THREE.Texture | null,
  lod: boolean,
  sharedAtlas?: THREE.Texture,
): THREE.Texture | null {
  if (lod) {
    return embedded ? configureLodColormap(embedded) : null;
  }
  // hires：清单声明了共享图集就用它。瓦片可能内嵌了一份图集副本（旧格式），
  // 也可能只带 UV 不内嵌（共享图集模式）——两种情况都换成共享那张。
  if (sharedAtlas) {
    return sharedAtlas;
  }
  return embedded ? configureHiresAtlas(embedded) : null;
}

/**
 * LOD 瓦片的漫反射贴图选择。
 *
 * 优先级：**内嵌色图 > 该层图集页**。两种瓦片共存于同一张地图：
 * - 全量生成的瓦片不内嵌 PNG，UV 已烘焙成图集坐标 → 用该层图集页；
 * - 增量重跑或旧格式的瓦片内嵌自己的 128² 色图，UV 是瓦片局部 0..1
 *   → 必须保留内嵌色图，否则会拿局部 UV 去采整页图集（乱色）。
 *
 * @param embedded glb 内嵌纹理（无则 null）
 * @param atlas    该层图集页纹理（清单未声明该层则 undefined）
 * @returns 应挂到材质上的纹理；两者都没有时返回 null（仅方向明暗）
 */
export function resolveLodTexture(
  embedded: THREE.Texture | null,
  atlas?: THREE.Texture,
): THREE.Texture | null {
  if (embedded) {
    return configureLodColormap(embedded);
  }
  if (atlas) {
    return configureLodColormap(atlas);
  }
  return null;
}

export class GlbTileLoader {
  private readonly loader = new GLTFLoader();
  private readonly sharedAtlas?: THREE.Texture;
  private readonly lodAtlases: ReadonlyMap<number, THREE.Texture>;

  constructor(options: GlbTileLoaderOptions = {}) {
    // EXT_meshopt_compression 瓦片（EncodeOptions.meshopt）靠自带 WASM 解码器还原几何。
    // 不挂解码器时 GLTFLoader 会因 extensionsRequired 直接抛「setMeshoptDecoder must be called」。
    this.loader.setMeshoptDecoder(MeshoptDecoder);
    this.sharedAtlas = options.sharedAtlas;
    if (this.sharedAtlas) {
      configureHiresAtlas(this.sharedAtlas);
      this.sharedAtlas.userData[SHARED_TEXTURE_KEY] = true;
    }
    this.lodAtlases = options.lodAtlases ?? new Map();
    for (const atlas of this.lodAtlases.values()) {
      configureLodColormap(atlas);
      atlas.userData[SHARED_TEXTURE_KEY] = true;
    }
  }

  /**
   * 加载并归一化一个瓦片。
   *
   * @param url   瓦片地址（含缓存版本戳）
   * @param level LOD 层级：0 = hires（内嵌共享图集副本），>0 = LOD（内嵌色图或层级图集页）。
   *              必传：hires/LOD 的纹理处理不同，靠猜会整片渲染错色。
   */
  async load(url: string, level: number): Promise<THREE.Group> {
    const gltf = await this.loader.loadAsync(url);
    const root = gltf.scene;
    validateTileGroup(root, url);
    // 同一 glb 内多个 primitive（opaque + 水面）共享同一内嵌纹理，替换时去重避免重复 dispose
    const replaced = new Set<THREE.Texture>();
    root.traverse((node) => {
      if (node instanceof THREE.Mesh) {
        node.material = this.toLitMaterial(node, replaced, level);
        node.matrixAutoUpdate = false;
        node.updateMatrix();
      }
    });
    return root;
  }

  private toLitMaterial(
    mesh: THREE.Mesh,
    replaced: Set<THREE.Texture>,
    level: number,
  ): THREE.MeshBasicMaterial {
    const source = mesh.material as THREE.MeshStandardMaterial;
    const geometry = mesh.geometry;
    const hasBakedLight = geometry.hasAttribute("_light");
    const material = new THREE.MeshBasicMaterial({
      map: source.map ?? null,
      alphaTest: source.alphaTest > 0 ? source.alphaTest : 0,
      transparent: source.transparent,
      opacity: source.transparent ? source.opacity : 1,
      side: THREE.FrontSide,
      vertexColors: geometry.hasAttribute("color"),
    });
    if (source.transparent) {
      // 半透明面（水）：写入深度以避免相邻瓦片叠blend接缝；透明通道内按距离排序绘制
      material.depthWrite = true;
    }
    if (level > 0) {
      // LOD：内嵌色图（增量/旧瓦片）优先，否则用该层共享图集页（全量瓦片）。
      // 两者都没有时保持 map=null，仅由 COLOR_0 的方向明暗着色。
      const embedded = material.map;
      const atlas = this.lodAtlases.get(level);
      const resolved = resolveLodTexture(embedded, atlas);
      if (resolved !== embedded) {
        material.map = resolved;
        if (embedded && !replaced.has(embedded)) {
          // 换成图集页后，逐瓦片内嵌色图不再需要
          replaced.add(embedded);
          embedded.dispose();
        }
      }
    } else {
      const shared = resolveTileTexture(material.map, false, this.sharedAtlas);
      if (shared !== material.map) {
        // 多 primitive 共享同一内嵌纹理：第二个 mesh 同样换掉，但只 dispose 一次
        const embedded = material.map;
        material.map = shared;
        if (embedded && !replaced.has(embedded)) {
          replaced.add(embedded);
          embedded.dispose();
        }
      }
    }
    material.onBeforeCompile = (shader) => {
      // 共享引用：LightingUniforms 的 value 修改自动同步到所有已编译材质
      Object.assign(shader.uniforms, LightingUniforms);
      // Y 轴切片：同样共享引用。判别代码常驻（uniform 开关），切片开关不触发重编译
      Object.assign(shader.uniforms, YSliceUniforms);
      shader.vertexShader = shader.vertexShader
        .replace(
          "#include <common>",
          `#include <common>
${hasBakedLight ? "attribute vec3 _light;\nvarying vec3 vBakedLight;" : ""}
varying float vSliceY;`,
        )
        .replace(
          "#include <begin_vertex>",
          `#include <begin_vertex>
${hasBakedLight ? "vBakedLight = _light;" : ""}
vSliceY = (modelMatrix * vec4(transformed, 1.0)).y;`,
        );
      shader.fragmentShader = shader.fragmentShader
        .replace(
          "#include <common>",
          `#include <common>
${hasBakedLight ? "varying vec3 vBakedLight;" : ""}
varying float vSliceY;
uniform float ySliceEnabled;
uniform float ySliceMin;
uniform float ySliceMax;
uniform float skyLightStrength;
uniform float blockLightStrength;
uniform float aoStrength;
uniform float ambientFloor;`,
        )
        .replace(
          "#include <color_fragment>",
          `#include <color_fragment>
if (ySliceEnabled > 0.5 && (vSliceY < ySliceMin || vSliceY > ySliceMax)) {
  discard;
}
${
  hasBakedLight
    ? `{
  float lum = max(vBakedLight.x * skyLightStrength, vBakedLight.y * blockLightStrength);
  float bright = mix(ambientFloor, 1.0, pow(lum, 1.3));
  float ao = 1.0 - aoStrength * 0.75 * vBakedLight.z;
  diffuseColor.rgb *= bright * ao;
}`
    : ""
}`,
        );
    };
    // onBeforeCompile 与默认材质不同，必须区分程序缓存键
    material.customProgramCacheKey = () =>
      hasBakedLight ? "yudream-baked-light" : "yudream-plain";
    // GLTFLoader 创建的原始 PBR 材质不再使用，立即释放
    source.dispose();
    return material;
  }
}
