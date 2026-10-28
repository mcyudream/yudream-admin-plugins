package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.application.service.CeResourcePackageService;
import online.yudream.base.plugin.mcpanel.application.service.InstanceDeploymentService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.http.*;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

/** HTTP boundary for administrator deployment workflows. */
public final class InstanceDeploymentFacade {
    private final InstanceDeploymentService deployments;
    private final CeResourcePackageService packages;
    private final PluginSecurityService security;
    public InstanceDeploymentFacade(InstanceDeploymentService deployments, CeResourcePackageService packages, PluginSecurityService security) {
        this.deployments = deployments; this.packages = packages; this.security = security;
    }
    public PluginHttpResponse preview(PluginHttpRequest request) {
        return manage(request, () -> {
            var body = McpanelJson.readMap(request.body());
            return deployments.preview(scope(request), id(request), body.string("targetId"), body.stringList("paths"));
        });
    }
    public PluginHttpResponse deploy(PluginHttpRequest request) {
        return manage(request, () -> deployments.deploy(HttpGuards.actorOf(request), scope(request), id(request), McpanelJson.readMap(request.body()).string("planId")));
    }
    public PluginHttpResponse copy(PluginHttpRequest request) {
        return manage(request, () -> deployments.copy(HttpGuards.actorOf(request), scope(request), id(request), McpanelJson.readMap(request.body()).string("name")));
    }
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> PluginHttpResponse.ok(deployments.list(scope(request), id(request), queryNumber(request, "page", 1), queryNumber(request, "size", 10))));
    }
    public PluginHttpResponse status(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> PluginHttpResponse.ok(deployments.status(scope(request), id(request), query(request, "jobId"))));
    }
    public PluginHttpResponse rollback(PluginHttpRequest request) {
        return manage(request, () -> deployments.rollback(HttpGuards.actorOf(request), scope(request), id(request), McpanelJson.readMap(request.body()).string("jobId")));
    }
    public PluginHttpResponse inspectPackage(PluginHttpRequest request) {
        return manage(request, () -> {
            PluginHttpPart file = request.parts() == null ? null : request.parts().get("file");
            if (file == null || !file.isFile()) throw new IllegalArgumentException("请选择 CE 安装包 ZIP");
            return packages.inspect(HttpGuards.actorOf(request), scope(request), id(request), file.data());
        });
    }
    public PluginHttpResponse installPackage(PluginHttpRequest request) {
        return manage(request, () -> packages.install(HttpGuards.actorOf(request), scope(request), id(request), McpanelJson.readMap(request.body()).string("packageId")));
    }
    private PluginHttpResponse manage(PluginHttpRequest request, java.util.function.Supplier<Object> action) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> PluginHttpResponse.ok(action.get()));
    }
    private static String scope(PluginHttpRequest request) { return "user:" + request.principal().userId(); }
    private static String id(PluginHttpRequest request) { return request.path().split("/")[3]; }
    private static String query(PluginHttpRequest request, String key) {
        var values = request.query().get(key); return values == null || values.isEmpty() ? null : values.getFirst();
    }
    private static int queryNumber(PluginHttpRequest request, String key, int fallback) {
        String value = query(request, key); return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
