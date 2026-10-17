package online.yudream.base.plugin.todo.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.todo.interfaces.http.TodoHttpFacade;

/**
 * 用户端接口：只服务当前登录者自己的待办，归属由 principal 推导。
 *
 * <p>管理员调用这些接口时同样只访问自己的数据，不会因管理权限扩大数据范围。
 */
public class TodoUserController {

    private final TodoHttpFacade http;

    public TodoUserController(TodoHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/overview")
    public PluginHttpResponse overview(PluginHttpRequest request) {
        return http.myOverview(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/tasks")
    public PluginHttpResponse tasks(PluginHttpRequest request) {
        return http.myTasks(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/tasks")
    public PluginHttpResponse create(PluginHttpRequest request) {
        return http.createMyTask(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/tasks/{id}")
    public PluginHttpResponse task(PluginHttpRequest request) {
        return http.myTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}")
    public PluginHttpResponse update(PluginHttpRequest request) {
        return http.updateMyTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}/status")
    public PluginHttpResponse toggle(PluginHttpRequest request) {
        return http.toggleMyTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}/sub-tasks/{subTaskId}")
    public PluginHttpResponse toggleSubTask(PluginHttpRequest request) {
        return http.toggleMySubTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}/sub-tasks/{subTaskId}/join")
    public PluginHttpResponse joinSubTask(PluginHttpRequest request) {
        return http.joinMySubTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}/sub-tasks/{subTaskId}/leave")
    public PluginHttpResponse leaveSubTask(PluginHttpRequest request) {
        return http.leaveMySubTask(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/tasks/{id}")
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return http.deleteMyTask(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/templates")
    public PluginHttpResponse templates(PluginHttpRequest request) {
        return http.myTemplates(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/departments")
    public PluginHttpResponse myDepartments(PluginHttpRequest request) {
        return http.myDepartments(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/tasks/from-template")
    public PluginHttpResponse createFromTemplate(PluginHttpRequest request) {
        return http.createFromTemplate(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}/join")
    public PluginHttpResponse joinTask(PluginHttpRequest request) {
        return http.joinTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}/leave")
    public PluginHttpResponse leaveTask(PluginHttpRequest request) {
        return http.leaveTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/tasks/{id}/archive")
    public PluginHttpResponse archiveMyTask(PluginHttpRequest request) {
        return http.archiveMyTask(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/schedule")
    public PluginHttpResponse schedule(PluginHttpRequest request) {
        return http.mySchedule(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/users")
    public PluginHttpResponse users(PluginHttpRequest request) {
        return http.myUserOptions(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/users/resolve")
    public PluginHttpResponse resolveUsers(PluginHttpRequest request) {
        return http.myResolveUsers(request);
    }
}
