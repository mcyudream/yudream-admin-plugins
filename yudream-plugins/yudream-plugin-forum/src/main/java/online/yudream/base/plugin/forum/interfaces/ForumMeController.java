package online.yudream.base.plugin.forum.interfaces;

import online.yudream.base.plugin.forum.application.ForumAppService;
import online.yudream.base.plugin.forum.bootstrap.ForumPlugin;
import online.yudream.base.plugin.forum.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public final class ForumMeController {
    private final ForumHttpFacade http;
    public ForumMeController(ForumAppService service) { this.http = new ForumHttpFacade(service); }
    @PluginHttpEndpoint(method="POST", path="/me/posts", permission=ForumPlugin.USE_PERMISSION)
    public PluginHttpResponse createPost(PluginHttpRequest r) { return http.savePost(r, null); }
    @PluginHttpEndpoint(method="PUT", path="/me/posts/{id}", permission=ForumPlugin.USE_PERMISSION)
    public PluginHttpResponse updatePost(PluginHttpRequest r) { return http.savePost(r, HttpSupport.segmentAfter(r.path(), "posts")); }
    @PluginHttpEndpoint(method="GET", path="/me/posts", permission=ForumPlugin.USE_PERMISSION)
    public PluginHttpResponse myPosts(PluginHttpRequest r) { return http.posts(r, true); }
    @PluginHttpEndpoint(method="POST", path="/me/posts/{id}/comments", permission=ForumPlugin.USE_PERMISSION)
    public PluginHttpResponse comment(PluginHttpRequest r) { return http.comment(r, HttpSupport.segmentAfter(r.path(), "posts")); }
    @PluginHttpEndpoint(method="POST", path="/me/posts/{id}/like", permission=ForumPlugin.USE_PERMISSION)
    public PluginHttpResponse like(PluginHttpRequest r) { return http.interact(r, HttpSupport.segmentAfter(r.path(), "posts"), "like"); }
    @PluginHttpEndpoint(method="POST", path="/me/posts/{id}/bookmark", permission=ForumPlugin.USE_PERMISSION)
    public PluginHttpResponse bookmark(PluginHttpRequest r) { return http.interact(r, HttpSupport.segmentAfter(r.path(), "posts"), "bookmark"); }
}
