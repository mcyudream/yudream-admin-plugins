package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record UserActivityDTO(
        String id,
        String title,
        String summary,
        String description,
        String coverUrl,
        long signupStart,
        long signupEnd,
        long activityStart,
        long activityEnd,
        String status,
        boolean deptRestricted,
        List<String> allowedDeptNames,
        List<String> requirements,
        List<UserRequirementDTO> requirementDetails,
        long participantCount,
        boolean eligible,
        String joinDisabledReason,
        String participationStatus,
        long joinedAt,
        String verifyStatus,
        String verifyNote
) {
}
