package online.yudream.plugin.worldmap.infrastructure.resource;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * blockstate JSON 定义：variants 或 multipart 二选一（原版惯例）。
 * variants 键为属性组合（含 "" 空键）；multipart 为 when 条件（AND / OR / "|" 多值）+ apply 列表。
 */
final class BlockStateDefinition {

    /** 模型引用（blockstate 层）：模型路径 + x/y 旋转（90 倍数）+ uvlock。 */
    record ModelRef(String model, int x, int y, boolean uvlock) {
    }

    /** when 条件：对完整属性表求值。 */
    interface Condition {
        boolean test(Map<String, String> props);

        Condition ALWAYS = props -> true;

        /** 单条件组：多个 属性→允许值集合，全部满足（AND）。 */
        static Condition and(Map<String, Set<String>> clauses) {
            if (clauses.isEmpty()) {
                return ALWAYS;
            }
            return props -> clauses.entrySet().stream()
                    .allMatch(e -> e.getValue().contains(props.get(e.getKey())));
        }

        /** OR 组：任一子条件满足。 */
        static Condition or(List<Condition> children) {
            if (children.isEmpty()) {
                return ALWAYS;
            }
            return props -> children.stream().anyMatch(c -> c.test(props));
        }
    }

    private record MultipartPart(Condition when, List<ModelRef> apply) {
    }

    /** variants 条目：键的属性表 + 模型列表（权重忽略，取第一个）。 */
    private record VariantEntry(Map<String, String> props, List<ModelRef> refs) {
    }

    /** variants：规范化属性键（排序后的 kv 串）→ 条目（精确匹配快速路径）。 */
    private final Map<String, VariantEntry> variants;
    private final List<MultipartPart> multipartParts;

    private BlockStateDefinition(Map<String, VariantEntry> variants, List<MultipartPart> multipartParts) {
        this.variants = variants;
        this.multipartParts = multipartParts;
    }

    /** 解析 blockstate JSON；无 variants/multipart 时返回 null。 */
    static BlockStateDefinition parse(JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        JsonNode variantsNode = json.get("variants");
        if (variantsNode != null && variantsNode.isObject()) {
            Map<String, VariantEntry> variants = new LinkedHashMap<>();
            for (var entry : variantsNode.properties()) {
                Map<String, String> keyProps = parseVariantKey(entry.getKey());
                variants.put(normalizeVariantKey(entry.getKey()),
                        new VariantEntry(keyProps, parseModelList(entry.getValue())));
            }
            return new BlockStateDefinition(variants, List.of());
        }
        JsonNode multipartNode = json.get("multipart");
        if (multipartNode != null && multipartNode.isArray()) {
            List<MultipartPart> parts = new ArrayList<>();
            for (JsonNode part : multipartNode) {
                Condition when = parseCondition(part.get("when"));
                List<ModelRef> apply = parseModelList(part.get("apply"));
                if (!apply.isEmpty()) {
                    parts.add(new MultipartPart(when, apply));
                }
            }
            return new BlockStateDefinition(Map.of(), parts);
        }
        return null;
    }

    /**
     * 按属性表选出模型引用列表。
     * variants：先精确匹配；未命中再做子集匹配（1.20.2+ 允许省略不影响模型的属性，
     * 如 leaves 的 distance/persistent 被合并为 "" 空键），取约束最多的条目。
     * multipart：合并所有命中部分的首个模型。
     */
    List<ModelRef> modelsFor(Map<String, String> props) {
        if (!variants.isEmpty()) {
            VariantEntry exact = variants.get(variantKeyOf(props));
            if (exact != null) {
                return List.of(exact.refs().get(0));
            }
            VariantEntry best = null;
            for (VariantEntry entry : variants.values()) {
                if (!isSubset(entry.props(), props)) {
                    continue;
                }
                if (best == null || entry.props().size() > best.props().size()) {
                    best = entry;
                }
            }
            return best == null ? List.of() : List.of(best.refs().get(0));
        }
        List<ModelRef> result = new ArrayList<>();
        for (MultipartPart part : multipartParts) {
            if (part.when().test(props)) {
                result.add(part.apply().get(0));
            }
        }
        return result;
    }

    /** 键的属性表是否为状态属性表的子集。 */
    private static boolean isSubset(Map<String, String> keyProps, Map<String, String> props) {
        for (var entry : keyProps.entrySet()) {
            if (!entry.getValue().equals(props.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** 是否包含能匹配该属性的模型（用于区分"合法无面"与"缺失"）。 */
    boolean matches(Map<String, String> props) {
        return !modelsFor(props).isEmpty();
    }

    /** 是否为 variants 形式（false 即 multipart 形式）。 */
    boolean hasVariants() {
        return !variants.isEmpty();
    }

    /** 收集全部引用的模型路径（用于贴图集预收集）。 */
    List<String> allModelPaths() {
        List<String> paths = new ArrayList<>();
        if (!variants.isEmpty()) {
            variants.values().forEach(entry -> entry.refs().forEach(r -> paths.add(r.model())));
        } else {
            multipartParts.forEach(p -> p.apply().forEach(r -> paths.add(r.model())));
        }
        return paths;
    }

    // ---------- 解析辅助 ----------

    /** 解析 variants 键为属性表（"" → 空表）。 */
    private static Map<String, String> parseVariantKey(String key) {
        if (key == null || key.isBlank()) {
            return Map.of();
        }
        Map<String, String> props = new LinkedHashMap<>();
        for (String kv : key.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                props.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        return props;
    }

    private static List<ModelRef> parseModelList(JsonNode node) {
        List<ModelRef> refs = new ArrayList<>();
        if (node == null) {
            return refs;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                ModelRef ref = parseModelRef(child);
                if (ref != null) {
                    refs.add(ref);
                }
            }
        } else {
            ModelRef ref = parseModelRef(node);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static ModelRef parseModelRef(JsonNode node) {
        if (node == null || !node.isObject() || !node.has("model")) {
            return null;
        }
        String model = ResourcePackLoader.normalizePath(node.get("model").asText());
        int x = normalizeAngle(node.path("x").asInt(0));
        int y = normalizeAngle(node.path("y").asInt(0));
        boolean uvlock = node.path("uvlock").asBoolean(false);
        return new ModelRef(model, x, y, uvlock);
    }

    private static int normalizeAngle(int angle) {
        int a = angle % 360;
        return a < 0 ? a + 360 : a;
    }

    private static Condition parseCondition(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return Condition.ALWAYS;
        }
        JsonNode or = node.get("OR");
        if (or != null && or.isArray()) {
            List<Condition> children = new ArrayList<>();
            for (JsonNode child : or) {
                children.add(parseCondition(child));
            }
            return Condition.or(children);
        }
        Map<String, Set<String>> clauses = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            Set<String> values = Arrays.stream(entry.getValue().asText().split("\\|"))
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            clauses.put(entry.getKey(), values);
        }
        return Condition.and(clauses);
    }

    /** 规范化 variants 键："b=2,a=1" → "a=1,b=2"，便于精确匹配。 */
    private static String normalizeVariantKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return Arrays.stream(key.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /** 由属性表生成规范化 variants 键。 */
    private static String variantKeyOf(Map<String, String> props) {
        return props.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }
}
