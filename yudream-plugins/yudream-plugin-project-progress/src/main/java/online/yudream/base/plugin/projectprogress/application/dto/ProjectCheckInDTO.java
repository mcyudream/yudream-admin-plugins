package online.yudream.base.plugin.projectprogress.application.dto;

import java.util.List;

public record ProjectCheckInDTO(
        String id,
        String projectId,
        String detailId,
        String userId,
        String type,
        String summary,
        List<FileEvidenceDTO> files,
        LocationDTO location,
        MinecraftEvidenceDTO minecraft,
        String reviewStatus,
        String reviewedByUserId,
        Long reviewedAt,
        long createdAt
) {
    public record FileEvidenceDTO(String objectKey, String filename, String contentType, long size, boolean image) {
    }

    public record LocationDTO(String address, Double latitude, Double longitude) {
    }

    public record MinecraftEvidenceDTO(String serverId, String playerId, String playerName,
                                       String subServer,
                                       long totalOnlineMillis, long totalAfkMillis, long effectiveOnlineMillis,
                                       long periodStart, long periodEnd,
                                       List<MinecraftSubServerDTO> subServers) {
    }

    /**
     * 一台子服上的累计时长；{@code name} 为空表示该记录没有子服维度。
     *
     * <p>与同级的三个 millis 字段口径不同：那三个是打卡周期内的窗口值，本明细是该玩家在这台子服上的
     * 全部历史累计，只作附注。
     */
    public record MinecraftSubServerDTO(String name, long onlineMillis, long afkMillis) {
    }
}
