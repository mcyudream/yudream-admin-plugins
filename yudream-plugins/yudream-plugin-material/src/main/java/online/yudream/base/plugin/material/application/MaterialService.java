package online.yudream.base.plugin.material.application;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
import online.yudream.base.plugin.material.application.dto.DeptOption;
import online.yudream.base.plugin.material.application.dto.MaterialDetail;
import online.yudream.base.plugin.material.application.dto.MaterialSummary;
import online.yudream.base.plugin.material.application.dto.TagView;
import online.yudream.base.plugin.material.application.dto.VersionView;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.CoverImageSupport;
import online.yudream.base.plugin.material.infrastructure.Ids;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialVersionRepository;
import online.yudream.base.plugin.material.infrastructure.PlatformFileIntake;
import online.yudream.base.plugin.material.infrastructure.ShareRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.spi.system.user.PluginDeptOption;
import online.yudream.base.plugin.spi.system.user.PluginUserDept;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

/**
 * 用户端物料用例：写操作以 ownerId 限定归属，无管理员越权分支；
 * 读操作按 visibility 放行——属主全部可见，非属主可见 PUBLIC 与同部门（部门快照交集）的 DEPT。
 */
public final class MaterialService {
    private static final int MAX_TAGS = 8;
    private static final int MAX_TAG_LENGTH = 20;

    private final MaterialRepository materials;
    private final MaterialVersionRepository versions;
    private final CategoryRepository categories;
    private final MaterialFileStorage storage;
    private final PlatformFileIntake intake;
    private final ShareRepository shares;
    private final FrameworkServices framework;
    /** 每物料一把锁，防并发上传拿到相同版本号。 */
    private final Map<String, Object> versionLocks = new ConcurrentHashMap<>();

    public MaterialService(MaterialRepository materials, MaterialVersionRepository versions,
                           CategoryRepository categories, MaterialFileStorage storage,
                           PlatformFileIntake intake, ShareRepository shares, FrameworkServices framework) {
        this.materials = materials;
        this.versions = versions;
        this.categories = categories;
        this.storage = storage;
        this.intake = intake;
        this.shares = shares;
        this.framework = framework;
    }

    // ---------- 查询 ----------

    /** 库页列表：默认返回当前用户全部可见物料（自己的 + 他人公开/同部门的）；scope=mine 时只列自己的。 */
    public PageResult<MaterialSummary> listVisible(String viewerId, String scope, String keyword, String type,
                                                   String categoryId, String status, String tag, int page, int size) {
        boolean mineOnly = "mine".equalsIgnoreCase(scope == null ? "" : scope.trim());
        java.util.Set<String> viewerDepts = mineOnly ? java.util.Set.of() : deptIdsOf(viewerId);
        String keywordFilter = keyword == null ? "" : keyword.trim();
        String typeFilter = type == null ? "" : type.trim();
        String categoryFilter = categoryId == null ? "" : categoryId.trim();
        String statusFilter = status == null ? "" : status.trim();
        String tagFilter = tag == null ? "" : tag.trim();
        List<Material> filtered = materials.scanAll().stream()
                .filter(material -> mineOnly ? viewerId.equals(material.ownerId())
                        : canView(viewerId, material, viewerDepts))
                .filter(material -> statusFilter.isBlank()
                        ? !Material.STATUS_ARCHIVED.equals(material.status())
                        : statusFilter.equalsIgnoreCase(material.status()))
                .filter(material -> typeFilter.isBlank() || material.type().name().equalsIgnoreCase(typeFilter))
                .filter(material -> categoryFilter.isBlank() || categoryFilter.equals(material.categoryId()))
                .filter(material -> tagFilter.isBlank() || hasTag(material, tagFilter))
                .filter(material -> keywordFilter.isBlank() || matchesKeyword(material, keywordFilter))
                .toList();
        return page(filtered, page, size);
    }

