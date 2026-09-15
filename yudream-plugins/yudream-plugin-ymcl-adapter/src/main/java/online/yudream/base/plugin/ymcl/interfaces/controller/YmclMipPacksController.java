package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * YAP §7 MIP 分发面（写路径 + 读路径）。
 *
 * 上传通道：SPI 的 request.body() 是 String，无法承载原始二进制——
 * 文件内容以 base64 随 JSON 上传（v1，适合增量包体量），服务端解码后
 * 入 CAS（PluginFileStore，objectKey = "cas/{sha512}"），sha512 即唯一
 * 信任锚（MIP §12）。
 *
 * - POST /mip/api/packs/{packId}/ingest        初始包（全量文件）
 * - POST /mip/api/packs/{packId}/ingest/delta  增量包（base + 变更集，
 *   服务端合成与全量 ingest 同构的标准 manifest）
 * - GET  /mip/api/packs/{packId}/versions      版本列表（channel 过滤）
 * - GET  /mip/api/packs/{packId}/manifest/{v}  不可变 manifest
 * - GET  /mip/objects/{sha512}                 CAS 对象（公共分发）
 */
public class YmclMipPacksController {

    private static final String PACK_COLLECTION = "ymcl_packs";
    private static final String VERSION_COLLECTION = "ymcl_pack_versions";
    private static final String CAS_PREFIX = "cas/";

    private final PluginFileStore files;
    private final PluginDocumentStore documents;
    private final YmclEventBus eventBus;

    public YmclMipPacksController(PluginFileStore files, PluginDocumentStore documents, YmclEventBus eventBus) {
        this.files = files;
        this.documents = documents;
        this.eventBus = eventBus;
    }

    // ── Ingest：初始包 ──────────────────────────────────────────────────

