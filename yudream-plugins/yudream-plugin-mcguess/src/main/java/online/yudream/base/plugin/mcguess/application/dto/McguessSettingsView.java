package online.yudream.base.plugin.mcguess.application.dto;

import java.util.List;

/**
 * 管理端插件设置视图。
 *
 * @param gameVersion       钉住的 MC 数据版本（空 = 跟随 mc-wiki 默认发布版本）
 * @param effectiveVersion  实际生效的数据版本（目录与图标共用）；provider 不可用或从未发布时为 null
 * @param defaultVersion    mc-wiki 默认发布版本（最新发布）；provider 不可用时为 null
 * @param publishedVersions mc-wiki 全部已发布版本（版本选择器 options，按发布先后排序）
 * @param pinnedMissing     钉住的版本已被取消发布、当前降级回默认
 * @param providerAvailable mc-wiki provider 当前是否可用
 */
public record McguessSettingsView(String gameVersion, String effectiveVersion, String defaultVersion,
                                  List<String> publishedVersions, boolean pinnedMissing, boolean providerAvailable) {
}
