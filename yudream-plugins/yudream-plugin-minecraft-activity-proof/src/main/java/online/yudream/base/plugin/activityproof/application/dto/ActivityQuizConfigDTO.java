package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

public record ActivityQuizConfigDTO(
        String activityId,
        boolean enabled,
        String categoryId,
        List<String> tags,
        List<String> types,
        List<Integer> difficulties,
        int count,
        int passCorrect,
        String subjectiveMode,
        boolean quizAvailable
) {
}
