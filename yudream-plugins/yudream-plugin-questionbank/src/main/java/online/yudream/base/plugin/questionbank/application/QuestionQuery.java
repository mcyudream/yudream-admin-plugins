package online.yudream.base.plugin.questionbank.application;

import java.util.List;

/** 管理端题目查询条件（全部可选，空值不参与过滤）；ids 非空时只保留勾选的题目（导出用）。 */
public record QuestionQuery(
        String keyword,
        String categoryId,
        String tag,
        String type,
        Integer difficulty,
        String status,
        int page,
        int size,
        List<String> ids
) {
    public QuestionQuery(String keyword, String categoryId, String tag, String type,
            Integer difficulty, String status, int page, int size) {
        this(keyword, categoryId, tag, type, difficulty, status, page, size, List.of());
    }
}
