package online.yudream.base.plugin.activityproof.domain.aggregate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 活动效果提交：活动结束后上传的文字与图片、视频等材料。
 * 提交后进入待审核状态，管理员采纳后进入有效效果池，供下游（如 AI 模板办公）消费。
 */
public record ActivityEffect(
        String id,
        String activityId,
        String submittedBy,
        long submittedAt,
        String text,
        List<Attachment> attachments,
        String status,
        String reviewedBy,
        long reviewedAt,
        String reviewNote
) {

    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_REJECTED = "rejected";

    /** 效果附件：文件已落插件文件存储，这里只引用 objectKey。 */
    public record Attachment(
            String fileKey,
            String filename,
            String contentType,
            long size,
            String kind,
            String caption
    ) {
        public Attachment {
            fileKey = text(fileKey);
            filename = text(filename);
            contentType = text(contentType);
            kind = kind == null || kind.isBlank() ? "doc" : kind.trim().toLowerCase(java.util.Locale.ROOT);
            caption = text(caption);
            size = Math.max(size, 0);
        }

        public boolean image() {
            return "image".equals(kind);
        }

        public boolean video() {
            return "video".equals(kind);
        }
    }

    public ActivityEffect {
        activityId = require(activityId, "活动编号不能为空");
        submittedBy = text(submittedBy);
        text = text == null ? "" : text.trim();
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        status = status == null || status.isBlank() ? STATUS_SUBMITTED : status.trim().toLowerCase(java.util.Locale.ROOT);
        reviewedBy = text(reviewedBy);
        reviewNote = text(reviewNote);
        submittedAt = Math.max(submittedAt, 0);
        reviewedAt = Math.max(reviewedAt, 0);
    }

    public static ActivityEffect create(String activityId, String submittedBy, String text, List<Attachment> attachments) {
        return new ActivityEffect(UUID.randomUUID().toString(), activityId, submittedBy,
                System.currentTimeMillis(), text, attachments, STATUS_SUBMITTED, "", 0, "");
    }

    public boolean accepted() {
        return STATUS_ACCEPTED.equals(status);
    }

    public boolean pending() {
        return STATUS_SUBMITTED.equals(status);
    }

    /** 审核只对待审记录生效，返回新实例。 */
    public ActivityEffect review(String reviewer, boolean accept, String note) {
        if (!pending()) {
            throw new IllegalArgumentException("该效果提交已审核过");
        }
        return new ActivityEffect(id, activityId, submittedBy, submittedAt, text, attachments,
                accept ? STATUS_ACCEPTED : STATUS_REJECTED, text(reviewer), System.currentTimeMillis(), note);
    }

    public ActivityEffect withText(String newText) {
        return new ActivityEffect(id, activityId, submittedBy, submittedAt, newText, attachments,
                status, reviewedBy, reviewedAt, reviewNote);
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** 供仓储重建集合字段使用。 */
    public static List<Attachment> safeCopy(List<Attachment> attachments) {
        return attachments == null ? List.of() : new ArrayList<>(attachments);
    }
}
