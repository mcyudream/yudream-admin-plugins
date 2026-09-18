package online.yudream.base.plugin.projectprogress.interfaces.res;

import java.util.List;

public record ProjectCheckInRes(
        String id,
        String projectId,
        String detailId,
        String userId,
        String type,
        String summary,
        List<FileEvidenceRes> files,
        LocationRes location,
        MinecraftEvidenceRes minecraft,
        String reviewStatus,
        String reviewedByUserId,
        Long reviewedAt,
        long createdAt
) {
    public record FileEvidenceRes(String objectKey, String filename, String contentType, long size, boolean image) {
    }

    public record LocationRes(String address, Double latitude, Double longitude) {
    }

    public record MinecraftEvidenceRes(String serverId, String playerId, String playerName,
                                       String subServer,
                                       long totalOnlineMillis, long totalAfkMillis, long effectiveOnlineMillis,
                                       long periodStart, long periodEnd,
                                       List<MinecraftSubServerRes> subServers) {
    }

    /** 一台子服上的累计时长；{@code name} 为空表示该记录没有子服维度。 */
    public record MinecraftSubServerRes(String name, long onlineMillis, long afkMillis) {
    }
}
