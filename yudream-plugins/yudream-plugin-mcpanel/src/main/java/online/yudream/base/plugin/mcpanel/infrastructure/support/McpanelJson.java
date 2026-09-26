package online.yudream.base.plugin.mcpanel.infrastructure.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 插件内共享 ObjectMapper（record 兼容、忽略未知字段——协议演进不破版）。
 */
public final class McpanelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);

    private McpanelJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T read(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body == null || body.isBlank() ? "{}" : body, type);
        } catch (Exception error) {
            throw new IllegalArgumentException("请求体 JSON 解析失败：" + error.getMessage(), error);
        }
    }

    public static MapReader readMap(String body) {
        try {
            return new MapReader(body == null || body.isBlank()
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(body));
        } catch (Exception error) {
            throw new IllegalArgumentException("请求体 JSON 解析失败：" + error.getMessage(), error);
        }
    }

    public record MapReader(com.fasterxml.jackson.databind.JsonNode node) {

        public String string(String field) {
            var value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }

        public Boolean bool(String field) {
            var value = node.get(field);
            return value == null || value.isNull() || !value.isBoolean() ? null : value.asBoolean();
        }

        public java.util.List<String> stringList(String field) {
            var value = node.get(field);
            if (value == null || value.isNull() || !value.isArray()) {
                return java.util.List.of();
            }
            java.util.List<String> items = new java.util.ArrayList<>();
            value.forEach(item -> items.add(item.asText()));
            return items;
        }
    }
}
