package online.yudream.base.plugin.activityproof.application.service;

import online.yudream.base.plugin.activityproof.application.cmd.ActivityQuizSaveCmd;
import online.yudream.base.plugin.activityproof.application.dto.ActivityQuizCategoryOptionDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityQuizConfigDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityQuizViewDTO;
import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityParticipation;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityQuizAttempt;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityQuizConfig;
import online.yudream.base.plugin.activityproof.domain.enumerate.VerifyStatus;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityProofRepository;
import online.yudream.base.plugin.activityproof.domain.valobj.ActivityBinding;
import online.yudream.base.plugin.questionbank.api.QuestionBankApi;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 活动答题（软依赖题库插件）：管理员配置随机抽题规则，用户在活动详情发起作答，
 * 达标后回写参与核验为已通过。题库插件不可用时配置可保存但用户端显式降级。
 */
public final class ActivityQuizService {

    private final ActivityProofRepository repository;
    private final Supplier<Optional<QuestionBankApi>> questionBankApi;

    public ActivityQuizService(ActivityProofRepository repository, Supplier<Optional<QuestionBankApi>> questionBankApi) {
        this.repository = repository;
        this.questionBankApi = questionBankApi;
    }

    /** 题库插件是否可用（软依赖，现取不缓存）。 */
    public boolean quizAvailable() {
        return questionBankApi.get().isPresent();
    }

    // ---------------------------------------------------------------- admin

    public ActivityQuizConfigDTO quizConfigForAdmin(String activityId) {
        requireActivity(activityId);
        return toConfigDTO(repository.quizConfig(activityId).orElseGet(() -> ActivityQuizConfig.disabled(activityId)));
    }

