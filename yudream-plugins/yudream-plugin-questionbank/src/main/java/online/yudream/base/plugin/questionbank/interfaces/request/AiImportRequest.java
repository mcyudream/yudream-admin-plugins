package online.yudream.base.plugin.questionbank.interfaces.request;

/** AI 题目解析请求体：粘贴或上传 Markdown 读取的原始文本。 */
public record AiImportRequest(String text) {
}
