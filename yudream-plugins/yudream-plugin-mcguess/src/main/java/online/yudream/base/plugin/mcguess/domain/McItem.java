package online.yudream.base.plugin.mcguess.domain;

/**
 * Minecraft 物品条目（数据截止 JE 1.20.5）。
 */
public record McItem(String id, String en, String zh, boolean craftable, boolean icon) {
}
