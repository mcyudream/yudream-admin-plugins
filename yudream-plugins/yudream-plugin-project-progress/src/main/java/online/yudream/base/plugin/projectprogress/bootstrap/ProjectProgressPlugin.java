package online.yudream.base.plugin.projectprogress.bootstrap;

import online.yudream.base.plugin.projectprogress.application.service.ProjectProgressAppService;
import online.yudream.base.plugin.projectprogress.infrastructure.repository.ProjectProgressDocumentRepository;
import online.yudream.base.plugin.projectprogress.interfaces.controller.ProjectProgressController;
import online.yudream.base.plugin.projectprogress.interfaces.http.ProjectProgressHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginCommand;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.command.PluginCommandContext;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;

import java.util.Map;

@PluginSpec(
        code = ProjectProgressPlugin.CODE,
        name = "project-progress",
        version = "1.0.0",
        description = "项目管理、进度监控、任务分配、打卡与验收插件，可选联动 Minecraft 在线时长。"
)
@PluginPermissions({
        @PluginPermission(code = ProjectProgressPlugin.VIEW_PERMISSION, name = "查看项目进度", module = "平台插件", description = "查看项目、工作细节、打卡和进度事件"),
        @PluginPermission(code = ProjectProgressPlugin.MANAGE_PERMISSION, name = "管理项目进度", module = "平台插件", description = "创建和维护项目、状态、成员和工作细节"),
        @PluginPermission(code = ProjectProgressPlugin.ASSIGN_PERMISSION, name = "分配项目任务", module = "平台插件", description = "发布工作细节、随机分配或调整负责人"),
        @PluginPermission(code = ProjectProgressPlugin.CHECK_IN_PERMISSION, name = "项目任务打卡", module = "平台插件", description = "认领任务并提交图片、文件、定位或 Minecraft 在线时长打卡"),
        @PluginPermission(code = ProjectProgressPlugin.ACCEPT_PERMISSION, name = "验收项目任务", module = "平台插件", description = "验收工作细节并退回返工")
})
@PluginFrontend(
        moduleName = "projectProgress",
        menuTitle = "项目进度",
        menuIcon = "i-ri:progress-5-line",
        menuSort = 50,
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/project-progress/dashboard",
                        name = "platform-plugin-project-progress-dashboard",
                        title = "项目看板",
                        icon = "i-ri:dashboard-3-line",
                        component = "project-progress/Dashboard",
                        permission = ProjectProgressPlugin.VIEW_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/admin/projects",
                        name = "platform-plugin-project-progress-projects",
                        title = "项目管理",
                        icon = "i-ri:folder-settings-line",
                        component = "project-progress/Projects",
                        permission = ProjectProgressPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/admin/details",
                        name = "platform-plugin-project-progress-details",
                        title = "工作细节",
                        icon = "i-ri:list-check-3",
                        component = "project-progress/Details",
                        permission = ProjectProgressPlugin.MANAGE_PERMISSION,
                        sort = 22
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/me/task-center",
                        name = "platform-plugin-project-progress-task-center",
                        title = "任务中心",
                        icon = "i-ri:todo-line",
                        component = "project-progress/TaskCenter",
                        permission = ProjectProgressPlugin.CHECK_IN_PERMISSION,
                        sort = 25
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/me/tasks",
                        name = "platform-plugin-project-progress-my-tasks",
                        title = "我的任务",
                        icon = "i-ri:checkbox-circle-line",
                        component = "project-progress/MyTasks",
                        permission = ProjectProgressPlugin.CHECK_IN_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/me/check-ins",
                        name = "platform-plugin-project-progress-check-ins",
                        title = "打卡记录",
                        icon = "i-ri:map-pin-time-line",
                        component = "project-progress/CheckIns",
                        permission = ProjectProgressPlugin.CHECK_IN_PERMISSION,
                        sort = 40
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/admin/check-in-statistics",
                        name = "platform-plugin-project-progress-check-in-statistics",
                        title = "打卡统计",
                        icon = "i-ri:bar-chart-box-line",
                        component = "project-progress/CheckInStatistics",
                        permission = ProjectProgressPlugin.MANAGE_PERMISSION,
                        sort = 45
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/acceptance",
                        name = "platform-plugin-project-progress-acceptance",
                        title = "任务验收",
                        icon = "i-ri:shield-check-line",
                        component = "project-progress/Acceptance",
                        permission = ProjectProgressPlugin.ACCEPT_PERMISSION,
                        sort = 50
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/admin/members",
                        name = "platform-plugin-project-progress-members",
                        title = "成员统计",
                        icon = "i-ri:team-line",
                        component = "project-progress/Members",
                        permission = ProjectProgressPlugin.MANAGE_PERMISSION,
                        sort = 55
                ),
                @PluginRoute(
                        path = "/platform/plugins/project-progress/admin/settings",
                        name = "platform-plugin-project-progress-settings",
                        title = "插件设置",
                        icon = "i-ri:settings-3-line",
                        component = "project-progress/Settings",
                        permission = ProjectProgressPlugin.MANAGE_PERMISSION,
                        sort = 60
                )
        }
)
public class ProjectProgressPlugin implements YuDreamPlugin {

