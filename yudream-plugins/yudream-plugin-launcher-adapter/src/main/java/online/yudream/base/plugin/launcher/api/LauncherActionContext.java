package online.yudream.base.plugin.launcher.api;

import java.util.List;
import java.util.Map;

/**
 * 启动器动作调用上下文（只读）。provider 在 {@link LauncherProvider#executeAction} 时收到。
 *
 * @param userId      当前启动器登录的站点用户 id（字符串；匿名连接时为 null）
 * @param permissions 当前用户已解析的权限码列表（与 {@link LauncherContextView#permissions()} 同源）
 * @param payload     动作参数（启动器按动作声明的 schema 透传，provider 自行解析约定的 key）
 */
public record LauncherActionContext(
        String userId,
        List<String> permissions,
        Map<String, Object> payload
) {
}
