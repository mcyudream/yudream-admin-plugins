package online.yudream.base.plugin.minecraft.domain.aggregate;

import java.util.UUID;

public record MinecraftPlayerActivityEvent(
        String id,
        String serverId,
        String playerId,
        String playerName,
        Type type,
        long occurredAt
) {
    public enum Type { JOIN, QUIT, AFK_START, AFK_END }

    public MinecraftPlayerActivityEvent {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        serverId = requireText(serverId, "Server ID is required");
        playerId = requireText(playerId, "Player ID is required");
        playerName = playerName == null ? "" : playerName.trim();
        if (type == null) throw new IllegalArgumentException("Activity event type is required");
        occurredAt = occurredAt <= 0 ? System.currentTimeMillis() : occurredAt;
    }

    public static MinecraftPlayerActivityEvent create(String serverId, String playerId, String playerName, Type type, long occurredAt) {
        return new MinecraftPlayerActivityEvent(null, serverId, playerId, playerName, type, occurredAt);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
