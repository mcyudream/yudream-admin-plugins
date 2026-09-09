package online.yudream.base.plugin.minecraft.domain.valobj;

import java.net.URI;

/** A ZIP stored in plugin files, or an external cloud-drive URL. Exactly one source is required. */
public record MinecraftServerMap(String fileId, String objectKey, String originalName, boolean publicAccess, String externalUrl) {
    private static final int MAX_EXTERNAL_URL_LENGTH = 2048;

    public MinecraftServerMap {
        String storedKey = objectKey == null ? "" : objectKey.trim();
        String url = normalizeExternalUrl(externalUrl);
        boolean hasFile = !storedKey.isBlank();
        boolean hasLink = !url.isBlank();
        if (hasFile == hasLink) {
            throw new IllegalArgumentException("地图必须是 ZIP 文件或网盘链接之一");
        }
        if (hasFile && (fileId == null || fileId.isBlank())) {
            throw new IllegalArgumentException("地图文件不能为空");
        }
        fileId = fileId == null ? "" : fileId.trim();
        objectKey = storedKey;
        originalName = originalName == null || originalName.isBlank()
                ? (hasLink ? "网盘下载" : fileId + ".zip")
                : originalName.trim();
        externalUrl = url;
    }

    public static MinecraftServerMap storedFile(String fileId, String objectKey, String originalName, boolean publicAccess) {
        return new MinecraftServerMap(fileId, objectKey, originalName, publicAccess, "");
    }

    public static MinecraftServerMap externalLink(String url, String originalName, boolean publicAccess) {
        return new MinecraftServerMap("", "", originalName, publicAccess, url);
    }

    public MinecraftServerMap withPublicAccess(boolean value) {
        return new MinecraftServerMap(fileId, objectKey, originalName, value, externalUrl);
    }

    public boolean external() {
        return !externalUrl.isBlank();
    }

    private static String normalizeExternalUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        if (url.length() > MAX_EXTERNAL_URL_LENGTH) {
            throw new IllegalArgumentException("网盘链接不能超过 " + MAX_EXTERNAL_URL_LENGTH + " 个字符");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("网盘链接格式无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("网盘链接必须是 http 或 https 地址");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("网盘链接格式无效");
        }
        return url;
    }
}
