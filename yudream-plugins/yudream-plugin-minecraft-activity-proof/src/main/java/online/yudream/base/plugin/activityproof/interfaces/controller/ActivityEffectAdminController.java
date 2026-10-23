package online.yudream.base.plugin.activityproof.interfaces.controller;

import online.yudream.base.plugin.activityproof.interfaces.http.ActivityEffectHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 活动效果提交·管理面：审核、删除与附件下载。 */
public class ActivityEffectAdminController {

    private final ActivityEffectHttpFacade http;

    public ActivityEffectAdminController(ActivityEffectHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/effects",
            permission = MinecraftActivityProofPermissions.MANAGE)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return http.adminList(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/effects/{effectId}/review",
            permission = MinecraftActivityProofPermissions.MANAGE)
    public PluginHttpResponse review(PluginHttpRequest request) {
        return http.adminReview(request, segmentAfter(request.path(), "effects"),
                ActivityEffectHttpFacade.currentUserId(request));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/effects/{effectId}",
            permission = MinecraftActivityProofPermissions.MANAGE)
    public PluginHttpResponse remove(PluginHttpRequest request) {
        return http.adminDelete(request, segmentAfter(request.path(), "effects"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/effects/{effectId}/attachments/{index}",
            permission = MinecraftActivityProofPermissions.MANAGE, wrapResult = false)
    public PluginHttpResponse download(PluginHttpRequest request) {
        return http.adminDownload(request, segmentAfter(request.path(), "effects"),
                intSegmentAfter(request.path(), "attachments"));
    }

    private static String segmentAfter(String path, String marker) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (marker.equals(segments[i])) {
                return URLDecoder.decode(segments[i + 1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int intSegmentAfter(String path, String marker) {
        String value = segmentAfter(path, marker);
        try {
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
