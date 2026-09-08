package online.yudream.base.plugin.mcpet.application.cmd;

public record PetDefaultsSaveCmd(
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
