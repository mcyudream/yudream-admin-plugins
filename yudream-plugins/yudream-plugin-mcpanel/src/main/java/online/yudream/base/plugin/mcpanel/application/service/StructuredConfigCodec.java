package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构化配置编解码：properties 之外的 YAML / TOML 树形配置读写。
 *
 * 面板视角统一为「点路径扁平键 → 字符串值」：
 * - 标量按 String.valueOf 展平；纯标量列表合并为逗号分隔串（编辑后按逗号拆回）；
 * - 表（Map）递归展平；列表内含表（如 BungeeCord listeners）与键名含 '.' 的项
 *   不参与结构化编辑（保持原文，由 raw 模式处理），避免 TOML 点键歧义；
 * - 结构化保存 = 解析当前文件 → 应用提供的键（按原值类型做类型还原）→ 全量重写。
 *   与 properties 行为一致：未提供的键保留；注释与文件头会丢失（UI 明确提示走原文模式）。
 *
 * TOML 序列化前做「标量在前、子表在后」重排：TOML 规范要求表内标量键先于子表，
 * jackson-dataformat-toml 不自动重排，读入顺序直接回写会产出非法 TOML。
 */
final class StructuredConfigCodec {

    enum Format {
        PROPERTIES, YAML, TOML, RAW
    }

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper TOML_MAPPER = new ObjectMapper(new TomlFactory());

    private StructuredConfigCodec() {
    }

    static Format formatOf(String path) {
        String normalized = path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".properties") || normalized.equals("eula.txt")) {
            return Format.PROPERTIES; // eula.txt 就是 eula=true 的 properties 语法
        }
        if (normalized.endsWith(".yml") || normalized.endsWith(".yaml")) {
            return Format.YAML;
        }
        if (normalized.endsWith(".toml")) {
            return Format.TOML;
        }
        return Format.RAW;
    }

    /** 解析为有序树；解析失败由调用方决定回退（原文编辑/报错），不在此吞异常。 */
    static Map<String, Object> parseTree(String content, Format format) throws IOException {
        if (content == null || content.isBlank()) {
            return new LinkedHashMap<>();
        }
        ObjectMapper mapper = mapperOf(format);
        if (mapper == null) {
            throw new IllegalArgumentException("格式不支持结构化解析: " + format);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = mapper.readValue(content, Map.class);
        return tree == null ? new LinkedHashMap<>() : tree;
    }

    static String writeTree(Map<String, Object> tree, Format format) throws IOException {
        ObjectMapper mapper = mapperOf(format);
        if (mapper == null) {
            throw new IllegalArgumentException("格式不支持结构化写出: " + format);
        }
        Object out = format == Format.TOML ? tomlOrdered(tree) : tree;
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    /** 树 → 点路径扁平键；跳过不可结构化表达的项（见类注释）。 */
    static Map<String, String> flatten(Map<String, Object> tree) {
        Map<String, String> out = new LinkedHashMap<>();
        collect("", tree, out);
        return out;
    }

    private static void collect(String prefix, Map<String, Object> node, Map<String, String> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.contains(".")) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) nested;
                collect(path, cast, out);
            }
            else if (value instanceof List<?> list) {
                if (list.stream().allMatch(item -> item == null || item instanceof String || isScalar(item))) {
                    List<String> parts = new ArrayList<>();
                    for (Object item : list) {
                        parts.add(item == null ? "" : String.valueOf(item));
                    }
                    out.put(path, String.join(", ", parts));
                }
                // 列表内含表（listeners 等）不展平：保持原文编辑。
            }
            else if (value == null || isScalar(value)) {
                out.put(path, value == null ? "" : String.valueOf(value));
            }
        }
    }

    /** 把扁平键编辑应用回树（按既有值类型还原布尔/数字/列表；新键落为字符串）。 */
    static Map<String, Object> applyEdits(Map<String, Object> tree, Map<String, String> edits) {
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            String key = edit.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String[] segments = key.split("\\.");
            if (segments.length == 0 || key.contains("..")) {
                continue;
            }
            Map<String, Object> cursor = tree;
            for (int i = 0; i < segments.length - 1; i++) {
                Object next = cursor.get(segments[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<>();
                    cursor.put(segments[i], next);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) next;
                cursor = cast;
            }
            String leaf = segments[segments.length - 1];
            cursor.put(leaf, coerce(edit.getValue(), cursor.get(leaf)));
        }
        return tree;
    }

    private static Object coerce(String raw, Object existingTypeHint) {
        String value = raw == null ? "" : raw;
        if (existingTypeHint instanceof Boolean) {
            return Boolean.parseBoolean(value.trim());
        }
        if (existingTypeHint instanceof Integer || existingTypeHint instanceof Long
                || existingTypeHint instanceof Short || existingTypeHint instanceof java.math.BigInteger) {
            try {
                return Long.parseLong(value.trim());
            }
            catch (NumberFormatException error) {
                return value;
            }
        }
        if (existingTypeHint instanceof Double || existingTypeHint instanceof Float
                || existingTypeHint instanceof java.math.BigDecimal) {
            try {
                return Double.parseDouble(value.trim());
            }
            catch (NumberFormatException error) {
                return value;
            }
        }
        if (existingTypeHint instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                items.add(coerce(trimmed, list.isEmpty() ? null : list.get(0)));
            }
            return items;
        }
        return value;
    }

    /** TOML 写出排序：每层先标量（含列表），后子表；递归。 */
    private static Map<String, Object> tomlOrdered(Map<String, Object> node) {
        Map<String, Object> scalars = new LinkedHashMap<>();
        Map<String, Object> tables = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) entry.getValue();
                tables.put(entry.getKey(), tomlOrdered(cast));
            }
            else {
                scalars.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> ordered = new LinkedHashMap<>(scalars);
        ordered.putAll(tables);
        return ordered;
    }

    private static ObjectMapper mapperOf(Format format) {
        return switch (format) {
            case YAML -> YAML_MAPPER;
            case TOML -> TOML_MAPPER;
            default -> null;
        };
    }

    private static boolean isScalar(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof String;
    }
}
