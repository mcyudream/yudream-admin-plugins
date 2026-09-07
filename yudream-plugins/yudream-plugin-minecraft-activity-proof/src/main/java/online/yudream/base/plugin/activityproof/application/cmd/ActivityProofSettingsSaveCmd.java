package online.yudream.base.plugin.activityproof.application.cmd;

import java.util.List;

public record ActivityProofSettingsSaveCmd(
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
