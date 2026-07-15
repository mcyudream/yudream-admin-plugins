package online.yudream.plugin.codextasknotify.interfaces.support;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    public static <T> T read(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body == null ? "{}" : body, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("请求 JSON 格式无效", exception);
        }
    }
}
