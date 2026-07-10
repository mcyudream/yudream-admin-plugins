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

    @PluginHttpEndpoint(method = "GET", path = "/me/exports", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION)
    public PluginHttpResponse myExports(PluginHttpRequest request) {
        return http.myExports(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/exports/{id}/stamped-pdf/download", permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION, wrapResult = false)
    public PluginHttpResponse downloadMyStampedPdf(PluginHttpRequest request) {
        return http.downloadMyStampedPdf(request);
    }
}
