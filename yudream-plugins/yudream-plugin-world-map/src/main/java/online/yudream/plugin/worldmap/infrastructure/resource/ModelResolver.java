package online.yudream.plugin.worldmap.infrastructure.resource;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型解析器：负责 parent 父链合并（textures 覆盖合并、elements 就近覆盖、
 * ambientocclusion 就近覆盖）、纹理变量（#xxx）引用链解析、elements/faces 解析。
 */
final class ModelResolver {

    private final Map<String, byte[]> rawModels;
    private final Map<String, ResolvedModel> cache = new ConcurrentHashMap<>();

    ModelResolver(Map<String, byte[]> rawModels) {
        this.rawModels = rawModels;
    }

    /** 解析模型（带缓存）。路径可带 minecraft: 前缀；解析失败返回 {@link ResolvedModel#missing()}。 */
    ResolvedModel resolve(String modelPath) {
        String path = ResourcePackLoader.normalizePath(modelPath);
        if (path == null || path.isBlank() || path.startsWith("builtin/")) {
            // builtin/*（箱子和床等方块实体渲染）无几何元素，按缺失处理由上层决定表现
            return ResolvedModel.missing();
        }
        return cache.computeIfAbsent(path, this::doResolve);
    }

    private ResolvedModel doResolve(String path) {
        // 沿 parent 链收集（子在前、根在后），带环检测
        Deque<JsonNode> chain = new ArrayDeque<>();
        String current = path;
        Deque<String> guard = new ArrayDeque<>();
        while (current != null) {
            if (guard.contains(current)) {
                break; // 父链成环，截断
            }
            guard.addLast(current);
            byte[] raw = rawModels.get(current);
            if (raw == null) {
                if (chain.isEmpty()) {
                    return ResolvedModel.missing();
                }
                break; // 父缺失则用已有部分
            }
            JsonNode json = ResourcePackLoader.parseJson(raw);
            if (json == null || !json.isObject()) {
                break;
            }
            chain.addLast(json);
            JsonNode parent = json.get("parent");
            current = parent != null && parent.isTextual()
                    ? ResourcePackLoader.normalizePath(parent.asText()) : null;
            if (current != null && current.startsWith("builtin/")) {
                current = null; // builtin 无 JSON 元素
            }
        }
        if (chain.isEmpty()) {
            return ResolvedModel.missing();
        }

        // 合并：从根到子，子覆盖父
        Map<String, String> texturesRaw = new LinkedHashMap<>();
        List<ModelElement> elements = null;
        boolean ambientOcclusion = true;
        boolean foundAny = false;
        List<JsonNode> rootToChild = new ArrayList<>();
        chain.descendingIterator().forEachRemaining(rootToChild::add);
        for (JsonNode json : rootToChild) {
            foundAny = true;
            JsonNode textures = json.get("textures");
            if (textures != null && textures.isObject()) {
                textures.properties().forEach(e -> texturesRaw.put(e.getKey(), e.getValue().asText()));
            }
            if (json.has("elements")) {
                elements = parseElements(json.get("elements"));
            }
            if (json.has("ambientocclusion")) {
                ambientOcclusion = json.get("ambientocclusion").asBoolean(true);
            }
        }
        if (!foundAny) {
            return ResolvedModel.missing();
        }

        // 纹理变量链解析：每个 key 解析到最终路径
        Map<String, String> resolved = new HashMap<>();
        for (String key : texturesRaw.keySet()) {
            String path2 = resolveTexture(texturesRaw, key);
            if (path2 != null) {
                resolved.put(key, path2);
            }
        }
        return new ResolvedModel(elements == null ? List.of() : elements, resolved, ambientOcclusion, true);
    }

    /** 解析 "#a" → "#b" → "block/stone" 引用链，返回最终纹理路径；无法解析返回 null。 */
    private String resolveTexture(Map<String, String> raw, String key) {
        String value = raw.get(key);
        Deque<String> guard = new ArrayDeque<>();
        while (value != null && value.startsWith("#")) {
            String ref = value.substring(1);
            if (!guard.add(ref)) {
                return null; // 变量环
            }
            value = raw.get(ref);
        }
        return value == null ? null : ResourcePackLoader.normalizePath(value);
    }

    /** 解析 faces 中使用的纹理引用（"#side" 等）为最终路径。 */
    String resolveFaceTexture(ResolvedModel model, String textureRef) {
        if (textureRef == null) {
            return null;
        }
        if (textureRef.startsWith("#")) {
            return model.textures().get(textureRef.substring(1));
        }
        return ResourcePackLoader.normalizePath(textureRef);
    }

    private List<ModelElement> parseElements(JsonNode elementsNode) {
        List<ModelElement> result = new ArrayList<>();
        if (!elementsNode.isArray()) {
            return result;
        }
        for (JsonNode el : elementsNode) {
            float[] from = readVec3(el.get("from"), new float[]{0, 0, 0});
            float[] to = readVec3(el.get("to"), new float[]{16, 16, 16});
            ModelElement.ElementRotation rotation = parseRotation(el.get("rotation"));
            boolean shade = el.path("shade").asBoolean(true);
            Map<String, ModelFace> faces = new LinkedHashMap<>();
            JsonNode facesNode = el.get("faces");
            if (facesNode != null && facesNode.isObject()) {
                for (var entry : facesNode.properties()) {
                    ModelFace face = parseFace(entry.getValue());
                    if (face != null) {
                        faces.put(entry.getKey(), face);
                    }
                }
            }
            result.add(new ModelElement(from, to, rotation, shade, faces));
        }
        return result;
    }

    private ModelElement.ElementRotation parseRotation(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        float[] origin = readVec3(node.get("origin"), new float[]{8, 8, 8});
        String axisText = node.path("axis").asText("y");
        char axis = axisText.isEmpty() ? 'y' : axisText.charAt(0);
        float angle = (float) node.path("angle").asDouble(0);
        boolean rescale = node.path("rescale").asBoolean(false);
        return new ModelElement.ElementRotation(origin, axis, angle, rescale);
    }

    private ModelFace parseFace(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String texture = node.has("texture") ? node.get("texture").asText() : null;
        float[] uv = null;
        JsonNode uvNode = node.get("uv");
        if (uvNode != null && uvNode.isArray() && uvNode.size() >= 4) {
            uv = new float[4];
            for (int i = 0; i < 4; i++) {
                uv[i] = (float) uvNode.get(i).asDouble();
            }
        }
        int rotation = node.path("rotation").asInt(0);
        String cullface = node.has("cullface") ? node.get("cullface").asText() : null;
        Integer tintIndex = node.has("tintindex") ? node.get("tintindex").asInt() : null;
        return new ModelFace(uv, texture, rotation, cullface, tintIndex);
    }

    private static float[] readVec3(JsonNode node, float[] fallback) {
        if (node == null || !node.isArray() || node.size() < 3) {
            return fallback.clone();
        }
        return new float[]{
                (float) node.get(0).asDouble(),
                (float) node.get(1).asDouble(),
                (float) node.get(2).asDouble()};
    }
}
