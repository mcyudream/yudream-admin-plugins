package online.yudream.base.plugin.material.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物料聚合根。id 为「倒置毫秒时间戳-随机hex」，宿主文档存储 _id 字典序升序即最新在前。
 * ext/type/size/contentType 始终跟随当前版本。
 * visibility 控制非属主可见范围：PRIVATE 仅自己 / DEPT 同部门（deptIds 为上传或改可见性时的部门快照）/ PUBLIC 全站。
 * 历史文档缺 visibility 字段一律按 PRIVATE 处理。
 *
 * 物料是「父物料」：既可自带主文件（currentVersion ≥ 1），也可只作为组合容器。
 * 组合物料（currentVersion = 0，无主文件）下挂 0..N 个子物料（{@link MaterialItem}，各自独立版本链）。
 * itemCount 是子物料数量的冗余统计，只由 MaterialItemService 在子物料增删时维护，
 * 列表页因此不必为每行反查子物料集合。
 *
 * <p>previewItemId 是组合物料的「预览主文件」指针：指向自己的一个子物料，父物料的在线预览、下载与库页封面
 * 都跟随该子物料的<b>当前版本</b>（子物料升级后自动同步，不留死版本号）。未指定时回退第一个子物料。
 * 带主文件的普通物料不使用该字段；子物料被删除时由 MaterialItemService 清空指针，避免悬空。
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
        int itemCount,
        String previewItemId,
        long createdAt,
        long updatedAt
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_DEPT = "DEPT";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    /** 是否已有主文件；无主文件的物料是组合物料，文件全部来自子物料。 */
    public boolean mainFilePresent() {
        return currentVersion >= 1;
    }

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
        doc.put("itemCount", itemCount);
        DocValues.put(doc, "previewItemId", previewItemId);
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
                DocValues.integer(doc, "itemCount"),
                DocValues.str(doc, "previewItemId"),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt"));
    }

    public Material withMeta(String newName, String newCategoryId, List<String> newTags, long now) {
        return new Material(id, newName, ext, type, newCategoryId, newTags, ownerId, ownerName,
                visibility, deptIds, deptNames, currentVersion, size, contentType, status, itemCount,
                previewItemId, createdAt, now);
    }

    public Material withVisibility(String newVisibility, List<String> newDeptIds, List<String> newDeptNames, long now) {
        return new Material(id, name, ext, type, categoryId, tags, ownerId, ownerName,
                newVisibility, newDeptIds, newDeptNames, currentVersion, size, contentType, status, itemCount,
                previewItemId, createdAt, now);
    }

    public Material withCurrentVersion(MaterialVersion version, long now) {
        return new Material(id, name, version.ext() != null ? version.ext() : ext,
                MaterialType.fromExt(version.ext() != null ? version.ext() : ext),
                categoryId, tags, ownerId, ownerName, visibility, deptIds, deptNames,
                version.version(), version.size(), version.contentType(), status, itemCount,
                previewItemId, createdAt, now);
    }

    public Material withStatus(String newStatus, long now) {
        return new Material(id, name, ext, type, categoryId, tags, ownerId, ownerName,
                visibility, deptIds, deptNames, currentVersion, size, contentType, newStatus, itemCount,
                previewItemId, createdAt, now);
    }

    public Material withItemCount(int newItemCount, long now) {
        return new Material(id, name, ext, type, categoryId, tags, ownerId, ownerName,
                visibility, deptIds, deptNames, currentVersion, size, contentType, status,
                Math.max(0, newItemCount), previewItemId, createdAt, now);
    }

    /** 设置/清空组合物料的预览主文件指针（newPreviewItemId 为 null 表示取消指定）。 */
    public Material withPreviewItem(String newPreviewItemId, long now) {
        return new Material(id, name, ext, type, categoryId, tags, ownerId, ownerName,
                visibility, deptIds, deptNames, currentVersion, size, contentType, status, itemCount,
                newPreviewItemId, createdAt, now);
    }
}
