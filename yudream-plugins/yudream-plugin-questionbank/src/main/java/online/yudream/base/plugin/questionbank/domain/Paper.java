package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题单（试卷定义）。两种组题模式：
 * RULE（随机抽题）按 categoryId/tags/types/difficulties/count 规则，每次作答或打印时现场随机抽题；
 * MANUAL（手动选题）固定 questionIds 顺序。
 * subjectiveMode 决定简答题评分方式：SELF 用户对照参考答案自评；REVIEW 管理员人工审核；
 * AI 提交后调用宿主 AI 能力自动判分，失败或超时回落到人工审核队列。
 */
public record Paper(
        String id,
        String name,
        String description,
        String mode,
        String categoryId,
        List<String> tags,
        List<String> types,
        List<Integer> difficulties,
        int count,
        List<String> questionIds,
        String subjectiveMode,
        String status,
        String createdBy,
        String createdByName,
        long createdAt,
        long updatedAt
) {
    public static final String MODE_RULE = "RULE";
    public static final String MODE_MANUAL = "MANUAL";

    public static final String SUBJECTIVE_SELF = "SELF";
    public static final String SUBJECTIVE_REVIEW = "REVIEW";
    public static final String SUBJECTIVE_AI = "AI";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public boolean ruleMode() {
        return MODE_RULE.equals(mode);
    }

    public boolean published() {
        return STATUS_PUBLISHED.equals(status);
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("name", name == null ? "" : name);
        DocValues.put(doc, "description", description);
        doc.put("mode", mode == null ? MODE_RULE : mode);
        DocValues.put(doc, "categoryId", categoryId);
        doc.put("tags", tags == null ? List.of() : List.copyOf(tags));
        doc.put("types", types == null ? List.of() : List.copyOf(types));
        doc.put("difficulties", difficulties == null ? List.of() : List.copyOf(difficulties));
        doc.put("count", count);
        doc.put("questionIds", questionIds == null ? List.of() : List.copyOf(questionIds));
        doc.put("subjectiveMode", subjectiveMode == null ? SUBJECTIVE_SELF : subjectiveMode);
        doc.put("status", status == null ? STATUS_DRAFT : status);
        DocValues.put(doc, "createdBy", createdBy);
        DocValues.put(doc, "createdByName", createdByName);
        doc.put("createdAt", createdAt);
        doc.put("updatedAt", updatedAt);
        return doc;
    }

    public static Paper fromDoc(Map<String, Object> doc) {
        return new Paper(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "name", ""),
                DocValues.str(doc, "description"),
                DocValues.strOr(doc, "mode", MODE_RULE),
                DocValues.str(doc, "categoryId"),
                DocValues.stringList(doc, "tags"),
                DocValues.stringList(doc, "types"),
                DocValues.intList(doc, "difficulties"),
                DocValues.integerOr(doc, "count", 10),
                DocValues.stringList(doc, "questionIds"),
                DocValues.strOr(doc, "subjectiveMode", SUBJECTIVE_SELF),
                DocValues.strOr(doc, "status", STATUS_DRAFT),
                DocValues.str(doc, "createdBy"),
                DocValues.str(doc, "createdByName"),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt")
        );
    }
}