    @PluginHttpEndpoint(method = "POST", path = "/mip/api/packs/{packId}/ingest",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse ingest(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        Map<String, Object> body;
        try {
            body = json(request.body());
        } catch (Exception error) {
            return badRequest("invalid_body", "Request body must be JSON");
        }
        String version = str(body, "version");
        if (version == null || version.isBlank()) {
            return badRequest("invalid_body", "version is required");
        }
        String versionKey = versionKey(packId, version);
        if (documents.findById(VERSION_COLLECTION, versionKey).isPresent()) {
            return badRequest(409, "version_exists", version + " already exists");
        }

        List<Map<String, Object>> files_in = castMapList(body.get("files"));
        if (files_in.isEmpty()) {
            return badRequest("invalid_body", "files must not be empty");
        }

        String origin = YmclCapabilitiesController.resolveOrigin(request);
        List<Map<String, Object>> manifestFiles = new ArrayList<>();
        try {
            for (Map<String, Object> file : files_in) {
                manifestFiles.add(storeCas(file, origin));
            }
        } catch (RuntimeException error) {
            return badRequest("invalid_file", String.valueOf(error.getMessage()));
        }

        Map<String, Object> manifest = buildManifest(packId, version, channel(body), null, manifestFiles);
        saveVersion(packId, version, channel(body), manifest);
        registerPackVersion(packId, version, channel(body));

        eventBus.publish(YmclEventBus.TYPE_PACK_PUBLISHED,
                Map.of("packId", packId, "version", version, "channel", channel(body)));
        return PluginHttpResponse.rawJson(200, ingestReport(manifest, 0));
    }

    // ── Ingest delta：增量包 ────────────────────────────────────────────

    @PluginHttpEndpoint(method = "POST", path = "/mip/api/packs/{packId}/ingest/delta",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse ingestDelta(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        Map<String, Object> body;
        try {
            body = json(request.body());
        } catch (Exception error) {
            return badRequest("invalid_body", "Request body must be JSON");
        }
        String version = str(body, "version");
        String baseVersion = str(body, "baseVersion");
        if (version == null || version.isBlank() || baseVersion == null || baseVersion.isBlank()) {
            return badRequest("invalid_body", "version and baseVersion are required");
        }
        String versionKey = versionKey(packId, version);
        if (documents.findById(VERSION_COLLECTION, versionKey).isPresent()) {
            return badRequest(409, "version_exists", version + " already exists");
        }
        Optional<Map<String, Object>> baseDoc =
                documents.findById(VERSION_COLLECTION, versionKey(packId, baseVersion));
        if (baseDoc.isEmpty()) {
            return badRequest(404, "parent_not_found", "base version " + baseVersion + " not found");
        }
        Map<String, Object> baseManifest = castMap(baseDoc.get().get("manifest"));

        String origin = YmclCapabilitiesController.resolveOrigin(request);
        Map<String, Object> manifest = composeDeltaManifest(baseManifest, body, origin);
        manifest.put("pack_id", packId);
        manifest.put("version", version);
        manifest.put("parent", baseVersion);
        manifest.put("format_version", 1);
        manifest.put("channel", channel(body));

        saveVersion(packId, version, channel(body), manifest);
        registerPackVersion(packId, version, channel(body));

        // 一键推送的绑定语义（YAP §7）：body.bind {serverId, updatePolicy}。
        Map<String, Object> bind = castMap(body.get("bind"));
        if (bind != null && bind.get("serverId") != null) {
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("packId", packId);
            binding.put("channel", channel(body));
            binding.put("pinnedVersion", version);
            binding.put("updatePolicy", bind.getOrDefault("updatePolicy", "prompt"));
            binding.put("serverId", String.valueOf(bind.get("serverId")));
            binding.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            documents.save("ymcl_bindings", String.valueOf(bind.get("serverId")), binding);
            eventBus.publish(YmclEventBus.TYPE_BINDING_UPDATED, Map.of(
                    "serverId", String.valueOf(bind.get("serverId")),
                    "packId", packId));
        }

        eventBus.publish(YmclEventBus.TYPE_PACK_PUBLISHED,
                Map.of("packId", packId, "version", version, "channel", channel(body)));
        return PluginHttpResponse.rawJson(200, ingestReport(manifest, body.size()));
    }

    // ── 读路径 ──────────────────────────────────────────────────────────

    @PluginHttpEndpoint(method = "GET", path = "/mip/api/packs/{packId}/versions",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId).orElse(null);
        List<Object> versions = pack == null ? new ArrayList<>() : castList(pack.get("versions"));
        return PluginHttpResponse.rawJson(200, Map.of("versions", versions));
    }

    @PluginHttpEndpoint(method = "GET", path = "/mip/api/packs/{packId}/manifest/{version}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse manifest(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        String version = PathSegments.segment(request.path(), 5);
        Optional<Map<String, Object>> doc =
                documents.findById(VERSION_COLLECTION, versionKey(packId, version));
        if (doc.isEmpty()) {
            return badRequest(404, "version_not_found", version + " not found");
        }
        return PluginHttpResponse.rawJson(200, doc.get());
    }

    @PluginHttpEndpoint(method = "GET", path = "/mip/objects/{sha512}")
    public PluginHttpResponse object(PluginHttpRequest request) {
        String sha512 = PathSegments.segment(request.path(), 2);
        var stored = files.get(CAS_PREFIX + sha512.toLowerCase());
        if (stored == null) {
            return badRequest(404, "object_not_found", sha512);
        }
        try (var input = stored.inputStream()) {
            return new PluginHttpResponse(200,
                    Map.of("Cache-Control", "public, max-age=31536000, immutable"),
                    "application/octet-stream",
                    input.readAllBytes(), false);
        } catch (Exception error) {
            return badRequest(500, "object_read_failed", String.valueOf(error.getMessage()));
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 解码 base64 → 入 CAS → 生成 manifest 条目（path/sha512/size/policy/sources）。 */
    private Map<String, Object> storeCas(Map<String, Object> file, String origin) {
        String path = String.valueOf(file.get("path"));
        if (path.contains("..") || path.contains("\\") || path.startsWith("/")) {
            throw new IllegalArgumentException("unsafe path " + path);
        }
        String data = String.valueOf(file.get("data"));
        byte[] bytes = Base64.getDecoder().decode(data);
        String sha512 = sha512(bytes);
        files.put(CAS_PREFIX + sha512, new ByteArrayInputStream(bytes),
                (long) bytes.length, "application/octet-stream");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("sha512", sha512);
        entry.put("size", bytes.length);
        entry.put("policy", file.getOrDefault("policy", "managed"));
        entry.put("sources", List.of(Map.of(
                "type", "cas",
                "url", origin + "/mip/objects/" + sha512)));
        return entry;
    }

    /**
     * base manifest + delta → 新 manifest（MIP §6/WF-1）：changed/added 换成
     * 新 CAS 条目，deleted 移除，moved 改路径并写 movedFrom，其余沿用 base。
     */
    private Map<String, Object> composeDeltaManifest(
            Map<String, Object> baseManifest, Map<String, Object> body, String origin) {
        Map<String, Object> files = new LinkedHashMap<>();
        Object baseFilesObj = baseManifest.get("files");
        if (baseFilesObj instanceof List<?> baseFiles) {
            for (Object item : baseFiles) {
                if (item instanceof Map<?, ?> entry && entry.get("path") != null) {
                    files.put(String.valueOf(entry.get("path")), new LinkedHashMap<>((Map<String, Object>) entry));
                }
            }
        }
        for (Object item : listParam(body, "deleted")) {
            files.remove(String.valueOf(item));
        }
        for (Object item : listParam(body, "moved")) {
            if (item instanceof Map<?, ?> move) {
                String from = String.valueOf(move.get("from"));
                String to = String.valueOf(move.get("to"));
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) files.remove(from);
                if (entry != null) {
                    entry.put("path", to);
                    entry.put("moved_from", from);
                    files.put(to, entry);
                }
            }
        }
        for (Object item : listParam(body, "changed")) {
            if (item instanceof Map<?, ?> change) {
                Map<String, Object> stored = storeCas(castMap(item), origin);
                files.put(String.valueOf(change.get("path")), stored);
            }
        }
        for (Object item : listParam(body, "added")) {
            if (item instanceof Map<?, ?> added) {
                Map<String, Object> stored = storeCas(castMap(added), origin);
                files.put(String.valueOf(added.get("path")), stored);
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("files", new ArrayList<>(files.values()));
        return manifest;
    }

    private Map<String, Object> buildManifest(
            String packId, String version, String channel, String parent, List<Map<String, Object>> files) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format_version", 1);
        manifest.put("pack_id", packId);
        manifest.put("version", version);
        manifest.put("parent", parent);
        manifest.put("channel", channel);
        manifest.put("files", files);
        return manifest;
    }

    private void saveVersion(String packId, String version, String channel, Map<String, Object> manifest) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("packId", packId);
        doc.put("version", version);
        doc.put("channel", channel == null ? "stable" : channel);
        doc.put("releasedAt", String.valueOf(System.currentTimeMillis()));
        doc.put("manifest", manifest);
        documents.save(VERSION_COLLECTION, versionKey(packId, version), doc);
    }

    @SuppressWarnings("unchecked")
    private void registerPackVersion(String packId, String version, String channel) {
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId)
                .orElseGet(() -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("packId", packId);
                    created.put("versions", new ArrayList<Map<String, Object>>());
                    return created;
                });
        List<Object> versions = new ArrayList<>();
        Object existing = pack.get("versions");
        if (existing instanceof List<?> list) {
            versions.addAll(list);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", version);
        entry.put("channel", channel == null ? "stable" : channel);
        entry.put("releasedAt", String.valueOf(System.currentTimeMillis()));
        versions.add(entry);
        pack.put("versions", versions);
        documents.save(PACK_COLLECTION, packId, pack);
    }

    private Map<String, Object> ingestReport(Map<String, Object> manifest, int deltaParts) {
        Object files = manifest.get("files");
        int count = files instanceof List<?> list ? list.size() : 0;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "published");
        report.put("files", count);
        report.put("deltaParts", deltaParts);
        return report;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static String versionKey(String packId, String version) {
        return packId + "@" + version;
    }

    private static String channel(Map<String, Object> body) {
        return (String) body.getOrDefault("channel", "stable");
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<Object> listParam(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return new ArrayList<Object>(list);
        }
        return List.of();
    }

    private static List<Map<String, Object>> castMapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) item;
                    result.add(cast);
                }
            }
        }
        return result;
    }

    private static List<Object> castList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<Object>(list);
        }
        return new ArrayList<>();
    }

    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private Map<String, Object> json(String body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    private static PluginHttpResponse badRequest(String code, String message) {
        return badRequest(400, code, message);
    }

    private static PluginHttpResponse badRequest(int status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return PluginHttpResponse.rawJson(status, payload);
    }

    private static String sha512(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512").digest(bytes);
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
