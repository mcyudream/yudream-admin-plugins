package online.yudream.base.plugin.mcnews.interfaces.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 控制器共享的 HTTP 小工具：查询参数、路径段与异常到状态码的映射。 */
public final class HttpSupport {
    private HttpSupport() {
    }

    public static String first(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    public static int pageParam(PluginHttpRequest request) {
        return intParam(first(request, "page"), 1, 1, 100000);
    }

    public static int sizeParam(PluginHttpRequest request, int fallback) {
        return intParam(first(request, "size"), fallback, 1, 100);
    }

    /** 取插件相对路径的第 index 段（/admin/sources/{id} 的 id 为第 2 段）。 */
    public static String segment(PluginHttpRequest request, int index) {
        String trimmed = request.path() == null ? "" : request.path().trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String[] segments = trimmed.split("/");
        if (index < 0 || index >= segments.length) {
            throw new IllegalArgumentException("路径缺少资源 ID");
        }
        return URLDecoder.decode(segments[index], StandardCharsets.UTF_8);
    }

    /**
     * 统一异常映射：参数/状态错误 → 400，其余异常冒泡给宿主全局处理器（500）。
     * 响应体携带 message 字段，前端标准错误反馈直接可用。
     */
    public static PluginHttpResponse guard(Supplier<PluginHttpResponse> handler) {
        try {
            return handler.get();
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return PluginHttpResponse.rawJson(400, java.util.Map.of("message", e.getMessage()));
        }
    }

    private static int intParam(String raw, int fallback, int min, int max) {
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
