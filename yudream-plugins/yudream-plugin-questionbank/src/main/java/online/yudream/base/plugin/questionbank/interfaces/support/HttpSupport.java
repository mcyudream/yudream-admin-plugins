package online.yudream.base.plugin.questionbank.interfaces.support;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import online.yudream.base.plugin.questionbank.application.NotFoundException;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

/** 控制器共享的 HTTP 小工具：查询参数、路径段、下载响应、404 映射。 */
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

    /** 锚点段之后的下一段（不存在返回 null）。 */
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
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public static String requireUserId(PluginHttpRequest request) {
        PluginPrincipal principal = request.principal();
        if (principal == null || principal.userId() == null) {
            throw new IllegalStateException("未登录或会话已失效");
        }
        return String.valueOf(principal.userId());
    }

    /** 统一把 NotFoundException 映射为 404 JSON。 */
    public static PluginHttpResponse guard(Supplier<PluginHttpResponse> handler) {
        try {
            return handler.get();
        }
        catch (NotFoundException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        }
    }

    public static PluginHttpResponse download(String filename, String contentType, byte[] bytes) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        headers.put("Cache-Control", "no-cache");
        return new PluginHttpResponse(200, headers, contentType, bytes, false);
    }
}
