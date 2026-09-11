package online.yudream.base.plugin.mcnews.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/**
 * 文档存储统一封装。文档存储不接受 null 值（写入前必须 stripNulls），
 * save 会覆写 "id" 字段（每份文档显式携带 id），find 分页为 1-based 且单页上限 200。
 */
public final class McNewsStore {
    public static final String COL_SETTINGS = "mc_news_settings";
    public static final String COL_SOURCES = "mc_news_sources";
    public static final String COL_TARGETS = "mc_news_targets";
    public static final String COL_SEEN = "mc_news_seen";
    public static final String COL_SUBS = "mc_news_subs";
    public static final String COL_LOGS = "mc_news_logs";

    public static final String DOC_SETTINGS = "global";
    public static final String DOC_SEEN = "cache";

    private final PluginDocumentStore documents;

    public McNewsStore(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public Optional<Map<String, Object>> find(String collection, String id) {
        return documents.findById(collection, id);
    }

    public void save(String collection, String id, Map<String, Object> doc) {
        Map<String, Object> clean = new HashMap<>(doc);
        clean.put("id", id);
        documents.save(collection, id, stripNulls(clean));
    }

    public List<Map<String, Object>> all(String collection) {
        return documents.findAll(collection, 1, 200);
    }

    public List<Map<String, Object>> page(String collection, int page, int size) {
        return documents.findAll(collection, page, size);
    }

    public List<Map<String, Object>> byField(String collection, String field, Object value, int page, int size) {
        return documents.findByField(collection, field, value, page, size);
    }

    public long count(String collection) {
        return documents.count(collection);
    }

    public void delete(String collection, String id) {
        documents.delete(collection, id);
    }

    /** 文档存储拒绝 null 值：递归移除 null 键并复制可变集合。 */
    public static Map<String, Object> stripNulls(Map<String, Object> source) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> converted = new HashMap<>();
                for (Map.Entry<?, ?> nestedEntry : nested.entrySet()) {
                    if (nestedEntry.getKey() instanceof String key && nestedEntry.getValue() != null) {
                        converted.put(key, nestedEntry.getValue());
                    }
                }
                result.put(entry.getKey(), stripNulls(converted));
            }
            else if (value instanceof List<?> list) {
                List<Object> copied = new ArrayList<>();
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    if (item instanceof Map<?, ?> nested) {
                        Map<String, Object> converted = new HashMap<>();
                        for (Map.Entry<?, ?> nestedEntry : nested.entrySet()) {
                            if (nestedEntry.getKey() instanceof String key && nestedEntry.getValue() != null) {
                                converted.put(key, nestedEntry.getValue());
                            }
                        }
                        copied.add(stripNulls(converted));
                    }
                    else {
                        copied.add(item);
                    }
                }
                result.put(entry.getKey(), copied);
            }
            else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    public static String str(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        return value instanceof String text ? text : "";
    }

    public static String strOr(Map<String, Object> doc, String key, String fallback) {
        Object value = doc.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    public static boolean bool(Map<String, Object> doc, String key, boolean fallback) {
        Object value = doc.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    public static long longOr(Map<String, Object> doc, String key, long fallback) {
        Object value = doc.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            }
            catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static int intOr(Map<String, Object> doc, String key, int fallback) {
        return (int) longOr(doc, key, fallback);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> nested) {
                    result.add((Map<String, Object>) nested);
                }
            }
            return result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> stringList(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        }
        return List.of();
    }
}
