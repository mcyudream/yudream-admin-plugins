package online.yudream.base.plugin.material.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
import online.yudream.base.plugin.material.application.dto.DeptOption;
import online.yudream.base.plugin.material.application.dto.MaterialDetail;
import online.yudream.base.plugin.material.application.dto.MaterialSummary;
import online.yudream.base.plugin.material.domain.Material;

/** 管理端物料用例：跨用户范围，仅 /admin/** 端点调用。 */
public final class AdminMaterialService {
    /** 单次批量操作的 id 上限，对齐文件夹导入。 */
    public static final int MAX_BATCH = 200;

    private final MaterialService materialService;
    private final online.yudream.base.plugin.material.infrastructure.MaterialRepository materials;

    public AdminMaterialService(MaterialService materialService,
                                online.yudream.base.plugin.material.infrastructure.MaterialRepository materials) {
        this.materialService = materialService;
        this.materials = materials;
    }

    public PageResult<MaterialSummary> list(String keyword, String type, String categoryId,
                                            String owner, String status, int page, int size) {
        String statusFilter = status == null ? "" : status.trim();
        String typeFilter = type == null ? "" : type.trim();
        String categoryFilter = categoryId == null ? "" : categoryId.trim();
        String keywordFilter = keyword == null ? "" : keyword.trim();
        String ownerFilter = owner == null ? "" : owner.trim();
        List<Material> filtered = materials.scanAll().stream()
                .filter(material -> statusFilter.isBlank() || statusFilter.equalsIgnoreCase(material.status()))
                .filter(material -> typeFilter.isBlank() || material.type().name().equalsIgnoreCase(typeFilter))
                .filter(material -> categoryFilter.isBlank() || categoryFilter.equals(material.categoryId()))
                .filter(material -> keywordFilter.isBlank() || MaterialService.matchesKeyword(material, keywordFilter))
                .filter(material -> ownerFilter.isBlank()
                        || (material.ownerName() != null && material.ownerName().toLowerCase(Locale.ROOT).contains(ownerFilter.toLowerCase(Locale.ROOT)))
                        || material.ownerId().contains(ownerFilter))
                .toList();
        Map<String, String> names = materialService.categoryNames();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to).stream()
                .map(material -> MaterialSummary.from(material, names))
                .toList(), filtered.size());
    }

    public MaterialDetail detail(String id) {
        return materialService.toDetail(materialService.requireAny(id));
    }

    /** 代编辑元数据与可见范围（不校验归属，可见性按全量部门树解析）。 */
    public MaterialDetail update(String id, UpdateMaterialCommand command) {
        return materialService.updateMetaAs(id, command);
    }

    /** 代传新版本：版本记录的上传人记操作者。 */
    public MaterialDetail newVersion(String operatorId, String id, NewVersionCommand command) {
        return materialService.newVersionAs(operatorId, id, command);
    }

    /** 全量部门树拍平选项，供管理端选择器。 */
    public List<DeptOption> departmentOptions(String keyword) {
        return materialService.departmentOptions(keyword);
    }

    public MaterialDetail setStatus(String id, String status) {
        Material material = materialService.requireAny(id);
        Material updated = material.withStatus(normalizeStatus(status), System.currentTimeMillis());
        materials.save(updated);
        return materialService.toDetail(updated);
    }

    public void delete(String id) {
        materialService.deleteCascade(materialService.requireAny(id));
    }

    // ---------- 批量操作：逐项容错不中断，返回每项失败原因 ----------

    /** 批量移动分组；categoryId 为空表示移出分类，非空且不存在时整批拒绝。 */
    public BatchResult batchCategory(List<String> ids, String categoryId) {
        String normalized = materialService.requireCategoryOrNull(categoryId);
        return runBatch(ids, material -> {
            materialService.applyCategory(material, normalized);
            return null;
        });
    }

    /** 批量打标签；mode=APPEND 合并去重（超上限的物料列入失败），REPLACE 整体替换。 */
    public BatchResult batchTags(List<String> ids, List<String> tags, String mode) {
        boolean append = "APPEND".equalsIgnoreCase(mode == null ? "" : mode.trim());
        if (!append && (tags == null || tags.isEmpty())) {
            throw new IllegalArgumentException("覆盖模式下标签不能为空");
        }
        return runBatch(ids, material -> {
            materialService.applyTags(material, tags, append);
            return null;
        });
    }

    /** 批量归档/恢复。 */
    public BatchResult batchStatus(List<String> ids, String status) {
        String normalized = normalizeStatus(status);
        return runBatch(ids, material -> {
            materials.save(material.withStatus(normalized, System.currentTimeMillis()));
            return null;
        });
    }

    /** 批量删除：级联清理版本对象与分享。 */
    public BatchResult batchDelete(List<String> ids) {
        return runBatch(ids, material -> {
            materialService.deleteCascade(material);
            return null;
        });
    }

    private BatchResult runBatch(List<String> rawIds, java.util.function.Function<Material, Void> action) {
        List<String> ids = normalizeIds(rawIds);
        int succeeded = 0;
        List<BatchFailure> failures = new ArrayList<>();
        for (String id : ids) {
            String name = "";
            try {
                Material material = materialService.requireAny(id);
                name = material.name();
                action.apply(material);
                succeeded++;
            }
            catch (RuntimeException e) {
                failures.add(new BatchFailure(id, name,
                        e.getMessage() == null || e.getMessage().isBlank() ? "操作失败" : e.getMessage()));
            }
        }
        return new BatchResult(ids.size(), succeeded, List.copyOf(failures));
    }

    private static List<String> normalizeIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要操作的物料");
        }
        List<String> ids = rawIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要操作的物料");
        }
        if (ids.size() > MAX_BATCH) {
            throw new IllegalArgumentException("单次最多操作 " + MAX_BATCH + " 条");
        }
        return ids;
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Material.STATUS_ACTIVE.equals(normalized) && !Material.STATUS_ARCHIVED.equals(normalized)) {
            throw new IllegalArgumentException("状态仅支持 ACTIVE / ARCHIVED");
        }
        return normalized;
    }

    public record BatchResult(int total, int succeeded, List<BatchFailure> failures) {
    }

    public record BatchFailure(String id, String name, String message) {
    }
}
