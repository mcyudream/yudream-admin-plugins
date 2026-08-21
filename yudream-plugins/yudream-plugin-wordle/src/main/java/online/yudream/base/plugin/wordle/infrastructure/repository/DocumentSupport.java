package online.yudream.base.plugin.wordle.infrastructure.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    static boolean bool(Map<String, Object> doc, String key, boolean fallback) {
        Object value = doc.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
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

    static List<String> stringList(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    static Map<String, Integer> intMap(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            Object number = entry.getValue();
            if (number instanceof Number n) {
                result.put(String.valueOf(entry.getKey()), n.intValue());
            } else {
                try {
                    result.put(String.valueOf(entry.getKey()), Integer.parseInt(String.valueOf(number)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }
}
