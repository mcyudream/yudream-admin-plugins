package online.yudream.base.plugin.apprelease.bootstrap;

import online.yudream.base.plugin.apprelease.application.ReleaseService;
import online.yudream.base.plugin.apprelease.infrastructure.FileReleaseRepository;
import online.yudream.base.plugin.apprelease.interfaces.AppReleaseAdminController;
import online.yudream.base.plugin.apprelease.interfaces.AppReleaseHttpFacade;
import online.yudream.base.plugin.apprelease.interfaces.AppReleasePublicController;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

/**
 * YDAM 更新发布：更新包上传/发布/下架/删除、更新日志与强制更新管理。
 * 公开端点供 YDAM 移动 App 启动检查更新、拉取更新日志与下载更新包。
 */
@PluginSpec(
        code = AppReleasePlugin.CODE,
        name = "YDAM 更新发布",
        version = AppReleasePlugin.VERSION,
        description = "YDAM 移动应用更新包发布与更新日志：上传更新包、发布版本、更新公告与强制更新管理"
)
@PluginPermissions({
        @PluginPermission(code = AppReleasePlugin.MANAGE_PERMISSION, name = "管理 YDAM 更新发布", module = "平台插件",
                description = "上传更新包、发布/下架版本、删除与强制更新设置")
})
@PluginFrontend(
        moduleName = "app-release",
        menuTitle = "更新发布",
        menuIcon = "i-ri:rocket-2-line",
        menuSort = 78,
        styles = {"style.css"},
        routes = {
                @PluginRoute(path = "/platform/plugins/app-release/releases", name = "app-release-releases",
                        title = "更新发布", icon = "i-ri:rocket-2-line", component = "app-release/Releases",
                        permission = AppReleasePlugin.MANAGE_PERMISSION, sort = 10)
        }
)
public class AppReleasePlugin implements YuDreamPlugin {

    public static final String CODE = "app-release";
    public static final String VERSION = "1.0.1";
    public static final String MANAGE_PERMISSION = "plugin:app-release:manage";

    @Override
    public void onEnable(PluginContext context) {
        FileReleaseRepository repository = new FileReleaseRepository(context.files());
        ReleaseService service = new ReleaseService(repository);
        AppReleaseHttpFacade facade = new AppReleaseHttpFacade(service);
        context.registerHttpController(new AppReleaseAdminController(facade));
        context.registerHttpController(new AppReleasePublicController(facade));
    }
}
