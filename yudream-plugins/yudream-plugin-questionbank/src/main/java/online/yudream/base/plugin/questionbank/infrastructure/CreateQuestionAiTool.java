package online.yudream.base.plugin.questionbank.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.questionbank.application.QuestionPayload;
import online.yudream.base.plugin.questionbank.application.QuestionService;
import online.yudream.base.plugin.questionbank.bootstrap.QuestionBankPlugin;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiTool;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolCall;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolRisk;

/**
 * 「创建一道题目」AI 工具：AI 导入任务中模型每识别出一道题就调用一次，逐题原子落库。
 * WRITE 风险，仅在调用方于 executionContext.grantedWriteToolNames 显式点名且具备题库管理权限时
 * 宿主才放行（SPI 2.16.0 契约）；归属人固定取执行上下文里的当前管理员。
 */
public final class CreateQuestionAiTool implements PluginAiTool {
    public static final String TOOL_NAME = "questionbank.create_question";
    public static final String TRIGGER = "QUESTIONBANK_IMPORT";
    private static final Logger LOGGER = Logger.getLogger(CreateQuestionAiTool.class.getName());

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("type", "content"),
            "properties", Map.ofEntries(
                    Map.entry("type", Map.of("type", "string", "enum",
                            List.of("SINGLE", "MULTIPLE", "TRUE_FALSE", "FILL", "SHORT"),
                            "description", "题型：单选/多选/判断/填空/简答")),
                    Map.entry("content", Map.of("type", "string", "description", "仅题干本身（Markdown，保留图片链接）；严禁包含选项列表（如 A. xx B. xx），选项只能放 options")),
                    Map.entry("options", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "选择题选项内容数组（不含 A/B/C 标号）")),
                    Map.entry("answer", Map.of("type", "string",
                            "description", "单选题正确选项字母（如 B）；判断题 TRUE/FALSE")),
                    Map.entry("answers", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "多选题正确选项字母数组")),
                    Map.entry("blanks", Map.of("type", "array", "items", Map.of("type", "array", "items", Map.of("type", "string")),
                            "description", "填空题每空可接受答案的二维数组")),
                    Map.entry("referenceAnswer", Map.of("type", "string", "description", "简答题参考答案（Markdown）")),
                    Map.entry("analysis", Map.of("type", "string", "description", "解析（Markdown，可省略）")),
                    Map.entry("difficulty", Map.of("type", "integer", "description", "难度 1~5，拿不准填 3")),
                    Map.entry("tags", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "建议标签（可省略）")),
                    Map.entry("categoryName", Map.of("type", "string", "description", "建议分类名（可省略）"))));

    private final QuestionService questionService;
    private final JsonSupport json;

    public CreateQuestionAiTool(QuestionService questionService, JsonSupport json) {
        this.questionService = questionService;
        this.json = json;
    }

    @Override
    public PluginAiToolDescriptor descriptor() {
        return new PluginAiToolDescriptor(TOOL_NAME, "创建一道题目",
                "把识别出的一道题目写入题库。每识别出一道题调用一次；原文未给答案/解析时先自行解答再调用。",
                QuestionBankPlugin.MANAGE_PERMISSION, PluginAiToolRisk.WRITE, false,
                Set.of(TRIGGER), INPUT_SCHEMA);
    }

    @Override
    public PluginAiToolResult execute(PluginAiExecutionContext context, PluginAiToolCall call) {
        if (context == null || context.userId() == null) {
            return new PluginAiToolResult("create_question", "缺少操作人身份，未入库",
                    Map.of("ok", false, "reason", "缺少操作人身份"));
        }
        String operatorId = String.valueOf(context.userId());
        try {
            QuestionPayload payload = json.read(json.write(call.arguments()), QuestionPayload.class);
            var result = questionService.importQuestions(List.of(payload), operatorId,
                    questionService.resolveUserName(operatorId));
            if (result.failures().isEmpty()) {
                return new PluginAiToolResult("create_question", "已入库",
                        Map.of("ok", true, "summary", summarize(payload.content())));
            }
            return new PluginAiToolResult("create_question", "校验失败：" + result.failures().get(0).reason(),
                    Map.of("ok", false, "summary", summarize(payload.content()),
                            "reason", result.failures().get(0).reason()));
        }
        catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] AI 工具创建题目失败", e);
            return new PluginAiToolResult("create_question", "创建失败：" + e.getMessage(),
                    Map.of("ok", false, "reason", e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private static String summarize(String content) {
        if (content == null) {
            return "";
        }
        String text = content.replaceAll("!\\[[^]]*]\\([^)]*\\)", "[图片]")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[#>*`_~-]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }
}
