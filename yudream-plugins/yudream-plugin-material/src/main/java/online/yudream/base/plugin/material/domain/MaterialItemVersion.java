package online.yudream.base.plugin.material.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * 子物料的一次不可变版本，结构与 {@link MaterialVersion} 同构，版本独立编号（各子物料从 v1 开始）。
 *
 * <p>文件对象键为 materials/{materialId}/items/{itemId}/v{version}/file，
 * 缩略图为同级 cover.jpg；原始文件名仅存元数据。
 * version id 为「{itemId}#v{6 位版本号}」，父物料删除时按 materialId 反查级联清理。
 */
public record MaterialItemVersion(
        String id,
        String itemId,
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
        long createdAt,
        String coverObjectKey
) {
    public static String idOf(String itemId, int version) {
        return itemId + "#" + String.format("%06d", version);
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("itemId", itemId);
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
        DocValues.put(doc, "coverObjectKey", coverObjectKey);
        return doc;
    }

    public static MaterialItemVersion fromDoc(Map<String, Object> doc) {
        return new MaterialItemVersion(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "itemId", ""),
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
                DocValues.lng(doc, "createdAt"),
                DocValues.str(doc, "coverObjectKey"));
    }

    public MaterialItemVersion withCoverObjectKey(String newCoverObjectKey) {
        return new MaterialItemVersion(id, itemId, materialId, version, objectKey, originalName, ext, size,
                contentType, note, uploaderId, uploaderName, createdAt, newCoverObjectKey);
    }
}
