package online.yudream.base.plugin.activityproof.application.cmd;

public record ActivityParticipantAddCmd(
        String activityId,
        String userId,
        Boolean passed,
        String note
) {
}
