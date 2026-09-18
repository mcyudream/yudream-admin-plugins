package online.yudream.base.plugin.ymclcontent.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YMCL 更新平台制品：安装包 / updater 包 / 签名。
 * 文件可走站内 PluginFileStore（fileKey）或外部下载地址（downloadUrl）。
 */
public record UpdateArtifact(
        String id,
        String kind,
        String variant,
        String platform,
        String architecture,
        List<String> targetPlatforms,
        String filename,
        String downloadUrl,
        String fileKey,
        String sha256,
        long size,
        String signature,
        String contentType,
        String createdAt) {

    public static final String KIND_UPDATER = "updater";
    public static final String KIND_INSTALLER = "installer";
    public static final String KIND_PORTABLE = "portable";
    public static final String KIND_SIGNATURE = "signature";

    public boolean hostedLocally() {
        return fileKey != null && !fileKey.isBlank();
    }

    public boolean hasExternalUrl() {
        return downloadUrl != null && downloadUrl.isBlank() == false
                && (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://"));
    }

    public Map<String, Object> toAdminRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("kind", kind);
        record.put("variant", variant == null ? "" : variant);
        record.put("platform", platform == null ? "" : platform);
        record.put("architecture", architecture == null ? "" : architecture);
        record.put("targetPlatforms", targetPlatforms == null ? List.of() : targetPlatforms);
        record.put("filename", filename == null ? "" : filename);
        record.put("downloadUrl", downloadUrl);
        record.put("fileKey", fileKey);
        record.put("hostedLocally", hostedLocally());
        record.put("sha256", sha256);
        record.put("size", size);
        record.put("signature", signature);
        record.put("contentType", contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType);
        record.put("createdAt", createdAt);
        return record;
    }

    @SuppressWarnings("unchecked")
    public static UpdateArtifact fromMap(Map<String, Object> map) {
        return new UpdateArtifact(
                stringOf(map.get("id")),
                stringOf(map.get("kind")),
                stringOf(map.get("variant")),
                stringOf(map.get("platform")),
                stringOf(map.get("architecture")),
                stringList(map.get("targetPlatforms")),
                stringOf(map.get("filename")),
                stringOf(map.get("downloadUrl")),
                stringOf(map.get("fileKey")),
                stringOf(map.get("sha256")),
                longOf(map.get("size")),
                stringOf(map.get("signature")),
                stringOf(map.get("contentType")),
                stringOf(map.get("createdAt")));
    }

    public static List<UpdateArtifact> listFromMaps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<UpdateArtifact> items = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                items.add(fromMap((Map<String, Object>) map));
            }
        }
        return items;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (Object entry : list) {
            if (entry != null) {
                String text = String.valueOf(entry);
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }
}
