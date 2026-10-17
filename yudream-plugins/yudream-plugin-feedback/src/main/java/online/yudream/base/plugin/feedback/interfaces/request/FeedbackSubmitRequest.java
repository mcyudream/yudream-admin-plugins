package online.yudream.base.plugin.feedback.interfaces.request;

import java.util.List;

/** 提交或修改反馈内容的请求体。 */
public record FeedbackSubmitRequest(
        String title,
        String description,
        String type,
        String severity,
        List<String> attachments
) {

    public String safeTitle() {
        return title == null ? "" : title.trim();
    }

    public String safeDescription() {
        return description == null ? "" : description.trim();
    }

    /** 图片地址来自宿主文件服务，URL 中不应含空白；清洗后交由聚合去重与限数。 */
    public List<String> safeAttachments() {
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
