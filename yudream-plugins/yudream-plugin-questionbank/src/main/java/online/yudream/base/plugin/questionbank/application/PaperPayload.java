package online.yudream.base.plugin.questionbank.application;

import java.util.List;

/** 管理端题单创建/更新负载。RULE 模式使用 categoryId/tags/types/difficulties/count；MANUAL 模式使用 questionIds。 */
public record PaperPayload(
        String name,
        String description,
        String mode,
        String categoryId,
        List<String> tags,
        List<String> types,
        List<Integer> difficulties,
        Integer count,
        List<String> questionIds,
        String subjectiveMode,
        String status
) {
}
