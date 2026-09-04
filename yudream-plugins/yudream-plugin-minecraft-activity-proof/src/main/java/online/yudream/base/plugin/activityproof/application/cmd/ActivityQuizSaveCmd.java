package online.yudream.base.plugin.activityproof.application.cmd;

import java.util.List;

public record ActivityQuizSaveCmd(
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
