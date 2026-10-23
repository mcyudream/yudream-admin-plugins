package online.yudream.base.plugin.aioffice.domain.service;

import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 映射表达式解析器（纯函数，可单测）。
 * source 取值域：ctx:点路径 | list:点路径 | ai:草稿变量 | text:字面量。
 * transform：date:pattern（epoch ms → 文本）| money（分 → 元文本）| join:分隔符（列表拼接）。
 */
public final class MappingResolver {

    private MappingResolver() {
    }

    /** 解析单个数据源：返回标量或 List（list: 前缀）。 */
    public static Object resolve(String source, Map<String, Object> context, Map<String, String> draft) {
        String trimmed = source == null ? "" : source.trim();
        if (trimmed.startsWith("text:")) {
            return trimmed.substring(5);
        }
        if (trimmed.startsWith("ai:")) {
            String variable = trimmed.substring(3).trim();
            return draft == null ? "" : draft.getOrDefault(variable, "");
        }
        if (trimmed.startsWith("list:")) {
            Object value = lookup(context, trimmed.substring(5).trim());
            return value instanceof List<?> list ? list : List.of();
        }
        if (trimmed.startsWith("ctx:")) {
            return lookup(context, trimmed.substring(4).trim());
        }
        // 无前缀时按 ctx: 宽容处理，便于手写映射
        return lookup(context, trimmed);
    }

    /** 对变换声明应用格式化；transform 为空时原样返回（非字符串转 String）。 */
    public static Object applyTransform(String transform, Object value) {
        if (transform == null || transform.isBlank()) {
            return value == null ? "" : value;
        }
        String rule = transform.trim();
        if (rule.startsWith("date:")) {
            String pattern = rule.substring(5).trim();
            long epoch = value instanceof Number number ? number.longValue() : parseLong(value);
            return new SimpleDateFormat(pattern.isEmpty() ? "yyyy年MM月dd日" : pattern)
                    .format(new Date(epoch));
        }
        if (rule.startsWith("money")) {
            long cents = value instanceof Number number ? number.longValue() : parseLong(value);
            return moneyText(cents);
        }
        if (rule.startsWith("join:")) {
            String separator = rule.substring(5);
            return joinText(value, separator);
        }
        return value == null ? "" : value;
    }

    /** 组装渲染数据：映射列表 → 变量名到标量/列表的映射。 */
    public static Map<String, Object> buildRenderData(List<OfficeDocType.Mapping> mappings,
                                                      Map<String, Object> context,
                                                      Map<String, String> draft) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (mappings == null) {
            return data;
        }
        for (OfficeDocType.Mapping mapping : mappings) {
            Object resolved = resolve(mapping.source(), context, draft);
            if (resolved instanceof List<?> list && !mapping.itemMapping().isEmpty()) {
                data.put(mapping.var(), buildItems(list, mapping.itemMapping(), context, draft));
            } else {
                data.put(mapping.var(), applyTransform(mapping.transform(), resolved));
            }
        }
        return data;
    }

    /** 列表映射的行数据：每个元素按 itemMapping 解析出行变量。 */
    public static List<Map<String, Object>> buildItems(List<?> elements, Map<String, String> itemMapping,
                                                       Map<String, Object> context, Map<String, String> draft) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object element : elements) {
            Map<String, Object> scope = new LinkedHashMap<>(context);
            scope.put("this", element);
            if (element instanceof Map<?, ?> map) {
                map.forEach((key, value) -> scope.put(String.valueOf(key), value));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            itemMapping.forEach((rowVar, rowSource) -> {
                String effective = rowSource.startsWith("item.") ? "this." + rowSource.substring(5) : rowSource;
                row.put(rowVar, applyTransform(null, resolve(effective, scope, draft)));
            });
            rows.add(row);
        }
        return rows;
    }

    /** 点路径取值：逐级 Map 键访问，缺失返回空串。 */
    public static Object lookup(Map<String, Object> context, String path) {
        if (context == null || path == null || path.isBlank()) {
            return "";
        }
        Object current = context;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(segment);
            if (current == null) {
                return "";
            }
        }
        return current;
    }

    /** 用上下文替换文本中的 {点路径} 占位（用于 AI 提示词组装）。 */
    public static String interpolate(String template, Map<String, Object> context, Map<String, String> draft) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{([a-zA-Z0-9_.]+)\\}").matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = draft != null && draft.containsKey(key)
                    ? draft.get(key) : lookup(context, key);
            matcher.appendReplacement(result, java.util.regex.Matcher
                    .quoteReplacement(value == null ? "" : stringify(value)));
            continue;
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String stringify(Object value) {
        if (value instanceof List<?> list) {
            return joinText(list, "；");
        }
        return String.valueOf(value);
    }

    private static String joinText(Object value, String separator) {
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    parts.add(String.valueOf(item));
                }
            }
            return String.join(separator == null ? "、" : separator, parts);
        }
        return value == null ? "" : String.valueOf(value);
    }

    public static String moneyText(long cents) {
        long yuan = cents / 100;
        long remainder = Math.abs(cents % 100);
        return remainder == 0 ? String.valueOf(yuan) : yuan + "." + String.format("%02d", remainder);
    }

    private static long parseLong(Object value) {
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
