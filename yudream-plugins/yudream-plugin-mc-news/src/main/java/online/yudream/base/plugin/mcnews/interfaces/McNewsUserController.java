package online.yudream.base.plugin.mcnews.interfaces;

import online.yudream.base.plugin.mcnews.bootstrap.McNewsPlugin;
import online.yudream.base.plugin.mcnews.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 用户端点（/me/**，plugin:mc-news:use）：我的订阅状态、私信开关与个人 Webhook 维护。 */
public class McNewsUserController {
    private final McNewsHttpFacade http;

    public McNewsUserController(McNewsHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/subscription", permission = McNewsPlugin.USE_PERMISSION)
    public PluginHttpResponse mySubscription(PluginHttpRequest request) {
        return http.mySubscription(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/subscription/direct", permission = McNewsPlugin.USE_PERMISSION)
    public PluginHttpResponse setDirectEnabled(PluginHttpRequest request) {
        return http.setDirectEnabled(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/webhooks", permission = McNewsPlugin.USE_PERMISSION)
    public PluginHttpResponse createWebhook(PluginHttpRequest request) {
        return http.createMyWebhook(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/webhooks/{id}", permission = McNewsPlugin.USE_PERMISSION)
    public PluginHttpResponse updateWebhook(PluginHttpRequest request) {
        return http.updateMyWebhook(request, HttpSupport.segment(request, 2));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/webhooks/{id}", permission = McNewsPlugin.USE_PERMISSION)
    public PluginHttpResponse deleteWebhook(PluginHttpRequest request) {
        return http.deleteMyWebhook(request, HttpSupport.segment(request, 2));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/webhooks/{id}/test", permission = McNewsPlugin.USE_PERMISSION)
    public PluginHttpResponse testWebhook(PluginHttpRequest request) {
        return http.testMyWebhook(request, HttpSupport.segment(request, 2));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/subscription/direct/test", permission = McNewsPlugin.USE_PERMISSION)
    public PluginHttpResponse testDirect(PluginHttpRequest request) {
        return http.testMyDirect(request);
    }
}
