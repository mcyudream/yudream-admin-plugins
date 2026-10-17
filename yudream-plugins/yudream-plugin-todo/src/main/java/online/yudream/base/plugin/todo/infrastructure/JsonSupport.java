package online.yudream.base.plugin.todo.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON 读写支持：请求体反序列化与文档字段读取共用。 */
public final class JsonSupport {

    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public <T> T read(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        try {
            return mapper.readValue(body, type);
        }
        catch (Exception e) {
            // 带上原始原因：只回一句「格式不正确」无法定位是字段类型不匹配还是绑定失败
            String reason = e.getMessage() == null ? "" : e.getMessage().trim();
            if (reason.length() > 200) {
                reason = reason.substring(0, 200);
            }
            throw new IllegalArgumentException(
                    reason.isEmpty() ? "请求体格式不正确" : "请求体格式不正确：" + reason);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
