package online.yudream.base.plugin.cas.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginProvider;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import online.yudream.base.plugin.spi.widget.PluginGlobalWidget;
import online.yudream.base.plugin.cas.application.service.AccessControlService;
import online.yudream.base.plugin.cas.application.service.BindingQueryService;
import online.yudream.base.plugin.cas.application.service.SettingsService;
import online.yudream.base.plugin.cas.application.service.StudentInfoService;
import online.yudream.base.plugin.cas.application.service.CasSsoLoginProvider;
import online.yudream.base.plugin.cas.infrastructure.archive.StudentArchiveQueryFactory;
import online.yudream.base.plugin.cas.infrastructure.bridge.BridgeProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.cas.CasProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.oidc.OidcProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.repository.AccessControlDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.repository.SsoSettingsDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.repository.StudentProfileDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.secret.ClientSecretStore;
import online.yudream.base.plugin.cas.interfaces.controller.CasSsoAdminController;
import online.yudream.base.plugin.cas.interfaces.http.CasSsoHttpFacade;

@PluginSpec(
        code = CasSsoPlugin.CODE,
        name = "统一身份认证",
        version = CasSsoPlugin.VERSION,
        description = "接入 Apereo CAS / OIDC / 学院 SSO 中转统一身份认证，作为站点第三方登录提供方；支持绑定情况查看与未绑定访问门禁",
        icon = "i-ri:graduation-cap-line"
)
@PluginPermissions({
        @PluginPermission(
                code = CasSsoPlugin.MANAGE_PERMISSION,
                name = "管理统一身份认证",
                module = "统一身份认证",
                description = "维护 CAS/OIDC/SSO 中转协议、回调地址、客户端凭据与绑定访问控制"
        )
})
@PluginFrontend(
        moduleName = "cas",
        menuTitle = "统一身份认证",
        menuIcon = "i-ri:graduation-cap-line",
        menuSort = 48,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/cas/settings",
                        name = "platform-plugin-cas-settings",
                        title = "认证设置",
                        icon = "i-ri:settings-3-line",
                        component = "cas/Settings",
                        permission = CasSsoPlugin.MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/cas/students",
                        name = "platform-plugin-cas-students",
                        title = "学生信息",
                        icon = "i-ri:group-line",
                        component = "cas/Students",
                        permission = CasSsoPlugin.MANAGE_PERMISSION,
                        sort = 20
                )
        }
)
public final class CasSsoPlugin implements YuDreamPlugin {

    public static final String CODE = "cas";
    public static final String VERSION = "2.3.0";
    public static final String MANAGE_PERMISSION = "plugin:cas:manage";
    /** 学生档案插件 code：学院 / 班级等信息的唯一来源（软依赖，只读）。 */
    public static final String STUDENT_INFO_CODE = "yudream-student-info";

    @Override
    public void onEnable(PluginContext context) {
        ClientSecretStore secrets = new ClientSecretStore(context.secrets());
        SsoSettingsDocumentRepository repository = new SsoSettingsDocumentRepository(context.documents());
        SettingsService settings = new SettingsService(
                repository,
                secrets,
                new CasProtocolClient(),
                new OidcProtocolClient(),
                new BridgeProtocolClient()
        );
        StudentInfoService studentInfo = new StudentInfoService(
                new StudentProfileDocumentRepository(context.documents()),
                new BindingQueryService(pluginUserService(context), CODE),
                StudentArchiveQueryFactory.create(context, STUDENT_INFO_CODE)
        );
        AccessControlService accessControl = new AccessControlService(
                new AccessControlDocumentRepository(context.documents())
        );
        CasSsoHttpFacade http = new CasSsoHttpFacade(settings, studentInfo, accessControl);
        context.registerHttpController(new CasSsoAdminController(http));
        context.registerExtension(PluginExternalLoginProvider.class, new CasSsoLoginProvider(settings, studentInfo));
        // 全站绑定门禁挂件：未绑定 CAS 的用户在开启开关后被引导完成绑定（组件 key = cas/Gate）
        context.registerGlobalWidget(new PluginGlobalWidget("cas-binding-gate", "cas/Gate", "", 900));
    }

    /**
     * 宿主用户服务：只用于「学生信息 → 绑定账号」查询（SPI 2.29.0 的 findByExternalIdentity）。
     * 宿主未注册或沙箱拒绝时返回 null，绑定信息降级为「无法查询」，不影响插件其他功能。
     */
    private static PluginUserService pluginUserService(PluginContext context) {
        try {
            return context.framework().users();
        } catch (RuntimeException e) {
            System.err.println("[cas] 宿主用户服务不可用，学生信息页将不显示绑定情况: " + e.getMessage());
            return null;
        }
    }
}
