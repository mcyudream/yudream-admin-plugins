package online.yudream.base.plugin.activityproof.application.dto;

public record MyParticipationDTO(
        String activityId,
        String title,
        String coverUrl,
        long activityStart,
        long activityEnd,
        String activityStatus,
        String status,
        long joinedAt,
        long cancelledAt,
        String verifyStatus,
        long verifiedAt,
        String verifyNote
) {
}
