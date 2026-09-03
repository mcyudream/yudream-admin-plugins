package online.yudream.base.plugin.activityproof.interfaces.request;

import java.util.List;

public record ActivityTemplateMembersSaveRequest(
        String templateId,
        List<String> userIds
) {
}
