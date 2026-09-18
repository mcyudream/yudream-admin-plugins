package online.yudream.base.plugin.ymclcontent.interfaces.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpPart;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.ymclcontent.application.service.UpdatePlatformService;
import online.yudream.base.plugin.ymclcontent.bootstrap.YmclContentPlugin;
import online.yudream.base.plugin.ymclcontent.domain.UpdateRelease;
import online.yudream.base.plugin.ymclcontent.interfaces.support.UpdateHttpSupport;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * YMCL 更新平台管理端。
 * 路径相对插件命名空间：/api/plugins/ymcl-content/admin/update/**
 */
public class UpdateAdminController {

    private final UpdatePlatformService updates;
    private final ObjectMapper mapper = new ObjectMapper();

    public UpdateAdminController(UpdatePlatformService updates) {
        this.updates = updates;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/update/releases",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse listReleases(PluginHttpRequest request) {
        List<Map<String, Object>> records = updates.listReleases().stream()
                .map(UpdateRelease::toAdminRecord)
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", records.size()));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/update/releases/{version}",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse getRelease(PluginHttpRequest request) {
        String version = releaseVersion(request);
        return updates.findRelease(version)
                .map(release -> PluginHttpResponse.ok(release.toAdminRecord()))
                .orElseGet(() -> error(404, "not_found", "release not found"));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/update/releases",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveRelease(PluginHttpRequest request) {
        Map<String, Object> payload = readJsonObject(request);
        if (payload == null) {
            return error(400, "invalid_body", "Request body must be a JSON object");
        }
        try {
            UpdateRelease release = updates.saveRelease(payload);
            return PluginHttpResponse.ok(release.toAdminRecord());
        } catch (IllegalArgumentException invalid) {
            return error(400, "invalid_release", invalid.getMessage());
        }
    }

    /** URL 模式注册制品。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/update/releases/{version}/artifacts",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveArtifact(PluginHttpRequest request) {
        String version = releaseVersion(request);
        Map<String, Object> payload = readJsonObject(request);
        if (payload == null) {
            return error(400, "invalid_body", "Request body must be a JSON object");
        }
        try {
            Optional<UpdateRelease> release;
            String mode = payload.get("mode") == null ? "url" : String.valueOf(payload.get("mode"));
            if ("file".equalsIgnoreCase(mode) || payload.containsKey("data")) {
                byte[] bytes = UpdatePlatformService.decodeBase64(String.valueOf(payload.get("data")));
                release = updates.upsertFileArtifact(version, payload, bytes,
                        UpdatePlatformService.sanitizeFilename(
                                payload.get("filename") == null ? null : String.valueOf(payload.get("filename"))));
            } else {
                release = updates.upsertUrlArtifact(version, payload);
            }
            return release
                    .map(value -> PluginHttpResponse.ok(value.toAdminRecord()))
                    .orElseGet(() -> error(404, "not_found", "release not found"));
        } catch (IllegalArgumentException invalid) {
            return error(400, "invalid_artifact", invalid.getMessage());
        }
    }

    /** multipart 上传站内制品。parts: file + 可选 kind/variant/platform/architecture/filename/signature... */
    @PluginHttpEndpoint(method = "POST", path = "/admin/update/releases/{version}/artifacts/upload",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse uploadArtifact(PluginHttpRequest request) {
        String version = releaseVersion(request);
        Map<String, PluginHttpPart> parts = request.parts();
        if (parts == null || parts.get("file") == null) {
            return error(400, "invalid_body", "multipart part 'file' is required");
        }
        PluginHttpPart file = parts.get("file");
        byte[] bytes = file.data();
        if (bytes == null || bytes.length == 0) {
            return error(400, "invalid_file", "uploaded file is empty");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        copyPart(parts, payload, "id");
        copyPart(parts, payload, "kind");
        copyPart(parts, payload, "variant");
        copyPart(parts, payload, "platform");
        copyPart(parts, payload, "architecture");
        copyPart(parts, payload, "platformId");
        copyPart(parts, payload, "signature");
        copyPart(parts, payload, "contentType");
        copyPart(parts, payload, "filename");
        String targets = UpdatePlatformService.partText(parts, "targetPlatforms");
        if (targets != null && !targets.isBlank()) {
            payload.put("targetPlatforms", List.of(targets.split(",")));
        }
        String filename = UpdatePlatformService.partText(parts, "filename");
        if (filename == null || filename.isBlank()) {
            filename = UpdatePlatformService.partText(parts, "file.name");
            if (filename == null || filename.isBlank()) {
                filename = "artifact-" + version;
            }
        }
        try {
            Optional<UpdateRelease> release = updates.upsertFileArtifact(version, payload, bytes, filename);
            return release
                    .map(value -> PluginHttpResponse.ok(value.toAdminRecord()))
                    .orElseGet(() -> error(404, "not_found", "release not found"));
        } catch (IllegalArgumentException invalid) {
            return error(400, "invalid_artifact", invalid.getMessage());
        }
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/update/releases/{version}/artifacts/{artifactId}",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteArtifact(PluginHttpRequest request) {
        String version = releaseVersion(request);
        String artifactId = UpdateHttpSupport.segmentAfter(request.path(), "artifacts", 0);
        return updates.removeArtifact(version, artifactId)
                .map(value -> PluginHttpResponse.ok(value.toAdminRecord()))
                .orElseGet(() -> error(404, "not_found", "release or artifact not found"));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/update/releases/{version}/yank",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse yankRelease(PluginHttpRequest request) {
        String version = releaseVersion(request);
        Map<String, Object> payload = readJsonObject(request);
        boolean yanked = true;
        if (payload != null && payload.containsKey("yanked")) {
            yanked = Boolean.TRUE.equals(payload.get("yanked"))
                    || "true".equalsIgnoreCase(String.valueOf(payload.get("yanked")));
        }
        return updates.setYanked(version, yanked)
                .map(value -> PluginHttpResponse.ok(value.toAdminRecord()))
                .orElseGet(() -> error(404, "not_found", "release not found"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/update/releases/{version}",
            permission = YmclContentPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteRelease(PluginHttpRequest request) {
        String version = releaseVersion(request);
        if (!updates.deleteRelease(version)) {
            return error(404, "not_found", "release not found");
        }
        return PluginHttpResponse.ok(Map.of("deleted", true, "version", version));
    }

    private static String releaseVersion(PluginHttpRequest request) {
        String path = request.path();
        List<String> segments = UpdateHttpSupport.pathSegments(path);
        int index = segments.indexOf("releases");
        if (index >= 0 && index + 1 < segments.size()) {
            return segments.get(index + 1);
        }
        return UpdateHttpSupport.segmentAfter(path, "releases", 0);
    }

    private static void copyPart(Map<String, PluginHttpPart> parts, Map<String, Object> payload, String name) {
        String value = UpdatePlatformService.partText(parts, name);
        if (value != null && !value.isBlank()) {
            payload.put(name, value);
        }
    }

    private Map<String, Object> readJsonObject(PluginHttpRequest request) {
        try {
            return mapper.readValue(request.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception error) {
            return null;
        }
    }

    private static PluginHttpResponse error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? code : message);
        return PluginHttpResponse.rawJson(status, body);
    }
}
