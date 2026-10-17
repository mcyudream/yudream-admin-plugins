package online.yudream.base.plugin.feedback.application;

/** 反馈不存在：HTTP 层统一映射为 404。 */
public class FeedbackNotFoundException extends RuntimeException {

    public FeedbackNotFoundException(String message) {
        super(message);
    }
}