    /** 标签云：统计可见范围内未归档物料的标签使用次数，按次数降序，最多 100 个（侧栏与选择器均支持搜索）。 */
    public List<TagView> listVisibleTags(String viewerId) {
        java.util.Set<String> viewerDepts = deptIdsOf(viewerId);
        Map<String, long[]> counts = new java.util.HashMap<>();
        Map<String, String> display = new java.util.HashMap<>();
        for (Material material : materials.scanAll()) {
            if (!canView(viewerId, material, viewerDepts) || Material.STATUS_ARCHIVED.equals(material.status())
                    || material.tags() == null) {
                continue;
            }
            for (String tag : material.tags()) {
                String key = tag.toLowerCase(Locale.ROOT);
                counts.computeIfAbsent(key, k -> new long[1])[0]++;
                display.putIfAbsent(key, tag);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new TagView(display.get(entry.getKey()), entry.getValue()[0]))
                .sorted(java.util.Comparator.comparingLong(TagView::count).reversed()
                        .thenComparing(TagView::name))
                .limit(100)
                .toList();
    }

    public MaterialDetail detailVisible(String viewerId, String id) {
        Material material = requireVisible(viewerId, id);
        return toDetail(material);
    }

    public List<VersionView> listVersions(String viewerId, String id) {
        Material material = requireVisible(viewerId, id);
        return versions.listByMaterial(material.id()).stream()
                .map(version -> VersionView.from(version, material.currentVersion()))
                .toList();
    }

    // ---------- 创建与更新 ----------

    public MaterialDetail create(String ownerId, CreateMaterialCommand command) {
        String filename = sanitizeFilename(command.filename());
        PluginStoredFile platform = intake.require(command.fileId());
        String id = Ids.newId();
        long now = System.currentTimeMillis();
        String ownerName = resolveUserName(ownerId);
        VisibilityAssignment visibility = resolveVisibilityForUser(ownerId, command.visibility(), command.deptIds());
        StoredPayload payload = storePayload(storage.objectKey(id, 1), platform, MaterialType.extOf(filename));
        String coverKey = storeCover(id, 1, MaterialType.extOf(filename), payload.objectKey());
        MaterialVersion version = new MaterialVersion(MaterialVersion.idOf(id, 1), id, 1,
                payload.objectKey(), filename, MaterialType.extOf(filename), payload.size(),
                payload.contentType(), null, ownerId, ownerName, now, coverKey);
        versions.save(version);
        Material material = new Material(id, displayName(command.name(), filename), MaterialType.extOf(filename),
                MaterialType.fromFilename(filename), blankToNull(command.categoryId()), normalizeTags(command.tags()),
                ownerId, ownerName, visibility.value(), visibility.deptIds(), visibility.deptNames(),
                1, payload.size(), payload.contentType(), Material.STATUS_ACTIVE, now, now);
        materials.save(material);
        return toDetail(material);
    }

    public MaterialDetail updateMeta(String ownerId, String id, UpdateMaterialCommand command) {
        Material material = requireOwn(ownerId, id);
        return applyMeta(material, command, true, ownerId);
    }

    /** 管理端代编辑：不校验归属，可见性按全量部门树解析。 */
    public MaterialDetail updateMetaAs(String id, UpdateMaterialCommand command) {
        Material material = requireAny(id);
        return applyMeta(material, command, false, null);
    }

    private MaterialDetail applyMeta(Material material, UpdateMaterialCommand command, boolean userPath, String actorId) {
        String name = command.name() == null || command.name().isBlank() ? material.name() : command.name().trim();
        if (name.length() > 120) {
            throw new IllegalArgumentException("名称不能超过 120 字");
        }
        Material updated = material.withMeta(name, blankToNull(command.categoryId()),
                normalizeTags(command.tags()), System.currentTimeMillis());
        if (command.visibility() != null && !command.visibility().isBlank()) {
            VisibilityAssignment visibility = userPath
                    ? resolveVisibilityForUser(actorId, command.visibility(), command.deptIds())
                    : resolveVisibilityForAdmin(command.visibility(), command.deptIds());
            updated = updated.withVisibility(visibility.value(), visibility.deptIds(), visibility.deptNames(),
                    System.currentTimeMillis());
        }
        materials.save(updated);
        return toDetail(updated);
    }

    public MaterialDetail newVersion(String ownerId, String id, NewVersionCommand command) {
        synchronized (lockOf(id)) {
            Material material = requireOwn(ownerId, id);
            return applyNewVersion(material, ownerId, command);
        }
    }

    /** 管理端代传新版本：版本记录的上传人记操作者。 */
    public MaterialDetail newVersionAs(String operatorId, String id, NewVersionCommand command) {
        synchronized (lockOf(id)) {
            Material material = requireAny(id);
            return applyNewVersion(material, operatorId, command);
        }
    }

    private MaterialDetail applyNewVersion(Material material, String actorId, NewVersionCommand command) {
        String filename = sanitizeFilename(command.filename());
        String id = material.id();
        PluginStoredFile platform = intake.require(command.fileId());
        int next = material.currentVersion() + 1;
        long now = System.currentTimeMillis();
        String ext = MaterialType.extOf(filename);
        StoredPayload payload = storePayload(storage.objectKey(id, next), platform, ext);
        String coverKey = storeCover(id, next, ext, payload.objectKey());
        MaterialVersion version = new MaterialVersion(MaterialVersion.idOf(id, next), id, next,
                payload.objectKey(), filename, ext, payload.size(), payload.contentType(),
                normalizeNote(command.note()), actorId, resolveUserName(actorId), now, coverKey);
        versions.save(version);
        Material updated = material.withCurrentVersion(version, now);
        materials.save(updated);
        return toDetail(updated);
    }

    // ---------- 回溯与删除 ----------

    public MaterialDetail restore(String ownerId, String id, int targetVersion) {
        synchronized (lockOf(id)) {
            Material material = requireOwn(ownerId, id);
            MaterialVersion version = versions.find(id, targetVersion)
                    .orElseThrow(() -> new IllegalArgumentException("版本 v" + targetVersion + " 不存在"));
            Material updated = material.withCurrentVersion(version, System.currentTimeMillis());
            materials.save(updated);
            return toDetail(updated);
        }
    }

    public void deleteVersion(String ownerId, String id, int versionNumber) {
        synchronized (lockOf(id)) {
            Material material = requireOwn(ownerId, id);
            if (versionNumber == material.currentVersion()) {
                throw new IllegalStateException("当前版本不可删除，请先回滚到其他版本");
            }
            MaterialVersion version = versions.find(id, versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException("版本 v" + versionNumber + " 不存在"));
            versions.delete(version.id());
            deleteStored(version);
        }
    }

    public void deleteMine(String ownerId, String id) {
        Material material = requireOwn(ownerId, id);
        deleteCascade(material);
    }

    // ---------- 共享辅助（管理端复用） ----------

    public Material requireOwn(String ownerId, String id) {
        Material material = materials.findById(id)
                .orElseThrow(() -> new NotFoundException("物料不存在"));
        if (!ownerId.equals(material.ownerId())) {
            throw new NotFoundException("物料不存在");
        }
        return material;
    }

    /** 读操作准入：属主或 visibility 放行；不可见一律按不存在处理，避免泄露存在性。 */
    public Material requireVisible(String viewerId, String id) {
        Material material = materials.findById(id)
                .orElseThrow(() -> new NotFoundException("物料不存在"));
        if (!canView(viewerId, material, deptIdsOf(viewerId))) {
            throw new NotFoundException("物料不存在");
        }
        return material;
    }

    private boolean canView(String viewerId, Material material, java.util.Set<String> viewerDepts) {
        if (viewerId.equals(material.ownerId())) {
            return true;
        }
        String visibility = material.visibility() == null ? Material.VISIBILITY_PRIVATE : material.visibility();
        if (Material.VISIBILITY_PUBLIC.equals(visibility)) {
            return true;
        }
        if (Material.VISIBILITY_DEPT.equals(visibility)) {
            return material.deptIds() != null && material.deptIds().stream().anyMatch(viewerDepts::contains);
        }
        return false;
    }

    /** 用户当前部门 id 集合；SPI 异常或用户不存在时按无部门处理（不会放大可见范围）。 */
    private java.util.Set<String> deptIdsOf(String userId) {
        try {
            List<PluginUserDept> depts = framework.users().listDepartments(Long.parseLong(userId));
            if (depts == null) {
                return java.util.Set.of();
            }
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (PluginUserDept dept : depts) {
                if (dept != null && dept.id() != null) {
                    ids.add(String.valueOf(dept.id()));
                }
            }
            return ids;
        }
        catch (Exception e) {
            return java.util.Set.of();
        }
    }

    /**
     * 用户端可见性解析：DEPT 必须显式给出可见部门，且全部属于操作者自己所在的部门；
     * 部门名从操作者部门列表解析。PRIVATE/PUBLIC 忽略 deptIds。
     */
    public VisibilityAssignment resolveVisibilityForUser(String userId, String raw, List<String> deptIds) {
        String normalized = normalizeVisibility(raw);
        if (!Material.VISIBILITY_DEPT.equals(normalized)) {
            return new VisibilityAssignment(normalized, List.of(), List.of());
        }
        List<String> requested = normalizeDeptIds(deptIds);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("可见范围为仅部门时请选择可见部门");
        }
        Map<String, String> own = new java.util.HashMap<>();
        for (DeptOption dept : myDepartments(userId)) {
            own.put(dept.id(), dept.name());
        }
        List<String> names = new java.util.ArrayList<>();
        for (String id : requested) {
            String name = own.get(id);
            if (name == null) {
                throw new IllegalArgumentException("只能选择您所在的部门作为可见范围");
            }
            names.add(name);
        }
        return new VisibilityAssignment(normalized, requested, names);
    }

