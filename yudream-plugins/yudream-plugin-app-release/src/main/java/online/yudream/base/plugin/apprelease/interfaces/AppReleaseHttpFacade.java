package online.yudream.base.plugin.apprelease.interfaces;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.apprelease.application.ReleaseService;
import online.yudream.base.plugin.apprelease.domain.AppRelease;
import online.yudream.base.plugin.apprelease.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.http.PluginHttpPart;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 更新发布 HTTP 门面：公开端（检查/更新日志/下载）与管理端（上传/发布/下架/删除/设置）。
 */
public class AppReleaseHttpFacade {

    private static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";

    private final ReleaseService service;

    public AppReleaseHttpFacade(ReleaseService service) {
        this.service = service;
    }

    /* ---------------- 公开端 ---------------- */

    public PluginHttpResponse check(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String platform = HttpSupport.first(request, "platform");
            int clientVersionCode = HttpSupport.intParam(HttpSupport.first(request, "versionCode"), 0, 0, Integer.MAX_VALUE);
            return PluginHttpResponse.ok(service.check(platform, clientVersionCode));
        });
    }

    public PluginHttpResponse changelogs(PluginHttpRequest request) {
        String platform = HttpSupport.first(request, "platform");
        List<Map<String, Object>> logs = service.listPublished(platform).stream()
                .map(release -> service.releaseView(release, false))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", logs));
    }

    public PluginHttpResponse download(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String id = HttpSupport.segment(request, 2);
            AppRelease release = service.listAll().stream()
                    .filter(r -> r.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("版本包不存在"));
            byte[] content = service.readPackage(id)
                    .orElseThrow(() -> new IllegalArgumentException("更新包文件已缺失，请重新上传该版本"));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Disposition", "attachment; filename=\"" + safeFileName(release.fileName()) + "\"");
            headers.put("Cache-Control", "no-store");
            return new PluginHttpResponse(200, headers, APK_CONTENT_TYPE, content, false);
        });
    }

    /* ---------------- 管理端 ---------------- */

    public PluginHttpResponse listAll() {
        List<Map<String, Object>> rows = service.listAll().stream()
                .map(release -> service.releaseView(release, true))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", rows));
    }

    public PluginHttpResponse upload(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            PluginHttpPart file = request.parts().get("file");
            if (file == null || !file.isFile() || file.data().length == 0) {
                throw new IllegalArgumentException("请上传更新包文件（multipart 字段 file）");
            }
            String versionName = partText(request, "versionName");
            if (versionName.isBlank()) {
                throw new IllegalArgumentException("versionName 不能为空");
            }
            int versionCode = parseIntStrict(partText(request, "versionCode"), "versionCode 必须为正整数");
            if (versionCode <= 0) {
                throw new IllegalArgumentException("versionCode 必须为正整数");
            }
            String changelog = partText(request, "changelog");
            boolean forceUpdate = Boolean.parseBoolean(partText(request, "forceUpdate"));
            String platform = partText(request, "platform");
            AppRelease release = service.create(platform, versionCode, versionName, changelog, forceUpdate,
                    file.filename(), file.data());
            return PluginHttpResponse.ok(service.releaseView(release, true));
        });
    }

    public PluginHttpResponse publish(PluginHttpRequest request) {
        return HttpSupport.guard(() ->
                PluginHttpResponse.ok(service.releaseView(service.publish(HttpSupport.segment(request, 2)), true)));
    }

    public PluginHttpResponse unpublish(PluginHttpRequest request) {
        return HttpSupport.guard(() ->
                PluginHttpResponse.ok(service.releaseView(service.unpublish(HttpSupport.segment(request, 2)), true)));
    }

    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            service.delete(HttpSupport.segment(request, 2));
            return PluginHttpResponse.noContent();
        });
    }

    public PluginHttpResponse settings() {
        var settings = service.settings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minVersionCode", settings.minVersionCode());
        body.put("updatedAt", settings.updatedAt());
        return PluginHttpResponse.ok(body);
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            int minVersionCode = HttpSupport.intField(HttpSupport.json(request), "minVersionCode", 0);
            if (minVersionCode < 0) {
                throw new IllegalArgumentException("minVersionCode 不能为负数");
            }
            service.saveSettings(minVersionCode);
            return settings();
        });
    }

    /* ---------------- 内部 ---------------- */

    private static String partText(PluginHttpRequest request, String field) {
        PluginHttpPart part = request.parts().get(field);
        return part == null ? "" : part.text().trim();
    }

    private static int parseIntStrict(String raw, String message) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String safeFileName(String fileName) {
        String cleaned = fileName.replaceAll("[\\r\\n\"\\\\]", "_");
        return cleaned.isBlank() ? "app-update.apk" : cleaned;
    }
}
