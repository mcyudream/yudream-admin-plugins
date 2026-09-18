package online.yudream.base.plugin.ymclcontent.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.ymclcontent.application.service.UpdatePlatformService;
import online.yudream.base.plugin.ymclcontent.interfaces.controller.UpdateAdminController;
import online.yudream.base.plugin.ymclcontent.interfaces.controller.UpdatePublicController;

import java.util.Map;

/**
 * YMCL 启动器自有更新平台插件。
 *
 * 更新：/api/plugins/ymcl-content/v1/update/**
 * 独立于 ymcl-adapter / YAP，也独立于 Axolotl 的 update.axlmc.org。
 */
@PluginSpec(
        code = YmclContentPlugin.CODE,
        name = "ymcl-content",
        version = YmclContentPlugin.VERSION,
        description = "YMCL 启动器自有更新平台：发包/清单/下载（独立公开端点）。"
)
@PluginPermissions({
        @PluginPermission(
                code = YmclContentPlugin.MANAGE_PERMISSION,
                name = "管理 YMCL 更新发包",
                module = "平台插件",
                description = "维护启动器版本发布、制品与撤回"
        )
})
@PluginFrontend(
        moduleName = "ymclContent",
        menuTitle = "YMCL 更新发包",
        menuIcon = "i-ri:rocket-2-line",
        menuSort = 39,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/ymcl-content/releases",
                        name = "platform-plugin-ymcl-content-releases",
                        title = "更新发包",
                        icon = "i-ri:rocket-2-line",
                        component = "ymcl-content/Releases",
                        permission = YmclContentPlugin.MANAGE_PERMISSION,
                        sort = 10
                )
        }
)
public class YmclContentPlugin implements YuDreamPlugin {

    public static final String CODE = "ymcl-content";
    public static final String VERSION = "2.1.0";

    public static final String MANAGE_PERMISSION = "plugin:ymcl-content:manage";

    /** 1.x 内容目录（指引/公告/在线内容）已移除，启用时清理其遗留文档。 */
    private static final String LEGACY_CATALOG_COLLECTION = "ymcl_content_catalog";
    private static final String LEGACY_CATALOG_DOC_ID = "catalog";

    @Override
    public void onEnable(PluginContext context) {
        removeLegacyCatalog(context);
        UpdatePlatformService updates = new UpdatePlatformService(context.documents(), context.files());
        context.registerHttpController(new UpdatePublicController(updates));
        context.registerHttpController(new UpdateAdminController(updates));
    }

    private static void removeLegacyCatalog(PluginContext context) {
        Map<String, Object> legacy = context.documents()
                .findById(LEGACY_CATALOG_COLLECTION, LEGACY_CATALOG_DOC_ID)
                .orElse(null);
        if (legacy != null) {
            context.documents().delete(LEGACY_CATALOG_COLLECTION, LEGACY_CATALOG_DOC_ID);
        }
    }
}
