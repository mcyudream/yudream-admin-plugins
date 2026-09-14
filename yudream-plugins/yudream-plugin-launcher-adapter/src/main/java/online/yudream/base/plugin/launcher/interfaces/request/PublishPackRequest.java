package online.yudream.base.plugin.launcher.interfaces.request;

/**
 * 创建新整合包。
 */
public record PublishPackRequest(
        String packId,
        String name,
        String description,
        String icon
) {
}
