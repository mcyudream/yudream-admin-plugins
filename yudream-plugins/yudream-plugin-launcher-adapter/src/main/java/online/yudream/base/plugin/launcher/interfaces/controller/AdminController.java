package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.bootstrap.LauncherAdapterPlugin;
import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class AdminController {

    private final LauncherHttpFacade http;

    public AdminController(LauncherHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/packs", permission = LauncherAdapterPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse listPacks(PluginHttpRequest request) {
        return http.adminListPacks(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/packs", permission = LauncherAdapterPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createPack(PluginHttpRequest request) {
        return http.adminCreatePack(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/packs/{packId}", permission = LauncherAdapterPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse getPack(PluginHttpRequest request) {
        return http.adminGetPack(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/packs/{packId}/rollback", permission = LauncherAdapterPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse rollback(PluginHttpRequest request) {
        return http.adminRollback(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/chrome", permission = LauncherAdapterPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse getChrome(PluginHttpRequest request) {
        return http.adminGetChrome(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/chrome", permission = LauncherAdapterPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveChrome(PluginHttpRequest request) {
        return http.adminSaveChrome(request);
    }
}
