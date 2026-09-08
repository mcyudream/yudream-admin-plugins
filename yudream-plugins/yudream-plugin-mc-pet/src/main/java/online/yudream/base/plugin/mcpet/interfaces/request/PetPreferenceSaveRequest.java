package online.yudream.base.plugin.mcpet.interfaces.request;

/** 用户保存宠物偏好请求；归属一律取登录主体，不接受 userId 字段。除 mode 外字段为空表示沿用当前值。 */
public record PetPreferenceSaveRequest(
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
