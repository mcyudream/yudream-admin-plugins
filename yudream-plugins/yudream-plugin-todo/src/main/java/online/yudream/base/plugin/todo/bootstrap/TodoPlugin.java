package online.yudream.base.plugin.todo.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.todo.application.service.TodoAppService;
import online.yudream.base.plugin.todo.infrastructure.JsonSupport;
import online.yudream.base.plugin.todo.infrastructure.repository.TodoTaskDocumentRepository;
import online.yudream.base.plugin.todo.interfaces.controller.TodoAdminController;
import online.yudream.base.plugin.todo.interfaces.controller.TodoUserController;
import online.yudream.base.plugin.todo.interfaces.http.TodoHttpFacade;

/** 待办清单插件入口：只负责装配、注册与生命周期，业务流程全部下沉到应用层。 */
@PluginSpec(
        code = TodoPlugin.CODE,
        name = "todo",
        version = TodoPlugin.VERSION,
        description = "待办清单：用户自建计划任务与截止时间并拆分二级小任务，可复用已有任务快速创建，"
                + "管理员可向指定用户派发清单任务，并提供「我的待办」主页卡片（表单清单 + 日程表双视图，"
                + "只读展示，点击待办可跳转我的清单）。"
)
@PluginPermissions({
        @PluginPermission(code = TodoPlugin.USER_PERMISSION, name = "使用待办清单", module = "平台插件",
                description = "查看和维护自己的待办任务，包括新建、编辑、完成与删除"),
        @PluginPermission(code = TodoPlugin.MANAGE_PERMISSION, name = "管理待办清单", module = "平台插件",
                description = "跨用户查看待办，并向指定用户派发、编辑与删除清单任务")
})
@PluginDashboardCard(
        code = "todo-today",
        title = "我的待办",
        description = "表单形式列出全部待办（截止时间、优先级、负责人）；日程表周视图一周一行，"
                + "按卡片宽高自适应铺 1~4 周、月视图 6 行均分铺满卡片；统计胶囊与筛选胶囊同步、"
                + "点击即联动筛选。卡片只做展示，点击任意待办可跳转到「我的清单」处理。",
        icon = "i-ri:list-check-2",
        category = "待办清单",
        component = "todo/DashboardTodoCard",
        actionPath = "/platform/plugins/todo/tasks",
        tone = "blue",
        defaultW = 8,
        // 默认宽高 8x4（比工作入口高一格，保证周视图能铺满三周）；最小 6x3——保证清单面板 400px 基准宽、
        // 分栏 220px 最小高不被突破，待办清单在任何允许尺寸下都完整可读、不裁切。
        // 卡片高度三档自适应：低（<200px）只显示待办清单；中（<310px）清单 + 周视图日程；
        // 高（≥310px）清单 + 周视图/月视图切换，拖动即时切档。
        // 注意 defaultH 只影响新卡片，已保存的布局需手动调整。
        defaultH = 4,
        minW = 6,
        minH = 3,
        sort = 10
)
@PluginFrontend(
        moduleName = "todo",
        menuTitle = "待办清单",
        menuIcon = "i-ri:checkbox-circle-line",
        menuSort = 22,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/todo/tasks",
                        name = "platform-plugin-todo-tasks",
                        title = "我的清单",
                        icon = "i-ri:checkbox-circle-line",
                        component = "todo/Tasks",
                        permission = TodoPlugin.USER_PERMISSION,
                        sort = 60
                ),
                @PluginRoute(
                        path = "/platform/plugins/todo/admin/tasks",
                        name = "platform-plugin-todo-admin-tasks",
                        title = "清单管理",
                        icon = "i-ri:list-check-2",
                        component = "todo/AdminTasks",
                        permission = TodoPlugin.MANAGE_PERMISSION,
                        sort = 20
                )
        }
)
public class TodoPlugin implements YuDreamPlugin {

    public static final String CODE = "todo";
    public static final String VERSION = "1.4.34";
    public static final String USER_PERMISSION = "plugin:todo:use";
    public static final String MANAGE_PERMISSION = "plugin:todo:manage";

    @Override
    public void onEnable(PluginContext context) {
        JsonSupport json = new JsonSupport(new ObjectMapper());
        TodoTaskDocumentRepository tasks = new TodoTaskDocumentRepository(context.documents());
        TodoAppService appService = new TodoAppService(tasks, userDirectory(context));
        TodoHttpFacade http = new TodoHttpFacade(appService, json);
        context.registerHttpController(new TodoUserController(http));
        context.registerHttpController(new TodoAdminController(http));
    }

    /** 宿主用户目录不可用时降级为不解析昵称，插件主体仍可运行。 */
    private static online.yudream.base.plugin.spi.system.user.PluginUserService userDirectory(PluginContext context) {
        try {
            return context.framework() == null ? null : context.framework().users();
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
