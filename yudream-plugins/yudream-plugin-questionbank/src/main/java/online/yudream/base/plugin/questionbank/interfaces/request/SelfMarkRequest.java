package online.yudream.base.plugin.questionbank.interfaces.request;

/** 简答自评请求体。 */
public record SelfMarkRequest(String questionId, Boolean correct) {
}
