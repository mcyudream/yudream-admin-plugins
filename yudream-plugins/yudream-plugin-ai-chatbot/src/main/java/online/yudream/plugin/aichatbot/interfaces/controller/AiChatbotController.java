package online.yudream.plugin.aichatbot.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.aichatbot.bootstrap.AiChatbotPlugin;
import online.yudream.plugin.aichatbot.interfaces.http.AiChatbotHttpFacade;

public class AiChatbotController {
    private final AiChatbotHttpFacade http;
    public AiChatbotController(AiChatbotHttpFacade http) { this.http = http; }
    @PluginHttpEndpoint(method = "GET", path = "/admin/policies", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse policies(PluginHttpRequest request) { return http.policies(); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/policy", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse policy(PluginHttpRequest request) { return http.policy(request); }
    @PluginHttpEndpoint(method = "PUT", path = "/admin/policy", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse save(PluginHttpRequest request) { return http.save(request); }
    @PluginHttpEndpoint(method = "PUT", path = "/admin/policies/batch", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveBatch(PluginHttpRequest request) { return http.saveBatch(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/connections", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse connections(PluginHttpRequest request) { return http.connections(); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/groups", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse groups(PluginHttpRequest request) { return http.groups(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/tools", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse tools(PluginHttpRequest request) { return http.tools(); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/options/providers", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse providers(PluginHttpRequest request) { return http.providers(); }
}
