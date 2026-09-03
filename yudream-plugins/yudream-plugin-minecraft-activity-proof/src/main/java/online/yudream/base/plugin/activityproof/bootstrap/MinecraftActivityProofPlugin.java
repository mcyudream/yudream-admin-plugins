package online.yudream.base.plugin.activityproof.bootstrap;

import online.yudream.base.plugin.activityproof.application.service.ActivityProofAppService;
import online.yudream.base.plugin.activityproof.infrastructure.repository.ActivityProofDocumentRepository;
import online.yudream.base.plugin.activityproof.interfaces.controller.ActivityProofAdminController;
import online.yudream.base.plugin.activityproof.interfaces.controller.ActivityProofUserController;
import online.yudream.base.plugin.activityproof.interfaces.http.ActivityProofHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(
        code = MinecraftActivityProofPlugin.CODE,
        name = "minecraft-activity-proof",
        version = "2.1.1",
        description = "活动发布与参与管理平台：活动广场、部门限制、时长/表单核验、活动证明导出。",
        dependencies = { "yudream-student-info" }
)
@PluginPermissions({
        @PluginPermission(code = MinecraftActivityProofPlugin.VIEW_PERMISSION, name = "查看 MC 活动", module = "平台插件", description = "浏览活动广场、参与活动并查看我的活动证明"),
        @PluginPermission(code = MinecraftActivityProofPlugin.MANAGE_PERMISSION, name = "管理 MC 活动", module = "平台插件", description = "发布活动、维护映射、核验参与并导出活动证明")
})
@PluginDashboardCard(
        code = "activity-overview",
        title = "活动中心",
        description = "展示最新活动与我的参与、核验状态。",
        icon = "i-ri:calendar-event-line",
        category = "活动",
        permission = MinecraftActivityProofPlugin.VIEW_PERMISSION,
        component = "minecraft-activity-proof/DashboardActivityCard",
        actionPath = "/platform/plugins/yudream-student-info/activity-square",
        tone = "blue",
        defaultW = 4,
        defaultH = 3,
        minW = 3,
        minH = 2,
        sort = 35
)
@PluginFrontend(
        moduleName = "minecraftActivityProof",
        menuTitle = "学生信息",
        menuIcon = "i-ri:id-card-line",
        menuSort = 35,
        parentCode = "plugin:yudream-student-info:module:yudreamStudentInfo",
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-square",
                        name = "platform-plugin-yudream-student-info-activity-square",
                        title = "活动广场",
                        icon = "i-ri:layout-masonry-line",
                        component = "minecraft-activity-proof/Square",
                        permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION,
                        sort = 15
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-square/detail",
                        name = "platform-plugin-yudream-student-info-activity-square-detail",
                        title = "活动详情",
                        icon = "i-ri:file-text-line",
                        component = "minecraft-activity-proof/ActivityDetail",
                        permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION,
                        sort = 15,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/my-activities",
                        name = "platform-plugin-yudream-student-info-my-activities",
                        title = "我的参与",
                        icon = "i-ri:flag-line",
                        component = "minecraft-activity-proof/MyActivities",
                        permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION,
                        sort = 16
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/my-activity-proofs",
                        name = "platform-plugin-yudream-student-info-my-activity-proofs",
                        title = "我的活动证明",
                        icon = "i-ri:verified-badge-line",
                        component = "minecraft-activity-proof/Mine",
                        permission = MinecraftActivityProofPlugin.ACCESS_USER_PERMISSION,
                        sort = 17
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-proof/activities",
                        name = "platform-plugin-yudream-student-info-activity-proof-activities",
                        title = "活动管理",
                        icon = "i-ri:calendar-event-line",
                        component = "minecraft-activity-proof/Activities",
                        permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-proof/activities/edit",
                        name = "platform-plugin-yudream-student-info-activity-proof-activities-edit",
                        title = "活动编辑",
                        icon = "i-ri:edit-box-line",
                        component = "minecraft-activity-proof/ActivityEdit",
                        permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION,
                        sort = 10,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-proof/activities/detail",
                        name = "platform-plugin-yudream-student-info-activity-proof-activities-detail",
                        title = "活动详情与核验",
                        icon = "i-ri:checkbox-multiple-line",
                        component = "minecraft-activity-proof/ActivityAdminDetail",
                        permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION,
                        sort = 10,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-proof/records",
                        name = "platform-plugin-yudream-student-info-activity-proof-records",
                        title = "活动证明记录",
                        icon = "i-ri:file-list-3-line",
                        component = "minecraft-activity-proof/Records",
                        permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION,
                        sort = 11
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-proof/mappings",
                        name = "platform-plugin-yudream-student-info-activity-proof-mappings",
                        title = "玩家学号映射",
                        icon = "i-ri:link-m",
                        component = "minecraft-activity-proof/Mappings",
                        permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION,
                        sort = 12
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-student-info/activity-proof/settings",
                        name = "platform-plugin-yudream-student-info-activity-proof-settings",
                        title = "活动证明配置",
                        icon = "i-ri:settings-3-line",
                        component = "minecraft-activity-proof/Settings",
                        permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION,
                        sort = 13
                )
        }
)
public class MinecraftActivityProofPlugin implements YuDreamPlugin {

    public static final String CODE = "minecraft-activity-proof";
    public static final String VIEW_PERMISSION = "plugin:minecraft-activity-proof:view";
    public static final String MANAGE_PERMISSION = "plugin:minecraft-activity-proof:manage";
    public static final String ACCESS_VIEW_PERMISSION = VIEW_PERMISSION;
    public static final String ACCESS_USER_PERMISSION = VIEW_PERMISSION;
    public static final String ACCESS_MANAGE_PERMISSION = MANAGE_PERMISSION;

    @Override
    public void onEnable(PluginContext context) {
        ActivityProofAppService appService = new ActivityProofAppService(
                new ActivityProofDocumentRepository(context.documents()),
                context.files(),
                context.framework(),
                context
        );
        ActivityProofHttpFacade http = new ActivityProofHttpFacade(appService);
        context.registerHttpController(new ActivityProofUserController(http));
        context.registerHttpController(new ActivityProofAdminController(http));
    }
}
