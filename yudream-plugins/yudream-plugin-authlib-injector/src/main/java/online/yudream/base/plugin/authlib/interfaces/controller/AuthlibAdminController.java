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

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = AuthlibInjectorPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings(PluginHttpRequest request) {
        return http.settings(request);
    }

    /** 站点签名密钥信息：公钥 PEM/指纹（联盟共享钥核对用）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/signing-key", permission = AuthlibInjectorPlugin.MANAGE_PERMISSION)
    public Object signingKey(PluginHttpRequest request) {
        return http.signingKey(request);
    }

    /** 导入联盟共享材质签名密钥（PEM 对）。 */
    @PluginHttpEndpoint(method = "PUT", path = "/admin/signing-key", permission = AuthlibInjectorPlugin.MANAGE_PERMISSION)
    public Object importSigningKey(PluginHttpRequest request) {
        return http.importSigningKey(request);
    }

    /** 丢弃导入的密钥对，重新自生成。 */
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/signing-key", permission = AuthlibInjectorPlugin.MANAGE_PERMISSION)
    public Object resetSigningKey(PluginHttpRequest request) {
        return http.resetSigningKey(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = AuthlibInjectorPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return http.saveSettings(request);
    }
}
