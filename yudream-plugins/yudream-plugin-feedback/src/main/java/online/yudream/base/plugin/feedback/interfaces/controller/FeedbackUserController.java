package online.yudream.base.plugin.feedback.interfaces.controller;

import online.yudream.base.plugin.feedback.bootstrap.FeedbackPlugin;
import online.yudream.base.plugin.feedback.interfaces.http.FeedbackHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 用户端接口：只服务当前登录者提交的反馈，归属由 principal 推导。
 *
 * <p>管理员调用这些接口时同样只访问自己的反馈，不会因管理权限扩大数据范围。
 */
public class FeedbackUserController {

    private final FeedbackHttpFacade http;

    public FeedbackUserController(FeedbackHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/feedbacks", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse feedbacks(PluginHttpRequest request) {
        return http.myFeedbacks(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/feedbacks", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse submit(PluginHttpRequest request) {
        return http.submit(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/feedbacks/{id}", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse feedback(PluginHttpRequest request) {
        return http.myFeedback(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/feedbacks/{id}", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return http.updateMyFeedback(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/feedbacks/{id}/comments", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse comment(PluginHttpRequest request) {
        return http.commentMyFeedback(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/feedbacks/{id}/archive", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse archive(PluginHttpRequest request) {
        return http.archiveMyFeedback(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/feedbacks/{id}", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse withdraw(PluginHttpRequest request) {
        return http.withdrawMyFeedback(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/stats", permission = FeedbackPlugin.USER_PERMISSION)
    public PluginHttpResponse stats(PluginHttpRequest request) {
        return http.myStats(request);
    }
}
