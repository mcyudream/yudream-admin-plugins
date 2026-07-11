package online.yudream.base.plugin.projectprogress.domain.valobj;

public record ProjectMinecraftEvidence(
        String serverId,
        String playerId,
        String playerName,
        long totalOnlineMillis,
        long totalAfkMillis,
        long effectiveOnlineMillis,
        long periodStart,
        long periodEnd
) {

    public ProjectMinecraftEvidence {
        serverId = serverId == null ? "" : serverId.trim();
        playerId = playerId == null ? "" : playerId.trim();
        playerName = playerName == null ? "" : playerName.trim();
        totalOnlineMillis = Math.max(totalOnlineMillis, 0);
        totalAfkMillis = Math.max(totalAfkMillis, 0);
        effectiveOnlineMillis = Math.max(effectiveOnlineMillis, 0);
        periodStart = Math.max(periodStart, 0);
        periodEnd = Math.max(periodEnd, periodStart);
    }
}
