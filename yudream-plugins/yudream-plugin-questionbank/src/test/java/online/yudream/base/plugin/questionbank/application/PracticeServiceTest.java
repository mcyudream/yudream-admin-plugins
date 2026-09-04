package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import online.yudream.base.plugin.questionbank.api.QuestionBankApi;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.questionbank.infrastructure.CategoryRepository;
import online.yudream.base.plugin.questionbank.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.questionbank.infrastructure.FakeFramework;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.infrastructure.PaperRepository;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import online.yudream.base.plugin.questionbank.infrastructure.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 练习用例：抽题筛选池、提交判分、简答自评、题单作答、刷题开关、归属隔离。 */
class PracticeServiceTest {
    private PracticeService practiceService;
    private QuestionService questionService;
    private PaperService paperService;
    private SettingsService settingsService;
    private AdminRecordService recordService;
    private CategoryService categoryService;
    private FakeFramework framework;

    @BeforeEach
    void setUp() {
        FakeDocumentStore store = new FakeDocumentStore();
        QuestionRepository questions = new QuestionRepository(store);
        CategoryRepository categories = new CategoryRepository(store);
        SessionRepository sessions = new SessionRepository(store);
        PaperRepository papers = new PaperRepository(store);
        categoryService = new CategoryService(categories, questions);
        framework = new FakeFramework();
        questionService = new QuestionService(questions, categoryService,
                new JsonSupport(new ObjectMapper()), framework);
        paperService = new PaperService(papers, questions);
        settingsService = new SettingsService(store);
        practiceService = new PracticeService(questions, categories, sessions,
                paperService, settingsService, framework, new AiGraderService(sessions, settingsService, framework));
        recordService = new AdminRecordService(sessions);
    }

    private String addSingle(String tag, String status) {
        return questionService.create(new QuestionPayload("SINGLE", null, null, List.of(tag), "题干" + tag,
                List.of("甲", "乙"), "A", List.of(), List.of(), null, null, null, status), "1", null).id();
    }

    @Test
    void drawRespectsFiltersAndSkipsDisabled() {
        addSingle("java", null);
        addSingle("java", "DISABLED");
        addSingle("sql", null);
        assertEquals(1, practiceService.countPool(new PracticeFilter(null, List.of("java"), List.of(), List.of(), 10)));
        PracticeSession session = practiceService.createSession("100",
                new PracticeFilter(null, List.of("java"), List.of(), List.of(), 10));
        assertEquals(1, session.totalCount());
        assertEquals(PracticeSession.STATUS_ONGOING, session.status());
        assertEquals("用户100", session.userName());
    }

    @Test
    void drawFailsWhenPoolEmpty() {
        assertThrows(IllegalArgumentException.class, () -> practiceService.createSession("100",
                new PracticeFilter(null, List.of("none"), List.of(), List.of(), 5)));
    }

    @Test
    void submitGradesObjectiveAndKeepsShortPending() {
        String singleId = addSingle("java", null);
        String shortId = questionService.create(new QuestionPayload("SHORT", null, null, List.of("java"),
                "谈谈理解", List.of(), null, List.of(), List.of(), "参考答案", null, null, null), "1", null).id();
        PracticeSession session = practiceService.createSession("100",
                new PracticeFilter(null, List.of("java"), List.of(), List.of(), 10));
        PracticeSession finished = practiceService.submit("100", session.id(), List.of(
                new AnswerPayload(singleId, "A", List.of(), List.of(), null),
                new AnswerPayload(shortId, null, List.of(), List.of(), "我的回答")));
        assertEquals(PracticeSession.STATUS_FINISHED, finished.status());
        assertEquals(1, finished.correctCount());
        SessionAnswerView single = new SessionAnswerView(finished, singleId);
        SessionAnswerView shorts = new SessionAnswerView(finished, shortId);
        assertTrue(single.correct());
        assertNull(shorts.correct());
    }

