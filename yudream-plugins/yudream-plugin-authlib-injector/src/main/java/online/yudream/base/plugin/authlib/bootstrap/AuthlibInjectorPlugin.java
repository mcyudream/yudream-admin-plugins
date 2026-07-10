package online.yudream.base.plugin.authlib.bootstrap;

import online.yudream.base.plugin.authlib.application.service.AuthlibAppService;
import online.yudream.base.plugin.authlib.infrastructure.repository.AuthlibRepository;
import online.yudream.base.plugin.authlib.infrastructure.service.AuthlibCryptoService;
import online.yudream.base.plugin.authlib.interfaces.http.AuthlibHttpFacade;
import online.yudream.base.plugin.authlib.interfaces.controller.AuthlibAdminController;
import online.yudream.base.plugin.authlib.interfaces.controller.AuthlibProtocolController;
import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(
        code = AuthlibInjectorPlugin.CODE,
        name = "Authlib Injector",
        version = "1.0.0",
        description = "基于系统用户和 yudream-skin 角色资料实现 authlib-injector/Yggdrasil 服务端协议。",
        dependencies = {"yudream-skin"}
)
@PluginPermissions({
        @PluginPermission(code = AuthlibInjectorPlugin.VIEW_PERMISSION, name = "查看 Authlib", module = "平台插件", description = "查看 Authlib Injector 插件状态"),
        @PluginPermission(code = AuthlibInjectorPlugin.MANAGE_PERMISSION, name = "管理 Authlib", module = "平台插件", description = "管理 Authlib Injector 配置")
})
@PluginDashboardCard(
        code = "api",
        title = "Authlib API",
        description = "提供 yudream-skin 角色资料的 Yggdrasil 验证服务地址。",
        icon = "i-ri:key-2-line",
        category = "认证服务",
        component = "authlib-injector/EndpointCard",
        actionPath = "/platform/plugins/authlib-injector/admin/status",
        dragPayloadTemplate = "authlib-injector:yggdrasil-server:{encodedUrl}",
        tone = "cyan",
        defaultW = 4,
        defaultH = 2,
        minW = 3,
        minH = 2,
        sort = 35
)
@PluginFrontend(
        moduleName = "authlibInjector",
        menuTitle = "Authlib",
        menuIcon = "i-ri:key-2-line",
        menuSort = 36,
        routes = @PluginRoute(
                path = "/platform/plugins/authlib-injector/admin/status",
                name = "platform-plugin-authlib-injector-admin-status",
                title = "Authlib 运行状态",
                icon = "i-ri:key-2-line",
                component = "authlib-injector/AdminStatus",
                permission = AuthlibInjectorPlugin.MANAGE_PERMISSION,
                sort = 10
        )
)
public class AuthlibInjectorPlugin implements YuDreamPlugin {

    public static final String CODE = "authlib-injector";
    public static final String VIEW_PERMISSION = "plugin:authlib:view";
    public static final String MANAGE_PERMISSION = "plugin:authlib:manage";

    @Override
    public void onEnable(PluginContext context) {
        AuthlibRepository repository = new AuthlibRepository(context.documents());
        AuthlibAppService appService = new AuthlibAppService(context, repository, new AuthlibCryptoService(repository));
        AuthlibHttpFacade http = new AuthlibHttpFacade(appService, context.framework());
        context.registerHttpController(new AuthlibProtocolController(http));
        context.registerHttpController(new AuthlibAdminController(http));
    }
}
