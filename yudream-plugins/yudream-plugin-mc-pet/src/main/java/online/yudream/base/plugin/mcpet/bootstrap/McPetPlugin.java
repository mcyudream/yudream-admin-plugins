package online.yudream.base.plugin.mcpet.bootstrap;

import online.yudream.base.plugin.mcpet.application.service.McPetAppService;
import online.yudream.base.plugin.mcpet.infrastructure.repository.McPetRepository;
import online.yudream.base.plugin.mcpet.infrastructure.skin.YudreamSkinPetSkinPort;
import online.yudream.base.plugin.mcpet.interfaces.controller.McPetAdminController;
import online.yudream.base.plugin.mcpet.interfaces.controller.McPetUserController;
import online.yudream.base.plugin.mcpet.interfaces.http.McPetHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginGlobalWidget;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(
        code = McPetPlugin.CODE,
        name = "mc-pet",
        version = "1.0.0",
        description = "把 Minecraft 皮肤渲染为贯穿全局的网页宠物，支持管理员默认风格与用户衣柜选肤。"
)
@PluginPermissions({
        @PluginPermission(code = McPetPlugin.USER_PERMISSION, name = "使用网页宠物", module = "平台插件", description = "查看并设置自己的网页宠物"),
        @PluginPermission(code = McPetPlugin.MANAGE_PERMISSION, name = "管理网页宠物", module = "平台插件", description = "配置全局默认皮肤与交互风格，查看和重置用户宠物偏好")
})
@PluginGlobalWidget(
        code = "mc-pet-pet",
        component = "mc-pet/GlobalPet",
        permission = McPetPlugin.USER_PERMISSION,
        sort = 500
)
@PluginFrontend(
        moduleName = "mcPet",
        menuTitle = "网页宠物",
        menuIcon = "i-ri:ghost-smile-line",
        menuSort = 32,
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/mc-pet",
                        name = "platform-plugin-mc-pet",
                        title = "我的宠物",
                        icon = "i-ri:ghost-smile-line",
                        component = "mc-pet/Home",
                        permission = McPetPlugin.USER_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/mc-pet/system/defaults",
                        name = "platform-plugin-mc-pet-defaults",
                        title = "默认设置",
                        icon = "i-ri:settings-3-line",
                        parentPath = "/platform/plugins/mc-pet/system",
                        parentTitle = "宠物管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 10,
                        component = "mc-pet/AdminDefaults",
                        permission = McPetPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/mc-pet/system/pets",
                        name = "platform-plugin-mc-pet-pets",
                        title = "用户宠物",
                        icon = "i-ri:group-line",
                        parentPath = "/platform/plugins/mc-pet/system",
                        parentTitle = "宠物管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 10,
                        component = "mc-pet/AdminPets",
                        permission = McPetPlugin.MANAGE_PERMISSION,
                        sort = 10
                )
        }
)
public class McPetPlugin implements YuDreamPlugin {

    public static final String CODE = "mc-pet";
    public static final String USER_PERMISSION = "plugin:mc-pet:user";
    public static final String MANAGE_PERMISSION = "plugin:mc-pet:manage";

    @Override
    public void onEnable(PluginContext context) {
        McPetAppService appService = new McPetAppService(
                new McPetRepository(context.documents()),
                YudreamSkinPetSkinPort.create(context));
        McPetHttpFacade http = new McPetHttpFacade(appService);
        context.registerHttpController(new McPetUserController(http));
        context.registerHttpController(new McPetAdminController(http));
    }
}
