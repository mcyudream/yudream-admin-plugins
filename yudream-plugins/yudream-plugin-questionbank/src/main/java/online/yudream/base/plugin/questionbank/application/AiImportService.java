package online.yudream.base.plugin.questionbank.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.questionbank.domain.QuestionType;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiModelOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;

/**
 * AI 辅助录入：把管理员粘贴/上传的文本（Markdown、题库导出、网页复制内容等）解析为题目草稿。
 * AI 负责识别题型、抽取选项与答案；原文未给答案/解析的题目由 AI 自行作答并给出参考答案。
 * 输出仅为草稿，确认后仍走 QuestionService.importQuestions 的完整校验落库。
 * 供应商/模型沿用题库设置，留空走宿主默认。
 */
public final class AiImportService {
    private static final Logger LOGGER = Logger.getLogger(AiImportService.class.getName());
    private static final long TIMEOUT_SECONDS = 180;
    private static final int MAX_TEXT_LENGTH = 20000;
    private static final int MAX_QUESTIONS = 50;

    private static final String SYSTEM_PROMPT = "你是题库录入助手。把用户给出的文本识别为一道或多道题目，"
            + "只输出一个 JSON 数组，不要输出任何其他文字或 Markdown 代码块标记。"
            + "数组元素字段：\n"
            + "- type：题型，必须是 SINGLE（单选）/ MULTIPLE（多选）/ TRUE_FALSE（判断）/ FILL（填空）/ SHORT（简答）之一\n"
            + "- content：仅题干本身，Markdown 格式，保留原文中的图片链接；严禁把选项列表（如 A. xx B. xx）写进题干\n"
            + "- options：选择题的选项内容数组（不含 A/B/C 标号）；判断题与填空、简答填空此字段\n"
            + "- answer：单选题填正确选项字母（如 \"B\"）；判断题填 \"TRUE\" 或 \"FALSE\"\n"
            + "- answers：多选题正确选项字母数组（如 [\"A\",\"C\"]）\n"
            + "- blanks：填空题每空可接受答案的二维数组（如 [[\"答案1\",\"等价答案\"]]），空数须与题干中的空对应\n"
            + "- referenceAnswer：简答题参考答案（Markdown）\n"
            + "- analysis：解析（Markdown，没有可省略）\n"
            + "- difficulty：难度 1~5 的整数，拿不准填 3\n"
            + "- tags：建议标签数组（没有可省略）\n"
            + "原文未给出答案或解析时，你必须自行解答并填写答案/参考答案/解析。尽量保留题目的原始表述。";

    private final SettingsService settings;
    private final FrameworkServices framework;
    private final JsonSupport json;

    public AiImportService(SettingsService settings, FrameworkServices framework, JsonSupport json) {
        this.settings = settings;
        this.framework = framework;
        this.json = json;
    }

