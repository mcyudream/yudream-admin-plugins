// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * Display P3 广色域输出。
 *
 * three 0.179 的运行时只注册了 srgb / srgb-linear 两种输出色彩空间
 * （DisplayP3ColorSpace 常量与类型均已移除），因此 P3 模式由两个手动步骤组成：
 *
 * 1. `gl.drawingBufferColorSpace = "display-p3"` —— 让画布后备缓冲按 P3 解释；
 * 2. 全局替换 `ShaderChunk.colorspace_fragment`：sRGB 与 P3 使用相同的 sRGB 传递函数，
 *    差别仅在基色，故在原有"线性 → 输出编码"之前插入一次 线性sRGB → 线性P3 的 3×3 矩阵，
 *    后续 sRGB 编码照常执行即可得到正确的 P3 输出。背景清屏色同样走该 chunk，无需特判。
 *
 * 色彩基准仍是 sRGB：所有纹理/顶点色按 sRGB 创作、在线性空间计算，
 * P3 只是输出端的基色映射，不改变创作管线。
 */
import * as THREE from "three";

/** 线性 sRGB → 线性 Display P3（D65）基色转换，GLSL mat3 构造为列主序。 */
const LINEAR_SRGB_TO_P3 = `mat3(
  0.8224621, 0.0331941, 0.0170827,
  0.1775380, 0.9668058, 0.0723974,
  0.0,       0.0,       0.9105199
)`;

const P3_CHUNK = `gl_FragColor.rgb = ${LINEAR_SRGB_TO_P3} * gl_FragColor.rgb;\ngl_FragColor = linearToOutputTexel( gl_FragColor );`;

let originalChunk: string | null = null;
let patched = false;

function patchOutputChunk(p3: boolean): void {
  if (p3 === patched) {
    return;
  }
  if (p3) {
    originalChunk = THREE.ShaderChunk.colorspace_fragment;
    THREE.ShaderChunk.colorspace_fragment = P3_CHUNK;
  } else if (originalChunk !== null) {
    THREE.ShaderChunk.colorspace_fragment = originalChunk;
    originalChunk = null;
  }
  patched = p3;
}

/** 屏幕宣称支持 P3（CSS media query）；真正生效还需 WebGL 接受 display-p3 后备缓冲。 */
export function supportsDisplayP3(): boolean {
  return typeof window !== "undefined" && window.matchMedia?.("(color-gamut: p3)").matches === true;
}

/** 检测并开启 P3 后备缓冲；返回是否成功。失败时恢复 sRGB 并返回 false。 */
export function tryEnableP3DrawingBuffer(renderer: THREE.WebGLRenderer): boolean {
  const gl = renderer.getContext() as WebGLRenderingContext & { drawingBufferColorSpace?: string };
  try {
    gl.drawingBufferColorSpace = "display-p3";
    if (gl.drawingBufferColorSpace === "display-p3") {
      return true;
    }
  } catch {
    // 旧浏览器不支持该属性
  }
  try {
    gl.drawingBufferColorSpace = "srgb";
  } catch {
    // 忽略
  }
  return false;
}

/** 全局片元输出 chunk 的 P3 补丁开关（幂等）。切换后已有材质需 needsUpdate 重编译。 */
export function setP3OutputTransform(enabled: boolean): void {
  patchOutputChunk(enabled);
}
