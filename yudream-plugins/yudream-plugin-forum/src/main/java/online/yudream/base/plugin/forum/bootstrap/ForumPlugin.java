package online.yudream.base.plugin.forum.bootstrap;

import online.yudream.base.plugin.forum.application.ForumAppService;
import online.yudream.base.plugin.forum.infrastructure.ForumRepository;
import online.yudream.base.plugin.forum.interfaces.ForumAdminController;
import online.yudream.base.plugin.forum.interfaces.ForumMeController;
import online.yudream.base.plugin.forum.interfaces.ForumPublicController;
import online.yudream.base.plugin.forum.infrastructure.launcher.ForumYmclContributionProvider;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.permission.PluginPermissionItem;

@PluginSpec(code = ForumPlugin.CODE, name = "论坛", version = ForumPlugin.VERSION,
        description = "分类、标签、审核、互动与 YMCL 启动器浏览的社区论坛")
@PluginPermissions({
        @PluginPermission(code = ForumPlugin.VIEW_PERMISSION, name = "查看论坛", module = "论坛", description = "浏览有权限的论坛分类和公开帖子"),
        @PluginPermission(code = ForumPlugin.USE_PERMISSION, name = "使用论坛", module = "论坛", description = "发帖、评论、点赞和收藏"),
        @PluginPermission(code = ForumPlugin.MANAGE_PERMISSION, name = "管理论坛", module = "论坛", description = "管理分类、审核帖子和维护论坛设置")
})
@PluginFrontend(moduleName = "forum", menuTitle = "论坛", menuIcon = "i-ri:forum-line", menuSort = 78, styles = {"style.css"}, routes = {
        @PluginRoute(path = "/platform/plugins/forum", name = "platform-plugin-forum-home", title = "论坛", icon = "i-ri:forum-line", component = "forum/Home", permission = ForumPlugin.VIEW_PERMISSION, sort = 10),
        @PluginRoute(path = "/platform/plugins/forum/post", name = "platform-plugin-forum-post", title = "帖子详情", component = "forum/PostDetail", permission = ForumPlugin.VIEW_PERMISSION, hideInMenu = true),
        @PluginRoute(path = "/platform/plugins/forum/post/edit", name = "platform-plugin-forum-post-edit", title = "发布帖子", component = "forum/PostEdit", permission = ForumPlugin.USE_PERMISSION, hideInMenu = true),
        @PluginRoute(path = "/platform/plugins/forum/profile", name = "platform-plugin-forum-profile", title = "用户资料", component = "forum/Profile", permission = ForumPlugin.VIEW_PERMISSION, hideInMenu = true),
        @PluginRoute(path = "/platform/plugins/forum/my-posts", name = "platform-plugin-forum-my-posts", title = "我的帖子", icon = "i-ri:draft-line", component = "forum/MyPosts", permission = ForumPlugin.USE_PERMISSION, sort = 20),
        @PluginRoute(path = "/platform/plugins/forum/bookmarks", name = "platform-plugin-forum-bookmarks", title = "我的收藏", icon = "i-ri:bookmark-line", component = "forum/Bookmarks", permission = ForumPlugin.USE_PERMISSION, sort = 30),
        @PluginRoute(path = "/platform/plugins/forum/admin/posts", name = "platform-plugin-forum-admin-posts", title = "帖子审核", icon = "i-ri:shield-check-line", component = "forum/AdminPosts", permission = ForumPlugin.MANAGE_PERMISSION, sort = 90),
        @PluginRoute(path = "/platform/plugins/forum/admin/categories", name = "platform-plugin-forum-admin-categories", title = "分类管理", icon = "i-ri:folder-settings-line", component = "forum/AdminCategories", permission = ForumPlugin.MANAGE_PERMISSION, sort = 91),
        @PluginRoute(path = "/platform/plugins/forum/admin/settings", name = "platform-plugin-forum-admin-settings", title = "论坛设置", icon = "i-ri:settings-3-line", component = "forum/AdminSettings", permission = ForumPlugin.MANAGE_PERMISSION, sort = 92),
        @PluginRoute(path = "/platform/plugins/forum/admin/audit", name = "platform-plugin-forum-admin-audit", title = "操作审计", icon = "i-ri:history-line", component = "forum/AdminAudit", permission = ForumPlugin.MANAGE_PERMISSION, sort = 93)
})
public final class ForumPlugin implements YuDreamPlugin {
    public static final String CODE = "forum";
    public static final String VERSION = "1.0.2";
    public static final String VIEW_PERMISSION = "plugin:forum:view";
    public static final String USE_PERMISSION = "plugin:forum:use";
    public static final String MANAGE_PERMISSION = "plugin:forum:manage";
    public static String categoryViewPermission(String id) { return "plugin:" + CODE + ":category-" + id + "-view"; }
    public static String categoryPostPermission(String id) { return "plugin:" + CODE + ":category-" + id + "-post"; }

    @Override
    public void onEnable(PluginContext context) {
        ForumRepository repository = new ForumRepository(context.documents());
        ForumAppService service = new ForumAppService(repository, context.framework(), context);
        context.registerHttpController(new ForumPublicController(service));
        context.registerHttpController(new ForumMeController(service));
        context.registerHttpController(new ForumAdminController(service));
        for (var category : service.adminCategories()) {
            context.registerPermission(new PluginPermissionItem(category.viewPermission(), "查看分类：" + category.name(), "论坛分类", "查看该分类帖子"));
            context.registerPermission(new PluginPermissionItem(category.postPermission(), "在分类发帖：" + category.name(), "论坛分类", "在该分类发布帖子"));
        }
        try {
            context.registerExtension(online.yudream.base.plugin.ymcl.api.YmclContributionProvider.class,
                    new ForumYmclContributionProvider(service));
        } catch (LinkageError ignored) {
            // ymcl-adapter 未安装时论坛后台仍可独立运行。
        }
    }
}
