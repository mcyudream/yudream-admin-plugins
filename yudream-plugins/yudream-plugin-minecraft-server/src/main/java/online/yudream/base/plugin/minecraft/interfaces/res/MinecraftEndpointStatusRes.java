package online.yudream.base.plugin.minecraft.interfaces.res;

public record MinecraftEndpointStatusRes(
        String endpointId,
        String status,
        int onlinePlayers,
        int maxPlayers,
        String versionName,
        Integer protocolId,
        Long ping,
        String motd,
        String favicon,
        String errorMessage,
        long checkedAt
) {
}
