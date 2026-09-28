package online.yudream.base.plugin.forum.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import online.yudream.base.plugin.forum.bootstrap.ForumPlugin;
import online.yudream.base.plugin.forum.domain.ForumModels;
import online.yudream.base.plugin.forum.infrastructure.Doc;
import online.yudream.base.plugin.forum.infrastructure.ForumDocs;
import online.yudream.base.plugin.forum.infrastructure.ForumRepository;
import online.yudream.base.plugin.forum.infrastructure.Ids;
import online.yudream.base.plugin.forum.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.permission.PluginPermissionItem;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

public final class ForumAppService {
    private final ForumRepository repository;
    private final FrameworkServices framework;
    private final PluginContext context;

    public ForumAppService(ForumRepository repository, FrameworkServices framework, PluginContext context) {
        this.repository = repository; this.framework = framework; this.context = context;
    }

    public List<ForumModels.Category> categories(PluginPrincipal principal, boolean includeDisabled) {
        return repository.all(ForumRepository.CATEGORIES).stream().map(ForumDocs::category)
                .filter(c -> includeDisabled || c.enabled()).filter(c -> allowed(principal, c.viewPermission(), ForumPlugin.VIEW_PERMISSION))
                .sorted(Comparator.comparingInt(ForumModels.Category::sort).thenComparing(ForumModels.Category::name)).toList();
    }

    public List<ForumModels.Category> adminCategories() {
        return repository.all(ForumRepository.CATEGORIES).stream().map(ForumDocs::category)
                .sorted(Comparator.comparingInt(ForumModels.Category::sort).thenComparing(ForumModels.Category::name)).toList();
    }

    public ForumModels.Category createCategory(String name, String slug, String description, int sort, boolean enabled,
                                                ForumModels.Moderation moderation, Boolean aiTagging) {
        requireText(name, "分类名称"); String id = Ids.newId(); long now = System.currentTimeMillis();
        ForumModels.Category category = new ForumModels.Category(id, slugify(slug, name), name.trim(), description == null ? "" : description.trim(), sort, enabled,
                ForumPlugin.categoryViewPermission(id), ForumPlugin.categoryPostPermission(id), moderation == null ? ForumModels.Moderation.INHERIT : moderation, aiTagging, now, now);
        repository.save(ForumRepository.CATEGORIES, id, category.doc()); registerCategoryPermissions(category); return category;
    }

    public ForumModels.Category updateCategory(String id, String name, String slug, String description, Integer sort, Boolean enabled,
                                                ForumModels.Moderation moderation, Boolean aiTagging) {
        ForumModels.Category old = requireCategory(id); long now = System.currentTimeMillis();
        ForumModels.Category next = new ForumModels.Category(id, slugify(slug == null ? old.slug() : slug, name == null ? old.name() : name),
                name == null ? old.name() : name.trim(), description == null ? old.description() : description.trim(), sort == null ? old.sort() : sort,
                enabled == null ? old.enabled() : enabled, old.viewPermission(), old.postPermission(), moderation == null ? old.moderation() : moderation,
                aiTagging == null ? old.aiTagging() : aiTagging, old.createdAt(), now);
        repository.save(ForumRepository.CATEGORIES, id, next.doc()); registerCategoryPermissions(next); return next;
    }

    public void deleteCategory(String id) { requireCategory(id); if (repository.all(ForumRepository.POSTS).stream().anyMatch(d -> id.equals(Doc.text(d,"categoryId")) && !"ARCHIVED".equals(Doc.text(d,"status")))) throw new IllegalStateException("分类仍有未归档帖子"); repository.delete(ForumRepository.CATEGORIES, id); }

    public PageResult<ForumModels.Post> pagePosts(PluginPrincipal principal, String sort, String categoryId, String tag, String keyword, int page, int size, boolean admin, String status, String authorId) {
        return pagePosts(principal, sort, categoryId, tag, keyword, page, size, admin, status, authorId, false);
    }

