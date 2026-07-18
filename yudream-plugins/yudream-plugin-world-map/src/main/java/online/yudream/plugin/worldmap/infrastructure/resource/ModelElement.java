package online.yudream.plugin.worldmap.infrastructure.resource;

import java.util.Map;

/**
 * 模型元素（一个立方体/长方体），局部 0..16 坐标系。
 *
 * @param from     最小角 xyz
 * @param to       最大角 xyz
 * @param rotation 元素级旋转（轴心单轴 ±22.5/±45），可为 null
 * @param shade    元素级 shade 标记（默认 true）
 * @param faces    方向名（down/up/north/south/east/west）→ 面定义
 */
record ModelElement(float[] from, float[] to, ElementRotation rotation,
                    boolean shade, Map<String, ModelFace> faces) {

    /** 元素级旋转定义。 */
    record ElementRotation(float[] origin, char axis, float angleDegrees, boolean rescale) {
    }
}

/**
 * 模型面定义。
 *
 * @param uv        [u1,v1,u2,v2]，0..16 纹理空间，可为 null（按方向自动推导）
 * @param texture   纹理引用（"#xxx" 变量或直接路径）
 * @param rotation  uv 面内旋转（0/90/180/270，顺时针）
 * @param cullface  遮挡剔除方向，可为 null
 * @param tintIndex 染色索引（原版基本只用 0），null 表示不染色
 */
record ModelFace(float[] uv, String texture, int rotation, String cullface, Integer tintIndex) {
}
