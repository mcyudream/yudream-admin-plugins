package online.yudream.base.plugin.material.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物料聚合根。id 为「倒置毫秒时间戳-随机hex」，宿主文档存储 _id 字典序升序即最新在前。
 * ext/type/size/contentType 始终跟随当前版本。
 * visibility 控制非属主可见范围：PRIVATE 仅自己 / DEPT 同部门（deptIds 为上传或改可见性时的部门快照）/ PUBLIC 全站。
 * 历史文档缺 visibility 字段一律按 PRIVATE 处理。
 */
public record Material(
        String id,
        String name,
        String ext,
        MaterialType type,
        String categoryId,
        List<String> tags,
        String ownerId,
        String ownerName,
        String visibility,
        List<String> deptIds,
        List<String> deptNames,
        int currentVersion,
        long size,
        String contentType,
        String status,
        long createdAt,
        long updatedAt
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_DEPT = "DEPT";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("name", name);
        doc.put("ext", ext);
        doc.put("type", type.name());
        DocValues.put(doc, "categoryId", categoryId);
        doc.put("tags", tags == null ? List.of() : List.copyOf(tags));
        doc.put("ownerId", ownerId);
        DocValues.put(doc, "ownerName", ownerName);
        doc.put("visibility", visibility == null ? VISIBILITY_PRIVATE : visibility);
        doc.put("deptIds", deptIds == null ? List.of() : List.copyOf(deptIds));
        doc.put("deptNames", deptNames == null ? List.of() : List.copyOf(deptNames));
        doc.put("currentVersion", currentVersion);
        doc.put("size", size);
        DocValues.put(doc, "contentType", contentType);
        doc.put("status", status);
        doc.put("createdAt", createdAt);
        doc.put("updatedAt", updatedAt);
        return doc;
    }

    public static Material fromDoc(Map<String, Object> doc) {
        return new Material(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "name", "未命名物料"),
                DocValues.strOr(doc, "ext", ""),
                MaterialType.valueOf(DocValues.strOr(doc, "type", "OTHER")),
                DocValues.str(doc, "categoryId"),
                DocValues.stringList(doc, "tags"),
                DocValues.strOr(doc, "ownerId", ""),
                DocValues.str(doc, "ownerName"),
                DocValues.strOr(doc, "visibility", VISIBILITY_PRIVATE),
                DocValues.stringList(doc, "deptIds"),
                DocValues.stringList(doc, "deptNames"),
                DocValues.integerOr(doc, "currentVersion", 1),
                DocValues.lng(doc, "size"),
                DocValues.strOr(doc, "contentType", "application/octet-stream"),
                DocValues.strOr(doc, "status", STATUS_ACTIVE),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt"));
    }

    public Material withMeta(String newName, String newCategoryId, List<String> newTags, long now) {
        return new Material(id, newName, ext, type, newCategoryId, newTags, ownerId, ownerName,
                visibility, deptIds, deptNames, currentVersion, size, contentType, status, createdAt, now);
    }

    public Material withVisibility(String newVisibility, List<String> newDeptIds, List<String> newDeptNames, long now) {
        return new Material(id, name, ext, type, categoryId, tags, ownerId, ownerName,
                newVisibility, newDeptIds, newDeptNames, currentVersion, size, contentType, status, createdAt, now);
    }

    public Material withCurrentVersion(MaterialVersion version, long now) {
        return new Material(id, name, version.ext() != null ? version.ext() : ext,
                MaterialType.fromExt(version.ext() != null ? version.ext() : ext),
                categoryId, tags, ownerId, ownerName, visibility, deptIds, deptNames,
                version.version(), version.size(), version.contentType(), status, createdAt, now);
    }

    public Material withStatus(String newStatus, long now) {
        return new Material(id, name, ext, type, categoryId, tags, ownerId, ownerName,
                visibility, deptIds, deptNames, currentVersion, size, contentType, newStatus, createdAt, now);
    }
}
