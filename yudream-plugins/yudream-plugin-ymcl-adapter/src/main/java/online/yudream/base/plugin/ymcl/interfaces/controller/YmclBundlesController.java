package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.ymcl.api.YmclBundleContribution;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAP §6.8 /v1/bundles/**：扩展页面包（自包含 zip：ESM 入口 + 资产 +
 * ymcl-bundle.json 清单）的上传注册与下发。
 *
 * - PUT  /v1/bundles/{bundleId}/{version}（publish 权限，base64 JSON：
 *   {@code {"data": "<base64(zip)>"}}）——服务端重算 sha256 存入 CAS 并
 *   注册版本（不可变，重复上传 409）。
 * - GET  /v1/bundles/{bundleId}/{version}/package.zip（匿名；启动器按
 *   manifest.pages[].bundle.sha256 自行校验，immutable 缓存）。
 *
 * 版本不可变原则与插件市场一致：{bundleId}@{version} 不可覆盖发布。
 */
public class YmclBundlesController {

    private static final String BUNDLE_COLLECTION = "ymcl_bundles";
    private static final String BUNDLE_PREFIX = "bundles/";

    private final PluginFileStore files;
    private final PluginDocumentStore documents;
    private final YmclContributionAggregator aggregator;

    public YmclBundlesController(PluginFileStore files, PluginDocumentStore documents,
            YmclContributionAggregator aggregator) {
        this.files = files;
        this.documents = documents;
        this.aggregator = aggregator;
    }

    @PluginHttpEndpoint(method = "PUT", path = "/v1/bundles/{bundleId}/{version}",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse upload(PluginHttpRequest request) {
        String bundleId = PathSegments.segment(request.path(), 2);
        String version = PathSegments.segment(request.path(), 3);
        Map<String, Object> body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return YmclSessionController.errorResponse(400, "invalid_body",
                    "Request body must be JSON with base64 \"data\"");
        }
        String data = body.get("data") == null ? null : String.valueOf(body.get("data"));
        if (data == null || data.isBlank()) {
            return YmclSessionController.errorResponse(400, "invalid_body",
                    "data (base64 zip) is required");
        }

        String versionKey = bundleKey(bundleId, version);
        if (documents.findById(BUNDLE_COLLECTION, versionKey).isPresent()) {
            return YmclSessionController.errorResponse(409, "bundle_version_exists",
                    bundleId + "@" + version + " already exists");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException error) {
            return YmclSessionController.errorResponse(400, "invalid_body",
                    "data is not valid base64");
        }

        String sha256 = sha256Hex(bytes);
        files.put(objectKey(bundleId, version),
                new java.io.ByteArrayInputStream(bytes), (long) bytes.length, "application/zip");

        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("bundleId", bundleId);
        registration.put("version", version);
        registration.put("sha256", sha256);
        registration.put("size", bytes.length);
        registration.put("uploadedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(BUNDLE_COLLECTION, versionKey, registration);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("sha256", sha256);
        result.put("size", bytes.length);
        return PluginHttpResponse.json(200, result);
    }

    /** 管理端 bundle 注册清单：全量翻页聚合（文档存储单页 200 上限）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/bundles",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse listBundles(PluginHttpRequest request) {
        List<Map<String, Object>> bundles = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(BUNDLE_COLLECTION, page, 200);
            if (batch.isEmpty()) {
                break;
            }
            bundles.addAll(batch);
            page++;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bundles", bundles);
        return PluginHttpResponse.json(200, payload);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/bundles/{bundleId}/{version}/package.zip")
    public PluginHttpResponse download(PluginHttpRequest request) {
        String bundleId = PathSegments.segment(request.path(), 2);
        String version = PathSegments.segment(request.path(), 3);
        var stored = tryGetStored(bundleId, version);
        if (stored == null) {
            // 上传包未命中时回退到提供方随 jar 贡献的包（YAP §6.8）。
            var contributed = aggregator.findBundle(bundleId, version);
            if (contributed.isPresent()) {
                return zipResponse(contributed.get().content());
            }
            return YmclSessionController.errorResponse(404, "bundle_not_found",
                    bundleId + "@" + version + " not found");
        }
        try (var input = stored.inputStream()) {
            return zipResponse(input.readAllBytes());
        } catch (Exception error) {
            return YmclSessionController.errorResponse(500, "bundle_read_failed",
                    String.valueOf(error.getMessage()));
        }
    }

    /**
     * ObjectStoragePluginFileStore 对缺失键抛 BizException（"文件不存在"）
     * 而非返回空——统一按未命中处理，让提供方贡献包回退有机会执行。
     */
    private PluginStoredFile tryGetStored(String bundleId, String version) {
        try {
            return files.get(objectKey(bundleId, version));
        } catch (Exception miss) {
            return null;
        }
    }

    private static PluginHttpResponse zipResponse(byte[] bytes) {
        return new PluginHttpResponse(200,
                Map.of("Cache-Control", "public, max-age=31536000, immutable"),
                "application/zip",
                bytes, false);
    }

    private static String objectKey(String bundleId, String version) {
        // bundleId/version are admin-supplied identifiers; keep the CAS key
        // flat and validate to prevent path games.
        if (bundleId.contains("/") || bundleId.contains("..")
                || version.contains("/") || version.contains("..")) {
            throw new IllegalArgumentException("unsafe bundle identifier");
        }
        return BUNDLE_PREFIX + bundleId + "/" + version + ".zip";
    }

    private static String bundleKey(String bundleId, String version) {
        return bundleId + "@" + version;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
