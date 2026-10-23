package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 数据模型（schema）：一组有名字的字段定义，约束档案/数据集记录/映射可选键。 */
public record OfficeSchema(
        String id,
        String code,
        String name,
        boolean builtin,
        List<FieldDef> fields,
        long updatedAt
) {

    public OfficeSchema {
        code = require(code, "schema 编码不能为空");
        if (!code.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("schema 编码只能用小写字母/数字/下划线，且以字母开头");
        }
        name = require(name, "schema 名称不能为空");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static OfficeSchema create(String code, String name, List<FieldDef> fields, boolean builtin) {
        return new OfficeSchema(UUID.randomUUID().toString(), code, name, builtin, fields,
                System.currentTimeMillis());
    }

    /** 字段定义：key 为 ASCII 标识（进文档存储），label 为展示名（可中文）。 */
    public record FieldDef(String key, String label, String type, boolean required,
                           List<String> options) {
        public FieldDef {
            key = require(key, "字段 key 不能为空");
            if (!key.matches("[a-z][a-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("字段 key 只能用小写字母/数字/下划线，且以字母开头：" + key);
            }
            label = require(label, "字段名称不能为空");
            type = type == null || type.isBlank() ? "text" : type.trim().toLowerCase(Locale.ROOT);
            options = options == null ? List.of() : List.copyOf(options);
        }

        public static FieldDef of(String key, String label, String type) {
            return new FieldDef(key, label, type, false, List.of());
        }
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** 常用字段类型。 */
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_LONGTEXT = "longtext";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_MONEY = "money";
    public static final String TYPE_DATE = "date";
    public static final String TYPE_SELECT = "select";
    public static final String TYPE_ATTACHMENTS = "attachments";

    public static List<FieldDef> copyFields(List<FieldDef> fields) {
        return fields == null ? List.of() : new ArrayList<>(fields);
    }
}
