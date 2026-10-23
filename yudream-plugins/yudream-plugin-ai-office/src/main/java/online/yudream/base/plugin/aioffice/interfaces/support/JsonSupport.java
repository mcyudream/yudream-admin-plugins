package online.yudream.base.plugin.aioffice.interfaces.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** 请求体 JSON 解析（与 activity-proof 的 JsonSupport 同模式）。 */
public class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "{}" : json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("请求 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    /** 把对象序列化为可返回的 Map/JSON 节点（预设导出等）。 */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败：" + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json) {
        return MAPPER.convertValue(read(json, Map.class), Map.class);
    }
}
