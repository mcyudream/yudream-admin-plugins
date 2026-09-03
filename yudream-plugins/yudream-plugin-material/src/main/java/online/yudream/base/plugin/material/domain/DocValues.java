package online.yudream.base.plugin.material.domain;

import java.util.List;
import java.util.Map;

/** 文档存储 Map 值的安全读取工具：宿主文档存储遇 null 值会裸 NPE，写入前必须剥除 null。 */
public final class DocValues {
    private DocValues() {
    }

    public static String str(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static String strOr(Map<String, Object> doc, String key, String fallback) {
        String value = str(doc, key);
        return value == null ? fallback : value;
    }

    public static long lng(Map<String, Object> doc, String key) {
        return lngOr(doc, key, 0L);
    }

    public static long lngOr(Map<String, Object> doc, String key, long fallback) {
        Object value = doc.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int integer(Map<String, Object> doc, String key) {
        return (int) lngOr(doc, key, 0L);
    }

    public static int integerOr(Map<String, Object> doc, String key, int fallback) {
        return (int) lngOr(doc, key, fallback);
    }

    public static boolean bool(Map<String, Object> doc, String key, boolean fallback) {
        Object value = doc.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    public static List<String> stringList(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (value instanceof List<?> list) {
            return list.stream().filter(item -> item != null).map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 非 null 才写入，规避沙盒/宿主存储的 Map.copyOf null NPE。 */
    public static void put(Map<String, Object> doc, String key, Object value) {
        if (value != null) {
            doc.put(key, value);
        }
    }
}
