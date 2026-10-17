package online.yudream.base.plugin.feedback.interfaces.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.feedback.application.dto.AssignableUsersDTO;
import online.yudream.base.plugin.feedback.application.service.FeedbackAppService;
import online.yudream.base.plugin.feedback.infrastructure.JsonSupport;
import online.yudream.base.plugin.feedback.interfaces.assembler.FeedbackWebAssembler;
import online.yudream.base.plugin.feedback.interfaces.request.FeedbackAssigneeRequest;
import online.yudream.base.plugin.feedback.interfaces.request.FeedbackCommentRequest;
import online.yudream.base.plugin.feedback.interfaces.request.FeedbackSeverityRequest;
import online.yudream.base.plugin.feedback.interfaces.request.FeedbackStatusRequest;
import online.yudream.base.plugin.feedback.interfaces.request.FeedbackSubmitRequest;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * HTTP facade：负责解析边界参数并把用例结果转成响应。
 *
 * <p>用户端归属一律来自 {@code request.principal().userId()}，且所有读写都按
 * 「反馈 ID + 归属人」定位；只有管理端接口才接受跨用户筛选与目标参数。
 */
public class FeedbackHttpFacade {

    private final FeedbackAppService appService;
    private final FeedbackWebAssembler assembler = new FeedbackWebAssembler();
    private final JsonSupport json;

    public FeedbackHttpFacade(FeedbackAppService appService, JsonSupport json) {
        this.appService = appService;
        this.json = json;
    }

    // ------------------------------------------------------------------ 用户端

    public PluginHttpResponse myFeedbacks(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.listMyFeedbacks(ownerId(request), HttpSupport.query(request))));
    }

    public PluginHttpResponse myStats(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.myStats(ownerId(request))));
    }

    public PluginHttpResponse myFeedback(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.myFeedback(ownerId(request), feedbackId(request))));
    }

    public PluginHttpResponse submit(PluginHttpRequest request) {
        FeedbackSubmitRequest body = body(request, FeedbackSubmitRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.json(201,
                appService.submit(ownerId(request), assembler.toSubmitCmd(body))));
    }

    public PluginHttpResponse updateMyFeedback(PluginHttpRequest request) {
        FeedbackSubmitRequest body = body(request, FeedbackSubmitRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.updateMyFeedback(ownerId(request), feedbackId(request), assembler.toSubmitCmd(body))));
    }

    public PluginHttpResponse commentMyFeedback(PluginHttpRequest request) {
        FeedbackCommentRequest body = body(request, FeedbackCommentRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.commentMyFeedback(ownerId(request), feedbackId(request), body.safeContent())));
    }

    public PluginHttpResponse archiveMyFeedback(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.archiveMyFeedback(ownerId(request), feedbackId(request))));
    }

    public PluginHttpResponse withdrawMyFeedback(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            appService.withdrawMyFeedback(ownerId(request), feedbackId(request));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    // ------------------------------------------------------------------ 管理端

    public PluginHttpResponse adminFeedbacks(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.adminPageFeedbacks(HttpSupport.adminQuery(request))));
    }

    public PluginHttpResponse adminStats() {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.adminStats()));
    }

    public PluginHttpResponse adminFeedback(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(appService.adminFeedback(feedbackId(request))));
    }

    public PluginHttpResponse adminReply(PluginHttpRequest request) {
        FeedbackCommentRequest body = body(request, FeedbackCommentRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.adminReply(feedbackId(request), operatorId(request), body.safeContent())));
    }

    public PluginHttpResponse adminNote(PluginHttpRequest request) {
        FeedbackCommentRequest body = body(request, FeedbackCommentRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.adminNote(feedbackId(request), operatorId(request), body.safeContent())));
    }

    public PluginHttpResponse adminChangeStatus(PluginHttpRequest request) {
        FeedbackStatusRequest body = body(request, FeedbackStatusRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.adminChangeStatus(feedbackId(request), operatorId(request),
                        body.safeStatus(), body.safeNote())));
    }

    public PluginHttpResponse adminArchiveFeedback(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.adminArchiveFeedback(feedbackId(request), operatorId(request))));
    }

    public PluginHttpResponse adminAssign(PluginHttpRequest request) {
        FeedbackAssigneeRequest body = body(request, FeedbackAssigneeRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.adminAssign(feedbackId(request), operatorId(request), body.safeAssigneeIds())));
    }

    public PluginHttpResponse adminUpdateSeverity(PluginHttpRequest request) {
        FeedbackSeverityRequest body = body(request, FeedbackSeverityRequest.class);
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                appService.adminUpdateSeverity(feedbackId(request), operatorId(request), body.safeSeverity())));
    }

    public PluginHttpResponse adminDeleteFeedback(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            appService.adminDeleteFeedback(feedbackId(request));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    /**
     * 处理人 / 提交人选择器数据源：只回传账号目录信息，不含任何反馈数据。
     *
     * <p>{@code scope=manage} 供处理人场景使用，响应额外带 {@code available} 与 {@code notice}。
     * 宿主 SPI 不提供「按权限反查用户」，因此候选来自账号目录、未按反馈管理权限预筛；
     * {@code notice} 会明确提醒管理员确认所选成员可访问反馈管理页。账号目录不可用时
     * {@code available=false}，前端拒绝选择而不是静默放开全员。
     */
    public PluginHttpResponse adminUsers(PluginHttpRequest request) {
        String keyword = HttpSupport.first(request, "keyword");
        int page = HttpSupport.pageParam(request);
        int size = HttpSupport.sizeParam(request, 20);
        if (!"manage".equalsIgnoreCase(HttpSupport.first(request, "scope").trim())) {
            return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                    "records", appService.searchUsers(keyword, page, size))));
        }
        return HttpSupport.guard(() -> {
            AssignableUsersDTO result = appService.searchAssignableUsers(keyword, page, size);
            // Map.of 不收 null，notice 缺省时干脆不放这个键
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("records", result.records());
            payload.put("available", result.available());
            if (result.notice() != null && !result.notice().isBlank()) {
                payload.put("notice", result.notice());
            }
            return PluginHttpResponse.ok(payload);
        });
    }

    public PluginHttpResponse adminResolveUsers(PluginHttpRequest request) {
        List<String> ids = request.query().get("ids");
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", appService.usersByIds(ids == null ? List.of() : ids))));
    }

    // ------------------------------------------------------------------ 内部

    private <T> T body(PluginHttpRequest request, Class<T> type) {
        return json.read(request.body(), type);
    }

    /** 反馈 ID 一律由路径段解析，避免客户端把 ID 混进请求体绕过归属校验。 */
    private static String feedbackId(PluginHttpRequest request) {
        String id = HttpSupport.segmentAfter(request.path(), "feedbacks");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("缺少反馈 ID");
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
            throw new IllegalStateException("当前请求缺少已认证身份");
        }
        return String.valueOf(request.principal().userId());
    }
}
