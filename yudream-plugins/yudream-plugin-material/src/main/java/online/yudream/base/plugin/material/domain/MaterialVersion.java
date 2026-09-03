package online.yudream.base.plugin.material.domain;

import java.util.HashMap;
import java.util.Map;

/** 物料的一次不可变版本。文件对象键为 materials/{materialId}/v{version}/file，原始文件名仅存元数据。 */
public record MaterialVersion(
        String id,
        String materialId,
        int version,
        String objectKey,
        String originalName,
        String ext,
        long size,
        String contentType,
        String note,
        String uploaderId,
        String uploaderName,
        long createdAt
) {
    public static String idOf(String materialId, int version) {
        return materialId + "#" + String.format("%06d", version);
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("materialId", materialId);
        doc.put("version", version);
        doc.put("objectKey", objectKey);
        DocValues.put(doc, "originalName", originalName);
        DocValues.put(doc, "ext", ext);
        doc.put("size", size);
        DocValues.put(doc, "contentType", contentType);
        DocValues.put(doc, "note", note);
        DocValues.put(doc, "uploaderId", uploaderId);
        DocValues.put(doc, "uploaderName", uploaderName);
        doc.put("createdAt", createdAt);
        return doc;
    }

    public static MaterialVersion fromDoc(Map<String, Object> doc) {
        return new MaterialVersion(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "materialId", ""),
                DocValues.integerOr(doc, "version", 1),
                DocValues.strOr(doc, "objectKey", ""),
                DocValues.str(doc, "originalName"),
                DocValues.str(doc, "ext"),
                DocValues.lng(doc, "size"),
                DocValues.strOr(doc, "contentType", "application/octet-stream"),
                DocValues.str(doc, "note"),
                DocValues.str(doc, "uploaderId"),
                DocValues.str(doc, "uploaderName"),
                DocValues.lng(doc, "createdAt"));
    }
}
