package online.yudream.base.plugin.apprelease.interfaces;

import online.yudream.base.plugin.apprelease.bootstrap.AppReleasePlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 管理端点（/admin/**，plugin:app-release:manage）：上传、发布、下架、删除与强制更新设置。 */
public class AppReleaseAdminController {
    private final AppReleaseHttpFacade http;

    public AppReleaseAdminController(AppReleaseHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/releases", permission = AppReleasePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list() {
        return http.listAll();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/releases", permission = AppReleasePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse upload(PluginHttpRequest request) {
        return http.upload(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/releases/{id}/publish", permission = AppReleasePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse publish(PluginHttpRequest request) {
        return http.publish(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/releases/{id}/unpublish", permission = AppReleasePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse unpublish(PluginHttpRequest request) {
        return http.unpublish(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/releases/{id}", permission = AppReleasePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return http.delete(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = AppReleasePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings() {
        return http.settings();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = AppReleasePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return http.saveSettings(request);
    }
}
