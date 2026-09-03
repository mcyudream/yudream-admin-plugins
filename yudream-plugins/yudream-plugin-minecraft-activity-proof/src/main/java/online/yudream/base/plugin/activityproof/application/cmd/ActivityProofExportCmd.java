package online.yudream.base.plugin.activityproof.application.cmd;

import java.util.List;

public record ActivityProofExportCmd(
        String activityId,
        String proofNo,
        String college,
        String issuer,
        String issueDate,
        List<String> selectedUserIds
) {
}
