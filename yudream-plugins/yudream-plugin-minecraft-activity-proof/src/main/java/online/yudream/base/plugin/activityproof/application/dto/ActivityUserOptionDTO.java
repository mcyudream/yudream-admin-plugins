package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record ActivityUserOptionDTO(
        String id,
        String username,
        String nickname,
        List<String> deptNames
) {
}
