package online.yudream.base.plugin.mcpet.application.cmd;

/**
 * 保存用户宠物偏好。除 mode 外的字段为 null 表示沿用当前值，
 * 便于挂件拖拽结束后只回传坐标；clearPosition=true 显式清除拖拽位置回到停靠角。
 */
public record PetPreferenceSaveCmd(
        String mode,
        String playerName,
        String closetItemId,
        Integer size,
        String corner,
        Double positionX,
        Double positionY,
        Boolean hidden,
        Boolean clearPosition
) {
}
