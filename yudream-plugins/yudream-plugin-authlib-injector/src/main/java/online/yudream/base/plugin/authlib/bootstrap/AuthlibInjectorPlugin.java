package online.yudream.base.plugin.authlib.bootstrap;

import online.yudream.base.plugin.authlib.api.PluginAuthService;
import online.yudream.base.plugin.authlib.api.PluginAuthlibInfo;
import online.yudream.base.plugin.authlib.application.service.AuthlibAppService;
import online.yudream.base.plugin.authlib.application.service.AuthlibInfoService;
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
        version = "1.4.0",
        description = "基于系统用户和 yudream-skin 角色资料实现 authlib-injector/Yggdrasil 服务端协议，并接入 YD 联合认证。",
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
        styles = {"style.css"},
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
        AuthlibInfoService infoService = new AuthlibInfoService(context.framework());
        AuthlibHttpFacade http = new AuthlibHttpFacade(appService, infoService);
        context.registerHttpController(new AuthlibProtocolController(http));
        context.registerHttpController(new AuthlibAdminController(http));
        // 供 launcher-adapter 等 trusted caller 调用：按站点 userId 签发 ygg session（免密码）。
        context.exposeService(PluginAuthService.class, appService);
        // 供 mcpanel 等消费方读取本站验证服 API 根（免手填）。
        context.exposeService(PluginAuthlibInfo.class, infoService);
        // YD 联合认证接入（softdepend，全链路可选降级为纯本站）：
        // 网关每次查询才取 union service，故 union 晚于 authlib 启用也可正常接入。
        if (context.dependencyAvailable("yudream-union")) {
            try {
                online.yudream.base.plugin.authlib.api.PluginFederationGateway gateway =
                        new online.yudream.base.plugin.authlib.infrastructure.launcher.UnionGatewayBridge(context);
                appService.bindFederationGateway(gateway);
                // union 先于本插件启用（authlib plugin.yml softdepend），本扩展随后进入其
                // live extension 列表，供 /peer/joins/query 按需读取本地 join 证据。
                context.registerExtension(online.yudream.base.plugin.union.api.UnionJoinEvidenceProvider.class,
                        new online.yudream.base.plugin.authlib.infrastructure.launcher.UnionEvidenceBridge(
                                repository, appService, infoService));
            } catch (RuntimeException | LinkageError ignored) {
                // union 契约不可用：保持纯本站行为
            }
        }
    }
}
