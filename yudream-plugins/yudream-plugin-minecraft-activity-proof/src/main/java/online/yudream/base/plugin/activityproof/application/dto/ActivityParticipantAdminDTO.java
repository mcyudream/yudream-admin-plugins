package online.yudream.base.plugin.activityproof.application.dto;

public record ActivityParticipantAdminDTO(
        String activityId,
        String userId,
        String username,
        String studentName,
        String studentNo,
        String className,
        String college,
        String playerId,
        String playerName,
        String status,
        long joinedAt,
        long cancelledAt,
        String verifyStatus,
        long verifiedAt,
        String verifyNote
) {
}
