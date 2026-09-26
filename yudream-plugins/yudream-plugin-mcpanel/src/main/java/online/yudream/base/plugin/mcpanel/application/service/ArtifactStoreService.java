package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 面板制品库：注入类 jar（authlib / playtime 等）统一走平台文件存储，
 * 设置里只保存 fileId 引用；下载经面板代理端点回源，节点端不需要平台凭据。
 */
public class ArtifactStoreService {

    public static final String KEY_PREFIX = "mcpanel/artifacts/";
    /** 制品上限 200MB（宿主 SPI 二进制全量进堆，再大不现实）。 */
    public static final long MAX_SIZE = 200L * 1024 * 1024;

    private final PluginFileStore files;

    public ArtifactStoreService(PluginFileStore files) {
        this.files = files;
    }

    /** 上传制品，返回 {fileId,name,size,sha256} 供设置表单回填。 */
    public Map<String, Object> upload(String filename, byte[] data) {
        if (data == null || data.length == 0) {
            throw McpanelBusinessException.invalid("制品文件为空");
        }
        if (data.length > MAX_SIZE) {
            throw McpanelBusinessException.invalid("制品超过 200MB 上限");
        }
        String safeName = sanitizeFilename(filename);
        String fileId = KEY_PREFIX + UUID.randomUUID().toString().replace("-", "") + "-" + safeName;
        files.put(fileId, new ByteArrayInputStream(data), data.length, "application/java-archive");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", fileId);
        result.put("name", safeName);
        result.put("size", data.length);
        result.put("sha256", sha256Hex(data));
        return result;
    }

    /** 代理下载：仅允许制品库前缀，防止任意 objectKey 读取。 */
    public byte[] download(String fileId) {
        if (fileId == null || !fileId.startsWith(KEY_PREFIX)) {
            throw McpanelBusinessException.invalid("非法制品 ID");
        }
        PluginStoredFile stored;
        try {
            stored = files.get(fileId);
        } catch (RuntimeException missing) {
            // 宿主对象存储对缺失 key 抛 BizException 而非返回 null，转成业务 404 语义。
            throw McpanelBusinessException.notFound("制品不存在或已被删除");
        }
        if (stored == null) {
            throw McpanelBusinessException.notFound("制品不存在或已被删除");
        }
        try {
            return stored.inputStream().readAllBytes();
        } catch (IOException error) {
            throw new McpanelBusinessException("artifact.read-failed", 502, "制品读取失败");
        }
    }

    private String sanitizeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "artifact.jar" : filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isBlank() ? "artifact.jar" : name;
    }

    private String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
