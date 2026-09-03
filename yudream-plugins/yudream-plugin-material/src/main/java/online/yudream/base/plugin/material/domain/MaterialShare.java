package online.yudream.base.plugin.material.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * 物料分享外链。文档 id 即 token（24 位 base64url 随机串），点查无需扫表。
 * token 是存储型凭证：撤销即删文档；expiresAt=0 表示永不过期。
 * 分享始终指向物料的当前版本——回滚/上传新版本后外链内容随之更新。
 */
public record MaterialShare(
        String token,
        String materialId,
        String createdById,
        String createdByName,
        String note,
        long expiresAt,
        long createdAt
) {
    public boolean expired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("materialId", materialId);
        DocValues.put(doc, "createdById", createdById);
        DocValues.put(doc, "createdByName", createdByName);
        DocValues.put(doc, "note", note);
        doc.put("expiresAt", expiresAt);
        doc.put("createdAt", createdAt);
        return doc;
    }

    public static MaterialShare fromDoc(Map<String, Object> doc) {
        return new MaterialShare(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "materialId", ""),
                DocValues.str(doc, "createdById"),
                DocValues.str(doc, "createdByName"),
                DocValues.str(doc, "note"),
                DocValues.lng(doc, "expiresAt"),
                DocValues.lng(doc, "createdAt"));
    }
}
