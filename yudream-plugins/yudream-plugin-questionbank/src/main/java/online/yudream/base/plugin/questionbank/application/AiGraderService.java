package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.questionbank.domain.QuestionType;
import online.yudream.base.plugin.questionbank.domain.SessionAnswer;
import online.yudream.base.plugin.questionbank.domain.SessionQuestion;
import online.yudream.base.plugin.questionbank.infrastructure.SessionRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;

/**
 * AI 简答判分：提交后对未判分的简答题逐题调用宿主 AI 能力（framework.ai()）异步判定对错。
 * 判分失败、超时或输出无法解析时保持 correct=null，由管理员人工审核队列兜底；
 * 供应商/模型可在题库设置中配置，留空走宿主默认。
 */
public final class AiGraderService {
    private static final Logger LOGGER = Logger.getLogger(AiGraderService.class.getName());
    private static final long TIMEOUT_SECONDS = 90;

    private static final String SYSTEM_PROMPT = "你是严格的阅卷老师。只输出 CORRECT 或 WRONG："
            + "对照参考答案判断学生作答是否正确。要点覆盖、语义等价即可判 CORRECT；"
            + "明显错误、答非所问或无法确认时输出 WRONG。不要输出任何其他内容。";

    private final SessionRepository sessions;
    private final SettingsService settings;
    private final FrameworkServices framework;

    public AiGraderService(SessionRepository sessions, SettingsService settings, FrameworkServices framework) {
        this.sessions = sessions;
        this.settings = settings;
        this.framework = framework;
    }

    /** 对 AI 模式的已提交会话异步判分；宿主 AI 能力不可用时直接保留待审核。 */
    public void gradeAsync(PracticeSession session) {
        PluginAiService ai;
        try {
            ai = framework.ai();
        }
        catch (Throwable e) {
            ai = null;
        }
        if (ai == null) {
            LOGGER.info("[YuDreamAdmin] [题库] AI 判分不可用，会话 " + session.id() + " 简答题转人工审核");
            return;
        }
        String providerCode = settings.aiProviderCode();
        String modelCode = settings.aiModelCode();
        for (SessionQuestion question : session.questions()) {
            if (question.questionType() != QuestionType.SHORT) {
                continue;
            }
            SessionAnswer answer = answerOf(session, question.questionId());
            if (answer == null || answer.correct() != null) {
                continue;
            }
            PluginAiExecutionContext context = new PluginAiExecutionContext(
                    parseUserId(session.userId()), null, null, null, null,
                    "QUESTIONBANK_GRADING", session.id() + ":" + question.questionId(), List.of());
            withTimeout(ai.chat(new PluginAiChatRequest(SYSTEM_PROMPT, userPrompt(question, answer.text()),
                    providerCode, modelCode, List.of(), context, false)))
                    .whenComplete((response, error) ->
                            apply(session.id(), question.questionId(), parse(response, error)));
        }
    }

    /** 为宿主返回的 CompletionStage 附加超时；非 CompletableFuture 实现时退化为无超时直通。 */
    private java.util.concurrent.CompletionStage<PluginAiChatResponse> withTimeout(
            java.util.concurrent.CompletionStage<PluginAiChatResponse> stage) {
        try {
            return stage.toCompletableFuture().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (UnsupportedOperationException e) {
            return stage;
        }
    }

    /** 单题判分落库：仅在会话仍为已提交且该题仍未判分时写入，避免覆盖人工审核结论。 */
    private void apply(String sessionId, String questionId, Boolean correct) {
        if (correct == null) {
            return;
        }
        try {
            PracticeSession session = sessions.findById(sessionId).orElse(null);
            if (session == null || !PracticeSession.STATUS_FINISHED.equals(session.status())) {
                return;
            }
            List<SessionAnswer> answers = new ArrayList<>();
            int correctCount = 0;
            boolean changed = false;
            for (SessionAnswer answer : session.answers()) {
                SessionAnswer next = answer;
                if (answer.questionId().equals(questionId) && answer.correct() == null) {
                    next = new SessionAnswer(answer.questionId(), answer.choice(), answer.choices(),
                            answer.blanks(), answer.text(), correct);
                    changed = true;
                }
                answers.add(next);
                if (Boolean.TRUE.equals(next.correct())) {
                    correctCount++;
                }
            }
            if (!changed) {
                return;
            }
            sessions.save(new PracticeSession(
                    session.id(), session.userId(), session.userName(), session.categoryId(), session.tags(),
                    session.types(), session.difficulties(), session.requestedCount(), session.questions(),
                    List.copyOf(answers), session.status(), correctCount, session.createdAt(), session.submittedAt(),
                    session.paperId(), session.paperName(), session.subjectiveMode()));
        }
        catch (Throwable e) {
            LOGGER.log(Level.WARNING,
                    "[YuDreamAdmin] [题库] AI 判分结果落库失败：session=" + sessionId + ", question=" + questionId, e);
        }
    }

    /** 解析模型输出：只接受 CORRECT/WRONG 前缀，其余视为判分失败，留给人工审核。 */
    private Boolean parse(PluginAiChatResponse response, Throwable error) {
        if (error != null) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] AI 判分调用失败，该题转人工审核", error);
            return null;
        }
        if (response == null || response.content() == null) {
            return null;
        }
        String content = response.content().trim().toUpperCase(Locale.ROOT);
        if (content.startsWith("CORRECT")) {
            return Boolean.TRUE;
        }
        if (content.startsWith("WRONG")) {
            return Boolean.FALSE;
        }
        LOGGER.info("[YuDreamAdmin] [题库] AI 判分输出无法解析，该题转人工审核：" + abbreviate(response.content()));
        return null;
    }

    private String userPrompt(SessionQuestion question, String answerText) {
        return "【题目】\n" + question.content()
                + "\n\n【参考答案】\n" + (question.referenceAnswer() == null ? "（无）" : question.referenceAnswer())
                + "\n\n【学生作答】\n" + (answerText == null ? "" : answerText);
    }

    private SessionAnswer answerOf(PracticeSession session, String questionId) {
        return session.answers().stream()
                .filter(answer -> answer.questionId().equals(questionId))
                .findFirst()
                .orElse(null);
    }

    private Long parseUserId(String userId) {
        try {
            return userId == null ? null : Long.parseLong(userId);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private String abbreviate(String content) {
        return content.length() <= 80 ? content : content.substring(0, 80) + "...";
    }
}
