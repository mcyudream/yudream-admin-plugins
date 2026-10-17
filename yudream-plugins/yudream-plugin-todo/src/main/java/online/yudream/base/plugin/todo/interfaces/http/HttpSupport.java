package online.yudream.base.plugin.todo.interfaces.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.todo.application.TodoNotFoundException;
import online.yudream.base.plugin.todo.domain.enumerate.TodoPriority;
import online.yudream.base.plugin.todo.domain.enumerate.TodoStatus;
import online.yudream.base.plugin.todo.domain.valobj.TodoQuery;

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

    /** 毫秒时间戳参数；非法或缺失时回退。 */
    public static long longParam(PluginHttpRequest request, String key, long fallback) {
        String raw = first(request, key);
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static TodoQuery query(PluginHttpRequest request) {
        return new TodoQuery()
                .keyword(first(request, "keyword"))
                .status(statusOrNull(first(request, "status")))
                .priority(priorityOrNull(first(request, "priority")))
                .overdueOnly(boolParam(request, "overdue"))
                .archived(archivedParam(request))
                .page(pageParam(request))
                .size(sizeParam(request, 10));
    }

    /** 归档三态查询参数：true/false 显式过滤，缺失按不过滤（管理端全量）。 */
    public static Boolean archivedParam(PluginHttpRequest request) {
        String raw = first(request, "archived").trim();
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 布尔查询参数：true/1 视为真，缺失或其余取值视为假。 */
    public static boolean boolParam(PluginHttpRequest request, String key) {
        String raw = first(request, key).trim();
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    /** 管理端查询才允许携带目标用户过滤条件。 */
    public static TodoQuery adminQuery(PluginHttpRequest request) {
        return query(request).ownerId(first(request, "ownerId"));
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
        catch (TodoNotFoundException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return PluginHttpResponse.rawJson(400, Map.of("message", e.getMessage()));
        }
    }

    private static TodoStatus statusOrNull(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return TodoStatus.of(raw);
    }

    private static TodoPriority priorityOrNull(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return TodoPriority.of(raw);
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
