package online.yudream.plugin.webcard.interfaces;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public final class JsonSupport {
    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private JsonSupport() { }
    public static <T> T read(String body, Class<T> type) { try { return MAPPER.readValue(body == null || body.isBlank() ? "{}" : body, type); } catch (Exception e) { throw new IllegalArgumentException("请求内容格式无效", e); } }
    public static Map<String, String> stringMap(byte[] bytes) { try { return MAPPER.readValue(bytes, new TypeReference<>() { }); } catch (Exception e) { throw new IllegalArgumentException("密钥内容格式无效", e); } }
    public static byte[] bytes(Object value) { try { return MAPPER.writeValueAsBytes(value); } catch (Exception e) { throw new IllegalArgumentException("内容序列化失败", e); } }
}
