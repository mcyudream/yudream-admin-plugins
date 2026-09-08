package online.yudream.base.plugin.activityproof.bootstrap;

import online.yudream.base.plugin.activityproof.application.service.ActivityProofAppService;
import online.yudream.base.plugin.activityproof.application.service.ActivityQuizService;
import online.yudream.base.plugin.activityproof.infrastructure.repository.ActivityProofDocumentRepository;
import online.yudream.base.plugin.activityproof.infrastructure.support.SoftDependencyServices;
import online.yudream.base.plugin.activityproof.interfaces.controller.ActivityProofAdminController;
import online.yudream.base.plugin.activityproof.interfaces.controller.ActivityProofUserController;
import online.yudream.base.plugin.activityproof.interfaces.http.ActivityProofHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginCommand;
import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.command.PluginCommandContext;

@PluginSpec(
        code = MinecraftActivityProofPlugin.CODE,
        name = "minecraft-activity-proof",
        version = "2.2.7",
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
        styles = {"style.css"},
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

    private ActivityProofAppService appService;

    @Override
    public void onEnable(PluginContext context) {
        ActivityProofDocumentRepository repository = new ActivityProofDocumentRepository(context.documents());
        // 题库为软依赖：经桥接类先做无类可用性检查再解析 API 类型，provider 缺失时降级而非 NoClassDefFoundError
        ActivityQuizService quizService = new ActivityQuizService(repository,
                () -> SoftDependencyServices.questionBank(context));
        appService = new ActivityProofAppService(
                repository,
                context.files(),
                context.framework(),
                context,
                quizService
        );
        ActivityProofHttpFacade http = new ActivityProofHttpFacade(appService, quizService);
        context.registerHttpController(new ActivityProofUserController(http));
        context.registerHttpController(new ActivityProofAdminController(http));
    }

    /** QQ 群活动报名：/报名 {活动ID}；官方连接的活动通知按钮点击后即以该指令发出。 */
    @PluginCommand(code = "activity-proof.signup", command = "报名", name = "活动报名",
            description = "报名参加活动：/报名 活动ID，或点击活动通知下方的报名按钮",
            permission = MinecraftActivityProofPlugin.VIEW_PERMISSION)
    public void signup(PluginCommandContext command, PluginContext ignored) {
        if (appService != null) {
            appService.signupFromQq(command.event(), command.arguments(), command.userId());
        }
    }

    /** QQ 群活动列表：/活动列表，展示未结束的已发布活动（最多 5 条），官方连接附一键报名按钮。 */
    @PluginCommand(code = "activity-proof.list", command = "活动列表", name = "活动列表",
            description = "查看未结束的活动列表（最多 5 条），官方 QQ 连接可点击按钮一键报名",
            permission = MinecraftActivityProofPlugin.VIEW_PERMISSION)
    public void activityList(PluginCommandContext command, PluginContext ignored) {
        if (appService != null) {
            appService.listActivitiesFromQq(command.event());
        }
    }
}