    /** AI 供应商与模型选项（设置页选择器用）；AI 能力不可用时返回空列表。 */
    public List<AiProviderOptionView> providerOptions() {
        PluginAiService ai = aiOrNull();
        if (ai == null) {
            return List.of();
        }
        try {
            List<AiProviderOptionView> views = new ArrayList<>();
            for (PluginAiProviderOption provider : ai.providers()) {
                List<AiModelOptionView> models = new ArrayList<>();
                for (PluginAiModelOption model : provider.models()) {
                    models.add(new AiModelOptionView(model.code(), model.name()));
                }
                views.add(new AiProviderOptionView(provider.code(), provider.name(), List.copyOf(models)));
            }
            return List.copyOf(views);
        }
        catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] 获取 AI 供应商列表失败", e);
            return List.of();
        }
    }

    /** 解析粘贴/上传的文本为题目草稿；AI 不可用或输出无法识别时抛业务异常。 */
    public List<QuestionPayload> parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("请粘贴或上传题目内容");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("内容过长（最多 " + MAX_TEXT_LENGTH + " 字），请分批导入");
        }
        PluginAiService ai = aiOrNull();
        if (ai == null) {
            throw new IllegalStateException("平台 AI 能力不可用，请先在平台配置 AI 供应商");
        }
        PluginAiExecutionContext context = new PluginAiExecutionContext(
                null, null, null, null, null, "QUESTIONBANK_IMPORT", null, List.of());
        PluginAiChatResponse response = await(ai.chat(new PluginAiChatRequest(
                SYSTEM_PROMPT, text, settings.aiProviderCode(), settings.aiModelCode(),
                List.of(), context, false)));
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("AI 未返回内容，请重试");
        }
        return parseDrafts(response.content());
    }

    /** 从 AI 输出文本中容错提取题目草稿（工具调用失败兜底用）：剥代码块围栏、截取首个 JSON 数组。 */
    public List<QuestionPayload> parseContent(String aiOutput) {
        return parseDrafts(aiOutput);
    }

    /** 容错解析 AI 输出：剥代码块围栏、截取首个 JSON 数组，逐题校验，无效题跳过。 */
    private List<QuestionPayload> parseDrafts(String content) {
        JsonNode root = json.readTree(extractJsonArray(content));
        if (!root.isArray()) {
            throw new IllegalStateException("AI 输出不是题目数组，请重试");
        }
        List<QuestionPayload> drafts = new ArrayList<>();
        for (JsonNode node : root) {
            if (drafts.size() >= MAX_QUESTIONS) {
                break;
            }
            QuestionPayload draft = toDraft(node);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        if (drafts.isEmpty()) {
            throw new IllegalStateException("AI 未能从内容中识别出有效题目，请检查文本或重试");
        }
        return List.copyOf(drafts);
    }

    /** 单题容错映射：题型无法识别或题干为空视为无效题（返回 null）。 */
    private QuestionPayload toDraft(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        QuestionType type = parseType(text(node, "type"));
        String content = text(node, "content");
        if (type == null || content == null || content.isBlank()) {
            return null;
        }
        Integer difficulty = null;
        JsonNode difficultyNode = node.get("difficulty");
        if (difficultyNode != null && difficultyNode.isInt()) {
            difficulty = difficultyNode.intValue();
        }
        else if (difficultyNode != null && difficultyNode.isTextual()) {
            try {
                difficulty = Integer.parseInt(difficultyNode.asText().trim());
            }
            catch (NumberFormatException ignored) {
                difficulty = null;
            }
        }
        return new QuestionPayload(type.name(), null, text(node, "categoryName"),
                stringList(node.get("tags")), content.trim(),
                stringList(node.get("options")), text(node, "answer"), stringList(node.get("answers")),
                blanksList(node.get("blanks")), text(node, "referenceAnswer"), text(node, "analysis"),
                difficulty, null);
    }

    private QuestionType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "SINGLE", "单选", "单选题" -> QuestionType.SINGLE;
            case "MULTIPLE", "MULTI", "多选", "多选题" -> QuestionType.MULTIPLE;
            case "TRUE_FALSE", "JUDGE", "判断", "判断题" -> QuestionType.TRUE_FALSE;
            case "FILL", "BLANK", "填空", "填空题" -> QuestionType.FILL;
            case "SHORT", "简答", "简答题", "问答", "问答题" -> QuestionType.SHORT;
            default -> null;
        };
    }

    private String extractJsonArray(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("AI 输出中未找到题目数组，请重试");
        }
        return text.substring(start, end + 1);
    }

    private PluginAiService aiOrNull() {
        try {
            return framework.ai();
        }
        catch (Throwable e) {
            return null;
        }
    }

    /** 同步等待 AI 结果并附加超时；宿主实现不支持阻塞等待时直接报业务错误。 */
    private PluginAiChatResponse await(CompletionStage<PluginAiChatResponse> stage) {
        java.util.concurrent.CompletableFuture<PluginAiChatResponse> future;
        try {
            future = stage.toCompletableFuture();
        }
        catch (UnsupportedOperationException e) {
            throw new IllegalStateException("当前 AI 实现不支持同步调用，请联系平台管理员");
        }
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("AI 响应超时，请重试");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断，请重试");
        }
        catch (java.util.concurrent.ExecutionException e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] AI 题目解析调用失败", e);
            throw new IllegalStateException("AI 调用失败：" +
                    (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private List<List<String>> blanksList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<List<String>> blanks = new ArrayList<>();
        for (JsonNode item : node) {
            List<String> accepted = stringList(item);
            if (accepted != null) {
                blanks.add(accepted);
            }
        }
        return blanks;
    }

    public record AiProviderOptionView(String code, String name, List<AiModelOptionView> models) {
    }

    public record AiModelOptionView(String code, String name) {
    }
}
