package online.yudream.base.plugin.minecraft.interfaces.request;

import java.util.List;

public record MinecraftPlayerSnapshotRequest(
        Long observedAt,
        List<Player> players
) {
    public record Player(String playerId, String uuid, String playerName, String name) {
    }
}
