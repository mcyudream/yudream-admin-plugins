package online.yudream.base.plugin.activityproof.interfaces.request;

import java.util.List;

public record ActivityProofExportRequest(
        String activityId,
        String proofNo,
        String college,
        String issuer,
        String issueDate,
        List<String> selectedUserIds
) {
}
