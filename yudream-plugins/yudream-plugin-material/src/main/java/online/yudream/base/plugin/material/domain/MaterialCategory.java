package online.yudream.base.plugin.material.domain;

import java.util.HashMap;
import java.util.Map;

/** 物料分类（管理员维护，用户端用于筛选）。 */
public record MaterialCategory(
        String id,
        String name,
        int sort,
        long createdAt
) {
    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("name", name);
        doc.put("sort", sort);
        doc.put("createdAt", createdAt);
        return doc;
    }

    public static MaterialCategory fromDoc(Map<String, Object> doc) {
        return new MaterialCategory(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "name", ""),
                DocValues.integer(doc, "sort"),
                DocValues.lng(doc, "createdAt"));
    }
}
