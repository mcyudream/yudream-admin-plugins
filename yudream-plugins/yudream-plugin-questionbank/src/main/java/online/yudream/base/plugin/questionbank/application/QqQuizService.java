package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.command.PluginCommandContext;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;

/**
 * QQ 群抢答抽题：`抽题 [分组名]` 指令抽一道题发到群里，群成员限时内直接回复答案。
 * 客观题本地判分，首个答对者揭晓并结束；简答题按设置走 AI 判分，AI 不可用时到点公布参考答案。
 * 状态仅存内存（按 connectionId+channelId 分组互斥），超时自动清理，插件 disable 时释放线程池。
 */
public final class QqQuizService implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(QqQuizService.class.getName());
    private static final long AI_TIMEOUT_SECONDS = 90;

    private static final String SHORT_SYSTEM_PROMPT = "你是严格的阅卷老师。只输出 CORRECT 或 WRONG："
            + "对照参考答案判断学生作答是否正确。要点覆盖、语义等价即可判 CORRECT；"
            + "明显错误、答非所问或无法确认时输出 WRONG。不要输出任何其他内容。";

    private final QuestionRepository questions;
    private final SettingsService settings;
    private final FrameworkServices framework;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "questionbank-qq-quiz");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, QuizState> activeQuizzes = new ConcurrentHashMap<>();

    public QqQuizService(QuestionRepository questions, SettingsService settings, FrameworkServices framework) {
        this.questions = questions;
        this.settings = settings;
        this.framework = framework;
    }

    /** `抽题 [分组名]` 指令入口。 */
    public void startQuiz(PluginCommandContext command, PluginContext context) {
        PluginEvent event = command.event();
        if (event.channelId() == null || event.channelId().isBlank()) {
            return;
        }
        String channelKey = channelKey(event);
        if (activeQuizzes.containsKey(channelKey)) {
            reply(command, context, "当前群正在抢答中，请先等这题结束～");
            return;
        }
        String groupName = command.arguments().isEmpty() ? settings.qqDefaultGroup() : String.join(" ", command.arguments()).trim();
        List<Question> pool = poolFor(groupName);
        if (groupName != null && !groupName.isBlank() && pool == null) {
            reply(command, context, "未找到分组「" + groupName + "」，可用分组：" + groupNames());
            return;
        }
        if (pool == null || pool.isEmpty()) {
            reply(command, context, "题库里暂时没有可抽的题目");
            return;
        }
        Question question = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        int seconds = settings.qqAnswerSeconds();
        QuizState state = new QuizState(question, event, System.currentTimeMillis() + seconds * 1000L);
        activeQuizzes.put(channelKey, state);
        reply(command, context, "【抢答 · " + question.type().label() + "】（限时 " + seconds + " 秒）\n"
                + questionText(question) + "\n\n直接回复答案即可抢答！");
        scheduler.schedule(() -> timeout(channelKey, state, context), seconds, TimeUnit.SECONDS);
    }

    /** 群消息监听入口：仅在有进行中的抢答时处理。 */
    public void onMessage(PluginEvent event, PluginContext context) {
        if (event.channelId() == null || event.channelId().isBlank()
                || event.userId() == null || event.userId().isBlank()
                || event.content() == null || event.content().isBlank()) {
            return;
        }
        if (event.userId().equals(event.selfId())) {
            return;
        }
        QuizState state = activeQuizzes.get(channelKey(event));
        if (state == null || System.currentTimeMillis() > state.deadlineMs()) {
            return;
        }
        String answer = event.content().trim();
        Question question = state.question();
        if (question.type() == online.yudream.base.plugin.questionbank.domain.QuestionType.SHORT) {
            gradeShort(state, event, answer, context);
            return;
        }
        boolean correct = gradeObjective(question, answer);
        if (correct) {
            if (activeQuizzes.remove(channelKey(event), state)) {
                send(state.event(), context, "🎉 抢答成功！答案：" + ComposeService.answerText(question)
                        + analysisSuffix(question));
            }
        }
        else {
            send(event, context, "✗ 回答错误，继续加油！");
        }
    }

    /** 简答题：AI 判分异步回调；AI 不可用时不判分，到点公布参考答案。 */
    private void gradeShort(QuizState state, PluginEvent answerEvent, String answer, PluginContext context) {
        PluginAiService ai = aiService();
        if (!settings.qqAiGrading() || ai == null) {
            send(answerEvent, context, "已收到你的作答～简答题将在到时后公布参考答案对照");
            return;
        }
        Question question = state.question();
        PluginAiExecutionContext executionContext = new PluginAiExecutionContext(
                null, null, null, null, null, "QUESTIONBANK_QQ_QUIZ", question.id(), List.of());
        String userPrompt = "【题目】\n" + question.content()
                + "\n\n【参考答案】\n" + (question.referenceAnswer() == null ? "（无）" : question.referenceAnswer())
                + "\n\n【学生作答】\n" + answer;
        try {
            ai.chat(new PluginAiChatRequest(SHORT_SYSTEM_PROMPT, userPrompt,
                    settings.aiProviderCode(), settings.aiModelCode(), List.of(), executionContext, false))
                    .toCompletableFuture().orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        if (error != null || response == null || response.content() == null) {
                            return;
                        }
                        String content = response.content().trim().toUpperCase(Locale.ROOT);
                        if (content.startsWith("CORRECT")) {
                            if (activeQuizzes.remove(channelKey(answerEvent), state)) {
                                send(state.event(), context, "🎉 抢答成功（AI 判分）！\n参考答案："
                                        + ComposeService.answerText(question) + analysisSuffix(question));
                            }
                        }
                        else if (content.startsWith("WRONG")) {
                            send(answerEvent, context, "✗ 回答错误（AI 判分），继续加油！");
                        }
                    });
        }
        catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] QQ 简答 AI 判分调用失败", e);
        }
    }

    /** 到时未有人答对：公布答案与解析。 */
    private void timeout(String channelKey, QuizState state, PluginContext context) {
        if (activeQuizzes.remove(channelKey, state)) {
            Question question = state.question();
            String prefix = question.type() == online.yudream.base.plugin.questionbank.domain.QuestionType.SHORT
                    ? "⏰ 时间到！参考答案：" : "⏰ 时间到！正确答案：";
            send(state.event(), context, prefix + ComposeService.answerText(question) + analysisSuffix(question));
        }
    }

    private boolean gradeObjective(Question question, String raw) {
        String answer = raw.trim();
        return switch (question.type()) {
            case SINGLE -> {
                String expected = question.answer() == null ? "" : question.answer();
                yield answer.equalsIgnoreCase(expected) || optionTextMatch(question, answer, List.of(expected));
            }
            case MULTIPLE -> {
                TreeSet<String> given = new TreeSet<>();
                for (char ch : answer.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "").toCharArray()) {
                    given.add(String.valueOf(ch));
                }
                TreeSet<String> expected = new TreeSet<>(question.answers() == null ? List.<String>of() : question.answers());
                yield !given.isEmpty() && given.equals(expected);
            }
            case TRUE_FALSE -> {
                Boolean value = switch (answer.toUpperCase(Locale.ROOT)) {
                    case "TRUE", "T", "对", "正确", "√", "是的", "是" -> Boolean.TRUE;
                    case "FALSE", "F", "错", "错误", "×", "不是", "否" -> Boolean.FALSE;
                    default -> null;
                };
                yield value != null && String.valueOf(value).equalsIgnoreCase(question.answer());
            }
            case FILL -> {
                List<List<String>> blanks = question.blanks() == null ? List.<List<String>>of() : question.blanks();
                String[] parts = answer.split("[,，;；、\\s]+");
                if (parts.length < blanks.size()) {
                    yield false;
                }
                boolean all = true;
                for (int i = 0; i < blanks.size() && all; i++) {
                    String part = parts[i];
                    all = blanks.get(i).stream().anyMatch(accepted -> accepted.equalsIgnoreCase(part));
                }
                yield all;
            }
            case SHORT -> false;
        };
    }

    /** 单选允许直接回复选项内容文本。 */
    private boolean optionTextMatch(Question question, String answer, List<String> expectedKeys) {
        List<String> options = question.options() == null ? List.of() : question.options();
        for (String key : expectedKeys) {
            int index = key.isEmpty() ? -1 : key.charAt(0) - 'A';
            if (index >= 0 && index < options.size() && options.get(index).equalsIgnoreCase(answer)) {
                return true;
            }
        }
        return false;
    }

    /** 分组抽题池：分组名 → 分类/标签规则；分组不存在返回 null；未配置分组返回全部启用题。 */
    private List<Question> poolFor(String groupName) {
        List<Question> enabled = questions.listAll().stream().filter(Question::enabled).toList();
        if (groupName == null || groupName.isBlank()) {
            return enabled;
        }
        for (Map<String, Object> group : settings.qqGroups()) {
            Object name = group.get("name");
            if (name != null && groupName.equalsIgnoreCase(String.valueOf(name).trim())) {
                Object categoryId = group.get("categoryId");
                @SuppressWarnings("unchecked")
                List<String> tags = group.get("tags") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of();
                return QuestionPool.filter(enabled,
                        categoryId == null ? null : String.valueOf(categoryId), tags, List.of(), List.of());
            }
        }
        return null;
    }

    private String groupNames() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> group : settings.qqGroups()) {
            Object name = group.get("name");
            if (name != null) {
                names.add(String.valueOf(name));
            }
        }
        return names.isEmpty() ? "（未配置，可在题库设置中添加）" : String.join("、", names);
    }

    private String questionText(Question question) {
        StringBuilder sb = new StringBuilder(MarkdownPlainText.toMultiline(question.content()));
        List<String> options = question.options() == null ? List.of() : question.options();
        for (int i = 0; i < options.size(); i++) {
            sb.append("\n").append(Question.optionKey(i)).append(". ").append(options.get(i));
        }
        return sb.toString();
    }

    private String analysisSuffix(Question question) {
        String analysis = question.analysis() == null ? "" : MarkdownPlainText.toSingleLine(question.analysis());
        return analysis.isEmpty() ? "" : "\n解析：" + analysis;
    }

    private PluginAiService aiService() {
        try {
            return framework.ai();
        }
        catch (Throwable e) {
            return null;
        }
    }

    private String channelKey(PluginEvent event) {
        return event.connectionId() + ":" + event.channelId();
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        Map<String, Object> referrer = command.event().messageId() == null
                ? Map.of() : Map.of("message_id", command.event().messageId());
        send(command.event(), context, new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, referrer));
    }

    private void send(PluginEvent event, PluginContext context, String text) {
        send(event, context, new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, Map.of()));
    }

    private void send(PluginEvent event, PluginContext context, PluginMessageContent content) {
        if (event.channelId() == null || event.channelId().isBlank()) {
            return;
        }
        try {
            context.framework().messaging().send(new PluginMessageRequest(event.connectionId(),
                    event.platform(), event.selfId(), event.channelId(), content));
        }
        catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] QQ 消息发送失败", e);
        }
    }

    @Override
    public void close() {
        activeQuizzes.clear();
        scheduler.shutdownNow();
    }

    private record QuizState(Question question, PluginEvent event, long deadlineMs) {
    }
}
