package online.yudream.base.plugin.activityproof.application.cmd;

import java.util.List;

public record ActivityTemplateMembersSaveCmd(
        String templateId,
        List<String> userIds
) {
}
