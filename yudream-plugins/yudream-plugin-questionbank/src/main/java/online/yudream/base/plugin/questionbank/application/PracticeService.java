package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import online.yudream.base.plugin.questionbank.domain.Grader;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.QuestionCategory;
import online.yudream.base.plugin.questionbank.domain.QuestionType;
import online.yudream.base.plugin.questionbank.domain.SessionAnswer;
import online.yudream.base.plugin.questionbank.domain.SessionQuestion;
import online.yudream.base.plugin.questionbank.infrastructure.CategoryRepository;
import online.yudream.base.plugin.questionbank.infrastructure.Ids;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import online.yudream.base.plugin.questionbank.infrastructure.SessionRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

/**
 * 用户端抽题练习与题单作答用例：筛选池统计、随机抽题（快照固化）、提交判分、简答自评、个人记录。
 * 归属一律以来源 userId（principal）约束，不接受请求传入的用户标识。
 * 自由刷题受全局开关 practiceEnabled 约束；题单作答不受该开关影响。
 */
public final class PracticeService {
    private static final int MAX_DRAW = 50;

    private final QuestionRepository questions;
    private final CategoryRepository categories;
    private final SessionRepository sessions;
    private final PaperService papers;
    private final SettingsService settings;
    private final FrameworkServices framework;
    private final AiGraderService aiGrader;

    public PracticeService(QuestionRepository questions, CategoryRepository categories,
            SessionRepository sessions, PaperService papers, SettingsService settings,
            FrameworkServices framework, AiGraderService aiGrader) {
        this.questions = questions;
        this.categories = categories;
        this.sessions = sessions;
        this.papers = papers;
        this.settings = settings;
        this.framework = framework;
        this.aiGrader = aiGrader;
    }

