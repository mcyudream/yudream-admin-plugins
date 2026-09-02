package online.yudream.base.plugin.mcwiki.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依据客户端 JAR 内的物品/方块模型 JSON 解析「背包渲染图标」。
 * 与原版一致先把整条 parent 链的贴图表按「子覆盖父」合并，再跟随 #ref 间接引用；
 * 扁平物品取 layer0..2 图层，方块类物品取代表面（all/side/front/top/end/texture/particle）作为图标。
 * 所有贴图最终归一为相对 assets/minecraft/textures 的 .png 路径。
 */
public final class ItemIconResolver {
    /** 方块面选取优先级，覆盖原版常用键。 */
    private static final List<String> FACE_PREFERENCE = List.of("all", "side", "front", "top", "end", "texture", "particle");
    private static final int MAX_DEPTH = 16;
    private final ObjectMapper mapper;
    private final Map<String, String> itemModels;
    private final Map<String, String> blockModels;

    public ItemIconResolver(ObjectMapper mapper, Map<String, String> itemModels, Map<String, String> blockModels) {
        this.mapper = mapper; this.itemModels = itemModels; this.blockModels = blockModels;
    }

    /** @param layers 背包图标图层（有序，先底后顶），空表示无可用图标；@param faces 方块面贴图（仅方块模型存在时非空） */
    public record Resolved(List<String> layers, Map<String, String> faces) {
        public static Resolved empty() { return new Resolved(List.of(), Map.of()); }
    }

    public Resolved resolve(String namespacedId, String kind) {
        String path = namespacedId.contains(":") ? namespacedId.substring(namespacedId.indexOf(':') + 1) : namespacedId;
        Model model = parse(itemModels.get(path), false);
        if (model == null) model = parse(blockModels.get(path), true);
        if (model == null) return Resolved.empty();
        List<Model> chain = chain(model, model.block());
        Model block = blockModelIn(chain);
        if (block == null && "block".equals(kind)) {
            block = parse(blockModels.get(path), true);
            if (block != null) chain = chain(block, true);
        }
        Map<String, String> merged = merge(chain);
        List<String> layers = layersFrom(merged);
        Map<String, String> faces = Map.of();
        if (block != null) {
            Map<String, String> all = new LinkedHashMap<>();
            for (String key : merged.keySet()) {
                if (key.startsWith("layer")) continue;
                String resolved = resolveMerged(key, merged);
                if (resolved != null) all.put(key, resolved);
            }
            faces = all;
            if (layers.isEmpty()) {
                String representative = pick(faces);
                if (representative != null) layers = List.of(representative);
            }
        }
        return new Resolved(layers, faces);
    }

    /** 沿 parent 链收集模型（自身在前）。方块模型只在 blockModels 里查找，其余按前缀分派。 */
    private List<Model> chain(Model first, boolean blockContext) {
        List<Model> chain = new ArrayList<>();
        Model current = first;
        boolean inBlock = blockContext;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            chain.add(current);
            String parent = current.parent();
            if (parent == null || parent.isBlank() || parent.startsWith("builtin/")) break;
            String normalized = stripNamespace(parent);
            if (normalized.startsWith("block/")) { inBlock = true; normalized = normalized.substring("block/".length()); }
            else if (normalized.startsWith("item/")) { inBlock = false; normalized = normalized.substring("item/".length()); }
            String raw = inBlock ? blockModels.get(normalized) : itemModels.get(normalized);
            boolean foundInBlock = inBlock;
            if (raw == null && !inBlock) { raw = blockModels.get(normalized); foundInBlock = true; }
            if (raw == null && inBlock) { raw = itemModels.get(normalized); foundInBlock = false; }
            inBlock = foundInBlock;
            current = parse(raw, inBlock);
        }
        return chain;
    }

    /** 合并整条链的贴图表：子模型覆盖父模型同名键，与原版纹理解析语义一致。 */
    private Map<String, String> merge(List<Model> chain) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) merged.putAll(chain.get(i).textures());
        return merged;
    }

    /** 合并表中的 layer0..2 即背包图标图层。 */
    private List<String> layersFrom(Map<String, String> merged) {
        List<String> layers = new ArrayList<>();
        for (String key : List.of("layer0", "layer1", "layer2")) {
            String resolved = resolveMerged(key, merged);
            if (resolved != null) layers.add(resolved);
        }
        return layers;
    }

    /** 在合并表内跟随 #ref 直到拿到具体路径。 */
    private String resolveMerged(String key, Map<String, String> merged) {
        String current = key;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String value = merged.get(current);
            if (value == null) return null;
            if (value.startsWith("#")) { current = value.substring(1); continue; }
            return texturePath(value);
        }
        return null;
    }

    private Model blockModelIn(List<Model> chain) {
        for (Model model : chain) if (model.block()) return model;
        return null;
    }

    private String pick(Map<String, String> faces) {
        for (String key : FACE_PREFERENCE) if (faces.containsKey(key)) return faces.get(key);
        return faces.values().stream().findFirst().orElse(null);
    }

    /** minecraft:item/foo / item/foo → item/foo.png；已带 .png 后缀的不重复追加。 */
    private String texturePath(String value) {
        String path = stripNamespace(value);
        return path.endsWith(".png") ? path : path + ".png";
    }

    private String stripNamespace(String value) {
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private Model parse(String raw, boolean block) {
        if (raw == null) return null;
        try {
            JsonNode node = mapper.readTree(raw);
            Map<String, String> textures = new LinkedHashMap<>();
            node.path("textures").fields().forEachRemaining(e -> textures.put(e.getKey(), e.getValue().asText()));
            String parent = node.path("parent").asText(null);
            return new Model(parent, textures, block);
        } catch (Exception ex) { return null; }
    }

    private record Model(String parent, Map<String, String> textures, boolean block) {}
}
