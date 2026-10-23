package online.yudream.base.plugin.cas.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.cas.bootstrap.CasSsoPlugin;
import online.yudream.base.plugin.cas.interfaces.http.CasSsoHttpFacade;

public final class CasSsoAdminController {

    private final CasSsoHttpFacade http;

    public CasSsoAdminController(CasSsoHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings() {
        return http.settings();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return http.saveSettings(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/test", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse test() {
        return http.test();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/oidc/register", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse registerOidc(PluginHttpRequest request) {
        return http.registerOidc(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/access-control", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse accessControl() {
        return http.accessControl();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/access-control", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveAccessControl(PluginHttpRequest request) {
        return http.saveAccessControl(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/students", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse students(PluginHttpRequest request) {
        return http.students(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/students/detail", permission = CasSsoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse studentDetail(PluginHttpRequest request) {
        return http.studentDetail(request);
    }

    /** 公开端点（permission 为空）：登录后的前端绑定门禁挂件读取。 */
    @PluginHttpEndpoint(method = "GET", path = "/public/gate")
    public PluginHttpResponse gate() {
        return http.gate();
    }

    /**
     * 公开端点（permission 为空）：登录前预热页。点第三方登录后先落到本站这一页，
     * 先发一次跨站请求拿到前置网关的会话 cookie，再跳真正的认证地址。
     */
    @PluginHttpEndpoint(method = "GET", path = "/public/warmup")
    public PluginHttpResponse warmup(PluginHttpRequest request) {
        return http.warmup(request);
    }
}
