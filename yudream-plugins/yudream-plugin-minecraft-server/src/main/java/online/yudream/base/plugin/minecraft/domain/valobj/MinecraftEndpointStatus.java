package online.yudream.base.plugin.minecraft.domain.valobj;

import java.util.List;

public record MinecraftEndpointStatus(
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
        List<PlayerInfo> players,
        long checkedAt
) {

    public static final int MAX_SAMPLED_PLAYERS = 12;

    public record PlayerInfo(String id, String name) {
    }

    public MinecraftEndpointStatus {
        players = players == null ? List.of() : List.copyOf(players);
    }

    public static MinecraftEndpointStatus offline(String endpointId, String errorMessage) {
        return new MinecraftEndpointStatus(endpointId, "OFFLINE", 0, 0, null, null, null,
                null, null, errorMessage, List.of(), System.currentTimeMillis());
    }

    public static MinecraftEndpointStatus online(String endpointId, int onlinePlayers, int maxPlayers, String versionName,
                                                 Integer protocolId, Long ping, String motd, String favicon,
                                                 List<PlayerInfo> players) {
        List<PlayerInfo> sampled = players == null ? List.of() : players;
        if (sampled.size() > MAX_SAMPLED_PLAYERS) {
            sampled = sampled.subList(0, MAX_SAMPLED_PLAYERS);
        }
        return new MinecraftEndpointStatus(endpointId, "ONLINE", onlinePlayers, maxPlayers, versionName,
                protocolId, ping, motd, favicon, null, sampled, System.currentTimeMillis());
    }
}
