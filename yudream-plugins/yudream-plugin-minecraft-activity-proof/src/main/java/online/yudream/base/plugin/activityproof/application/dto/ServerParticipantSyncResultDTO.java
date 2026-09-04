package online.yudream.base.plugin.activityproof.application.dto;

/**
 * 服务器自动参与同步结果汇总。
 */
public record ServerParticipantSyncResultDTO(
        String activityId,
        long scanned,
        long resolved,
        long added,
        long skippedExisting,
        long skippedExcluded,
        long unresolved,
        long verifiedPassed,
        long verifiedFailed
) {
}
