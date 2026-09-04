package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组卷记录：组卷中心保存的一次组卷结果（只存题目 id 引用，实时解析，缺失题目自动跳过）。
 * ownerId/ownerName 为归属人（COMPOSE 用户只能看自己的记录，MANAGE 可跨用户管理）。
 * shareToken 非空表示已分享，匿名公开页凭 token 在线查看（含答案解析）。
 */
public record ComposeRecord(
        String id,
        String title,
        String description,
        boolean withAnswers,
        List<String> questionIds,
        String ownerId,
        String ownerName,
        String shareToken,
        long shareAt,
        long createdAt,
        long updatedAt
) {
    public boolean shared() {
        return shareToken != null && !shareToken.isBlank();
    }

    public ComposeRecord withShare(String token, long shareAt) {
        return new ComposeRecord(id, title, description, withAnswers, questionIds, ownerId, ownerName,
                token, shareAt, createdAt, System.currentTimeMillis());
    }

    public ComposeRecord withoutShare() {
        return new ComposeRecord(id, title, description, withAnswers, questionIds, ownerId, ownerName,
                null, 0L, createdAt, System.currentTimeMillis());
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("title", title == null ? "" : title);
        DocValues.put(doc, "description", description);
        doc.put("withAnswers", withAnswers);
        doc.put("questionIds", questionIds == null ? List.of() : List.copyOf(questionIds));
        DocValues.put(doc, "ownerId", ownerId);
        DocValues.put(doc, "ownerName", ownerName);
        DocValues.put(doc, "shareToken", shareToken);
        doc.put("shareAt", shareAt);
        doc.put("createdAt", createdAt);
        doc.put("updatedAt", updatedAt);
        return doc;
    }

    public static ComposeRecord fromDoc(Map<String, Object> doc) {
        return new ComposeRecord(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "title", ""),
                DocValues.str(doc, "description"),
                DocValues.bool(doc, "withAnswers", false),
                DocValues.stringList(doc, "questionIds"),
                DocValues.str(doc, "ownerId"),
                DocValues.str(doc, "ownerName"),
                DocValues.str(doc, "shareToken"),
                DocValues.lng(doc, "shareAt"),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt")
        );
    }
}
