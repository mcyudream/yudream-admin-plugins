package online.yudream.plugin.qqbotautomation.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.qqbotautomation.bootstrap.QqbotAutomationPlugin;
import online.yudream.plugin.qqbotautomation.interfaces.http.QqbotAutomationHttpFacade;

public class QqbotAutomationController {
    private final QqbotAutomationHttpFacade http;
    public QqbotAutomationController(QqbotAutomationHttpFacade http) { this.http = http; }
    @PluginHttpEndpoint(method = "GET", path = "/admin/policies", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse policies() { return http.policies(); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/policy", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse policy(PluginHttpRequest request) { return http.policy(request); }
    @PluginHttpEndpoint(method = "PUT", path = "/admin/policy", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse save(PluginHttpRequest request) { return http.save(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/default-policy", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse defaults(PluginHttpRequest request) { return http.defaults(request); }
    @PluginHttpEndpoint(method = "PUT", path = "/admin/default-policy", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveDefaults(PluginHttpRequest request) { return http.saveDefaults(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/group-overrides", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse overrides(PluginHttpRequest request) { return http.overrides(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/group-overrides/{channelId}", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse override(PluginHttpRequest request) { return http.override(request); }
    @PluginHttpEndpoint(method = "PUT", path = "/admin/group-overrides", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveOverride(PluginHttpRequest request) { return http.saveOverride(request); }
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/group-overrides/{channelId}", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse deleteOverride(PluginHttpRequest request) { return http.deleteOverride(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/connections", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse connections() { return http.connections(); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/groups", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse groups(PluginHttpRequest request) { return http.groups(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/ai", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse aiOptions() { return http.aiOptions(); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/media-jobs/{id}", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse mediaJob(PluginHttpRequest request) { return http.mediaJob(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/media-jobs", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse mediaJobs(PluginHttpRequest request) { return http.mediaJobs(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/media-jobs/test", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse startMediaTest(PluginHttpRequest request) { return http.startMediaTest(request); }
}
