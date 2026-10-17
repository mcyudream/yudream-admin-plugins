package online.yudream.base.plugin.feedback.interfaces.request;

/** 严重程度调整请求。 */
public record FeedbackSeverityRequest(String severity) {

    public String safeSeverity() {
        return severity == null ? "" : severity.trim();
    }
}
