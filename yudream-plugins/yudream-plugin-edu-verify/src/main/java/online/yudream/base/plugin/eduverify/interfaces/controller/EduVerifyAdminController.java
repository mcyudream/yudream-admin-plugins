package online.yudream.base.plugin.eduverify.interfaces.controller;

import online.yudream.base.plugin.eduverify.bootstrap.EduVerifyPlugin;
import online.yudream.base.plugin.eduverify.interfaces.http.EduVerifyHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class EduVerifyAdminController {

    private final EduVerifyHttpFacade http;

    public EduVerifyAdminController(EduVerifyHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/verifications", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse page(PluginHttpRequest request) {
        return http.adminPage(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/verifications/{id}", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return http.adminDetail(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/verifications/{id}/approve", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse approve(PluginHttpRequest request) {
        return http.approve(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/verifications/{id}/reject", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse reject(PluginHttpRequest request) {
        return http.reject(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/verifications/{id}/revoke", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse revoke(PluginHttpRequest request) {
        return http.revoke(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/domains", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse domains() {
        return http.domains();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/domains", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveDomain(PluginHttpRequest request) {
        return http.saveDomain(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/domains/{domain}", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteDomain(PluginHttpRequest request) {
        return http.deleteDomain(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings() {
        return http.settings();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return http.saveSettings(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/audit-logs", permission = EduVerifyPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse audits(PluginHttpRequest request) {
        return http.audits(request);
    }
}
