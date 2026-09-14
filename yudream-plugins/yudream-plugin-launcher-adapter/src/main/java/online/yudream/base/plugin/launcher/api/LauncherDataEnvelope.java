package online.yudream.base.plugin.launcher.api;

import java.util.List;

/**
 * 协议 v2 数据信封（§4.2）。provider 在 {@link LauncherProvider#fetchData} 返回本类型即按信封下发；
 * 返回其它任意类型（v1 行为）由适配器自动包装为 {@code payload}、无动作。
 *
 * @param payload     渲染器定义的条目形状（List 或 Map）
 * @param total       分页总数；null 表示不分页（信封省略 page.total）
 * @param actions     页面级动作（已声明权限要求，适配器负责过滤）
 * @param itemActions 条目级动作
 */
public record LauncherDataEnvelope(
        Object payload,
        Long total,
        List<LauncherAction> actions,
        List<LauncherAction> itemActions
) {
    public LauncherDataEnvelope {
        actions = actions == null ? List.of() : List.copyOf(actions);
        itemActions = itemActions == null ? List.of() : List.copyOf(itemActions);
    }

    /**
     * 无分页、无动作的简单数据。
     */
    public static LauncherDataEnvelope of(Object payload) {
        return new LauncherDataEnvelope(payload, null, List.of(), List.of());
    }

    /**
     * 分页数据。
     */
    public static LauncherDataEnvelope paged(Object payload, long total) {
        return new LauncherDataEnvelope(payload, total, List.of(), List.of());
    }
}
