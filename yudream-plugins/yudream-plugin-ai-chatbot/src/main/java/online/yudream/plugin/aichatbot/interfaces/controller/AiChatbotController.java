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
    @PluginHttpEndpoint(method = "GET", path = "/admin/memory-profiles", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse profiles(PluginHttpRequest request) { return http.profiles(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/memory-profile", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse profile(PluginHttpRequest request) { return http.profile(request); }
    @PluginHttpEndpoint(method = "PUT", path = "/admin/memory-profile", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveProfile(PluginHttpRequest request) { return http.saveProfile(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/memory-profile/enabled", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse profileEnabled(PluginHttpRequest request) { return http.profileEnabled(request); }
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/memory-profile", permission = AiChatbotPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteProfile(PluginHttpRequest request) { return http.deleteProfile(request); }
}
