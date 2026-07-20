package online.yudream.plugin.worldmap.infrastructure.resource;

import java.util.List;
import java.util.Map;

/**
 * 父链合并 + 纹理变量解析完成的模型。
 *
 * @param elements           元素列表（可为空，如空气）
 * @param textures           变量名 → 最终纹理路径（不含 minecraft: 前缀、不含扩展名）
 * @param ambientOcclusion   模型级 AO 开关（默认 true）
 * @param found              模型 JSON 是否真实存在（false 表示解析失败/缺失）
 */
record ResolvedModel(List<ModelElement> elements, Map<String, String> textures,
                     boolean ambientOcclusion, boolean found) {

    /** 解析失败占位。 */
    static ResolvedModel missing() {
        return new ResolvedModel(List.of(), Map.of(), true, false);
    }
}
