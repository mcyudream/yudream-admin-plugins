package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.bootstrap.LauncherAdapterPlugin;
import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class PublishController {

    private final LauncherHttpFacade http;

    public PublishController(LauncherHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "POST", path = "/v1/packs", permission = LauncherAdapterPlugin.MANAGE_PERMISSION, wrapResult = false)
    public PluginHttpResponse createPack(PluginHttpRequest request) {
        return http.createPack(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/v1/packs/{packId}/versions", permission = LauncherAdapterPlugin.MANAGE_PERMISSION, wrapResult = false)
    public PluginHttpResponse publishVersion(PluginHttpRequest request) {
        return http.publishVersion(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/v1/packs/{packId}/versions/{versionId}/overrides", permission = LauncherAdapterPlugin.MANAGE_PERMISSION, wrapResult = false)
    public PluginHttpResponse uploadOverrides(PluginHttpRequest request) {
        return http.uploadOverrides(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/v1/packs/{packId}/rollback", permission = LauncherAdapterPlugin.MANAGE_PERMISSION, wrapResult = false)
    public PluginHttpResponse rollback(PluginHttpRequest request) {
        return http.rollback(request);
    }
}
