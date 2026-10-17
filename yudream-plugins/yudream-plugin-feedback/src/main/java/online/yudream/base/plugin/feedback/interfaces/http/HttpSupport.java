package online.yudream.base.plugin.feedback.interfaces.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import online.yudream.base.plugin.feedback.application.FeedbackNotFoundException;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackSeverity;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackStatus;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackType;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackQuery;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 控制器共享的 HTTP 小工具：查询参数、路径段解析与异常到状态码的映射。 */
public final class HttpSupport {

    private HttpSupport() {
    }

    public static String first(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    public static int pageParam(PluginHttpRequest request) {
        return clampInt(first(request, "page"), 1, 1, 100000);
    }

    public static int sizeParam(PluginHttpRequest request, int fallback) {
        return clampInt(first(request, "size"), fallback, 1, 100);
    }

    /** 毫秒时间戳参数；非法或缺失时返回 null，表示该项不过滤。 */
    public static Long timeParam(PluginHttpRequest request, String key) {
        String raw = first(request, key);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** 布尔查询参数：true/1 视为真，其余（含缺失）视为假。 */
    public static boolean boolParam(PluginHttpRequest request, String key) {
        String raw = first(request, key).trim();
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    /** 通用列表查询条件：用户端与管理端共用，但不含任何跨用户范围参数。 */
    public static FeedbackQuery query(PluginHttpRequest request) {
        return new FeedbackQuery()
                .keyword(first(request, "keyword"))
                .status(statusOrNull(first(request, "status")))
                .type(typeOrNull(first(request, "type")))
                .severity(severityOrNull(first(request, "severity")))
                .assigneeId(first(request, "assigneeId"))
                .unassignedOnly(boolParam(request, "unassigned") ? Boolean.TRUE : null)
                .openOnly(boolParam(request, "open") ? Boolean.TRUE : null)
                .from(timeParam(request, "from"))
                .to(timeParam(request, "to"))
                .page(pageParam(request))
                .size(sizeParam(request, 10));
    }

    /** 管理端查询：只在这里附加跨用户的提交人筛选。 */
    public static FeedbackQuery adminQuery(PluginHttpRequest request) {
        return query(request).authorId(first(request, "authorId"));
    }

    public static List<String> segments(String path) {
        String trimmed = path == null ? "" : path;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : trimmed.split("/")) {
            segments.add(decode(segment));
        }
        return segments;
    }

    /** 锚点段之后的下一段，不存在返回 null。 */
    public static String segmentAfter(String path, String anchor) {
        List<String> segments = segments(path);
        for (int i = 0; i < segments.size() - 1; i++) {
            if (anchor.equals(segments.get(i))) {
                return segments.get(i + 1);
            }
        }
        return null;
    }

    public static String decode(String value) {
        return value == null ? "" : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** 统一异常映射：资源不存在 → 404，参数/状态错误 → 400。 */
    public static PluginHttpResponse guard(Supplier<PluginHttpResponse> handler) {
        try {
            return handler.get();
        }
        catch (FeedbackNotFoundException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return PluginHttpResponse.rawJson(400, Map.of("message", e.getMessage()));
        }
    }

    /** 空值、all 与非法值都按「不过滤」处理，避免筛选器误传导致整页为空。 */
    private static FeedbackStatus statusOrNull(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return FeedbackStatus.of(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static FeedbackType typeOrNull(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return FeedbackType.of(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static FeedbackSeverity severityOrNull(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return FeedbackSeverity.of(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int clampInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }
}
