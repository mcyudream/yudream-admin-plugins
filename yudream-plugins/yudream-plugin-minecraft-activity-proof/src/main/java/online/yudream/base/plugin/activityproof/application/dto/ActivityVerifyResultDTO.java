package online.yudream.base.plugin.activityproof.application.dto;

public record ActivityVerifyResultDTO(
        String activityId,
        long total,
        long passed,
        long failed
) {
}
