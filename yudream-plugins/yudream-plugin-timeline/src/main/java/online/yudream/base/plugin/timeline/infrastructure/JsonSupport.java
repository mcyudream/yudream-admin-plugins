package online.yudream.base.plugin.timeline.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 请求体 JSON 解析（插件 HTTP 层 body 为 String）。 */
public final class JsonSupport {
    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public <T> T read(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        try {
            return mapper.readValue(body, type);
        }
        catch (Exception e) {
            throw new IllegalArgumentException("请求体不是合法的 JSON：" + e.getMessage());
        }
    }

    /** 解析为树模型，供发布开关等极小载荷的容错读取。 */
    public JsonNode readTree(String raw) {
        try {
            return mapper.readTree(raw);
        }
        catch (Exception e) {
            throw new IllegalArgumentException("内容不是合法的 JSON：" + e.getMessage());
        }
    }
}