    private PageResult<ForumModels.Post> pagePosts(PluginPrincipal principal, String sort, String categoryId, String tag, String keyword, int page, int size, boolean admin, String status, String authorId, boolean includeOwn) {
        String subjectId = principal == null || principal.userId() == null ? "" : String.valueOf(principal.userId());
        List<ForumModels.Post> posts = repository.all(ForumRepository.POSTS).stream().map(ForumDocs::post)
                .filter(p -> admin || (includeOwn && subjectId.equals(p.authorId())) || p.status() == ForumModels.Status.PUBLISHED)
                .filter(p -> admin || (includeOwn && subjectId.equals(p.authorId())) || allowedCategory(principal, p.categoryId(), false))
                .filter(p -> categoryId == null || categoryId.isBlank() || categoryId.equals(p.categoryId()))
                .filter(p -> tag == null || tag.isBlank() || p.tags().contains(tag))
                .filter(p -> keyword == null || keyword.isBlank() || (p.title()+" "+p.summary()).toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))
                .filter(p -> status == null || status.isBlank() || p.status().name().equalsIgnoreCase(status))
                .filter(p -> authorId == null || authorId.isBlank() || authorId.equals(p.authorId())).toList();
        Comparator<ForumModels.Post> order = Comparator.comparing(ForumModels.Post::pinned).reversed().thenComparing(ForumModels.Post::featured).reversed();
        if ("hot".equalsIgnoreCase(sort)) order = order.thenComparing(Comparator.comparingLong((ForumModels.Post p) -> p.likes()+p.comments()*2+p.bookmarks()*3+p.views()/10).reversed());
        else order = order.thenComparing(ForumModels.Post::publishedAt, Comparator.reverseOrder()).thenComparing(ForumModels.Post::createdAt, Comparator.reverseOrder());
        if ("featured".equalsIgnoreCase(sort)) posts = posts.stream().filter(ForumModels.Post::featured).toList();
        posts = posts.stream().sorted(order).toList();
        int from = Math.min((page-1)*size, posts.size()), to = Math.min(from+size, posts.size());
        return new PageResult<>(posts.subList(from, to), posts.size());
    }

    public ForumModels.Post publicPost(String id, PluginPrincipal principal) {
        ForumModels.Post post = requirePost(id);
        if (post.status() != ForumModels.Status.PUBLISHED || !allowedCategory(principal, post.categoryId(), false)) {
            throw new HttpNotFound();
        }
        // 浏览数真实自增：每次公开详情读取 +1 并持久化
        ForumModels.Post viewed = new ForumModels.Post(post.id(), post.title(), post.body(), post.summary(), post.categoryId(), post.tags(), post.authorId(), post.status(), post.rejectionReason(), post.pinned(), post.featured(), post.publishedAt(), post.createdAt(), System.currentTimeMillis(), post.views() + 1, post.likes(), post.comments(), post.bookmarks());
        repository.save(ForumRepository.POSTS, id, viewed.doc());
        return viewed;
    }

    public ForumModels.Post requirePost(String id) { return repository.find(ForumRepository.POSTS, id).map(ForumDocs::post).orElseThrow(HttpNotFound::new); }

    public boolean canPost(PluginPrincipal principal, String categoryId) {
        return allowedCategory(principal, categoryId, true);
    }

    public ForumModels.Post savePost(String id, long authorId, String title, String body, String summary, String categoryId, List<String> tags, boolean draft) {
        requireText(title, "标题"); requireText(body, "正文"); ForumModels.Category category = requireCategory(categoryId); if (!category.enabled()) throw new IllegalStateException("分类已停用");
        long now=System.currentTimeMillis(); ForumModels.Post old=id==null||id.isBlank()?null:requirePost(id); if(old!=null && !String.valueOf(authorId).equals(old.authorId())) throw new IllegalStateException("只能编辑自己的帖子");
        ForumModels.Status status = draft ? ForumModels.Status.DRAFT : effectiveModeration(category) == ForumModels.Moderation.OFF ? ForumModels.Status.PUBLISHED : ForumModels.Status.PENDING;
        ForumModels.Post post = new ForumModels.Post(old == null ? Ids.newId() : old.id(), title.trim(), body, summary == null ? excerpt(body) : summary, categoryId, normalizeTags(tags), String.valueOf(authorId), status, "", old != null && old.pinned(), old != null && old.featured(), status == ForumModels.Status.PUBLISHED ? now : old == null ? 0 : old.publishedAt(), old == null ? now : old.createdAt(), now, old == null ? 0 : old.views(), old == null ? 0 : old.likes(), old == null ? 0 : old.comments(), old == null ? 0 : old.bookmarks());
        repository.save(ForumRepository.POSTS, post.id(), post.doc()); return post;
    }

    public ForumModels.Comment addComment(long authorId, String postId, String body, String parentId) { ForumModels.Post post=requirePost(postId); if(post.status()!=ForumModels.Status.PUBLISHED) throw new IllegalStateException("帖子尚未发布"); requireText(body,"评论内容"); if(parentId!=null&&!parentId.isBlank()&&!parentId.equals(postId)&&repository.find(ForumRepository.COMMENTS,parentId).isEmpty()) throw new IllegalArgumentException("回复目标不存在"); ForumModels.Comment c=new ForumModels.Comment(Ids.newId(),postId,String.valueOf(authorId),body,parentId,ForumModels.Status.PUBLISHED,System.currentTimeMillis()); repository.save(ForumRepository.COMMENTS,c.id(),c.doc()); updatePostCount(post, "comments", post.comments()+1); return c; }
    public PageResult<ForumModels.Comment> pageComments(String postId, int page, int size) { List<ForumModels.Comment> all=repository.all(ForumRepository.COMMENTS).stream().map(ForumDocs::comment).filter(c->postId.equals(c.postId())&&c.status()==ForumModels.Status.PUBLISHED).sorted(Comparator.comparingLong(ForumModels.Comment::createdAt)).toList(); int from=Math.min((page-1)*size,all.size()),to=Math.min(from+size,all.size()); return new PageResult<>(all.subList(from,to),all.size()); }
    public boolean toggleInteraction(long userId, String postId, String type) { if(!type.equals("like")&&!type.equals("bookmark")) throw new IllegalArgumentException("不支持的互动类型"); ForumModels.Post post=requirePost(postId); String key=userId+":"+postId+":"+type; boolean exists=repository.find(ForumRepository.INTERACTIONS,key).isPresent(); if(exists){repository.delete(ForumRepository.INTERACTIONS,key);} else {repository.save(ForumRepository.INTERACTIONS,key,Map.of("userId",String.valueOf(userId),"postId",postId,"type",type,"createdAt",System.currentTimeMillis()));} updatePostCount(post,type,exists?count(post,type)-1:count(post,type)+1); return !exists; }
    public void moderate(String id, ForumModels.Status status, String reason) { if(status!=ForumModels.Status.PUBLISHED&&status!=ForumModels.Status.REJECTED&&status!=ForumModels.Status.ARCHIVED) throw new IllegalArgumentException("不支持的审核状态"); ForumModels.Post old=requirePost(id); long now=System.currentTimeMillis(); ForumModels.Post next=new ForumModels.Post(old.id(),old.title(),old.body(),old.summary(),old.categoryId(),old.tags(),old.authorId(),status,reason==null?"":reason,old.pinned(),old.featured(),status==ForumModels.Status.PUBLISHED?now:old.publishedAt(),old.createdAt(),now,old.views(),old.likes(),old.comments(),old.bookmarks()); repository.save(ForumRepository.POSTS,id,next.doc()); }
    public ForumModels.Post setFlags(String id, Boolean pinned, Boolean featured) { ForumModels.Post old=requirePost(id); ForumModels.Post next=new ForumModels.Post(old.id(),old.title(),old.body(),old.summary(),old.categoryId(),old.tags(),old.authorId(),old.status(),old.rejectionReason(),pinned==null?old.pinned():pinned,featured==null?old.featured():featured,old.publishedAt(),old.createdAt(),System.currentTimeMillis(),old.views(),old.likes(),old.comments(),old.bookmarks()); repository.save(ForumRepository.POSTS,id,next.doc()); return next; }
    public PluginUserProfile profile(String userId) { try { return framework.users().findById(Long.valueOf(userId)).orElse(null); } catch (Exception e) { return null; } }
    public PageResult<ForumModels.Post> pageMyPosts(long userId, String status, int page, int size) {
        return pagePosts(new PluginPrincipal(userId, List.of(ForumPlugin.VIEW_PERMISSION)), "latest", "", "", "", page, size, false, status, String.valueOf(userId), true);
    }

    public Map<String, Object> settings() {
        Map<String, Object> saved = repository.find(ForumRepository.SETTINGS, "global").orElse(Map.of());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("moderation", Doc.text(saved, "moderation").isBlank() ? "manual" : Doc.text(saved, "moderation").toLowerCase(Locale.ROOT));
        view.put("aiTagging", saved.containsKey("aiTagging") && Doc.bool(saved, "aiTagging"));
        view.put("aiProviderCode", Doc.text(saved, "aiProviderCode"));
        view.put("aiModelCode", Doc.text(saved, "aiModelCode"));
        view.put("aiTimeoutSeconds", Math.max(5, Math.min(120, Doc.integer(saved, "aiTimeoutSeconds") == 0 ? 20 : Doc.integer(saved, "aiTimeoutSeconds"))));
        return view;
    }

    public Map<String, Object> saveSettings(String moderation, Boolean aiTagging, String providerCode, String modelCode, Integer timeoutSeconds, long actorId) {
        ForumModels.Moderation mode = ForumModels.moderation(moderation);
        if (mode == ForumModels.Moderation.INHERIT) mode = ForumModels.Moderation.MANUAL;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("moderation", mode.name().toLowerCase(Locale.ROOT)); doc.put("aiTagging", Boolean.TRUE.equals(aiTagging));
        doc.put("aiProviderCode", providerCode == null ? "" : providerCode.trim()); doc.put("aiModelCode", modelCode == null ? "" : modelCode.trim());
        doc.put("aiTimeoutSeconds", Math.max(5, Math.min(120, timeoutSeconds == null ? 20 : timeoutSeconds)));
        repository.save(ForumRepository.SETTINGS, "global", doc); audit(actorId, "settings", "save", "论坛全局设置已更新"); return settings();
    }

    public PageResult<Map<String, Object>> audit(int page, int size) {
        List<Map<String, Object>> records = repository.all("forum_audit").stream().sorted((a,b) -> Long.compare(Doc.number(b,"createdAt"), Doc.number(a,"createdAt"))).toList();
        int from = Math.min((page - 1) * size, records.size()), to = Math.min(from + size, records.size());
        return new PageResult<>(records.subList(from, to), records.size());
    }

    private void audit(long actorId, String targetType, String action, String detail) {
        String id = Ids.newId(); repository.save("forum_audit", id, Map.of("id", id, "actorId", Long.toString(actorId), "targetType", targetType, "action", action, "detail", detail, "createdAt", System.currentTimeMillis()));
    }


    public List<String> tags() { return repository.all(ForumRepository.POSTS).stream().flatMap(d->Doc.strings(d,"tags").stream()).distinct().sorted().toList(); }

    private ForumModels.Category requireCategory(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("请选择分类");
        return repository.find(ForumRepository.CATEGORIES, id).map(ForumDocs::category).orElseThrow(() -> new HttpNotFound("分类不存在"));
    }
    private boolean allowedCategory(PluginPrincipal p,String id,boolean posting){ ForumModels.Category c=requireCategory(id); return allowed(p,posting?c.postPermission():c.viewPermission(),ForumPlugin.VIEW_PERMISSION); }
    private boolean allowed(PluginPrincipal p,String dynamic,String base){ return p!=null && (p.hasPermission("*") || p.hasPermission(base) && (dynamic==null||dynamic.isBlank()||p.hasPermission(dynamic))); }
    private void registerCategoryPermissions(ForumModels.Category c){ context.registerPermission(new PluginPermissionItem(c.viewPermission(),"查看分类："+c.name(),"论坛分类","查看该分类帖子")); context.registerPermission(new PluginPermissionItem(c.postPermission(),"在分类发帖："+c.name(),"论坛分类","在该分类发布帖子")); }
    private ForumModels.Moderation effectiveModeration(ForumModels.Category c){ if(c.moderation()!=ForumModels.Moderation.INHERIT)return c.moderation(); return ForumModels.Moderation.MANUAL; }
    private void updatePostCount(ForumModels.Post p,String type,long value){ ForumModels.Post n=new ForumModels.Post(p.id(),p.title(),p.body(),p.summary(),p.categoryId(),p.tags(),p.authorId(),p.status(),p.rejectionReason(),p.pinned(),p.featured(),p.publishedAt(),p.createdAt(),System.currentTimeMillis(),p.views(),type.equals("like")?value:p.likes(),type.equals("comments")?value:p.comments(),type.equals("bookmark")?value:p.bookmarks()); repository.save(ForumRepository.POSTS,p.id(),n.doc()); }
    private long count(ForumModels.Post p,String type){ return type.equals("like")?p.likes():p.bookmarks(); }
    private static String slugify(String slug,String fallback){ String value=(slug==null?"":slug).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+","-").replaceAll("^-+|-+$",""); return value.isBlank()?fallback.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+","-"):value; }
    private static String excerpt(String body){ String value=body.replaceAll("[#>*`_]","").replaceAll("\\s+"," ").trim(); return value.length()>180?value.substring(0,180):value; }
    private static List<String> normalizeTags(List<String> tags){ if(tags==null)return List.of(); return tags.stream().map(x->x==null?"":x.trim().toLowerCase(Locale.ROOT)).filter(x->!x.isBlank()).distinct().limit(8).toList(); }
    private static void requireText(String v,String label){ if(v==null||v.isBlank())throw new IllegalArgumentException(label+"不能为空"); }
    public static class HttpNotFound extends HttpSupport.NotFoundException { public HttpNotFound(){super("资源不存在");} public HttpNotFound(String m){super(m);} }
}
