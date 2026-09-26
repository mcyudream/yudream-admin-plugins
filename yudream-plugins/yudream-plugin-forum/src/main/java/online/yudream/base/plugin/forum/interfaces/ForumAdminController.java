package online.yudream.base.plugin.forum.interfaces;

import online.yudream.base.plugin.forum.application.ForumAppService;
import online.yudream.base.plugin.forum.bootstrap.ForumPlugin;
import online.yudream.base.plugin.forum.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public final class ForumAdminController {
    private final ForumHttpFacade http;
    public ForumAdminController(ForumAppService service) { this.http = new ForumHttpFacade(service); }
    @PluginHttpEndpoint(method="GET", path="/admin/categories", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse categories() { return http.adminCategories(); }
    @PluginHttpEndpoint(method="POST", path="/admin/categories", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createCategory(PluginHttpRequest r) { return http.createCategory(r); }
    @PluginHttpEndpoint(method="PUT", path="/admin/categories/{id}", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateCategory(PluginHttpRequest r) { return http.updateCategory(r, HttpSupport.segmentAfter(r.path(), "categories")); }
    @PluginHttpEndpoint(method="DELETE", path="/admin/categories/{id}", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteCategory(PluginHttpRequest r) { return http.deleteCategory(HttpSupport.segmentAfter(r.path(), "categories")); }
    @PluginHttpEndpoint(method="GET", path="/admin/posts", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse posts(PluginHttpRequest r) { return http.posts(r, true); }
    @PluginHttpEndpoint(method="POST", path="/admin/posts/{id}/moderate", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse moderate(PluginHttpRequest r) { return http.moderate(r, HttpSupport.segmentAfter(r.path(), "posts")); }
    @PluginHttpEndpoint(method="POST", path="/admin/posts/{id}/flags", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse flags(PluginHttpRequest r) { return http.flags(r, HttpSupport.segmentAfter(r.path(), "posts")); }
    @PluginHttpEndpoint(method="GET", path="/admin/settings", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings() { return http.settings(); }
    @PluginHttpEndpoint(method="PUT", path="/admin/settings", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest r) { return http.saveSettings(r); }
    @PluginHttpEndpoint(method="GET", path="/admin/audit", permission=ForumPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse audit(PluginHttpRequest r) { return http.audit(r); }
}
