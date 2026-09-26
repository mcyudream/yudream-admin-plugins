package online.yudream.base.plugin.activityproof.application.cmd;

import java.util.List;

public record ActivityBindingCmd(
        String type,
        String serverId,
        String subServer,
        Integer minOnlineMinutes,
        Boolean includeAfk,
        Boolean autoJoin,
        String formCode,
        List<ActivityParamCmd> params,
        String expression,
        Double minScore,
        Double maxScore
) {
}
