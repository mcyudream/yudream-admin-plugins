package online.yudream.base.plugin.interop.bootstrap;

import java.util.List;

import online.yudream.base.plugin.interop.application.service.CatalogService;
import online.yudream.base.plugin.interop.infrastructure.repository.InteropRepository;
import online.yudream.base.plugin.interop.interfaces.controller.InteropAdminController;
import online.yudream.base.plugin.interop.interfaces.controller.InteropPublicController;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(
        code = McInteropPlugin.CODE,
        name = "MC 互操作目录",
        version = McInteropPlugin.VERSION,
        description = "mc-interop v1 统一出口：站点身份、管理员选择的实例目录与友站发现，供任意启动器消费。"
)
@PluginPermissions({
        @PluginPermission(code = McInteropPlugin.VIEW_PERMISSION, name = "查看互操作目录", module = "互操作目录",
                description = "查看出口配置、目录条目与友站声明"),
        @PluginPermission(code = McInteropPlugin.MANAGE_PERMISSION, name = "管理互操作目录", module = "互操作目录",
                description = "发布条目、维护站点身份与友站清单")
})
@PluginFrontend(
        moduleName = "mcInterop",
        menuTitle = "互操作目录",
        menuIcon = "i-ri:share-circle-line",
        menuSort = 40,
        styles = {"style.css"},
        routes = {
                @PluginRoute(path = "/platform/plugins/mc-interop/admin/catalog", name = "platform-plugin-mc-interop-admin-catalog",
                        title = "清单目录", icon = "i-ri:share-circle-line", component = "mc-interop/AdminCatalog",
                        permission = McInteropPlugin.VIEW_PERMISSION,
                        sort = 10),
                @PluginRoute(path = "/platform/plugins/mc-interop/admin/site", name = "platform-plugin-mc-interop-admin-site",
                        title = "站点与友站", icon = "i-ri:global-line", component = "mc-interop/AdminSite",
                        permission = McInteropPlugin.VIEW_PERMISSION,
                        sort = 20)
        }
)
public class McInteropPlugin implements YuDreamPlugin {

    public static final String CODE = "mc-interop";
    public static final String VERSION = "0.1.0";
    public static final String VIEW_PERMISSION = "plugin:mc-interop:view";
    public static final String MANAGE_PERMISSION = "plugin:mc-interop:manage";

    @Override
    public void onEnable(PluginContext context) {
        InteropRepository repository = new InteropRepository(context.documents());
        // union 在位时站点身份以 union 为单一事实源；缺失/未配置回退本插件设置
        java.util.function.Supplier<online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord> identity =
                context.dependencyAvailable(online.yudream.base.plugin.interop.infrastructure.support.UnionIdentityBridge.UNION_PLUGIN_CODE)
                        ? () -> online.yudream.base.plugin.interop.infrastructure.support.UnionIdentityBridge.identityOf(context)
                        : null;
        java.util.function.Supplier<List<online.yudream.base.plugin.interop.domain.aggregate.AdapterCatalogEntry>> adapterCatalog =
                context.dependencyAvailable(online.yudream.base.plugin.interop.infrastructure.support.YmclAdapterCatalogBridge.ADAPTER_PLUGIN_CODE)
                        ? () -> online.yudream.base.plugin.interop.infrastructure.support.YmclAdapterCatalogBridge.catalogOf(context)
                        : null;
        CatalogService catalog = new CatalogService(repository, identity, adapterCatalog);
        context.registerHttpController(new InteropPublicController(catalog));
        context.registerHttpController(new InteropAdminController(catalog, repository));
        // yda 域内贡献「友站服务器」页面（YmclContributionProvider；ymcl-adapter 缺失时跳过）
        if (context.dependencyAvailable("ymcl-adapter")) {
            context.registerExtension(online.yudream.base.plugin.ymcl.api.YmclContributionProvider.class,
                    new online.yudream.base.plugin.interop.infrastructure.launcher.InteropYmclContributionProvider(
                            catalog::siteDocument));
        }
    }
}
