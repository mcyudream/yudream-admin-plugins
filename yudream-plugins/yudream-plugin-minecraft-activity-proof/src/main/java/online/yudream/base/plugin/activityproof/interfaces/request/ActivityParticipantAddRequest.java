package online.yudream.base.plugin.activityproof.interfaces.request;

public record ActivityParticipantAddRequest(
        String userId,
        Boolean passed,
        String note
) {
}
