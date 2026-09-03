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
import online.yudream.base.plugin.material.application.dto.MaterialDetail;
import online.yudream.base.plugin.material.application.dto.MaterialSummary;
import online.yudream.base.plugin.material.application.dto.TagView;
import online.yudream.base.plugin.material.application.dto.VersionView;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.Ids;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialVersionRepository;
import online.yudream.base.plugin.material.infrastructure.PlatformFileIntake;
import online.yudream.base.plugin.material.infrastructure.ShareRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

/** 用户端物料用例：全部操作以 ownerId 限定归属，无管理员越权分支。 */
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

    public PageResult<MaterialSummary> listMine(String ownerId, String keyword, String type,
                                                String categoryId, String status, String tag, int page, int size) {
        String keywordFilter = keyword == null ? "" : keyword.trim();
        String typeFilter = type == null ? "" : type.trim();
        String categoryFilter = categoryId == null ? "" : categoryId.trim();
        String statusFilter = status == null ? "" : status.trim();
        String tagFilter = tag == null ? "" : tag.trim();
        List<Material> filtered = materials.scanAll().stream()
                .filter(material -> ownerId.equals(material.ownerId()))
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

    /** 标签云：统计自己未归档物料的标签使用次数，按次数降序，最多 30 个。 */
    public List<TagView> listMyTags(String ownerId) {
        Map<String, long[]> counts = new java.util.HashMap<>();
        Map<String, String> display = new java.util.HashMap<>();
        for (Material material : materials.scanAll()) {
            if (!ownerId.equals(material.ownerId()) || Material.STATUS_ARCHIVED.equals(material.status())
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
                .limit(30)
                .toList();
    }

    public MaterialDetail detailMine(String ownerId, String id) {
        Material material = requireOwn(ownerId, id);
        return toDetail(material);
    }

    public List<VersionView> listVersions(String ownerId, String id) {
        Material material = requireOwn(ownerId, id);
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
        StoredPayload payload = storePayload(storage.objectKey(id, 1), platform, MaterialType.extOf(filename));
        MaterialVersion version = new MaterialVersion(MaterialVersion.idOf(id, 1), id, 1,
                payload.objectKey(), filename, MaterialType.extOf(filename), payload.size(),
                payload.contentType(), null, ownerId, ownerName, now);
        versions.save(version);
        Material material = new Material(id, displayName(command.name(), filename), MaterialType.extOf(filename),
                MaterialType.fromFilename(filename), blankToNull(command.categoryId()), normalizeTags(command.tags()),
                ownerId, ownerName, 1, payload.size(), payload.contentType(), Material.STATUS_ACTIVE, now, now);
        materials.save(material);
        return toDetail(material);
    }

    public MaterialDetail updateMeta(String ownerId, String id, UpdateMaterialCommand command) {
        Material material = requireOwn(ownerId, id);
        String name = command.name() == null || command.name().isBlank() ? material.name() : command.name().trim();
        if (name.length() > 120) {
            throw new IllegalArgumentException("名称不能超过 120 字");
        }
        Material updated = material.withMeta(name, blankToNull(command.categoryId()),
                normalizeTags(command.tags()), System.currentTimeMillis());
        materials.save(updated);
        return toDetail(updated);
    }

    public MaterialDetail newVersion(String ownerId, String id, NewVersionCommand command) {
        String filename = sanitizeFilename(command.filename());
        synchronized (lockOf(id)) {
            Material material = requireOwn(ownerId, id);
            PluginStoredFile platform = intake.require(command.fileId());
            int next = material.currentVersion() + 1;
            long now = System.currentTimeMillis();
            String ext = MaterialType.extOf(filename);
            StoredPayload payload = storePayload(storage.objectKey(id, next), platform, ext);
            MaterialVersion version = new MaterialVersion(MaterialVersion.idOf(id, next), id, next,
                    payload.objectKey(), filename, ext, payload.size(), payload.contentType(),
                    normalizeNote(command.note()), ownerId, resolveUserName(ownerId), now);
            versions.save(version);
            Material updated = material.withCurrentVersion(version, now);
            materials.save(updated);
            return toDetail(updated);
        }
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
            storage.deleteQuietly(version.objectKey());
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

    public Material requireAny(String id) {
        return materials.findById(id)
                .orElseThrow(() -> new NotFoundException("物料不存在"));
    }

    public MaterialVersion resolveVersion(Material material, Integer versionNumber) {
        int target = versionNumber == null ? material.currentVersion() : versionNumber;
        return versions.find(material.id(), target)
                .orElseThrow(() -> new NotFoundException("版本 v" + target + " 不存在"));
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
            storage.deleteQuietly(version.objectKey());
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
        return name == null || name.isBlank() ? filename : name.trim();
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
