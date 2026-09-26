package online.yudream.base.plugin.ymclcontent.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YMCL 内容分发的一条记录（公告/指引/在线内容）。
 * 消费方是启动器 helpers/ymcl-content.ts：updatedAt 必须是可 Date.parse 的 ISO-8601 文本，
 * 纯毫秒数字串会被 JS 引擎当作年份解析成远未来，导致公告永不生效。
 */
public record ContentRecord(
        String id,
        String kind,
        String title,
        String summary,
        String body,
        String coverUrl,
        String url,
        String meta,
        long sort,
        boolean enabled,
        long updatedAtMillis) {

    public static final String KIND_GUIDES = "guides";
    public static final String KIND_UPDATES = "updates";
    public static final String KIND_ONLINE = "online";

    public boolean isPubliclyVisible() {
        return enabled && title != null && !title.isBlank();
    }

    /** 启动器消费形态：时间字段为 ISO-8601。 */
    public Map<String, Object> toPublicRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("kind", kind);
        record.put("title", title == null ? "" : title);
        record.put("summary", summary == null ? "" : summary);
        record.put("body", body == null ? "" : body);
        record.put("coverUrl", coverUrl == null ? "" : coverUrl);
        record.put("url", url == null ? "" : url);
        record.put("meta", meta == null ? "" : meta);
        record.put("sort", sort);
        record.put("enabled", enabled);
        record.put("updatedAt", isoUpdatedAt());
        return record;
    }

    public Map<String, Object> toAdminRecord() {
        return toPublicRecord();
    }

    public String isoUpdatedAt() {
        if (updatedAtMillis <= 0) {
            return Instant.now().toString();
        }
        return Instant.ofEpochMilli(updatedAtMillis).toString();
    }

    public static ContentRecord fromMap(Map<String, Object> map) {
        return new ContentRecord(
                stringOf(map.get("id")),
                stringOf(map.get("kind")),
                stringOf(map.get("title")),
                stringOf(map.get("summary")),
                stringOf(map.get("body")),
                stringOf(map.get("coverUrl")),
                stringOf(map.get("url")),
                stringOf(map.get("meta")),
                longOf(map.get("sort")),
                map.get("enabled") == null || boolOf(map.get("enabled")),
                longOf(map.get("updatedAtMillis")));
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean boolOf(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
