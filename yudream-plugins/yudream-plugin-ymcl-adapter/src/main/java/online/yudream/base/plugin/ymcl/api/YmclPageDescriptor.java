package online.yudream.base.plugin.ymcl.api;

import java.util.Map;

/**
 * YAP §6.5 页面贡献描述。
 *
 * 字段名与启动器侧 manifest 契约（YmclPageDescriptor，snake_case）一致，
 * 由适配器序列化进 GET /v1/manifest 的 pages 数组。
 * icon 为可选的 Remix 图标名（如 {@code i-ri:server-line}），供管理台
 * 导航树/注册表与启动器侧栏展示；为空时回落默认图标。
 */
public record YmclPageDescriptor(
        String id,
        String renderer,
        String title,
        String icon,
        String dataSource,
        int sort,
        String requiredPermission,
        Map<String, Object> params,
        Map<String, Object> bundle) {
}
