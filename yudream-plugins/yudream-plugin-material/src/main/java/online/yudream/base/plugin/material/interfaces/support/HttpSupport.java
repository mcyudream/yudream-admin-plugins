package online.yudream.base.plugin.material.interfaces.support;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import online.yudream.base.plugin.material.application.NotFoundException;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

/** 控制器共享的 HTTP 小工具：查询参数、路径段、二进制响应、404 映射。 */
public final class HttpSupport {
    private HttpSupport() {
    }

    public static String first(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    public static String header(PluginHttpRequest request, String name) {
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    public static int pageParam(PluginHttpRequest request) {
        return intParam(first(request, "page"), 1, 1, 100000);
    }

    public static int sizeParam(PluginHttpRequest request, int fallback) {
        return intParam(first(request, "size"), fallback, 1, 100);
    }

    public static Integer optionalVersion(PluginHttpRequest request) {
        String raw = first(request, "version");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("version 必须是数字");
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

    public static String lastSegment(String path) {
        List<String> segments = segments(path);
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
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

    /** Range 支持：kkFileView 与浏览器媒体标签会分段拉取。 */
    public static PluginHttpResponse rangeAware(String range, String contentType, byte[] bytes) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "private, max-age=60");
        if (range == null || !range.startsWith("bytes=")) {
            return new PluginHttpResponse(200, headers, contentType, bytes, false);
        }
        String spec = range.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return new PluginHttpResponse(200, headers, contentType, bytes, false);
        }
        try {
            int total = bytes.length;
            long start = spec.substring(0, dash).isEmpty() ? 0 : Long.parseLong(spec.substring(0, dash).trim());
            long end = spec.substring(dash + 1).isEmpty() ? total - 1L : Long.parseLong(spec.substring(dash + 1).trim());
            if (start > end || start >= total) {
                Map<String, String> invalid = new HashMap<>(headers);
                invalid.put("Content-Range", "bytes */" + total);
                return new PluginHttpResponse(416, invalid, "application/json",
                        Map.of("message", "Range 不合法"), false);
            }
            end = Math.min(end, total - 1L);
            byte[] slice = java.util.Arrays.copyOfRange(bytes, (int) start, (int) end + 1);
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            return new PluginHttpResponse(206, headers, contentType, slice, false);
        }
        catch (NumberFormatException e) {
            return new PluginHttpResponse(200, headers, contentType, bytes, false);
        }
    }
}
