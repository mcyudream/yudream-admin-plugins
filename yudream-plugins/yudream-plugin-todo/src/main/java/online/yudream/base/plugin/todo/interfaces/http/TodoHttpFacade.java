package online.yudream.base.plugin.todo.interfaces.http;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.todo.application.service.TodoAppService;
import online.yudream.base.plugin.todo.infrastructure.JsonSupport;
import online.yudream.base.plugin.todo.infrastructure.TimeSupport;
import online.yudream.base.plugin.todo.interfaces.assembler.TodoWebAssembler;
import online.yudream.base.plugin.todo.interfaces.request.TodoArchiveRequest;
import online.yudream.base.plugin.todo.interfaces.request.TodoFromTemplateRequest;
import online.yudream.base.plugin.todo.interfaces.request.TodoParticipantRequest;
import online.yudream.base.plugin.todo.interfaces.request.TodoStatusRequest;
import online.yudream.base.plugin.todo.interfaces.request.TodoSubTaskAssigneesRequest;
import online.yudream.base.plugin.todo.interfaces.request.TodoSubTaskStatusRequest;
import online.yudream.base.plugin.todo.interfaces.request.TodoTaskSaveRequest;

/**
 * HTTP facade：负责解析边界参数并把用例结果转成响应。
 *
 * <p>用户端归属一律来自 {@code request.principal().userId()}；管理端才接受目标用户参数。
 */
public class TodoHttpFacade {

    private final TodoAppService appService;
    private final TodoWebAssembler assembler = new TodoWebAssembler();
    private final JsonSupport json;

    public TodoHttpFacade(TodoAppService appService, JsonSupport json) {
        this.appService = appService;
        this.json = json;
    }

    // ------------------------------------------------------------------ 用户端

