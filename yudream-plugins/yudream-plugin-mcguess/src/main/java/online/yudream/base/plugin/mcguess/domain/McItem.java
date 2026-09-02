package online.yudream.base.plugin.mcguess.domain;

/**
 * Minecraft 物品条目（数据版本由 mc-wiki 已发布版本供给，见 McCatalog.version()）。
 */
public record McItem(String id, String en, String zh, boolean craftable, boolean icon) {
}
