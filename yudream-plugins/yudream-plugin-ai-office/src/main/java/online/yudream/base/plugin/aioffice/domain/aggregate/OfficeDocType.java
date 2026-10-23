package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 文档类型：绑定宿主 Word 模板与占位符映射的站点自有定义。 */
public record OfficeDocType(
        String id,
        String code,
        String name,
        String stage,
        String wordTemplateCode,
        List<String> schemaCodes,
        List<Mapping> mappings,
        String fileNamePattern,
        boolean enabled,
        long updatedAt
) {

    public OfficeDocType {
        code = require(code, "文档类型编码不能为空");
        if (!code.matches("[a-z][a-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("文档类型编码只能用小写字母/数字/下划线/中划线：" + code);
        }
        name = require(name, "文档类型名称不能为空");
        stage = stage == null || stage.isBlank() ? "general" : stage.trim().toLowerCase(java.util.Locale.ROOT);
        wordTemplateCode = text(wordTemplateCode);
        schemaCodes = schemaCodes == null ? List.of() : List.copyOf(schemaCodes);
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        fileNamePattern = text(fileNamePattern);
    }

    public static OfficeDocType create(String code, String name, String stage, String wordTemplateCode,
                                       List<String> schemaCodes, List<Mapping> mappings, String fileNamePattern) {
        return new OfficeDocType(UUID.randomUUID().toString(), code, name, stage, wordTemplateCode,
                schemaCodes, mappings, fileNamePattern, true, System.currentTimeMillis());
    }

    /**
     * 占位符映射：渲染数据里的 var ← 数据源表达式。
     * source 取值域：ctx:点路径（上下文取值）| list:点路径（列表/表格循环）| ai:提示词编码（AI 起草）| text:字面量。
     * itemMapping 仅 list 映射使用：行内变量 ← 针对每个元素的数据源表达式。
     */
    public record Mapping(String var, String source, String transform, Map<String, String> itemMapping) {
        public Mapping {
            var = require(var, "映射变量名不能为空");
            source = require(source, "映射数据源不能为空");
            transform = text(transform);
            itemMapping = itemMapping == null ? Map.of() : new LinkedHashMap<>(itemMapping);
        }
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
