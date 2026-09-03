package online.yudream.base.plugin.activityproof.domain.aggregate;

import java.util.List;

public record ActivityProofSettings(
        String id,
        Long templateId,
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
    public static final String ID = "default";

    public ActivityProofSettings {
        qqConnectionId = text(qqConnectionId);
        qqGroupIds = qqGroupIds == null ? List.of() : qqGroupIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        qqMessageTemplate = qqMessageTemplate == null ? "" : qqMessageTemplate.trim();
    }

    public static ActivityProofSettings empty() {
        return new ActivityProofSettings(ID, null, "", "", "", 0, "", "", "", false, "", List.of(), "", 0);
    }

    public ActivityProofSettings withTemplate(Long templateId, String templateCode, String templateName,
                                              String filename, long templateUpdatedAt, long updatedAt) {
        return new ActivityProofSettings(ID, templateId, text(templateCode), text(templateName), text(filename), templateUpdatedAt,
                defaultActivityName, defaultCollege, defaultIssuer,
                qqNotifyEnabled, qqConnectionId, qqGroupIds, qqMessageTemplate, updatedAt);
    }

    public ActivityProofSettings withDefaults(String activityName, String college, String issuer, long updatedAt) {
        return new ActivityProofSettings(ID, templateId, templateCode, templateName, templateFilename, templateUpdatedAt,
                text(activityName), text(college), text(issuer),
                qqNotifyEnabled, qqConnectionId, qqGroupIds, qqMessageTemplate, updatedAt);
    }

    public ActivityProofSettings withQqNotify(boolean enabled, String connectionId, List<String> groupIds,
                                              String messageTemplate, long updatedAt) {
        return new ActivityProofSettings(ID, templateId, templateCode, templateName, templateFilename, templateUpdatedAt,
                defaultActivityName, defaultCollege, defaultIssuer,
                enabled, connectionId, groupIds, messageTemplate, updatedAt);
    }

    public boolean hasTemplate() {
        return templateId != null;
    }

    public boolean qqNotifyReady() {
        return qqNotifyEnabled && !qqConnectionId.isBlank() && !qqGroupIds.isEmpty();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
