package online.yudream.base.plugin.ymclcontent.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YMCL 更新平台的一次发布。
 * channel：release / beta。变更说明按 Keep a Changelog 分类分条保存。
 */
public record UpdateRelease(
        String version,
        String channel,
        String title,
        String notes,
        Map<String, List<String>> changes,
        String publishedAt,
        boolean forceUpdate,
        boolean yanked,
        boolean enabled,
        String externalUrl,
        List<UpdateArtifact> artifacts,
        String updatedAt) {

    public static final String CHANNEL_RELEASE = "release";
    public static final String CHANNEL_BETA = "beta";

    /** 与 Axolotl 更新公告一致的分类（Keep a Changelog）。 */
    public static final List<String> CHANGE_CATEGORIES = List.of(
            "added", "changed", "deprecated", "removed", "fixed", "security");

    public static final Map<String, String> CHANGE_CATEGORY_LABELS = Map.of(
            "added", "新增",
            "changed", "变更",
            "deprecated", "废弃",
            "removed", "移除",
            "fixed", "Bug 修复",
            "security", "安全");

    public boolean isPubliclyVisible() {
        return enabled && !yanked;
    }

    public boolean isPrerelease() {
        return version != null && version.contains("-");
    }

    public static String resolveChannel(String version) {
        return version != null && version.contains("-") ? CHANNEL_BETA : CHANNEL_RELEASE;
    }

    public String resolvedTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        return "YMCL " + (version == null ? "" : version);
    }

    /**
     * 给 Tauri / 客户端的 notes 文本。
     * 优先自由文本 notes；否则按分类生成 Markdown 列表。
     */
    public String resolvedNotes() {
        if (notes != null && !notes.isBlank()) {
            return notes;
        }
        Map<String, List<String>> grouped = normalizedChanges();
        if (grouped.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## ").append(resolvedTitle()).append("\n\n");
        for (String category : CHANGE_CATEGORIES) {
            List<String> items = grouped.get(category);
            if (items == null || items.isEmpty()) {
                continue;
            }
            builder.append("### ").append(CHANGE_CATEGORY_LABELS.getOrDefault(category, category))
                    .append("\n\n");
            for (String item : items) {
                builder.append("- ").append(item).append('\n');
            }
            builder.append('\n');
        }
        return builder.toString().stripTrailing();
    }

    /** 仅保留合法分类与非空条目，顺序固定。 */
    public Map<String, List<String>> normalizedChanges() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (changes == null) {
            return result;
        }
        for (String category : CHANGE_CATEGORIES) {
            List<String> source = changes.get(category);
            if (source == null) {
                continue;
            }
            List<String> items = new ArrayList<>();
            for (String item : source) {
                if (item != null && !item.isBlank()) {
                    items.add(item.trim());
                }
            }
            if (!items.isEmpty()) {
                result.put(category, items);
            }
        }
        return result;
    }

    public Map<String, Object> toAdminRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("version", version);
        record.put("channel", channel == null || channel.isBlank() ? resolveChannel(version) : channel);
        record.put("title", resolvedTitle());
        record.put("notes", notes == null ? "" : notes);
        record.put("changes", normalizedChanges());
        record.put("publishedAt", publishedAt);
        record.put("forceUpdate", forceUpdate);
        record.put("yanked", yanked);
        record.put("enabled", enabled);
        record.put("externalUrl", externalUrl == null ? "" : externalUrl);
        record.put("updatedAt", updatedAt);
        List<Map<String, Object>> artifactsOut = new ArrayList<>();
        if (artifacts != null) {
            for (UpdateArtifact artifact : artifacts) {
                artifactsOut.add(artifact.toAdminRecord());
            }
        }
        record.put("artifacts", artifactsOut);
        return record;
    }

    @SuppressWarnings("unchecked")
    public static UpdateRelease fromMap(Map<String, Object> map) {
        String version = stringOf(map.get("version"));
        String channel = stringOf(map.get("channel"));
        if (channel == null || channel.isBlank()) {
            channel = resolveChannel(version);
        }
        return new UpdateRelease(
                version,
                channel,
                stringOf(map.get("title")),
                stringOf(map.get("notes")),
                changesFromMap(map.get("changes")),
                stringOf(map.get("publishedAt")),
                boolOf(map.get("forceUpdate")),
                boolOf(map.get("yanked")),
                map.get("enabled") == null || boolOf(map.get("enabled")),
                stringOf(map.get("externalUrl")),
                UpdateArtifact.listFromMaps(map.get("artifacts")),
                stringOf(map.get("updatedAt")));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, List<String>> changesFromMap(Object raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return result;
        }
        for (String category : CHANGE_CATEGORIES) {
            Object value = map.get(category);
            if (!(value instanceof List<?> list)) {
                continue;
            }
            List<String> items = new ArrayList<>();
            for (Object entry : list) {
                if (entry != null && !String.valueOf(entry).isBlank()) {
                    items.add(String.valueOf(entry).trim());
                }
            }
            if (!items.isEmpty()) {
                result.put(category, items);
            }
        }
        return result;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean boolOf(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
