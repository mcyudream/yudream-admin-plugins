package online.yudream.base.plugin.shop.infrastructure.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DocumentSupport {

    private DocumentSupport() {
    }

    /** 文档存储拒绝 null 值：递归移除 null 键与列表中的 null 项。 */
    public static Map<String, Object> stripNulls(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value != null) {
                result.put(key, stripValue(value));
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object stripValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stripNulls((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    items.add(stripValue(item));
                }
            }
            return items;
        }
        return value;
    }
}
