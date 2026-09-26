package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record ActivityBindingDTO(
        String type,
        String serverId,
        String serverName,
        String subServer,
        int minOnlineMinutes,
        boolean includeAfk,
        boolean autoJoin,
        String formCode,
        String formName,
        List<ActivityBindingParamDTO> params,
        String expression,
        double minScore,
        Double maxScore,
        String requirementText
) {
}