    public PluginHttpResponse myTasks(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.listMyTasks(ownerId(request), HttpSupport.query(request))));
    }

    public PluginHttpResponse myOverview(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.myOverview(ownerId(request))));
    }

    public PluginHttpResponse myTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.findMyTask(taskId(request), ownerId(request))));
    }

    public PluginHttpResponse createMyTask(PluginHttpRequest request) {
        TodoTaskSaveRequest body = body(request, TodoTaskSaveRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.createMyTask(ownerId(request), assembler.toCmd(body))));
    }

    public PluginHttpResponse updateMyTask(PluginHttpRequest request) {
        TodoTaskSaveRequest body = body(request, TodoTaskSaveRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.updateMyTask(taskId(request), ownerId(request), assembler.toCmd(body))));
    }

    public PluginHttpResponse toggleMyTask(PluginHttpRequest request) {
        boolean done = Boolean.TRUE.equals(body(request, TodoStatusRequest.class).done());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.setMyTaskDone(taskId(request), ownerId(request), done)));
    }

    public PluginHttpResponse deleteMyTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            appService.deleteMyTask(taskId(request), ownerId(request));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    public PluginHttpResponse toggleMySubTask(PluginHttpRequest request) {
        boolean done = Boolean.TRUE.equals(body(request, TodoSubTaskStatusRequest.class).done());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.toggleMySubTask(taskId(request), subTaskId(request), ownerId(request), done)));
    }

    /** 加入小任务负责人。 */
    public PluginHttpResponse joinMySubTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.joinMySubTask(taskId(request), subTaskId(request), ownerId(request))));
    }

    /** 退出小任务负责人。 */
    public PluginHttpResponse leaveMySubTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.leaveMySubTask(taskId(request), subTaskId(request), ownerId(request))));
    }

    /** 当前用户加入的部门选项（用户端可见范围选择器数据源，只暴露自己所在部门）。 */
    public PluginHttpResponse myDepartments(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", appService.myDepartments(ownerId(request)))));
    }

    /** 全量部门树拍平选项（管理端可见范围选择器数据源）。 */
    public PluginHttpResponse adminDepartments(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", appService.departmentOptions(HttpSupport.first(request, "keyword")))));
    }

    public PluginHttpResponse myTemplates(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", appService.myTemplates(ownerId(request), HttpSupport.first(request, "keyword")))));
    }

    /** 从任务库复制一条待办到自己名下；清单中已有同名任务时拒绝。 */
    public PluginHttpResponse createFromTemplate(PluginHttpRequest request) {
        TodoFromTemplateRequest payload = body(request, TodoFromTemplateRequest.class);
        return HttpSupport.guard(() -> {
            if (payload == null || payload.templateId() == null || payload.templateId().isBlank()) {
                throw new IllegalArgumentException("请选择要复用的任务");
            }
            return PluginHttpResponse.json(201,
                    appService.createFromTemplate(ownerId(request), payload.templateId(), payload.dueAt()));
        });
    }

    /** 用户端：从任务库加入一条已有任务，把当前登录者加入参与人，不新建副本。 */
    public PluginHttpResponse joinTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.joinTask(taskId(request), ownerId(request))));
    }

    public PluginHttpResponse leaveTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.leaveTask(taskId(request), ownerId(request))));
    }

    public PluginHttpResponse archiveMyTask(PluginHttpRequest request) {
        boolean archived = Boolean.TRUE.equals(body(request, TodoArchiveRequest.class).archived());
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.setMyTaskArchived(taskId(request), ownerId(request), archived)));
    }

    /**
     * 用户端人员检索：参与人选择只能从宿主权威目录里挑，禁止手输内部用户 ID。
     *
     * <p>这里只回传账号目录信息（昵称/用户名/邮箱），不含任何待办数据，因此不会扩大用户端的数据范围。
     */
    public PluginHttpResponse myUserOptions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records",
                appService.searchUsers(HttpSupport.first(request, "keyword"),
                        HttpSupport.pageParam(request),
                        HttpSupport.sizeParam(request, 20)))));
    }

    public PluginHttpResponse myResolveUsers(PluginHttpRequest request) {
        List<String> ids = request.query().get("ids");
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", appService.usersByIds(ids == null ? List.of() : ids))));
    }

    /** 按自然日聚合的日程表，默认返回本周（含今天起 7 天）。 */
    public PluginHttpResponse mySchedule(PluginHttpRequest request) {
        long now = System.currentTimeMillis();
        long today = TimeSupport.dayStart(now);
        long from = HttpSupport.longParam(request, "from", today);
        long to = HttpSupport.longParam(request, "to", TimeSupport.dayStartPlusDays(today, 6));
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.mySchedule(ownerId(request), from, to)));
    }

    // ------------------------------------------------------------------ 管理端

    public PluginHttpResponse adminTasks(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.adminPageTasks(HttpSupport.adminQuery(request))));
    }

    public PluginHttpResponse adminTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.adminTask(taskId(request))));
    }

    public PluginHttpResponse adminUpdateTask(PluginHttpRequest request) {
        TodoTaskSaveRequest body = body(request, TodoTaskSaveRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.adminUpdateTask(taskId(request), assembler.toCmd(body))));
    }

    /** 管理端：把用户加入某条已有任务的参与人，复用同一条任务而非新建副本。 */
    public PluginHttpResponse adminAddParticipants(PluginHttpRequest request) {
        TodoParticipantRequest payload = body(request, TodoParticipantRequest.class);
        List<String> userIds = payload == null || payload.userIds() == null ? List.of() : payload.userIds();
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.addParticipants(taskId(request), operatorId(request), userIds, true)));
    }

    public PluginHttpResponse adminDeleteTask(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            appService.adminDeleteTask(taskId(request));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    /** 派发已有任务时按小任务 id 更新负责人，标题与勾选状态原样保留。 */
    public PluginHttpResponse adminUpdateSubTaskAssignees(PluginHttpRequest request) {
        TodoSubTaskAssigneesRequest payload = body(request, TodoSubTaskAssigneesRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.updateSubTaskAssignees(
                        taskId(request), payload == null ? List.of() : payload.safeItems(), operatorId(request))));
    }

    public PluginHttpResponse adminStats() {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.adminStats()));
    }

    /** 派发时可选的已有任务（跨用户），供管理员复用标题、备注与小任务。 */
    public PluginHttpResponse adminTemplates(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", appService.adminTemplates(HttpSupport.first(request, "keyword")))));
    }

    public PluginHttpResponse userOptions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records",
                appService.searchUsers(HttpSupport.first(request, "keyword"),
                        HttpSupport.pageParam(request),
                        HttpSupport.sizeParam(request, 10)))));
    }

    public PluginHttpResponse resolveUsers(PluginHttpRequest request) {
        List<String> ids = request.query().get("ids");
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", appService.usersByIds(ids == null ? List.of() : ids))));
    }

    // ------------------------------------------------------------------ 内部

    private <T> T body(PluginHttpRequest request, Class<T> type) {
        return json.read(request.body(), type);
    }

    private static String taskId(PluginHttpRequest request) {
        String id = HttpSupport.segmentAfter(request.path(), "tasks");
        return id == null ? HttpSupport.decode(request.path()) : id;
    }

    private static String subTaskId(PluginHttpRequest request) {
        String id = HttpSupport.segmentAfter(request.path(), "sub-tasks");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("缺少小任务 ID");
        }
        return id;
    }

    private static String ownerId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalStateException("当前请求缺少已认证身份");
        }
        return String.valueOf(request.principal().userId());
    }

    private static String operatorId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            return null;
        }
        return String.valueOf(request.principal().userId());
    }
}
