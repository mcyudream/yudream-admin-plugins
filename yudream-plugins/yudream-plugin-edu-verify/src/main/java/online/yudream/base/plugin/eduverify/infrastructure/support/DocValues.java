package online.yudream.base.plugin.eduverify.infrastructure.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 宿主文档存储值读取助手：容忍 long 序列化为 JSON 字符串的宿主行为，并剥离 null。 */
public final class DocValues {

    private DocValues() {
    }

    public static String string(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static long number(Map<String, Object> document, String key, long defaultValue) {
        Object value = document.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(String.valueOf(value));
    }

    public static int integer(Map<String, Object> document, String key, int defaultValue) {
        return (int) number(document, key, defaultValue);
    }

    public static boolean bool(Map<String, Object> document, String key, boolean defaultValue) {
        Object value = document.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Map<String, Object> document, String key) {
        Object value = document.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    public static Map<String, String> stringMap(Map<String, Object> document, String key) {
        Object value = document.get(key);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        map.forEach((entryKey, entryValue) -> {
            if (entryKey == null || entryValue == null) {
                return;
            }
            String text = String.valueOf(entryValue).trim();
            if (!text.isBlank()) {
                result.put(String.valueOf(entryKey).trim(), text);
            }
        });
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }
}
