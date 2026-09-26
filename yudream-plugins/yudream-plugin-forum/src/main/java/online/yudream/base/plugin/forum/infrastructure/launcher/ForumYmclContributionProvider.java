package online.yudream.base.plugin.forum.infrastructure.launcher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.forum.application.ForumAppService;
import online.yudream.base.plugin.forum.bootstrap.ForumPlugin;
import online.yudream.base.plugin.forum.domain.ForumModels;
import online.yudream.base.plugin.ymcl.api.YmclBundleContribution;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
import online.yudream.base.plugin.ymcl.api.YmclModuleSupport;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;

public final class ForumYmclContributionProvider implements YmclContributionProvider {
    private static final String RESOURCE = "/ymcl-pages/forum-page.js";
    private final ForumAppService service;
    public ForumYmclContributionProvider(ForumAppService service) { this.service = service; }
    @Override public String providerCode() { return ForumPlugin.CODE; }
    @Override public List<YmclPageDescriptor> pages() {
        YmclModuleSupport.YmclModuleBundle module = module();
        if (module == null) return List.of();
        return List.of(
                page("forum.home", "论坛", "home", module),
                page("forum.post", "帖子详情", "post", module),
                page("forum.profile", "用户资料", "profile", module));
    }
    private YmclPageDescriptor page(String id, String title, String view, YmclModuleSupport.YmclModuleBundle module) {
        return new YmclPageDescriptor(id, "module", title, "i-ri:forum-line", providerCode()+"."+view, 70,
                ForumPlugin.VIEW_PERMISSION, Map.of("view", view), module.descriptor());
    }
    @Override public List<YmclDataSourceDescriptor> dataSources() {
        return List.of(new YmclDataSourceDescriptor("home", "论坛首页", 1, 30), new YmclDataSourceDescriptor("post", "帖子详情", 1, 15), new YmclDataSourceDescriptor("profile", "用户资料", 1, 60));
    }
    @Override public List<YmclBundleContribution> bundles() { YmclModuleSupport.YmclModuleBundle m=module(); return m==null?List.of():List.of(m.contribution()); }
    @Override public Object fetchData(String sourceCode, YmclDataContext context) {
        int page=Math.max(1,context.page()), size=Math.min(50,Math.max(1,context.pageSize()));
        String sort=context.param("sort"); if(sort==null) sort="latest";
        Map<String,Object> out=new LinkedHashMap<>(); out.put("schemaVersion",1);
        if("post".equals(sourceCode)) {
            String id=context.param("id"); if(id==null) return envelope(List.of(),0);
            try { out.putAll(envelope(List.of(view(service.publicPost(id, new online.yudream.base.plugin.spi.system.security.PluginPrincipal(context.userId(), context.permissions())))),1)); } catch(Exception ignored) { return envelope(List.of(),0); }
        } else if("profile".equals(sourceCode)) {
            String id=context.param("userId"); var p=id==null?null:service.profile(id); out.putAll(envelope(p==null?List.of():List.of(Map.of("id",String.valueOf(p.id()),"username",safe(p.username()),"nickname",safe(p.nickname()),"avatar",safe(p.avatar()))),p==null?0:1));
        } else {
            var result=service.pagePosts(new online.yudream.base.plugin.spi.system.security.PluginPrincipal(context.userId(), context.permissions()),sort,context.param("categoryId"),context.param("tag"),context.param("keyword"),page,size,false,"","");
            out.putAll(envelope(result.records().stream().map(this::view).toList(),result.total()));
            out.put("actions", List.of(action("refresh","刷新","client:reload",false,Map.of())));
            out.put("itemActions", List.of(action("like","点赞","server:forum:like",false,Map.of("postId","{{item.id}}")),action("bookmark","收藏","server:forum:bookmark",false,Map.of("postId","{{item.id}}"))));
        }
        return out;
    }
    @Override public Object executeAction(String actionCode, Map<String,Object> params, YmclDataContext context) {
        String id=params==null?null:String.valueOf(params.getOrDefault("postId","")); if(id==null||id.isBlank()||context.userId()==null) throw new IllegalArgumentException("缺少帖子或登录身份");
        if(actionCode.endsWith(":like")||"like".equals(actionCode)) return Map.of("toast",service.toggleInteraction(context.userId(),id,"like"),"refresh",true);
        if(actionCode.endsWith(":bookmark")||"bookmark".equals(actionCode)) return Map.of("toast",service.toggleInteraction(context.userId(),id,"bookmark"),"refresh",true);
        throw new UnsupportedOperationException("不支持的论坛动作");
    }
    private YmclModuleSupport.YmclModuleBundle module(){ return YmclModuleSupport.fromResource(ForumYmclContributionProvider.class,RESOURCE,"ymcl-forum","forum-page.js",List.of(YmclModuleSupport.PERMISSION_DATA_FETCH,YmclModuleSupport.PERMISSION_ACTION_EXECUTE,YmclModuleSupport.PERMISSION_OPEN_URL)); }
    private Map<String,Object> envelope(List<?> records,long total){ return Map.of("records",records,"total",total,"actions",List.of(),"itemActions",List.of()); }
    private Map<String,Object> view(ForumModels.Post p){
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id",p.id()); m.put("title",p.title()); m.put("summary",p.summary()); m.put("categoryId",p.categoryId());
        m.put("tags",p.tags()); m.put("authorId",p.authorId()); m.put("status",p.status().name().toLowerCase());
        m.put("pinned",p.pinned()); m.put("featured",p.featured()); m.put("publishedAt",Long.toString(p.publishedAt()));
        m.put("likes",p.likes()); m.put("comments",p.comments()); m.put("bookmarks",p.bookmarks());
        return m;
    }
    private static Map<String,Object> action(String code,String title,String kind,boolean primary,Map<String,Object> params){ return Map.of("code",code,"title",title,"kind",kind,"primary",primary,"params",params); }
    private static String safe(String s){return s==null?"":s;}
}
