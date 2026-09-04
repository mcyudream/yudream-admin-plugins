package online.yudream.base.plugin.questionbank.api;

import java.util.List;
import java.util.Optional;

/**
 * 题库跨插件 API（供活动证明等插件做活动联动）：按规则现场随机抽题创建作答会话，并查询作答结果。
 * 作答过程在题库插件用户端页面完成（会话 id 可拼 /platform/plugins/questionbank/session?id=...）；
 * 会话归属以来源 userId 为准，消费方只能查询本人会话。
 */
public interface QuestionBankApi {
    /**
     * 按规则现场随机抽题创建作答会话。不受自由刷题开关约束（活动题单不是刷题入口）。
     * subjectiveMode：SELF 用户自评；REVIEW 管理员人工审核；AI 宿主 AI 自动判分（失败回落人工审核）；空按 SELF。
     */
    QuestionBankAttempt attempt(String userId, QuestionBankDrawRule rule);

    /** 查询作答结果；会话不存在或不属于该用户时为空。pendingReview 表示仍有简答题待判分，correctCount 尚非终值。 */
    Optional<QuestionBankResult> result(String userId, String sessionId);

    /** 分类选项（供消费方管理端做抽题规则选择器，避免手输分类 ID）。 */
    List<QuestionBankCategoryOption> categories();

    record QuestionBankDrawRule(String categoryId, List<String> tags, List<String> types,
            List<Integer> difficulties, int count, String subjectiveMode) {
    }

    record QuestionBankAttempt(String sessionId, int totalCount, long createdAt) {
    }

    record QuestionBankCategoryOption(String id, String name) {
    }

    record QuestionBankResult(String sessionId, String status, int totalCount, int correctCount,
            boolean pendingReview, long submittedAt) {
        public boolean finished() {
            return "FINISHED".equals(status);
        }
    }
}
