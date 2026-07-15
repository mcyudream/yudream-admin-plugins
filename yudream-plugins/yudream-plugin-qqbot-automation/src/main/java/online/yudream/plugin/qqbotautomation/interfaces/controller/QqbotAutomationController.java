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
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/connections", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse connections() { return http.connections(); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/groups", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse groups(PluginHttpRequest request) { return http.groups(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/media-jobs", permission = QqbotAutomationPlugin.MANAGE_PERMISSION) public PluginHttpResponse mediaJobs(PluginHttpRequest request) { return http.mediaJobs(request); }
}
