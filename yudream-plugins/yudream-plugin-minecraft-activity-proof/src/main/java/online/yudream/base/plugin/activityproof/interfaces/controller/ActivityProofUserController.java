package online.yudream.base.plugin.activityproof.interfaces.controller;

import online.yudream.base.plugin.activityproof.bootstrap.MinecraftActivityProofPlugin;
import online.yudream.base.plugin.activityproof.interfaces.http.ActivityProofHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class ActivityProofUserController {

    private final ActivityProofHttpFacade http;

    public ActivityProofUserController(ActivityProofHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/activities", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse myActivities(PluginHttpRequest request) {
        return http.myActivities(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/activities/{id}", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse myActivity(PluginHttpRequest request) {
        return http.myActivity(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/activities/{id}/join", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse joinActivity(PluginHttpRequest request) {
        return http.joinActivity(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/activities/{id}/cancel", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse cancelActivity(PluginHttpRequest request) {
        return http.cancelActivity(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/activities/{id}/quiz", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse myActivityQuiz(PluginHttpRequest request) {
        return http.myActivityQuiz(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/activities/{id}/quiz/attempt", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse startActivityQuiz(PluginHttpRequest request) {
        return http.startActivityQuiz(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/participations", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse myParticipations(PluginHttpRequest request) {
        return http.myParticipations(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/participations/{activityId}/verify", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse verifyMyParticipation(PluginHttpRequest request) {
        return http.verifyMyParticipation(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/exports", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse myExports(PluginHttpRequest request) {
        return http.myExports(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/exports/{id}/stamped-pdf/download", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION, wrapResult = false)
    public PluginHttpResponse downloadMyStampedPdf(PluginHttpRequest request) {
        return http.downloadMyStampedPdf(request);
    }
}