    @Test
    void selfMarkOnlyForFinishedShortQuestions() {
        String shortId = questionService.create(new QuestionPayload("SHORT", null, null, List.of("s"),
                "简答", List.of(), null, List.of(), List.of(), "参考", null, null, null), "1", null).id();
        PracticeSession session = practiceService.createSession("100",
                new PracticeFilter(null, List.of("s"), List.of(), List.of(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> practiceService.selfMark("100", session.id(), shortId, true));
        PracticeSession finished = practiceService.submit("100", session.id(),
                List.of(new AnswerPayload(shortId, null, List.of(), List.of(), "答")));
        PracticeSession marked = practiceService.selfMark("100", finished.id(), shortId, true);
        assertEquals(1, marked.correctCount());
    }

    @Test
    void ownershipIsolation() {
        addSingle("java", null);
        PracticeSession session = practiceService.createSession("100",
                new PracticeFilter(null, List.of("java"), List.of(), List.of(), 1));
        assertThrows(NotFoundException.class, () -> practiceService.requireMine("200", session.id()));
        assertThrows(NotFoundException.class, () -> practiceService.deleteMine("200", session.id()));
        assertEquals(1, practiceService.listMine("100", 1, 20).total());
        assertEquals(0, practiceService.listMine("200", 1, 20).total());
    }

    @Test
    void doubleSubmitRejected() {
        String id = addSingle("java", null);
        PracticeSession session = practiceService.createSession("100",
                new PracticeFilter(null, List.of("java"), List.of(), List.of(), 1));
        practiceService.submit("100", session.id(), List.of(new AnswerPayload(id, "A", List.of(), List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> practiceService.submit("100", session.id(), List.of()));
    }

    @Test
    void practiceDisabledBlocksFreePracticeButNotPaperAttempt() {
        addSingle("java", null);
        settingsService.update(false, null, null, null, null, null, null);
        assertFalse(settingsService.practiceEnabled());
        assertThrows(IllegalStateException.class, () -> practiceService.createSession("100",
                new PracticeFilter(null, List.of("java"), List.of(), List.of(), 1)));
        Paper paper = paperService.create("1", "管理员", new PaperPayload("招新题单", null,
                Paper.MODE_RULE, null, List.of("java"), List.of(), List.of(), 1, null,
                Paper.SUBJECTIVE_SELF, Paper.STATUS_PUBLISHED));
        PracticeSession attempt = practiceService.attemptPaper("100", paper.id());
        assertEquals(paper.id(), attempt.paperId());
        assertEquals("招新题单", attempt.paperName());
        assertEquals(1, attempt.totalCount());
    }

    @Test
    void manualPaperSkipsDeletedAndUnpublishedRejected() {
        String a = addSingle("a", null);
        String b = addSingle("b", null);
        Paper draft = paperService.create("1", "管理员", new PaperPayload("草稿", null,
                Paper.MODE_MANUAL, null, null, null, null, null, List.of(a, b),
                Paper.SUBJECTIVE_SELF, Paper.STATUS_DRAFT));
        assertThrows(IllegalArgumentException.class, () -> practiceService.attemptPaper("100", draft.id()));
        Paper published = paperService.update(draft.id(), new PaperPayload("固定题单", null,
                Paper.MODE_MANUAL, null, null, null, null, null, List.of(a, b),
                Paper.SUBJECTIVE_SELF, Paper.STATUS_PUBLISHED));
        questionService.delete(a);
        PracticeSession attempt = practiceService.attemptPaper("100", published.id());
        assertEquals(1, attempt.totalCount());
        assertEquals(b, attempt.questions().get(0).questionId());
    }

    @Test
    void rulePaperRedrawsFreshQuestions() {
        for (int i = 0; i < 5; i++) {
            addSingle("t" + i, null);
        }
        Paper paper = paperService.create("1", "管理员", new PaperPayload("随机题单", null,
                Paper.MODE_RULE, null, List.of(), List.of(), List.of(), 3, null,
                Paper.SUBJECTIVE_SELF, Paper.STATUS_PUBLISHED));
        assertEquals(3, paperService.draw(paper).size());
        assertEquals(3, paperService.draw(paper).size());
        Paper tooMany = paperService.create("1", "管理员", new PaperPayload("超量", null,
                Paper.MODE_RULE, null, List.of(), List.of(), List.of(), 50, null,
                Paper.SUBJECTIVE_SELF, Paper.STATUS_PUBLISHED));
        assertEquals(5, paperService.draw(tooMany).size());
    }

    @Test
    void reviewModeBlocksSelfMarkAndUsesAdminReview() {
        String shortId = questionService.create(new QuestionPayload("SHORT", null, null, List.of("r"),
                "简答", List.of(), null, List.of(), List.of(), "参考", null, null, null), "1", null).id();
        String singleId = addSingle("r", null);
        Paper paper = paperService.create("1", "管理员", new PaperPayload("审核题单", null,
                Paper.MODE_RULE, null, List.of("r"), List.of(), List.of(), 10, null,
                Paper.SUBJECTIVE_REVIEW, Paper.STATUS_PUBLISHED));
        PracticeSession session = practiceService.attemptPaper("100", paper.id());
        PracticeSession finished = practiceService.submit("100", session.id(), List.of(
                new AnswerPayload(shortId, null, List.of(), List.of(), "答"),
                new AnswerPayload(singleId, "A", List.of(), List.of(), null)));
        assertTrue(finished.reviewMode());
        assertEquals(1, finished.correctCount());
        assertThrows(IllegalArgumentException.class,
                () -> practiceService.selfMark("100", finished.id(), shortId, true));
        assertTrue(AdminRecordService.needsReview(finished));
        assertEquals(1, recordService.pendingReview(null, 1, 20).total());
        PracticeSession reviewed = recordService.review(finished.id(), shortId, true);
        assertEquals(2, reviewed.correctCount());
        assertFalse(AdminRecordService.needsReview(reviewed));
        assertEquals(0, recordService.pendingReview(null, 1, 20).total());
    }

    @Test
    void metaReportsPracticeEnabled() {
        assertEquals(Boolean.TRUE, practiceService.meta().get("practiceEnabled"));
        settingsService.update(false, null, null, null, null, null, null);
        assertEquals(Boolean.FALSE, practiceService.meta().get("practiceEnabled"));
    }

    @Test
    void aiModeGradesShortAnswersAndBlocksSelfMark() {
        framework.setAi(new StubAi("CORRECT"));
        String shortId = questionService.create(new QuestionPayload("SHORT", null, null, List.of("ai"),
                "简答", List.of(), null, List.of(), List.of(), "参考", null, null, null), "1", null).id();
        String singleId = addSingle("ai", null);
        Paper paper = paperService.create("1", "管理员", new PaperPayload("AI 题单", null,
                Paper.MODE_RULE, null, List.of("ai"), List.of(), List.of(), 10, null,
                Paper.SUBJECTIVE_AI, Paper.STATUS_PUBLISHED));
        PracticeSession session = practiceService.attemptPaper("100", paper.id());
        PracticeSession finished = practiceService.submit("100", session.id(), List.of(
                new AnswerPayload(shortId, null, List.of(), List.of(), "我的回答"),
                new AnswerPayload(singleId, "A", List.of(), List.of(), null)));
        assertTrue(finished.aiMode());
        // 完成的 future 同步回调：提交返回时 AI 判分已落库
        PracticeSession reloaded = practiceService.requireMine("100", finished.id());
        assertEquals(2, reloaded.correctCount());
        assertTrue(new SessionAnswerView(reloaded, shortId).correct());
        assertFalse(AdminRecordService.needsReview(reloaded));
        assertThrows(IllegalArgumentException.class,
                () -> practiceService.selfMark("100", finished.id(), shortId, true));
    }

    @Test
    void aiModeBlankAnswerMarkedWrongWithoutModelCall() {
        String shortId = questionService.create(new QuestionPayload("SHORT", null, null, List.of("ai"),
                "简答", List.of(), null, List.of(), List.of(), "参考", null, null, null), "1", null).id();
        Paper paper = paperService.create("1", "管理员", new PaperPayload("AI 题单", null,
                Paper.MODE_RULE, null, List.of("ai"), List.of(), List.of(), 10, null,
                Paper.SUBJECTIVE_AI, Paper.STATUS_PUBLISHED));
        PracticeSession session = practiceService.attemptPaper("100", paper.id());
        PracticeSession finished = practiceService.submit("100", session.id(), List.of(
                new AnswerPayload(shortId, null, List.of(), List.of(), "  ")));
        assertEquals(0, finished.correctCount());
        assertEquals(Boolean.FALSE, new SessionAnswerView(finished, shortId).correct());
        assertFalse(AdminRecordService.needsReview(finished));
    }

    @Test
    void aiFailureAndUnavailableFallBackToReview() {
        String shortId = questionService.create(new QuestionPayload("SHORT", null, null, List.of("ai"),
                "简答", List.of(), null, List.of(), List.of(), "参考", null, null, null), "1", null).id();
        Paper paper = paperService.create("1", "管理员", new PaperPayload("AI 题单", null,
                Paper.MODE_RULE, null, List.of("ai"), List.of(), List.of(), 10, null,
                Paper.SUBJECTIVE_AI, Paper.STATUS_PUBLISHED));

        // 宿主 AI 能力不可用：保持待审核
        PracticeSession unavailable = practiceService.submit("100",
                practiceService.attemptPaper("100", paper.id()).id(),
                List.of(new AnswerPayload(shortId, null, List.of(), List.of(), "答")));
        assertNull(new SessionAnswerView(unavailable, shortId).correct());
        assertTrue(AdminRecordService.needsReview(unavailable));

        // 模型调用失败：保持待审核，管理员可正常人工判分
        framework.setAi(new StubAi(true));
        PracticeSession failed = practiceService.submit("100",
                practiceService.attemptPaper("100", paper.id()).id(),
                List.of(new AnswerPayload(shortId, null, List.of(), List.of(), "答")));
        assertNull(new SessionAnswerView(failed, shortId).correct());
        assertTrue(AdminRecordService.needsReview(failed));
        PracticeSession reviewed = recordService.review(failed.id(), shortId, true);
        assertEquals(1, reviewed.correctCount());
        assertFalse(AdminRecordService.needsReview(reviewed));

        // 输出无法解析同样回落人工审核
        framework.setAi(new StubAi("无法判断"));
        PracticeSession unparsed = practiceService.submit("100",
                practiceService.attemptPaper("100", paper.id()).id(),
                List.of(new AnswerPayload(shortId, null, List.of(), List.of(), "答")));
        assertNull(new SessionAnswerView(unparsed, shortId).correct());
        assertTrue(AdminRecordService.needsReview(unparsed));
    }

    @Test
    void attemptRuleBypassesPracticeGateAndResultQueryRespectsOwnership() {
        settingsService.update(false, null, null, null, null, null, null);
        addSingle("act", null);
        DefaultQuestionBankApi api = new DefaultQuestionBankApi(practiceService, categoryService);
        QuestionBankApi.QuestionBankAttempt attempt = api.attempt("100",
                new QuestionBankApi.QuestionBankDrawRule(null, List.of("act"), List.of(), List.of(), 5, "REVIEW"));
        assertEquals(1, attempt.totalCount());
        var result = api.result("100", attempt.sessionId());
        assertTrue(result.isPresent());
        assertFalse(result.get().finished());
        assertEquals("REVIEW", practiceService.requireMine("100", attempt.sessionId()).subjectiveMode());
        // 会话归属隔离：他人查询为空
        assertTrue(api.result("200", attempt.sessionId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> api.attempt("100",
                new QuestionBankApi.QuestionBankDrawRule(null, List.of("act"), List.of(), List.of(), 0, null)));
        assertThrows(IllegalArgumentException.class, () -> api.attempt("100",
                new QuestionBankApi.QuestionBankDrawRule(null, List.of("none"), List.of(), List.of(), 1, null)));
        assertThrows(IllegalArgumentException.class, () -> api.attempt(" ",
                new QuestionBankApi.QuestionBankDrawRule(null, List.of("act"), List.of(), List.of(), 1, null)));
    }

    /** 测试用 AI 假实现：固定输出或固定失败。 */
    private static final class StubAi implements online.yudream.base.plugin.spi.system.ai.PluginAiService {
        private final String output;
        private final boolean failing;

        StubAi(String output) {
            this.output = output;
            this.failing = false;
        }

        StubAi(boolean failing) {
            this.output = null;
            this.failing = failing;
        }

        @Override
        public java.util.concurrent.CompletionStage<online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse> chat(
                online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest request) {
            if (failing) {
                java.util.concurrent.CompletableFuture<online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse> future =
                        new java.util.concurrent.CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("AI 服务不可用"));
                return future;
            }
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse(output, List.of()));
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor> tools() {
            return List.of();
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption> providers() {
            return List.of();
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiAgentOption> agents() {
            return List.of();
        }

        @Override
        public java.util.concurrent.CompletionStage<online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse> runAgent(
                String agent, online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest request) {
            return chat(request);
        }
    }

    /** 测试内快速读某题判定结果。 */
    private static final class SessionAnswerView {
        private final Boolean correct;

        SessionAnswerView(PracticeSession session, String questionId) {
            this.correct = session.answers().stream()
                    .filter(answer -> answer.questionId().equals(questionId))
                    .findFirst()
                    .map(answer -> answer.correct())
                    .orElse(null);
        }

        Boolean correct() {
            return correct;
        }
    }
}
