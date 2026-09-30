package online.yudream.base.plugin.forum.interfaces;

import online.yudream.base.plugin.forum.application.ForumAppService;
import online.yudream.base.plugin.forum.bootstrap.ForumPlugin;
import online.yudream.base.plugin.forum.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public final class ForumPublicController {
    private final ForumHttpFacade http;
    public ForumPublicController(ForumAppService service) { this.http = new ForumHttpFacade(service); }
    @PluginHttpEndpoint(method="GET", path="/public/categories", permission=ForumPlugin.VIEW_PERMISSION)
    public PluginHttpResponse categories(PluginHttpRequest r) { return http.categories(r); }
    @PluginHttpEndpoint(method="GET", path="/public/posts", permission=ForumPlugin.VIEW_PERMISSION)
    public PluginHttpResponse posts(PluginHttpRequest r) { return http.posts(r, false); }
    @PluginHttpEndpoint(method="GET", path="/public/mobile-feed", permission=ForumPlugin.VIEW_PERMISSION)
    public PluginHttpResponse mobileFeed(PluginHttpRequest r) { return http.mobileFeed(r); }
    @PluginHttpEndpoint(method="GET", path="/public/posts/{id}", permission=ForumPlugin.VIEW_PERMISSION)
    public PluginHttpResponse post(PluginHttpRequest r) { return http.post(r, HttpSupport.segmentAfter(r.path(), "posts")); }
    @PluginHttpEndpoint(method="GET", path="/public/posts/{id}/comments", permission=ForumPlugin.VIEW_PERMISSION)
    public PluginHttpResponse comments(PluginHttpRequest r) { return http.comments(r, HttpSupport.segmentAfter(r.path(), "posts")); }
    @PluginHttpEndpoint(method="GET", path="/public/tags", permission=ForumPlugin.VIEW_PERMISSION)
    public PluginHttpResponse tags() { return http.tags(); }
    @PluginHttpEndpoint(method="GET", path="/public/users/{id}", permission=ForumPlugin.VIEW_PERMISSION)
    public PluginHttpResponse profile(PluginHttpRequest r) { return http.profile(HttpSupport.segmentAfter(r.path(), "users")); }
}