    /** 管理端可见性解析：DEPT 必须显式给出可见部门，且全部存在于全量部门树。 */
    public VisibilityAssignment resolveVisibilityForAdmin(String raw, List<String> deptIds) {
        String normalized = normalizeVisibility(raw);
        if (!Material.VISIBILITY_DEPT.equals(normalized)) {
            return new VisibilityAssignment(normalized, List.of(), List.of());
        }
        List<String> requested = normalizeDeptIds(deptIds);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("可见范围为仅部门时请选择可见部门");
        }
        Map<String, String> all = new java.util.HashMap<>();
        for (DeptOption dept : departmentOptions(null)) {
            all.put(dept.id(), dept.name());
        }
        List<String> names = new java.util.ArrayList<>();
        for (String id : requested) {
            String name = all.get(id);
            if (name == null) {
                throw new IllegalArgumentException("部门不存在或已被删除");
            }
            names.add(name);
        }
        return new VisibilityAssignment(normalized, requested, names);
    }

    /** 当前用户加入的部门选项（用户端选择器数据源，只暴露自己所在部门）。 */
    public List<DeptOption> myDepartments(String userId) {
        try {
            List<PluginUserDept> depts = framework.users().listDepartments(Long.parseLong(userId));
            if (depts == null) {
                return List.of();
            }
            return depts.stream()
                    .filter(dept -> dept != null && dept.id() != null)
                    .map(dept -> {
                        String name = dept.name() == null || dept.name().isBlank()
                                ? String.valueOf(dept.id()) : dept.name();
                        return new DeptOption(String.valueOf(dept.id()), name, name);
                    })
                    .toList();
        }
        catch (Exception e) {
            return List.of();
        }
    }

    /** 全量部门树拍平选项（管理端选择器数据源），label 带父级路径；SPI 异常时按空树处理。 */
    public List<DeptOption> departmentOptions(String keyword) {
        List<DeptOption> result = new java.util.ArrayList<>();
        try {
            List<PluginDeptOption> roots = framework.users().listDepartments(
                    keyword == null || keyword.isBlank() ? null : keyword.trim());
            for (PluginDeptOption root : roots == null ? List.<PluginDeptOption>of() : roots) {
                flattenDept(root, "", result);
            }
        }
        catch (Exception e) {
            return List.of();
        }
        return result;
    }

    private void flattenDept(PluginDeptOption node, String parentLabel, List<DeptOption> output) {
        if (node == null || node.id() == null || node.id().isBlank()) {
            return;
        }
        String name = node.name() == null || node.name().isBlank() ? node.id() : node.name();
        String label = parentLabel.isBlank() ? name : parentLabel + " / " + name;
        output.add(new DeptOption(node.id(), name, label));
        for (PluginDeptOption child : node.children() == null ? List.<PluginDeptOption>of() : node.children()) {
            flattenDept(child, label, output);
        }
    }

    private static String normalizeVisibility(String raw) {
        String normalized = raw == null || raw.isBlank()
                ? Material.VISIBILITY_PRIVATE : raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case Material.VISIBILITY_PRIVATE, Material.VISIBILITY_DEPT, Material.VISIBILITY_PUBLIC -> normalized;
            default -> throw new IllegalArgumentException("可见性仅支持 PRIVATE / DEPT / PUBLIC");
        };
    }

    private static List<String> normalizeDeptIds(List<String> deptIds) {
        if (deptIds == null) {
            return List.of();
        }
        return deptIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public record VisibilityAssignment(String value, List<String> deptIds, List<String> deptNames) {
    }

    public Material requireAny(String id) {
        return materials.findById(id)
                .orElseThrow(() -> new NotFoundException("物料不存在"));
    }

    /** 批量辅助（管理端复用）：仅改分类，categoryId 为空表示移出分类。 */
    public Material applyCategory(Material material, String categoryId) {
        Material updated = material.withMeta(material.name(), blankToNull(categoryId),
                material.tags(), System.currentTimeMillis());
        materials.save(updated);
        return updated;
    }

    /** 批量辅助（管理端复用）：append=true 合并去重（超 8 个拒绝），否则整体替换。 */
    public Material applyTags(Material material, List<String> tags, boolean append) {
        List<String> next;
        if (append) {
            List<String> merged = new java.util.ArrayList<>(material.tags() == null ? List.of() : material.tags());
            for (String tag : tags == null ? List.<String>of() : tags) {
                if (tag != null && !tag.isBlank() && !merged.contains(tag.trim())) {
                    merged.add(tag.trim());
                }
            }
            if (merged.size() > MAX_TAGS) {
                throw new IllegalArgumentException("追加后标签超过 " + MAX_TAGS + " 个上限");
            }
            next = normalizeTags(merged);
        } else {
            next = normalizeTags(tags);
        }
        Material updated = material.withMeta(material.name(), material.categoryId(), next, System.currentTimeMillis());
        materials.save(updated);
        return updated;
    }

    /** 批量辅助（管理端复用）：分类存在性校验，返回归一化后的 id（null 表示移出分类）。 */
    public String requireCategoryOrNull(String categoryId) {
        String normalized = blankToNull(categoryId);
        if (normalized == null) {
            return null;
        }
        if (!categoryNames().containsKey(normalized)) {
            throw new IllegalArgumentException("分类不存在");
        }
        return normalized;
    }

    public MaterialVersion resolveVersion(Material material, Integer versionNumber) {
        int target = versionNumber == null ? material.currentVersion() : versionNumber;
        return versions.find(material.id(), target)
                .orElseThrow(() -> new NotFoundException("版本 v" + target + " 不存在"));
    }

    /**
     * 确保版本有库页缩略图：新上传已在落库时生成；旧数据在首次签发封面时补生成。
     * 生成失败返回原版本（封面字段仍为空），调用方不得回退签发原图。
     */
    public MaterialVersion ensureCover(MaterialVersion version) {
        if (version == null) {
            return null;
        }
        if (hasCoverKey(version)) {
            return version;
        }
        if (!CoverImageSupport.rasterizable(version.ext())) {
            return version;
        }
        synchronized (lockOf(version.materialId())) {
            MaterialVersion latest = versions.find(version.materialId(), version.version()).orElse(version);
            if (hasCoverKey(latest)) {
                return latest;
            }
            String coverKey = storeCover(latest.materialId(), latest.version(), latest.ext(), latest.objectKey());
            if (coverKey == null) {
                return latest;
            }
            MaterialVersion updated = latest.withCoverObjectKey(coverKey);
            versions.save(updated);
            return updated;
        }
    }

    public byte[] readBytes(MaterialVersion version) {
        PluginStoredFile stored = storage.getOrNull(version.objectKey());
        if (stored == null || stored.inputStream() == null) {
            throw new NotFoundException("文件对象缺失，可能已被清理");
        }
        try (InputStream input = stored.inputStream()) {
            return input.readAllBytes();
        }
        catch (Exception e) {
            throw new IllegalStateException("读取文件失败：" + e.getMessage(), e);
        }
    }

    public String resolveVersionFilename(Material material, MaterialVersion version) {
        return version.originalName() != null ? version.originalName() : material.name();
    }

    void deleteCascade(Material material) {
        for (MaterialVersion version : versions.listByMaterial(material.id())) {
            versions.delete(version.id());
            deleteStored(version);
        }
        shares.deleteByMaterial(material.id());
        materials.delete(material.id());
    }

    MaterialDetail toDetail(Material material) {
        Map<String, String> names = categoryNames();
        MaterialVersion current = versions.find(material.id(), material.currentVersion()).orElse(null);
        return new MaterialDetail(MaterialSummary.from(material, names),
                current == null ? null : VersionView.from(current, material.currentVersion()));
    }

    Map<String, String> categoryNames() {
        Map<String, String> names = new java.util.HashMap<>();
        for (var category : categories.listAll()) {
            names.put(category.id(), category.name());
        }
        return names;
    }

    // ---------- 内部工具 ----------

    private record StoredPayload(String objectKey, long size, String contentType) {
    }

    /** 平台文件复制进插件命名空间；长度未知时落内存（宿主平台上传本身有大小限制）。 */
    private StoredPayload storePayload(String objectKey, PluginStoredFile platform, String ext) {
        String contentType = platform.contentType() != null && !platform.contentType().isBlank()
                ? platform.contentType() : MaterialType.mimeOf(ext);
        try {
            Long length = platform.contentLength();
            if (length != null && length >= 0) {
                try (InputStream input = platform.inputStream()) {
                    storage.put(objectKey, input, length, contentType);
                }
                return new StoredPayload(objectKey, length, contentType);
            }
            byte[] bytes;
            try (InputStream input = platform.inputStream()) {
                bytes = input.readAllBytes();
            }
            storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
            return new StoredPayload(objectKey, bytes.length, contentType);
        }
        catch (Exception e) {
            throw new IllegalStateException("文件落库失败：" + e.getMessage(), e);
        }
    }

    /** 从已落库原图生成 JPEG 缩略图；不可栅格化或解码失败时返回 null，不影响原文件。 */
    private String storeCover(String materialId, int version, String ext, String sourceObjectKey) {
        if (!CoverImageSupport.rasterizable(ext)) {
            return null;
        }
        PluginStoredFile stored = storage.getOrNull(sourceObjectKey);
        if (stored == null || stored.inputStream() == null) {
            return null;
        }
        try (InputStream input = stored.inputStream()) {
            byte[] jpeg = CoverImageSupport.thumbnailJpeg(input);
            if (jpeg == null || jpeg.length == 0) {
                return null;
            }
            String coverKey = storage.coverObjectKey(materialId, version);
            storage.put(coverKey, new ByteArrayInputStream(jpeg), jpeg.length, CoverImageSupport.COVER_CONTENT_TYPE);
            return coverKey;
        }
        catch (Exception e) {
            return null;
        }
    }

    private void deleteStored(MaterialVersion version) {
        storage.deleteQuietly(version.objectKey());
        if (hasCoverKey(version)) {
            storage.deleteQuietly(version.coverObjectKey());
        }
    }

    private static boolean hasCoverKey(MaterialVersion version) {
        return version.coverObjectKey() != null && !version.coverObjectKey().isBlank();
    }

    private PageResult<MaterialSummary> page(List<Material> filtered, int page, int size) {
        Map<String, String> names = categoryNames();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<MaterialSummary> records = filtered.subList(from, to).stream()
                .map(material -> MaterialSummary.from(material, names))
                .toList();
        return PageResult.of(records, filtered.size());
    }

    static boolean matchesKeyword(Material material, String keyword) {
        String lowered = keyword.toLowerCase(Locale.ROOT);
        if (material.name().toLowerCase(Locale.ROOT).contains(lowered)) {
            return true;
        }
        return material.tags() != null && material.tags().stream()
                .anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(lowered));
    }

    private static boolean hasTag(Material material, String tagFilter) {
        return material.tags() != null && material.tags().stream()
                .anyMatch(tag -> tag.equalsIgnoreCase(tagFilter));
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String cleaned = filename.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1f]", "").trim();
        if (cleaned.isEmpty() || cleaned.length() > 200) {
            throw new IllegalArgumentException("文件名不合法");
        }
        return cleaned;
    }

    private static String displayName(String name, String filename) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("版本备注不能超过 200 字");
        }
        return trimmed;
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> normalized = tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .limit(MAX_TAGS)
                .toList();
        if (normalized.stream().anyMatch(tag -> tag.length() > MAX_TAG_LENGTH)) {
            throw new IllegalArgumentException("单个标签不能超过 " + MAX_TAG_LENGTH + " 字");
        }
        return normalized;
    }

    private String resolveUserName(String ownerId) {
        try {
            Optional<PluginUserProfile> profile = framework.users().findById(Long.parseLong(ownerId));
            return profile.map(user -> user.nickname() != null && !user.nickname().isBlank()
                    ? user.nickname() : user.username()).orElse(null);
        }
        catch (Exception e) {
            return null;
        }
    }

    private Object lockOf(String materialId) {
        return versionLocks.computeIfAbsent(materialId, key -> new Object());
    }
}
