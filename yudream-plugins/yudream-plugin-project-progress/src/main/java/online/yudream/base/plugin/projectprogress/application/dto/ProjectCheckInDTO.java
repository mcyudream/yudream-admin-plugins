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
                                       long totalOnlineMillis, long totalAfkMillis, long effectiveOnlineMillis,
                                       long periodStart, long periodEnd) {
    }
}
