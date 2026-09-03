package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record ActivityTemplateMembersDTO(
        String templateId,
        List<ActivityUserOptionDTO> members,
        long updatedAt,
        String updatedBy
) {
}
