package online.yudream.base.plugin.material.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * 子物料：挂在父物料（{@link Material}）下的具名文件槽，拥有完全独立的版本链。
 *
 * <p>典型场景：一张「明信片」父物料下挂「原图」png、「设计稿」psd、「成图」png 三个子物料；
 * 一个「logo」父物料下挂「非透明背景」jpg 与「透明背景」png 两个子物料。
 *
 * <p>id 为「{materialId}#i{4 位序号}」，同一父物料内按上传顺序递增，宿主文档存储 _id 字典序
 * 升序返回即上传顺序。子物料不单独设可见性，准入完全跟随父物料（属主可写，可见范围内可读）。
 * ext/type/size/contentType 始终跟随该子物料的当前版本。
 */
public record MaterialItem(
        String id,
        String materialId,
        String name,
        int sort,
        String ext,
        MaterialType type,
        long size,
        String contentType,
        int currentVersion,
        long createdAt,
        long updatedAt
) {
    /** 子物料 id：父物料 id + "#i" + 4 位序号，与版本 id 的「#+6 位数字」形态互不冲突。 */
    public static String idOf(String materialId, int sort) {
        return materialId + "#i" + String.format("%04d", sort);
    }

    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("materialId", materialId);
        doc.put("name", name);
        doc.put("sort", sort);
        DocValues.put(doc, "ext", ext);
        doc.put("type", type.name());
        doc.put("size", size);
        DocValues.put(doc, "contentType", contentType);
        doc.put("currentVersion", currentVersion);
        doc.put("createdAt", createdAt);
        doc.put("updatedAt", updatedAt);
        return doc;
    }

    public static MaterialItem fromDoc(Map<String, Object> doc) {
        return new MaterialItem(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "materialId", ""),
                DocValues.strOr(doc, "name", "未命名子物料"),
                DocValues.integer(doc, "sort"),
                DocValues.strOr(doc, "ext", ""),
                MaterialType.valueOf(DocValues.strOr(doc, "type", "OTHER")),
                DocValues.lng(doc, "size"),
                DocValues.strOr(doc, "contentType", "application/octet-stream"),
                DocValues.integerOr(doc, "currentVersion", 1),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt"));
    }

    public MaterialItem withName(String newName, long now) {
        return new MaterialItem(id, materialId, newName, sort, ext, type, size, contentType,
                currentVersion, createdAt, now);
    }

    /** 当前版本指针移动到指定版本；ext/type/size/contentType 一并跟随，与父物料一致。 */
    public MaterialItem withCurrentVersion(MaterialItemVersion version, long now) {
        return new MaterialItem(id, materialId, name, sort,
                version.ext() != null ? version.ext() : ext,
                MaterialType.fromExt(version.ext() != null ? version.ext() : ext),
                version.size(), version.contentType(), version.version(), createdAt, now);
    }
}
