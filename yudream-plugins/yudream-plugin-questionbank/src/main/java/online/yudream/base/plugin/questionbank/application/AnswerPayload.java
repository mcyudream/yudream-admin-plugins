package online.yudream.base.plugin.questionbank.application;

import java.util.List;

/** 用户提交作答的单题答案。按题型取用：choice（单选/判断）、choices（多选）、blanks（填空）、text（简答）。 */
public record AnswerPayload(
        String questionId,
        String choice,
        List<String> choices,
        List<String> blanks,
        String text
) {
}
