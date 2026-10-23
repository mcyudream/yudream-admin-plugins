package online.yudream.base.plugin.activityproof.interfaces.http;

import online.yudream.base.plugin.activityproof.application.service.ActivityEffectService;
import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityEffect;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityProofRepository;
import online.yudream.base.plugin.activityproof.interfaces.support.JsonSupport;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponseBody;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.http.PluginHttpPart;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 活动效果提交的 HTTP 装配层：解析请求、调用应用服务、组装视图。 */
public class ActivityEffectHttpFacade {

    private final ActivityEffectService service;
    private final ActivityProofRepository activities;
    private final online.yudream.base.plugin.spi.system.FrameworkServices framework;

    public ActivityEffectHttpFacade(ActivityEffectService service, ActivityProofRepository activities,
                                    online.yudream.base.plugin.spi.system.FrameworkServices framework) {
        this.service = service;
        this.activities = activities;
        this.framework = framework;
    }

    // ---------- user 面 ----------

    public PluginHttpResponse uploadMine(PluginHttpRequest request, String activityId, String userId) {
        return guard(() -> {
            PluginHttpPart file = request.parts() == null ? null : request.parts().get("file");
            ActivityEffect.Attachment attachment;
            if (file != null && file.isFile()) {
                attachment = service.uploadAttachment(activityId, file.filename(), file.contentType(), file.data());
            } else {
                // 平台文件桥：JSON {fileId, filename}
                Map<String, Object> body = JsonSupport.readMap(request.body());
                attachment = service.uploadPlatformFile(activityId,
                        String.valueOf(body.getOrDefault("fileId", "")),
                        body.get("filename") == null ? null : String.valueOf(body.get("filename")),
                        framework);
            }
            return PluginHttpResponse.ok(attachmentView(attachment));
        });
    }

    public PluginHttpResponse submitMine(PluginHttpRequest request, String activityId, String userId) {
        return guard(() -> {
            SubmitRequest body = JsonSupport.read(request.body(), SubmitRequest.class);
            ActivityEffect effect = service.submit(activityId, userId, body.text(), toAttachments(body.attachments()));
            return PluginHttpResponse.ok(effectView(effect));
        });
    }

    public PluginHttpResponse myEffects(PluginHttpRequest request, String activityId) {
        return guard(() -> PluginHttpResponse.ok(
                pageView(service.byActivity(activityId, page(request), size(request)),
                        service.countByActivity(activityId))));
    }

    public PluginHttpResponse mySubmissions(PluginHttpRequest request, String userId) {
        return guard(() -> PluginHttpResponse.ok(
                pageView(service.bySubmitter(userId, page(request), size(request)),
                        service.countBySubmitter(userId))));
    }

