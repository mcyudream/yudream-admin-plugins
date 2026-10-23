package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 经费类别字典：声明收入/支出方向下的一级类别与其登记字段面。 */
public record OfficeCategory(
        String id,
        String direction,
        String code,
        String name,
        List<String> fields,
        int sort,
        boolean enabled
) {

    public OfficeCategory {
        direction = require(direction, "类别方向不能为空").toLowerCase(Locale.ROOT);
        if (!"income".equals(direction) && !"expense".equals(direction)) {
            throw new IllegalArgumentException("类别方向只能是 income 或 expense");
        }
        code = require(code, "类别编码不能为空");
        name = require(name, "类别名称不能为空");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static OfficeCategory create(String direction, String code, String name, List<String> fields, int sort) {
        return new OfficeCategory(UUID.randomUUID().toString(), direction, code, name, fields, sort, true);
    }

    public boolean income() {
        return "income".equals(direction);
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
