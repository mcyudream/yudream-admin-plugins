package online.yudream.base.plugin.activityproof.interfaces.request;

import java.util.List;

public record ActivityBindingRequest(
        String type,
        String serverId,
        String subServer,
        Integer minOnlineMinutes,
        Boolean includeAfk,
        Boolean autoJoin,
        String formCode,
        List<ActivityParamRequest> params,
        String expression,
        Double minScore,
        Double maxScore
) {
}
