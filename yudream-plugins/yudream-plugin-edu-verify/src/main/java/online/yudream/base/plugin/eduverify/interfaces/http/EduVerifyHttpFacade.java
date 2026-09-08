package online.yudream.base.plugin.eduverify.interfaces.http;

import online.yudream.base.plugin.eduverify.application.service.EduVerifyAppService;
import online.yudream.base.plugin.eduverify.domain.aggregate.VerifySettings;
import online.yudream.base.plugin.eduverify.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.eduverify.interfaces.request.ChsiConfirmRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.ChsiVerifyRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.DomainSaveRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.EmailSendRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.EmailVerifyRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.ManualSubmitRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.ManualUploadRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.ReviewRequest;
import online.yudream.base.plugin.eduverify.interfaces.request.SettingsSaveRequest;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class EduVerifyHttpFacade {

    private final EduVerifyAppService app;

    public EduVerifyHttpFacade(EduVerifyAppService app) {
        this.app = app;
    }

    public PluginHttpResponse methods() {
        return PluginHttpResponse.ok(app.publicMethods());
    }

    public PluginHttpResponse sendEmailCode(PluginHttpRequest request) {
        EmailSendRequest body = JsonSupport.read(request.body(), EmailSendRequest.class);
        return PluginHttpResponse.ok(app.sendEmailCode(body.email()));
    }

    public PluginHttpResponse verifyEmailCode(PluginHttpRequest request) {
        EmailVerifyRequest body = JsonSupport.read(request.body(), EmailVerifyRequest.class);
        return PluginHttpResponse.ok(app.verifyEmailCode(body.email(), body.code()));
    }

    public PluginHttpResponse chsiVerify(PluginHttpRequest request) {
        ChsiVerifyRequest body = JsonSupport.read(request.body(), ChsiVerifyRequest.class);
        return PluginHttpResponse.ok(app.chsiVerify(body.email(), body.vcode(), body.realName(), body.schoolName()));
    }

    public PluginHttpResponse chsiConfirm(PluginHttpRequest request) {
        ChsiConfirmRequest body = JsonSupport.read(request.body(), ChsiConfirmRequest.class);
        return PluginHttpResponse.ok(app.confirmChsiMail(body.email()));
    }

    public PluginHttpResponse publicManualSubmit(PluginHttpRequest request) {
        ManualSubmitRequest body = JsonSupport.read(request.body(), ManualSubmitRequest.class);
        return PluginHttpResponse.ok(app.submitManual(body.email(), body.realName(), body.schoolName(), body.note(), body.vcode(), body.materials()));
    }

    public PluginHttpResponse publicManualUpload(PluginHttpRequest request) {
        ManualUploadRequest body = JsonSupport.read(request.body(), ManualUploadRequest.class);
        return PluginHttpResponse.ok(app.uploadManualMaterial(
                body.email(),
                body.filename(),
                body.contentType(),
                body.content(),
                clientKey(request)
        ));
    }

    public PluginHttpResponse publicStatus(PluginHttpRequest request) {
        return PluginHttpResponse.ok(app.statusByEmail(firstQuery(request, "email")));
    }

    public PluginHttpResponse adminPage(PluginHttpRequest request) {
        return PluginHttpResponse.ok(app.adminPage(
                firstQuery(request, "status"),
                firstQuery(request, "channel"),
                firstQuery(request, "keyword"),
                intQuery(request, "page", 1),
                intQuery(request, "size", 10)
        ));
    }

    public PluginHttpResponse adminDetail(PluginHttpRequest request) {
        return PluginHttpResponse.ok(app.adminDetail(pathSegment(request.path(), 2)));
    }

    public PluginHttpResponse approve(PluginHttpRequest request) {
        ReviewRequest body = JsonSupport.read(request.body(), ReviewRequest.class);
        return PluginHttpResponse.ok(app.approve(
                pathSegment(request.path(), 2),
                currentUserId(request),
                body == null ? null : body.reason(),
                body == null ? null : body.realName(),
                body == null ? null : body.schoolName()
        ));
    }

    public PluginHttpResponse reject(PluginHttpRequest request) {
        ReviewRequest body = JsonSupport.read(request.body(), ReviewRequest.class);
        return PluginHttpResponse.ok(app.reject(pathSegment(request.path(), 2), currentUserId(request), body.reason()));
    }

    public PluginHttpResponse revoke(PluginHttpRequest request) {
        ReviewRequest body = JsonSupport.read(request.body(), ReviewRequest.class);
        return PluginHttpResponse.ok(app.revoke(pathSegment(request.path(), 2), currentUserId(request), body.reason()));
    }

    public PluginHttpResponse domains() {
        return PluginHttpResponse.ok(Map.of("records", app.listDomains()));
    }

    public PluginHttpResponse saveDomain(PluginHttpRequest request) {
        DomainSaveRequest body = JsonSupport.read(request.body(), DomainSaveRequest.class);
        boolean enabled = body.enabled() == null || body.enabled();
        return PluginHttpResponse.ok(app.saveDomain(
                body.domain(), body.chineseName(), body.englishName(), enabled, body.source()
        ));
    }

    public PluginHttpResponse deleteDomain(PluginHttpRequest request) {
        app.deleteDomain(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse settings() {
        return PluginHttpResponse.ok(app.settings());
    }

    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        SettingsSaveRequest body = JsonSupport.read(request.body(), SettingsSaveRequest.class);
        VerifySettings current = app.settings();
        VerifySettings next = new VerifySettings(
                body.emailEnabled() == null ? current.emailEnabled() : body.emailEnabled(),
                body.chsiEnabled() == null ? current.chsiEnabled() : body.chsiEnabled(),
                body.manualEnabled() == null ? current.manualEnabled() : body.manualEnabled(),
                body.codeTtlMinutes() == null ? current.codeTtlMinutes() : body.codeTtlMinutes(),
                body.codeResendSeconds() == null ? current.codeResendSeconds() : body.codeResendSeconds(),
                body.codeDailyLimit() == null ? current.codeDailyLimit() : body.codeDailyLimit(),
                body.validityDays() == null ? current.validityDays() : body.validityDays(),
                body.retentionDays() == null ? current.retentionDays() : body.retentionDays(),
                body.chsiDailyLimit() == null ? current.chsiDailyLimit() : body.chsiDailyLimit(),
                body.chsiReportUrlTemplate() == null ? current.chsiReportUrlTemplate() : body.chsiReportUrlTemplate(),
                body.chsiSelectors() == null ? current.chsiSelectors() : body.chsiSelectors(),
                body.emailTutorialMarkdown() == null ? current.emailTutorialMarkdown() : body.emailTutorialMarkdown(),
                body.chsiTutorialMarkdown() == null ? current.chsiTutorialMarkdown() : body.chsiTutorialMarkdown(),
                body.manualTutorialMarkdown() == null ? current.manualTutorialMarkdown() : body.manualTutorialMarkdown(),
                body.manualNotifyEnabled() == null ? current.manualNotifyEnabled() : body.manualNotifyEnabled(),
                body.manualNotifyGroups() == null ? current.manualNotifyGroups() : body.manualNotifyGroups(),
                body.manualNotifyTemplate() == null ? current.manualNotifyTemplate() : body.manualNotifyTemplate(),
                body.chsiMailConfirmationEnabled() == null ? current.chsiMailConfirmationEnabled() : body.chsiMailConfirmationEnabled(),
                body.chsiMailboxId() == null ? current.chsiMailboxId() : body.chsiMailboxId(),
                body.chsiAllowedFromDomains() == null ? current.chsiAllowedFromDomains() : body.chsiAllowedFromDomains(),
                body.chsiMailKeywords() == null ? current.chsiMailKeywords() : body.chsiMailKeywords(),
                body.chsiMailWaitMinutes() == null ? current.chsiMailWaitMinutes() : body.chsiMailWaitMinutes()
        );
        return PluginHttpResponse.ok(app.saveSettings(next));
    }

    public PluginHttpResponse audits(PluginHttpRequest request) {
        return PluginHttpResponse.ok(app.auditPage(
                firstQuery(request, "email"),
                intQuery(request, "page", 1),
                intQuery(request, "size", 20)
        ));
    }

    private String currentUserId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return String.valueOf(request.principal().userId());
    }

    private String clientKey(PluginHttpRequest request) {
        String forwarded = firstHeader(request, "x-forwarded-for");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma < 0 ? forwarded.trim() : forwarded.substring(0, comma).trim();
        }
        String realIp = firstHeader(request, "x-real-ip");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = firstHeader(request, "x-original-forwarded-for");
        return remote == null ? "" : remote.trim();
    }

    private String firstHeader(PluginHttpRequest request, String name) {
        if (request.headers() == null || name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey() != null && name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? defaultValue : Integer.parseInt(values.get(0));
    }

    private String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String pathSegment(String path, int index) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        String[] segments = value.split("/");
        if (index < 0 || index >= segments.length) {
            throw new IllegalArgumentException("路径参数缺失");
        }
        return URLDecoder.decode(segments[index], StandardCharsets.UTF_8);
    }
}
