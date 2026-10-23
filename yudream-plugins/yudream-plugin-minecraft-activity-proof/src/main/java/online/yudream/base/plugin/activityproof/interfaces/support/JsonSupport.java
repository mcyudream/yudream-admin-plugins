package online.yudream.base.plugin.activityproof.interfaces.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /** 宽松读取 JSON 对象：body 为空返回空 map，用于轻量载荷（如平台文件 fileId）。 */
    public static Map<String, Object> readMap(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "{}" : json, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("请求 JSON 解析失败：" + e.getMessage(), e);
        }
    }
}
