package online.yudream.base.plugin.minecraft.application.dto;

import java.util.List;

public record MinecraftEndpointStatusDTO(
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
        List<PlayerDTO> players,
        long checkedAt
) {

    public record PlayerDTO(String id, String name) {
    }
}
