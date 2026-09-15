package online.yudream.base.plugin.ymcl.api;

import java.util.Map;

/**
 * YAP §6.5 页面贡献描述。
 *
 * 字段名与启动器侧 manifest 契约（YmclPageDescriptor，snake_case）一致，
 * 由适配器序列化进 GET /v1/manifest 的 pages 数组。
 */
public record YmclPageDescriptor(
        String id,
        String renderer,
        String title,
        String dataSource,
        int sort,
        String requiredPermission,
        Map<String, Object> params,
        Map<String, Object> bundle) {
}
