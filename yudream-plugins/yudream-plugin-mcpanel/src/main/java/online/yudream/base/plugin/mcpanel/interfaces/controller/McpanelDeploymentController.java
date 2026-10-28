package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.interfaces.http.InstanceDeploymentFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.*;

/** Administrator-only, scoped copy, deployment and rollback endpoints. */
public final class McpanelDeploymentController {
    private final InstanceDeploymentFacade facade;
    public McpanelDeploymentController(InstanceDeploymentFacade facade) { this.facade = facade; }
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/copy", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse copy(PluginHttpRequest request) { return facade.copy(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/deployments/preview", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse preview(PluginHttpRequest request) { return facade.preview(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/deployments", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deploy(PluginHttpRequest request) { return facade.deploy(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/deployments", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) { return facade.list(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/deployments/status", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse status(PluginHttpRequest request) { return facade.status(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/deployments/rollback", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse rollback(PluginHttpRequest request) { return facade.rollback(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/resource-packages/inspect", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse inspectPackage(PluginHttpRequest request) { return facade.inspectPackage(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/resource-packages/install", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse installPackage(PluginHttpRequest request) { return facade.installPackage(request); }
}
