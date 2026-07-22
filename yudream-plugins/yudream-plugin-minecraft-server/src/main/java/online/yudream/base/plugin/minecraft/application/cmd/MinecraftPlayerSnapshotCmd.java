package online.yudream.base.plugin.minecraft.application.cmd;

import java.util.List;

public record MinecraftPlayerSnapshotCmd(
        Long observedAt,
        List<Player> players
) {
    public MinecraftPlayerSnapshotCmd {
        players = players == null ? List.of() : List.copyOf(players);
    }

    public record Player(String playerId, String playerName) {
    }
}
