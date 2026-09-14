package online.yudream.base.plugin.launcher.api;

import java.util.List;
import java.util.Map;

/**
 * 启动器侧的请求上下文快照（只读）。provider 在 {@link LauncherProvider#fetchData} 时收到。
 *
 * @param userId      当前启动器登录的站点用户 id（字符串；匿名连接时为 null）
 * @param page        当前页码，从 1 开始
 * @param pageSize    每页大小
 * @param query       原始查询参数（搜索词、筛选条件等），provider 自行解析约定的 key
 * @param permissions 当前用户已解析的权限码列表（sa-token 会话为全量 RBAC、OAuth/API Key 为已授权 scope；
 *                    匿名连接为空列表）。provider 用它裁剪数据级动作，禁止自己再查权限服务
 */
public record LauncherContextView(
        String userId,
        int page,
        int pageSize,
        Map<String, String> query,
        List<String> permissions
) {
    /**
     * 协议 v1 签名：无 permissions（视为匿名空权限）。
     */
    public LauncherContextView(String userId, int page, int pageSize, Map<String, String> query) {
        this(userId, page, pageSize, query, List.of());
    }
}
