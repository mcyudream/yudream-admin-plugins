package online.yudream.base.plugin.questionbank.interfaces.request;

import java.util.List;
import online.yudream.base.plugin.questionbank.application.AnswerPayload;

/** 提交作答请求体。 */
public record SubmitRequest(List<AnswerPayload> answers) {
}
