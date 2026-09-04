package online.yudream.base.plugin.activityproof.interfaces.request;

import java.util.List;

public record ActivityQuizSaveRequest(
        Boolean enabled,
        String categoryId,
        List<String> tags,
        List<String> types,
        List<Integer> difficulties,
        Integer count,
        Integer passCorrect,
        String subjectiveMode
) {
}