    public ActivityQuizConfigDTO saveQuizConfig(String activityId, ActivityQuizSaveCmd cmd) {
        requireActivity(activityId);
        boolean enabled = cmd != null && Boolean.TRUE.equals(cmd.enabled());
        String categoryId = cmd == null || cmd.categoryId() == null ? "" : cmd.categoryId().trim();
        List<String> tags = cmd == null || cmd.tags() == null ? List.of() : cmd.tags();
        List<String> types = cmd == null || cmd.types() == null ? List.of() : cmd.types();
        List<Integer> difficulties = cmd == null || cmd.difficulties() == null ? List.of() : cmd.difficulties();
        int count = cmd == null || cmd.count() == null ? 0 : cmd.count();
        String subjectiveMode = cmd == null ? null : cmd.subjectiveMode();
        if (!List.of(ActivityQuizConfig.SUBJECTIVE_SELF, ActivityQuizConfig.SUBJECTIVE_REVIEW, ActivityQuizConfig.SUBJECTIVE_AI)
                .contains(subjectiveMode == null || subjectiveMode.isBlank()
                        ? ActivityQuizConfig.SUBJECTIVE_SELF : subjectiveMode.trim().toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("未知的简答判分方式：" + subjectiveMode);
        }
        int passCorrect = cmd == null || cmd.passCorrect() == null || cmd.passCorrect() <= 0 ? count : cmd.passCorrect();
        if (enabled) {
            if (count < 1 || count > ActivityQuizConfig.MAX_COUNT) {
                throw new IllegalArgumentException("抽题数量需在 1-" + ActivityQuizConfig.MAX_COUNT + " 之间");
            }
            if (passCorrect < 1 || passCorrect > count) {
                throw new IllegalArgumentException("达标题数需在 1 与抽题数量之间");
            }
        }
        ActivityQuizConfig saved = repository.saveQuizConfig(new ActivityQuizConfig(activityId, enabled, categoryId,
                tags, types, difficulties, count, passCorrect, subjectiveMode, System.currentTimeMillis()));
        return toConfigDTO(saved);
    }

    /** 题库分类选项（管理端抽题规则选择器）；题库插件不可用时为空。 */
    public List<ActivityQuizCategoryOptionDTO> categoryOptions() {
        return questionBankApi.get()
                .map(api -> api.categories().stream()
                        .map(option -> new ActivityQuizCategoryOptionDTO(option.id(), option.name()))
                        .toList())
                .orElse(List.of());
    }

    // ---------------------------------------------------------------- user

    public ActivityQuizViewDTO quizView(String activityId, String userId) {
        Activity activity = requireActivity(activityId);
        ActivityQuizConfig config = repository.quizConfig(activityId).orElseGet(() -> ActivityQuizConfig.disabled(activityId));
        if (!config.enabled()) {
            return ActivityQuizViewDTO.disabled();
        }
        boolean available = questionBankApi.get().isPresent();
        boolean joined = repository.participation(activityId, userId)
                .map(ActivityParticipation::isJoined)
                .orElse(false);
        ActivityQuizAttempt attempt = repository.quizAttempt(activityId, userId).orElse(null);
        attempt = syncQuizResult(activity, config, attempt, userId);
        return toViewDTO(config, available, joined, attempt);
    }

    public ActivityQuizViewDTO startAttempt(String activityId, String userId) {
        Activity activity = requireActivity(activityId);
        ActivityQuizConfig config = repository.quizConfig(activityId)
                .filter(ActivityQuizConfig::enabled)
                .orElseThrow(() -> new IllegalArgumentException("该活动未配置答题环节"));
        QuestionBankApi api = questionBankApi.get()
                .orElseThrow(() -> new IllegalArgumentException("题库插件不可用，请联系管理员"));
        ActivityParticipation participation = repository.participation(activityId, userId)
                .filter(ActivityParticipation::isJoined)
                .orElseThrow(() -> new IllegalArgumentException("请先报名参与活动"));

        ActivityQuizAttempt attempt = repository.quizAttempt(activityId, userId).orElse(null);
        attempt = syncQuizResult(activity, config, attempt, userId);
        if (attempt != null && attempt.passed()) {
            throw new IllegalArgumentException("答题已达标，无需重复作答");
        }
        // 进行中的会话直接续答，不重新抽题
        if (attempt != null && !attempt.sessionId().isBlank()) {
            Optional<QuestionBankApi.QuestionBankResult> result = safeResult(api, userId, attempt.sessionId());
            if (result.isPresent() && !result.get().finished()) {
                return toViewDTO(config, true, true, attempt);
            }
        }
        QuestionBankApi.QuestionBankAttempt created = api.attempt(userId, new QuestionBankApi.QuestionBankDrawRule(
                blankToNull(config.categoryId()), config.tags(), config.types(), config.difficulties(),
                config.count(), config.subjectiveMode()));
        attempt = attempt == null
                ? ActivityQuizAttempt.create(activityId, userId, created.sessionId())
                : attempt.withSession(created.sessionId());
        attempt = repository.saveQuizAttempt(attempt);
        return toViewDTO(config, true, participation.isJoined(), attempt);
    }

    // ---------------------------------------------------------------- internals

    private Activity requireActivity(String activityId) {
        return repository.activity(activityId)
                .orElseThrow(() -> new IllegalArgumentException("活动不存在"));
    }

    /**
     * 拉取题库侧最新结果；达标则持久化答题进度，并在活动配置了「答题」核验方式时
     * 回写参与核验为已通过（核验引擎自身也按 QUIZ 绑定走 OR 语义判定）。
     */
    public ActivityQuizAttempt syncQuizResult(Activity activity, ActivityQuizConfig config, ActivityQuizAttempt attempt, String userId) {
        if (attempt == null || attempt.passed() || attempt.sessionId().isBlank()) {
            return attempt;
        }
        Optional<QuestionBankApi> apiHolder = questionBankApi.get();
        if (apiHolder.isEmpty()) {
            return attempt;
        }
        Optional<QuestionBankApi.QuestionBankResult> result = safeResult(apiHolder.get(), userId, attempt.sessionId());
        if (result.isEmpty() || !result.get().finished() || result.get().pendingReview()) {
            return attempt;
        }
        if (result.get().correctCount() < config.passCorrect()) {
            return attempt;
        }
        ActivityQuizAttempt passed = repository.saveQuizAttempt(attempt.markPassed());
        boolean quizBound = activity.bindings().stream().anyMatch(ActivityBinding::isQuiz);
        if (quizBound) {
            repository.participation(activity.id(), userId)
                    .filter(item -> item.isJoined() && item.verifyStatus() != VerifyStatus.PASSED)
                    .ifPresent(item -> repository.saveParticipation(item.withVerification(VerifyStatus.PASSED,
                            "活动答题达标（" + result.get().correctCount() + "/" + result.get().totalCount() + "）")));
        }
        return passed;
    }

    private Optional<QuestionBankApi.QuestionBankResult> safeResult(QuestionBankApi api, String userId, String sessionId) {
        try {
            return api.result(userId, sessionId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private ActivityQuizViewDTO toViewDTO(ActivityQuizConfig config, boolean available, boolean joined, ActivityQuizAttempt attempt) {
        String sessionId = null;
        String sessionStatus = null;
        Integer correctCount = null;
        Integer totalCount = null;
        boolean pendingReview = false;
        int attempts = 0;
        boolean passed = false;
        if (attempt != null) {
            attempts = attempt.attempts();
            passed = attempt.passed();
            sessionId = attempt.sessionId().isBlank() ? null : attempt.sessionId();
            if (sessionId != null) {
                Optional<QuestionBankApi.QuestionBankResult> result = questionBankApi.get()
                        .flatMap(api -> safeResult(api, attempt.userId(), attempt.sessionId()));
                if (result.isPresent()) {
                    sessionStatus = result.get().status();
                    totalCount = result.get().totalCount();
                    pendingReview = result.get().pendingReview();
                    if (result.get().finished() && !pendingReview) {
                        correctCount = result.get().correctCount();
                    }
                }
            }
        }
        return new ActivityQuizViewDTO(true, available, joined, config.count(), config.passCorrect(),
                config.subjectiveMode(), attempts, passed, sessionId, sessionStatus, correctCount, totalCount, pendingReview);
    }

    private ActivityQuizConfigDTO toConfigDTO(ActivityQuizConfig config) {
        return new ActivityQuizConfigDTO(config.activityId(), config.enabled(), config.categoryId(),
                config.tags(), config.types(), config.difficulties(), config.count(), config.passCorrect(),
                config.subjectiveMode(), questionBankApi.get().isPresent());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
