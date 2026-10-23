package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 打包定义：把一组文档类型按活动粒度/周期粒度组装成目录结构的 zip。 */
public record OfficePackage(
        String id,
        String code,
        String name,
        String dirPattern,
        List<Item> items,
        boolean enabled,
        long updatedAt
) {

    public OfficePackage {
        code = require(code, "打包定义编码不能为空");
        if (!code.matches("[a-z][a-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("打包定义编码只能用小写字母/数字/下划线/中划线：" + code);
        }
        name = require(name, "打包定义名称不能为空");
        dirPattern = text(dirPattern);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static OfficePackage create(String code, String name, String dirPattern, List<Item> items) {
        return new OfficePackage(UUID.randomUUID().toString(), code, name, dirPattern, items, true,
                System.currentTimeMillis());
    }

    /** 打包项：target = PER_ACTIVITY（逐活动）或 PER_MONTH（周期一次）。 */
    public record Item(String docTypeCode, String target, String subDir) {
        public Item {
            docTypeCode = require(docTypeCode, "打包项缺少文档类型");
            target = target == null || target.isBlank()
                    ? "PER_MONTH" : target.trim().toUpperCase(Locale.ROOT);
            if (!"PER_ACTIVITY".equals(target) && !"PER_MONTH".equals(target)) {
                throw new IllegalArgumentException("打包项 target 只能是 PER_ACTIVITY 或 PER_MONTH");
            }
            subDir = text(subDir);
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
