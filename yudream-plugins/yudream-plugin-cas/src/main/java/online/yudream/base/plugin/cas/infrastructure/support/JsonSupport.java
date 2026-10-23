package online.yudream.base.plugin.cas.infrastructure.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonSupport() {
    }

    public static <T> T read(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body == null || body.isBlank() ? "{}" : body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("请求 JSON 解析失败：" + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(String body) {
        try {
            return MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("响应 JSON 解析失败：" + e.getOriginalMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败：" + e.getOriginalMessage(), e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
