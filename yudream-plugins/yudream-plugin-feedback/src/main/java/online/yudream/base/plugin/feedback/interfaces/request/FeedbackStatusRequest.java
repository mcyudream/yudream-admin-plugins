package online.yudream.base.plugin.feedback.interfaces.request;

/** 管理端状态流转请求：note 作为结论说明写入留痕。 */
public record FeedbackStatusRequest(String status, String note) {

    public String safeStatus() {
        return status == null ? "" : status.trim();
    }

    public String safeNote() {
        return note == null ? "" : note.trim();
    }
}