    /** 用户端筛选元数据：启用题目的分类/标签计数，分类附带名称供选择器展示。 */
    public Map<String, Object> meta() {
        List<Question> enabled = enabledQuestions();
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        Map<String, Long> tagCounts = new LinkedHashMap<>();
        for (Question question : enabled) {
            if (question.categoryId() != null) {
                categoryCounts.merge(question.categoryId(), 1L, Long::sum);
            }
            for (String tag : question.tags() == null ? List.<String>of() : question.tags()) {
                tagCounts.merge(tag, 1L, Long::sum);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("total", enabled.size());
        List<Map<String, Object>> categoryItems = new ArrayList<>();
        for (QuestionCategory category : categories.listAll()) {
            long count = categoryCounts.getOrDefault(category.id(), 0L);
            if (count == 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", category.id());
            item.put("name", category.name());
            item.put("count", count);
            categoryItems.add(item);
        }
        root.put("categories", categoryItems);
        root.put("tags", tagCounts);
        List<Map<String, Object>> types = new ArrayList<>();
        for (QuestionType type : QuestionType.values()) {
            long count = enabled.stream().filter(question -> question.type() == type).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type.name());
            item.put("label", type.label());
            item.put("count", count);
            types.add(item);
        }
        root.put("types", types);
        root.put("practiceEnabled", settings.practiceEnabled());
        return root;
    }

    public int countPool(PracticeFilter filter) {
        settings.requirePracticeEnabled();
        return pool(filter).size();
    }

    public PracticeSession createSession(String userId, PracticeFilter filter) {
        settings.requirePracticeEnabled();
        int count = filter.count() == null ? 10 : filter.count();
        if (count < 1 || count > MAX_DRAW) {
            throw new IllegalArgumentException("抽题数量必须在 1~" + MAX_DRAW + " 之间");
        }
        List<Question> pool = new ArrayList<>(pool(filter));
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("没有符合条件的题目，请调整筛选条件");
        }
        Collections.shuffle(pool, new Random());
        List<Question> drawn = sortByType(pool.subList(0, Math.min(count, pool.size())));
        long now = System.currentTimeMillis();
        PracticeSession session = new PracticeSession(
                Ids.newId(),
                userId,
                resolveUserName(userId),
                blankToNull(filter.categoryId()),
                filter.tags() == null ? List.of() : List.copyOf(filter.tags()),
                filter.types() == null ? List.of() : List.copyOf(filter.types()),
                filter.difficulties() == null ? List.of() : List.copyOf(filter.difficulties()),
                count,
                drawn.stream().map(SessionQuestion::snapshotOf).toList(),
                List.of(),
                PracticeSession.STATUS_ONGOING,
                0,
                now,
                0L,
                null,
                null,
                Paper.SUBJECTIVE_SELF
        );
        sessions.save(session);
        return session;
    }

    /**
     * 题单作答：从已发布题单现场抽题创建会话（RULE 模式每次随机）。
     * 题单作答不受自由刷题开关约束；简答评分方式继承题单配置。
     */
    public PracticeSession attemptPaper(String userId, String paperId) {
        Paper paper = papers.requirePublished(paperId);
        List<Question> drawn = sortByType(papers.draw(paper));
        long now = System.currentTimeMillis();
        PracticeSession session = new PracticeSession(
                Ids.newId(),
                userId,
                resolveUserName(userId),
                paper.categoryId(),
                paper.tags(),
                paper.types(),
                paper.difficulties(),
                drawn.size(),
                drawn.stream().map(SessionQuestion::snapshotOf).toList(),
                List.of(),
                PracticeSession.STATUS_ONGOING,
                0,
                now,
                0L,
                paper.id(),
                paper.name(),
                paper.subjectiveMode()
        );
        sessions.save(session);
        return session;
    }

    /**
     * 跨插件随机抽题（如活动证明联动）：按给定规则现场抽题创建会话。
     * 活动题单不是刷题入口，不受自由刷题开关约束；subjectiveMode 由调用方在 SELF/REVIEW/AI 中指定。
     */
    public PracticeSession attemptRule(String userId, String categoryId, List<String> tags,
            List<String> types, List<Integer> difficulties, int count, String subjectiveMode) {
        if (count < 1 || count > MAX_DRAW) {
            throw new IllegalArgumentException("抽题数量必须在 1~" + MAX_DRAW + " 之间");
        }
        String mode = subjectiveMode == null || subjectiveMode.isBlank()
                ? Paper.SUBJECTIVE_SELF : subjectiveMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Paper.SUBJECTIVE_SELF.equals(mode) && !Paper.SUBJECTIVE_REVIEW.equals(mode)
                && !Paper.SUBJECTIVE_AI.equals(mode)) {
            throw new IllegalArgumentException("不支持的主观题评分方式：" + subjectiveMode);
        }
        List<Question> pool = new ArrayList<>(QuestionPool.filter(enabledQuestions(),
                blankToNull(categoryId), tags, types, difficulties));
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("没有符合条件的题目，请调整抽题规则");
        }
        Collections.shuffle(pool, new Random());
        List<Question> drawn = sortByType(pool.subList(0, Math.min(count, pool.size())));
        long now = System.currentTimeMillis();
        PracticeSession session = new PracticeSession(
                Ids.newId(),
                userId,
                resolveUserName(userId),
                blankToNull(categoryId),
                tags == null ? List.of() : List.copyOf(tags),
                types == null ? List.of() : List.copyOf(types),
                difficulties == null ? List.of() : List.copyOf(difficulties),
                count,
                drawn.stream().map(SessionQuestion::snapshotOf).toList(),
                List.of(),
                PracticeSession.STATUS_ONGOING,
                0,
                now,
                0L,
                null,
                null,
                mode
        );
        sessions.save(session);
        return session;
    }

    public PageResult<PracticeSession> listMine(String userId, int page, int size) {
        List<PracticeSession> mine = sessions.listAll().stream()
                .filter(session -> userId.equals(session.userId()))
                .toList();
        int from = Math.min((page - 1) * size, mine.size());
        int to = Math.min(from + size, mine.size());
        return PageResult.of(mine.subList(from, to), mine.size());
    }

    public PracticeSession requireMine(String userId, String sessionId) {
        PracticeSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("练习记录不存在"));
        if (!userId.equals(session.userId())) {
            throw new NotFoundException("练习记录不存在");
        }
        return session;
    }

    /** 提交作答：客观题立即判分，简答待自评；会话转为 FINISHED。 */
    public PracticeSession submit(String userId, String sessionId, List<AnswerPayload> payloads) {
        PracticeSession session = requireMine(userId, sessionId);
        if (!session.ongoing()) {
            throw new IllegalArgumentException("本次练习已提交，请勿重复操作");
        }
        Map<String, AnswerPayload> payloadByQuestion = new LinkedHashMap<>();
        for (AnswerPayload payload : payloads == null ? List.<AnswerPayload>of() : payloads) {
            if (payload != null && payload.questionId() != null) {
                payloadByQuestion.put(payload.questionId(), payload);
            }
        }
        List<SessionAnswer> answers = new ArrayList<>();
        int correctCount = 0;
        for (SessionQuestion question : session.questions()) {
            AnswerPayload payload = payloadByQuestion.get(question.questionId());
            SessionAnswer answer = new SessionAnswer(
                    question.questionId(),
                    payload == null ? null : payload.choice(),
                    payload == null ? List.of() : payload.choices(),
                    payload == null ? List.of() : payload.blanks(),
                    payload == null ? null : payload.text(),
                    null
            );
            Boolean correct = Grader.grade(question, answer);
            if (correct == null && session.aiMode() && question.questionType() == QuestionType.SHORT
                    && (answer.text() == null || answer.text().isBlank())) {
                // AI 模式下空白作答直接判错，不消耗模型调用
                correct = Boolean.FALSE;
            }
            if (Boolean.TRUE.equals(correct)) {
                correctCount++;
            }
            answers.add(new SessionAnswer(answer.questionId(), answer.choice(), answer.choices(),
                    answer.blanks(), answer.text(), correct));
        }
        PracticeSession finished = new PracticeSession(
                session.id(), session.userId(), session.userName(), session.categoryId(), session.tags(),
                session.types(), session.difficulties(), session.requestedCount(), session.questions(),
                List.copyOf(answers), PracticeSession.STATUS_FINISHED, correctCount,
                session.createdAt(), System.currentTimeMillis(),
                session.paperId(), session.paperName(), session.subjectiveMode()
        );
        sessions.save(finished);
        if (finished.aiMode()) {
            aiGrader.gradeAsync(finished);
        }
        return finished;
    }

    /** 简答自评：已结束的会话中把某道简答题标记为对/错；人工审核模式的会话禁止自评。 */
    public PracticeSession selfMark(String userId, String sessionId, String questionId, boolean correct) {
        PracticeSession session = requireMine(userId, sessionId);
        if (session.ongoing()) {
            throw new IllegalArgumentException("请先提交作答再自评");
        }
        if (session.reviewMode() || session.aiMode()) {
            throw new IllegalArgumentException("本题单简答题由管理员或 AI 判分，无需自评");
        }
        SessionQuestion target = session.questions().stream()
                .filter(question -> question.questionId().equals(questionId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("题目不在本次练习中"));
        if (target.questionType() != QuestionType.SHORT) {
            throw new IllegalArgumentException("只有简答题需要自评");
        }
        List<SessionAnswer> answers = new ArrayList<>();
        int correctCount = 0;
        for (SessionAnswer answer : session.answers()) {
            SessionAnswer next = answer.questionId().equals(questionId)
                    ? new SessionAnswer(answer.questionId(), answer.choice(), answer.choices(), answer.blanks(),
                            answer.text(), correct)
                    : answer;
            answers.add(next);
            if (Boolean.TRUE.equals(next.correct())) {
                correctCount++;
            }
        }
        PracticeSession updated = new PracticeSession(
                session.id(), session.userId(), session.userName(), session.categoryId(), session.tags(),
                session.types(), session.difficulties(), session.requestedCount(), session.questions(),
                List.copyOf(answers), session.status(), correctCount, session.createdAt(), session.submittedAt(),
                session.paperId(), session.paperName(), session.subjectiveMode()
        );
        sessions.save(updated);
        return updated;
    }

    public void deleteMine(String userId, String sessionId) {
        requireMine(userId, sessionId);
        sessions.delete(sessionId);
    }

    private List<Question> pool(PracticeFilter filter) {
        return QuestionPool.filter(enabledQuestions(), filter.categoryId(),
                filter.tags(), filter.types(), filter.difficulties());
    }

    /**
     * 抽题结果按题型归拢（单选→多选→判断→填空→简答），同题型内部保持随机顺序。
     * 前端再按题型分大标题展示，历史会话的旧顺序不受影响。
     */
    private static List<Question> sortByType(List<Question> drawn) {
        List<Question> sorted = new ArrayList<>(drawn);
        sorted.sort(java.util.Comparator.comparingInt(question -> question.type().ordinal()));
        return sorted;
    }

    private List<Question> enabledQuestions() {
        return questions.listAll().stream().filter(Question::enabled).toList();
    }

    private String resolveUserName(String userId) {
        try {
            Optional<PluginUserProfile> profile = framework.users().findById(Long.parseLong(userId));
            return profile.map(user -> user.nickname() != null && !user.nickname().isBlank()
                    ? user.nickname() : user.username()).orElse(null);
        }
        catch (Exception e) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
