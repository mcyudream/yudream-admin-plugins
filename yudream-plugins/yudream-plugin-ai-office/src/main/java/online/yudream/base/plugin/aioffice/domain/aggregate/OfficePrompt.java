package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.UUID;

/** AI 提示词：按编码被文档类型映射引用；user 文本里可用 {点路径} 引用上下文变量。 */
public record OfficePrompt(
        String id,
        String code,
        String name,
        String system,
        String user,
        long updatedAt
) {

    public OfficePrompt {
        code = require(code, "提示词编码不能为空");
        if (!code.matches("[a-z][a-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("提示词编码只能用小写字母/数字/下划线/中划线：" + code);
        }
        name = require(name, "提示词名称不能为空");
        system = text(system);
        user = require(user, "提示词正文不能为空");
    }

    public static OfficePrompt create(String code, String name, String system, String user) {
        return new OfficePrompt(UUID.randomUUID().toString(), code, name, system, user,
                System.currentTimeMillis());
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
