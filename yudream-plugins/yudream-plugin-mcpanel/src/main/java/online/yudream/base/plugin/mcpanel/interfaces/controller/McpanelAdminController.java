package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.interfaces.http.McpanelHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 薄控制器：仅注解声明与委托。M1 全部为管理面 + 机器面 bootstrap；
 * 运行时挂载于 /api/plugins/mcpanel/**。
 */
public class McpanelAdminController {

    private final McpanelHttpFacade facade;

    public McpanelAdminController(McpanelHttpFacade facade) {
        this.facade = facade;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/nodes", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse page(PluginHttpRequest request) {
        return facade.page(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/nodes", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return facade.create(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/nodes/{nodeId}", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return facade.detail(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/nodes/{nodeId}", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return facade.update(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/nodes/{nodeId}", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return facade.delete(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/nodes/{nodeId}/enrollment",
            permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse issueEnrollment(PluginHttpRequest request) {
        return facade.issueEnrollment(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/nodes/{nodeId}/reconnect",
            permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse reconnect(PluginHttpRequest request) {
        return facade.reconnect(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/nodes/{nodeId}/events",
            permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse events(PluginHttpRequest request) {
        return facade.events(request);
    }

    /** 机器面唯一免登录端点：一次性 token 门禁，rawJson 下发（协议 §2）。 */
    @PluginHttpEndpoint(method = "POST", path = "/node/bootstrap", permission = "")
    public PluginHttpResponse bootstrap(PluginHttpRequest request) {
        return facade.bootstrap(request);
    }
}
