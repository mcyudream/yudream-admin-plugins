package online.yudream.base.plugin.feedback.interfaces.request;

/** 评论请求体：用户追加说明与管理员官方回复共用。 */
public record FeedbackCommentRequest(String content) {

    public String safeContent() {
        return content == null ? "" : content.trim();
    }
}
