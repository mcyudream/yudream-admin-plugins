package online.yudream.base.plugin.cas.infrastructure.support;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DocValues {

    private DocValues() {
    }

    public static String string(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static boolean bool(Map<String, Object> document, String key, boolean defaultValue) {
        Object value = document.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    public static Map<String, Object> stripNulls(Map<String, Object> document) {
        LinkedHashMap<String, Object> cleaned = new LinkedHashMap<>();
        document.forEach((key, value) -> {
            if (key != null && value != null) {
                cleaned.put(key, value);
            }
        });
        return cleaned;
    }
}
