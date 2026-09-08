package online.yudream.base.plugin.mcpet.interfaces.request;

/** 管理端保存全局默认配置请求；未传字段沿用当前值。 */
public record PetDefaultsSaveRequest(
        String mode,
        String playerName,
        String textureHash,
        String model,
        Boolean animation,
        String clickAction,
        Integer size,
        String corner
) {
}
