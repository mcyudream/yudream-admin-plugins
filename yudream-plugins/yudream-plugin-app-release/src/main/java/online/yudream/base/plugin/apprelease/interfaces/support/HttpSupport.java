package online.yudream.base.plugin.apprelease.interfaces.support;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 控制器共享的 HTTP 小工具：查询参数、路径段与异常到状态码的映射。 */
public final class HttpSupport {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private HttpSupport() {
    }

    public static String first(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    /** 取插件相对路径的第 index 段（/admin/releases/{id} 的 id 为第 2 段）。 */
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            return PluginHttpResponse.rawJson(400, java.util.Map.of("message", e.getMessage()));
        }
    }

    public static int intParam(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 解析 JSON 请求体为节点（空 body 视为空对象）。 */
    public static com.fasterxml.jackson.databind.JsonNode json(PluginHttpRequest request) {
        String body = request.body();
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(body);
            return node == null || node.isNull() ? MAPPER.createObjectNode() : node;
        } catch (IOException e) {
            throw new IllegalArgumentException("请求体不是有效 JSON");
        }
    }

    /** JSON 节点容错读取：字段缺失/null 时返回回退值。 */
    public static String textField(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    public static int intField(com.fasterxml.jackson.databind.JsonNode node, String field, int fallback) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.intValue();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean boolField(com.fasterxml.jackson.databind.JsonNode node, String field, boolean fallback) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return Boolean.parseBoolean(value.asText());
    }
}
