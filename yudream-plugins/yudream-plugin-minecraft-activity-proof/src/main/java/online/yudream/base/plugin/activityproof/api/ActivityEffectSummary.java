package online.yudream.base.plugin.activityproof.api;

import java.util.List;

/** 已采纳活动效果的只读摘要。 */
public record ActivityEffectSummary(
        String id,
        String activityId,
        String submittedBy,
        long submittedAt,
        String text,
        List<Attachment> attachments
) {
    public record Attachment(
            String fileKey,
            String filename,
            String contentType,
            long size,
            String kind,
            String caption
    ) {
    }
}
