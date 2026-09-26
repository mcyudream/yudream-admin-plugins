package online.yudream.base.plugin.forum.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.forum.application.ForumAppService;
import online.yudream.base.plugin.forum.application.PageResult;
import online.yudream.base.plugin.forum.domain.ForumModels;
import online.yudream.base.plugin.forum.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

public final class ForumHttpFacade {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ForumAppService service;
    public ForumHttpFacade(ForumAppService service) { this.service = service; }

    public PluginHttpResponse categories(PluginHttpRequest r) { return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", service.categories(r.principal(), false).stream().map(this::categoryView).toList()))); }
    public PluginHttpResponse settings() { return PluginHttpResponse.ok(service.settings()); }
    public PluginHttpResponse saveSettings(PluginHttpRequest r) { return HttpSupport.guard(() -> { JsonNode n=body(r); return PluginHttpResponse.ok(service.saveSettings(text(n,"moderation"),n.has("aiTagging")?n.get("aiTagging").asBoolean():null,optional(n,"aiProviderCode"),optional(n,"aiModelCode"),n.has("aiTimeoutSeconds")?n.get("aiTimeoutSeconds").asInt():null,HttpSupport.userId(r))); }); }
    public PluginHttpResponse audit(PluginHttpRequest r) { return HttpSupport.guard(() -> { PageResult<Map<String,Object>> page=service.audit(HttpSupport.page(r),HttpSupport.size(r)); return PluginHttpResponse.ok(Map.of("records",page.records(),"total",page.total())); }); }

    public PluginHttpResponse adminCategories() { return PluginHttpResponse.ok(Map.of("records", service.adminCategories().stream().map(this::categoryView).toList())); }
    public PluginHttpResponse posts(PluginHttpRequest r, boolean adminOrMine) { return HttpSupport.guard(() -> { boolean admin = r.path().contains("/admin/"); PageResult<ForumModels.Post> page;
        if (!admin && adminOrMine) {
            page = service.pageMyPosts(HttpSupport.userId(r), text(r, "status"), HttpSupport.page(r), HttpSupport.size(r));
        } else {
            String author = admin ? text(r,"authorId") : "";
            page = service.pagePosts(r.principal(),text(r,"sort"),text(r,"categoryId"),text(r,"tag"),text(r,"keyword"),HttpSupport.page(r),HttpSupport.size(r),admin,text(r,"status"),author);
        }
        return PluginHttpResponse.ok(Map.of("records",page.records().stream().map(this::postView).toList(),"total",page.total())); }); }
    public PluginHttpResponse post(PluginHttpRequest r, String id) { return HttpSupport.guard(() -> PluginHttpResponse.ok(postView(service.publicPost(id, r.principal())))); }
    public PluginHttpResponse comments(PluginHttpRequest r, String id) { return HttpSupport.guard(() -> { service.publicPost(id, r.principal()); PageResult<ForumModels.Comment> page=service.pageComments(id,HttpSupport.page(r),HttpSupport.size(r)); return PluginHttpResponse.ok(Map.of("records",page.records().stream().map(this::commentView).toList(),"total",page.total())); }); }
    public PluginHttpResponse tags() { return PluginHttpResponse.ok(Map.of("records",service.tags())); }
    public PluginHttpResponse profile(String id) { return HttpSupport.guard(() -> { PluginUserProfile p=service.profile(id); if(p==null) throw new HttpSupport.NotFoundException("用户不存在"); return PluginHttpResponse.ok(Map.of("id",String.valueOf(p.id()),"username",safe(p.username()),"nickname",safe(p.nickname()),"avatar",safe(p.avatar()))); }); }
    public PluginHttpResponse savePost(PluginHttpRequest r, String id) { return HttpSupport.guard(() -> { JsonNode n=body(r); String category=text(n,"categoryId"); if(!service.canPost(r.principal(), category)) throw new IllegalStateException("没有该分类的发帖权限"); ForumModels.Post p=service.savePost(id,HttpSupport.userId(r),text(n,"title"),text(n,"body"),text(n,"summary"),category,strings(n,"tags"),bool(n,"draft",false)); return PluginHttpResponse.ok(postView(p)); }); }
    public PluginHttpResponse comment(PluginHttpRequest r,String postId) { return HttpSupport.guard(() -> { JsonNode n=body(r); return PluginHttpResponse.ok(commentView(service.addComment(HttpSupport.userId(r),postId,text(n,"body"),text(n,"parentId")))); }); }
    public PluginHttpResponse interact(PluginHttpRequest r,String postId,String type) { return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("active",service.toggleInteraction(HttpSupport.userId(r),postId,type)))); }
    public PluginHttpResponse createCategory(PluginHttpRequest r) { return HttpSupport.guard(() -> { JsonNode n=body(r); return PluginHttpResponse.ok(categoryView(service.createCategory(text(n,"name"),text(n,"slug"),text(n,"description"),integer(n,"sort",0),bool(n,"enabled",true),ForumModels.moderation(text(n,"moderation")),n.has("aiTagging")?n.get("aiTagging").asBoolean():null))); }); }
    public PluginHttpResponse updateCategory(PluginHttpRequest r,String id) { return HttpSupport.guard(() -> { JsonNode n=body(r); return PluginHttpResponse.ok(categoryView(service.updateCategory(id,optional(n,"name"),optional(n,"slug"),optional(n,"description"),n.has("sort")?n.get("sort").asInt():null,n.has("enabled")?n.get("enabled").asBoolean():null,n.has("moderation")?ForumModels.moderation(text(n,"moderation")):null,n.has("aiTagging")?n.get("aiTagging").asBoolean():null))); }); }
    public PluginHttpResponse deleteCategory(String id) { return HttpSupport.guard(() -> { service.deleteCategory(id); return PluginHttpResponse.ok(Map.of("deleted",true)); }); }
    public PluginHttpResponse moderate(PluginHttpRequest r,String id) { return HttpSupport.guard(() -> { JsonNode n=body(r); service.moderate(id,ForumModels.status(text(n,"status")),text(n,"reason")); return PluginHttpResponse.ok(postView(service.requirePost(id))); }); }
    public PluginHttpResponse flags(PluginHttpRequest r,String id) { return HttpSupport.guard(() -> { JsonNode n=body(r); return PluginHttpResponse.ok(postView(service.setFlags(id,n.has("pinned")?n.get("pinned").asBoolean():null,n.has("featured")?n.get("featured").asBoolean():null))); }); }

    private Map<String,Object> categoryView(ForumModels.Category c) { Map<String,Object> m=new LinkedHashMap<>(); m.put("id",c.id());m.put("slug",c.slug());m.put("name",c.name());m.put("description",c.description());m.put("sort",c.sort());m.put("enabled",c.enabled());m.put("viewPermission",c.viewPermission());m.put("postPermission",c.postPermission());m.put("moderation",c.moderation().name().toLowerCase());m.put("aiTagging",Boolean.TRUE.equals(c.aiTagging()));return m; }
    private Map<String,Object> postView(ForumModels.Post p) { Map<String,Object> m=new LinkedHashMap<>();m.put("id",p.id());m.put("title",p.title());m.put("body",p.body());m.put("summary",p.summary());m.put("categoryId",p.categoryId());m.put("tags",p.tags());m.put("authorId",p.authorId());m.put("status",p.status().name().toLowerCase());m.put("rejectionReason",p.rejectionReason());m.put("pinned",p.pinned());m.put("featured",p.featured());m.put("publishedAt",Long.toString(p.publishedAt()));m.put("createdAt",Long.toString(p.createdAt()));m.put("updatedAt",Long.toString(p.updatedAt()));m.put("views",p.views());m.put("likes",p.likes());m.put("comments",p.comments());m.put("bookmarks",p.bookmarks());return m; }
    private Map<String,Object> commentView(ForumModels.Comment c) { return Map.of("id",c.id(),"postId",c.postId(),"authorId",c.authorId(),"body",c.body(),"parentId",c.parentId(),"status",c.status().name().toLowerCase(),"createdAt",Long.toString(c.createdAt())); }
    private static JsonNode body(PluginHttpRequest r) { try { return JSON.readTree(r.body()==null?"{}":r.body()); } catch(Exception e) { throw new IllegalArgumentException("请求体不是有效 JSON"); } }
    private static String text(JsonNode n,String key) { return n==null||!n.has(key)||n.get(key).isNull()?"":n.get(key).asText(); }
    private static String text(PluginHttpRequest r,String key) { return HttpSupport.first(r,key); }
    private static String optional(JsonNode n,String key) { return n.has(key)&&!n.get(key).isNull()?n.get(key).asText():null; }
    private static boolean bool(JsonNode n,String key,boolean fallback) { return n.has(key)?n.get(key).asBoolean():fallback; }
    private static int integer(JsonNode n,String key,int fallback) { return n.has(key)?n.get(key).asInt():fallback; }
    private static List<String> strings(JsonNode n,String key) { List<String> out=new ArrayList<>(); if(n.has(key)&&n.get(key).isArray()) n.get(key).forEach(v->out.add(v.asText())); return out; }
    private static String safe(String v) { return v==null?"":v; }
}
