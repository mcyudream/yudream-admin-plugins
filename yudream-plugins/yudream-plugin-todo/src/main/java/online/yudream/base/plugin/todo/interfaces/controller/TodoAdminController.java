package online.yudream.base.plugin.todo.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.todo.bootstrap.TodoPlugin;
import online.yudream.base.plugin.todo.interfaces.http.TodoHttpFacade;

/** 管理端接口：跨用户查询与维护，全部由管理权限保护。 */
public class TodoAdminController {

    private final TodoHttpFacade http;

    public TodoAdminController(TodoHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/tasks", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse tasks(PluginHttpRequest request) {
        return http.adminTasks(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/tasks/{id}", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse task(PluginHttpRequest request) {
        return http.adminTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/tasks/{id}", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return http.adminUpdateTask(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/tasks/{id}/participants", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse addParticipants(PluginHttpRequest request) {
        return http.adminAddParticipants(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/tasks/{id}", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return http.adminDeleteTask(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/tasks/{id}/sub-task-assignees", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateSubTaskAssignees(PluginHttpRequest request) {
        return http.adminUpdateSubTaskAssignees(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/stats", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse stats() {
        return http.adminStats();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/departments", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse departments(PluginHttpRequest request) {
        return http.adminDepartments(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/templates", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse templates(PluginHttpRequest request) {
        return http.adminTemplates(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/users", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse users(PluginHttpRequest request) {
        return http.userOptions(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/users/resolve", permission = TodoPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse resolveUsers(PluginHttpRequest request) {
        return http.resolveUsers(request);
    }
}
