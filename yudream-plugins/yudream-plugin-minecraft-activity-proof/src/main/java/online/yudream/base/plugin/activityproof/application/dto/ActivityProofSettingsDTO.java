package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record ActivityProofSettingsDTO(
        boolean templateReady,
        String templateId,
        String templateCode,
        String templateName,
        String templateFilename,
        long templateUpdatedAt,
        String defaultActivityName,
        String defaultCollege,
        String defaultIssuer,
        boolean qqNotifyEnabled,
        String qqConnectionId,
        List<String> qqGroupIds,
        String qqMessageTemplate,
        long updatedAt
) {
}
