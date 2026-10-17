package online.yudream.base.plugin.feedback.interfaces.controller;

import online.yudream.base.plugin.feedback.bootstrap.FeedbackPlugin;
import online.yudream.base.plugin.feedback.interfaces.http.FeedbackHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 管理端接口：跨用户受理反馈（回复、备注、流转状态、指派处理人），全部由管理权限保护。 */
public class FeedbackAdminController {

    private final FeedbackHttpFacade http;

    public FeedbackAdminController(FeedbackHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/feedbacks", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse feedbacks(PluginHttpRequest request) {
        return http.adminFeedbacks(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/feedbacks/{id}", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse feedback(PluginHttpRequest request) {
        return http.adminFeedback(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/feedbacks/{id}/replies", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse reply(PluginHttpRequest request) {
        return http.adminReply(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/feedbacks/{id}/notes", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse note(PluginHttpRequest request) {
        return http.adminNote(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/feedbacks/{id}/status", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse status(PluginHttpRequest request) {
        return http.adminChangeStatus(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/feedbacks/{id}/archive", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse archive(PluginHttpRequest request) {
        return http.adminArchiveFeedback(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/feedbacks/{id}/assignee", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse assign(PluginHttpRequest request) {
        return http.adminAssign(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/feedbacks/{id}/severity", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse severity(PluginHttpRequest request) {
        return http.adminUpdateSeverity(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/feedbacks/{id}", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return http.adminDeleteFeedback(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/stats", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse stats() {
        return http.adminStats();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/users", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse users(PluginHttpRequest request) {
        return http.adminUsers(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/users/resolve", permission = FeedbackPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse resolveUsers(PluginHttpRequest request) {
        return http.adminResolveUsers(request);
    }
}