    public static final String CODE = "project-progress";
    public static final String VIEW_PERMISSION = "plugin:project-progress:view";
    public static final String MANAGE_PERMISSION = "plugin:project-progress:manage";
    public static final String ASSIGN_PERMISSION = "plugin:project-progress:assign";
    public static final String CHECK_IN_PERMISSION = "plugin:project-progress:check-in";
    public static final String ACCEPT_PERMISSION = "plugin:project-progress:accept";
    private volatile ProjectProgressAppService appService;

    @Override
    public void onEnable(PluginContext context) {
        appService = new ProjectProgressAppService(
                new ProjectProgressDocumentRepository(context.documents()),
                context.files(),
                context.framework()
        );
        context.registerHttpController(new ProjectProgressController(new ProjectProgressHttpFacade(appService)));
    }

    @PluginCommand(code = "project-progress.my-tasks", command = "我的任务", name = "查询我的任务", description = "查看当前绑定账号的项目任务")
    public void myTasks(PluginCommandContext command, PluginContext context) {
        if (!requireUser(command, context)) return;
        var tasks = appService.myTasks(String.valueOf(command.userId()), 1, 10);
        reply(command, context, tasks.isEmpty() ? "当前没有分配给你的任务。" : "我的任务：\n" + tasks.stream()
                .map(task -> "- " + task.title() + "（" + task.statusCode() + "，ID: " + task.id() + "）").reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    @PluginCommand(code = "project-progress.claimable-tasks", command = "可认领任务", name = "查询可认领任务", description = "查看可由当前绑定账号认领的任务")
    public void claimableTasks(PluginCommandContext command, PluginContext context) {
        if (!requireUser(command, context)) return;
        var tasks = appService.claimableTasks(String.valueOf(command.userId()), 1, 10);
        reply(command, context, tasks.isEmpty() ? "当前没有可认领任务。" : "可认领任务：\n" + tasks.stream()
                .map(task -> "- " + task.title() + "（ID: " + task.id() + "）").reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    @PluginCommand(code = "project-progress.claim", command = "认领任务", name = "认领任务", description = "用法：/认领任务 任务ID")
    public void claim(PluginCommandContext command, PluginContext context) {
        if (!requireUser(command, context)) return;
        if (command.arguments().size() != 1) { reply(command, context, "用法：/认领任务 任务ID"); return; }
        try {
            var task = appService.claim(command.arguments().getFirst(), String.valueOf(command.userId()));
            reply(command, context, "已认领任务：" + task.title());
        } catch (RuntimeException e) { reply(command, context, message(e)); }
    }

    @PluginCommand(code = "project-progress.my-check-ins", command = "我的打卡", name = "查询我的打卡", description = "查看最近的项目打卡记录")
    public void myCheckIns(PluginCommandContext command, PluginContext context) {
        if (!requireUser(command, context)) return;
        var records = appService.myCheckIns(String.valueOf(command.userId()), null, 1, 10);
        reply(command, context, records.isEmpty() ? "当前没有打卡记录。" : "最近打卡：\n" + records.stream()
                .map(record -> "- " + record.type() + " " + record.summary() + "（" + record.reviewStatus() + "）").reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    private boolean requireUser(PluginCommandContext command, PluginContext context) {
        if (command.userId() != null) return true;
        reply(command, context, "当前机器人账号尚未绑定系统账号，请先完成绑定。");
        return false;
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        if (command.event().channelId() == null || command.event().channelId().isBlank()) return;
        context.framework().messaging().send(new PluginMessageRequest(command.event().connectionId(), command.event().platform(),
                command.event().selfId(), command.event().channelId(), new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null,
                command.event().messageId() == null ? Map.of() : Map.of("message_id", command.event().messageId()))));
    }

    private String message(RuntimeException exception) { return exception.getMessage() == null ? "操作失败" : exception.getMessage(); }
}
