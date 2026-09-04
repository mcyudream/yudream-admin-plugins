package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * QQ 群抢答答对记录：每题首个答对者积一条。归属以 QQ 号为准（事件里唯一稳定的身份），
 * 绑定的系统账号在读取排行榜时实时反解，后绑定的账号自动承接历史成绩。
 */
public record QuizScore(
        String id,
        String qq,
        String connectionId,
        String channelId,
        String questionId,
        String questionType,
        long answeredAt
) {
    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("qq", qq);
        DocValues.put(doc, "connectionId", connectionId);
        DocValues.put(doc, "channelId", channelId);
        DocValues.put(doc, "questionId", questionId);
        DocValues.put(doc, "questionType", questionType);
        doc.put("answeredAt", answeredAt);
        return doc;
    }

    public static QuizScore fromDoc(Map<String, Object> doc) {
        return new QuizScore(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "qq", ""),
                DocValues.str(doc, "connectionId"),
                DocValues.str(doc, "channelId"),
                DocValues.str(doc, "questionId"),
                DocValues.str(doc, "questionType"),
                DocValues.lng(doc, "answeredAt")
        );
    }
}
