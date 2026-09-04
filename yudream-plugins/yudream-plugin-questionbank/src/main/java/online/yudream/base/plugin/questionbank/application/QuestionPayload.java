package online.yudream.base.plugin.questionbank.application;

import java.util.List;

/**
 * 题目创建/更新/导入的入参。categoryId 用于常规保存；categoryName 仅在导入时按名查找或新建分类。
 * 各答案字段按题型取用（见 Question 注释）。
 */
public record QuestionPayload(
        String type,
        String categoryId,
        String categoryName,
        List<String> tags,
        String content,
        List<String> options,
        String answer,
        List<String> answers,
        List<List<String>> blanks,
        String referenceAnswer,
        String analysis,
        Integer difficulty,
        String status
) {
}
