package online.yudream.base.plugin.questionbank.domain;

import java.util.HashMap;
import java.util.Map;

/** 题目分类（扁平结构，与物料库分类一致）。 */
public record QuestionCategory(
        String id,
        String name,
        int sort,
        long createdAt,
        long updatedAt
) {
    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("name", name);
        doc.put("sort", sort);
        doc.put("createdAt", createdAt);
        doc.put("updatedAt", updatedAt);
        return doc;
    }

    public static QuestionCategory fromDoc(Map<String, Object> doc) {
        return new QuestionCategory(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "name", ""),
                DocValues.integer(doc, "sort"),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt")
        );
    }
}
