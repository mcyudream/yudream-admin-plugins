package online.yudream.base.plugin.activityproof.interfaces.request;

import java.util.List;

public record ActivityProofSettingsSaveRequest(
        String defaultActivityName,
        String defaultCollege,
        String defaultIssuer,
        String templateId,
        Boolean qqNotifyEnabled,
        String qqConnectionId,
        List<String> qqGroupIds,
        String qqMessageTemplate,
        Boolean qqSignupButtonEnabled,
        String qqSignupButtonLabel
) {
}