    public PluginHttpResponse withdrawMine(PluginHttpRequest request, String effectId, String userId) {
        return guard(() -> {
            service.withdraw(effectId, userId);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    public PluginHttpResponse downloadMine(PluginHttpRequest request, String effectId, int index, String userId) {
        return download(requireOwnedAttachment(effectId, index, userId));
    }

    // ---------- admin 面 ----------

    public PluginHttpResponse adminList(PluginHttpRequest request) {
        return guard(() -> {
            String activityId = firstQuery(request, "activityId");
            String status = firstQuery(request, "status");
            List<Map<String, Object>> records = new ArrayList<>();
            for (ActivityEffect effect : service.adminList(activityId, status, page(request), size(request))) {
                records.add(effectView(effect));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", records);
            result.put("total", service.adminCount(activityId, status));
            return PluginHttpResponse.ok(result);
        });
    }

    public PluginHttpResponse adminReview(PluginHttpRequest request, String effectId, String reviewer) {
        return guard(() -> {
            ReviewRequest body = JsonSupport.read(request.body(), ReviewRequest.class);
            ActivityEffect effect = service.review(effectId, reviewer, body.accept(), body.note());
            return PluginHttpResponse.ok(effectView(effect));
        });
    }

    public PluginHttpResponse adminDelete(PluginHttpRequest request, String effectId) {
        return guard(() -> {
            service.adminDelete(effectId);
            return PluginHttpResponse.ok(Map.of("ok", true));
        });
    }

    public PluginHttpResponse adminDownload(PluginHttpRequest request, String effectId, int index) {
        return download(requireAttachment(effectId, index));
    }

    // ---------- 视图 ----------

    private Map<String, Object> attachmentView(ActivityEffect.Attachment attachment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fileKey", attachment.fileKey());
        view.put("filename", attachment.filename());
        view.put("contentType", attachment.contentType());
        view.put("size", attachment.size());
        view.put("kind", attachment.kind());
        view.put("caption", attachment.caption());
        return view;
    }

    private Map<String, Object> effectView(ActivityEffect effect) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", effect.id());
        view.put("activityId", effect.activityId());
        view.put("activityTitle", activities.activity(effect.activityId()).map(Activity::title).orElse(""));
        view.put("submittedBy", effect.submittedBy());
        view.put("submittedAt", effect.submittedAt());
        view.put("text", effect.text());
        view.put("status", effect.status());
        view.put("reviewedBy", effect.reviewedBy());
        view.put("reviewedAt", effect.reviewedAt());
        view.put("reviewNote", effect.reviewNote());
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (ActivityEffect.Attachment attachment : effect.attachments()) {
            attachments.add(attachmentView(attachment));
        }
        view.put("attachments", attachments);
        return view;
    }

    private Map<String, Object> pageView(List<ActivityEffect> effects, long total) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (ActivityEffect effect : effects) {
            records.add(effectView(effect));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        return result;
    }

    // ---------- 附件读取 ----------

    private ActivityEffect.Attachment requireAttachment(String effectId, int index) {
        ActivityEffect effect = service.effect(effectId)
                .orElseThrow(() -> new IllegalArgumentException("效果提交不存在或已被删除"));
        if (index < 0 || index >= effect.attachments().size()) {
            throw new IllegalArgumentException("附件不存在");
        }
        return effect.attachments().get(index);
    }

    private ActivityEffect.Attachment requireOwnedAttachment(String effectId, int index, String userId) {
        ActivityEffect effect = service.effect(effectId)
                .orElseThrow(() -> new IllegalArgumentException("效果提交不存在或已被删除"));
        if (!effect.submittedBy().equals(userId)) {
            throw new IllegalArgumentException("只能访问本人的效果提交");
        }
        if (index < 0 || index >= effect.attachments().size()) {
            throw new IllegalArgumentException("附件不存在");
        }
        return effect.attachments().get(index);
    }

    private PluginHttpResponse download(ActivityEffect.Attachment attachment) {
        try {
            InputStream stream = service.openFile(attachment.fileKey()).inputStream();
            String contentType = attachment.contentType() == null || attachment.contentType().isBlank()
                    ? "application/octet-stream" : attachment.contentType();
            return new PluginHttpResponse(200,
                    Map.of("Content-Disposition", "attachment; filename*=UTF-8''"
                            + URLEncoder.encode(attachment.filename(), StandardCharsets.UTF_8),
                            "Cache-Control", "no-cache"),
                    contentType,
                    new PluginHttpResponseBody() {
                        @Override
                        public InputStream stream() {
                            return stream;
                        }

                        @Override
                        public long contentLength() {
                            return attachment.size();
                        }

                        @Override
                        public void close() {
                            try {
                                stream.close();
                            } catch (Exception ignored) {
                                // 宿主写完即关，幂等
                            }
                        }
                    }, false);
        } catch (Exception e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", "附件不存在或已被清理"));
        }
    }

    // ---------- 基础工具 ----------

    public static String currentUserId(PluginHttpRequest request) {
        return request.principal() == null || request.principal().userId() == null
                ? "" : String.valueOf(request.principal().userId());
    }

    private static int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    private static int size(PluginHttpRequest request) {
        return intQuery(request, "size", 20);
    }

    private static int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query().get(key);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static List<ActivityEffect.Attachment> toAttachments(List<Map<String, Object>> raw) {
        List<ActivityEffect.Attachment> attachments = new ArrayList<>();
        if (raw == null) {
            return attachments;
        }
        for (Map<String, Object> item : raw) {
            if (item == null) {
                continue;
            }
            attachments.add(new ActivityEffect.Attachment(
                    str(item.get("fileKey")), str(item.get("filename")), str(item.get("contentType")),
                    num(item.get("size")), str(item.get("kind")), str(item.get("caption"))));
        }
        return attachments;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private PluginHttpResponse guard(HttpCall call) {
        try {
            return call.invoke();
        } catch (IllegalArgumentException e) {
            return PluginHttpResponse.rawJson(400, Map.of("message", e.getMessage() == null ? "请求不合法" : e.getMessage()));
        } catch (IllegalStateException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", e.getMessage() == null ? "处理失败" : e.getMessage()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", e.getMessage() == null ? "处理失败" : e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface HttpCall {
        PluginHttpResponse invoke();
    }

    /** 请求体：提交效果。 */
    public record SubmitRequest(String text, List<Map<String, Object>> attachments) {
    }

    /** 请求体：审核。 */
    public record ReviewRequest(boolean accept, String note) {
    }
}
