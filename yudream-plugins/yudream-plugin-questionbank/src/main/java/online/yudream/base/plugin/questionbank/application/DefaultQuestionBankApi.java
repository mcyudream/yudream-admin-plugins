package online.yudream.base.plugin.questionbank.application;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.questionbank.api.QuestionBankApi;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;

/** QuestionBankApi 默认实现：随机抽题委托 PracticeService.attemptRule，结果查询复用归属校验与待审核判定。 */
public final class DefaultQuestionBankApi implements QuestionBankApi {
    private final PracticeService practiceService;
    private final CategoryService categoryService;

    public DefaultQuestionBankApi(PracticeService practiceService, CategoryService categoryService) {
        this.practiceService = practiceService;
        this.categoryService = categoryService;
    }

    @Override
    public QuestionBankAttempt attempt(String userId, QuestionBankDrawRule rule) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (rule == null) {
            throw new IllegalArgumentException("抽题规则不能为空");
        }
        PracticeSession session = practiceService.attemptRule(userId, rule.categoryId(), rule.tags(),
                rule.types(), rule.difficulties(), rule.count(), rule.subjectiveMode());
        return new QuestionBankAttempt(session.id(), session.totalCount(), session.createdAt());
    }

    @Override
    public Optional<QuestionBankResult> result(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        PracticeSession session;
        try {
            session = practiceService.requireMine(userId, sessionId);
        }
        catch (NotFoundException e) {
            return Optional.empty();
        }
        return Optional.of(new QuestionBankResult(session.id(), session.status(), session.totalCount(),
                session.correctCount(), AdminRecordService.needsReview(session), session.submittedAt()));
    }

    @Override
    public List<QuestionBankCategoryOption> categories() {
        return categoryService.listWithCounts().stream()
                .map(view -> new QuestionBankCategoryOption(String.valueOf(view.get("id")), String.valueOf(view.get("name"))))
                .toList();
    }
}
