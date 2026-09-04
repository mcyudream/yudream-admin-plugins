package online.yudream.base.plugin.questionbank.interfaces.request;

import java.util.List;
import online.yudream.base.plugin.questionbank.application.QuestionPayload;

/** 批量导入请求体（与 JSON 导出格式兼容）。 */
public record ImportRequest(List<QuestionPayload> questions) {
}
