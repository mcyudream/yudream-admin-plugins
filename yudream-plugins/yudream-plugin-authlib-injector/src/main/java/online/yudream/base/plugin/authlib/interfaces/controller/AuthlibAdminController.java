package online.yudream.base.plugin.authlib.interfaces.controller;

import online.yudream.base.plugin.authlib.bootstrap.AuthlibInjectorPlugin;
import online.yudream.base.plugin.authlib.interfaces.http.AuthlibHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class AuthlibAdminController {
    private final AuthlibHttpFacade http;

    public AuthlibAdminController(AuthlibHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/status", permission = AuthlibInjectorPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse status(PluginHttpRequest request) {
        return http.status(request);
    }
}
