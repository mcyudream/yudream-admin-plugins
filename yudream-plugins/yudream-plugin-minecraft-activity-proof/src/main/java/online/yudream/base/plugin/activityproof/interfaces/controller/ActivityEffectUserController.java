package online.yudream.base.plugin.activityproof.interfaces.controller;

import online.yudream.base.plugin.activityproof.interfaces.http.ActivityEffectHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 活动效果提交·用户面：活动结束后上传与查看本人提交。 */
public class ActivityEffectUserController {

    private final ActivityEffectHttpFacade http;

    public ActivityEffectUserController(ActivityEffectHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/activities/{activityId}/effects/upload",
            permission = MinecraftActivityProofPermissions.USER)
    public PluginHttpResponse upload(PluginHttpRequest request) {
        return http.uploadMine(request, segmentAfter(request.path(), "activities"),
                ActivityEffectHttpFacade.currentUserId(request));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/activities/{activityId}/effects",
            permission = MinecraftActivityProofPermissions.USER)
    public PluginHttpResponse submit(PluginHttpRequest request) {
        return http.submitMine(request, segmentAfter(request.path(), "activities"),
                ActivityEffectHttpFacade.currentUserId(request));
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/activities/{activityId}/effects",
            permission = MinecraftActivityProofPermissions.USER)
    public PluginHttpResponse byActivity(PluginHttpRequest request) {
        return http.myEffects(request, segmentAfter(request.path(), "activities"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/effects",
            permission = MinecraftActivityProofPermissions.USER)
    public PluginHttpResponse mine(PluginHttpRequest request) {
        return http.mySubmissions(request, ActivityEffectHttpFacade.currentUserId(request));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/effects/{effectId}",
            permission = MinecraftActivityProofPermissions.USER)
    public PluginHttpResponse withdraw(PluginHttpRequest request) {
        return http.withdrawMine(request, segmentAfter(request.path(), "effects"),
                ActivityEffectHttpFacade.currentUserId(request));
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/effects/{effectId}/attachments/{index}",
            permission = MinecraftActivityProofPermissions.USER, wrapResult = false)
    public PluginHttpResponse downloadMine(PluginHttpRequest request) {
        return http.downloadMine(request, segmentAfter(request.path(), "effects"),
                intSegmentAfter(request.path(), "attachments"),
                ActivityEffectHttpFacade.currentUserId(request));
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
