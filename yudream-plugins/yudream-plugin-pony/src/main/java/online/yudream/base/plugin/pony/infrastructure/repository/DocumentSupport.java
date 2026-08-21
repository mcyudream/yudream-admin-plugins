package online.yudream.base.plugin.pony.infrastructure.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DocumentSupport {

    private DocumentSupport() {
    }

    /**
     * 宿主沙盒 overlay 存储会用 Map.copyOf 复制文档，null 值直接抛 NPE；写入存储前必须移除 null 值。
     */
    static void stripNulls(Map<String, Object> doc) {
        doc.values().removeIf(Objects::isNull);
    }

    static String string(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String stringOrDefault(Map<String, Object> doc, String key, String fallback) {
        String value = string(doc, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static int integer(Map<String, Object> doc, String key, int fallback) {
        Object value = doc.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    static long longValue(Map<String, Object> doc, String key, long fallback) {
        Object value = doc.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    static Long longObject(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    static List<Integer> intList(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                result.add(number.intValue());
            } else if (item != null) {
                try {
                    result.add(Integer.parseInt(String.valueOf(item)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }
}
