package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record ActivityDTO(
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
        String deptMode,
        List<String> allowedDeptIds,
        List<String> allowedDeptNames,
        List<ActivityBindingDTO> bindings,
        long participantCount,
        long verifiedCount,
        String createdBy,
        long createdAt,
        long updatedAt,
        long publishedAt
) {
}
