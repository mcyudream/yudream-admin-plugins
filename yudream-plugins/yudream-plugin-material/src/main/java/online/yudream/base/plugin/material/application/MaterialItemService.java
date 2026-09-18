package online.yudream.base.plugin.material.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.material.application.command.CreateMaterialItemCommand;
import online.yudream.base.plugin.material.application.command.NewItemVersionCommand;
import online.yudream.base.plugin.material.application.command.RenameMaterialItemCommand;
import online.yudream.base.plugin.material.application.dto.MaterialItemDetail;
import online.yudream.base.plugin.material.application.dto.MaterialItemVersionView;
import online.yudream.base.plugin.material.application.dto.MaterialItemView;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialItem;
import online.yudream.base.plugin.material.domain.MaterialItemVersion;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.infrastructure.CoverImageSupport;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.material.infrastructure.MaterialItemRepository;
import online.yudream.base.plugin.material.infrastructure.MaterialItemVersionRepository;
import online.yudream.base.plugin.material.infrastructure.PlatformFileIntake;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

/**
 * 子物料用例：每个子物料拥有与其他子物料完全独立的版本链（各自从 v1 起编号、可独立回滚与删版本）。
 *
 * <p>权限模型与父物料一致且不放大：用户端写操作先 {@link MaterialService#requireOwn} 校验归属，
 * 读操作先 {@link MaterialService#requireVisible} 校验可见范围；管理端走 requireAny，
 * 子物料不单独设可见性，也从不因持有管理权限而放宽用户端数据范围。
 */
public final class MaterialItemService implements MaterialItemCascade {
    /** 单个父物料的子物料数量上限，防止把一次性全量读取的子物料集合撑到不可用。 */
    public static final int MAX_ITEMS = 50;
    /** 子物料名称上限；文件夹导入拼接「目录名-文件名」时按此截断，保证导入不因深层目录整项失败。 */
    public static final int MAX_NAME_LENGTH = 60;

    private final MaterialService materialService;
    private final MaterialItemRepository items;
    private final MaterialItemVersionRepository itemVersions;
    private final MaterialFileStorage storage;
    private final PlatformFileIntake intake;
    private final StoredFileWriter writer;
    /** 每个父物料一把锁：并发新增子物料时序号不撞、并发上传版本时版本号不撞。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public MaterialItemService(MaterialService materialService, MaterialItemRepository items,
                               MaterialItemVersionRepository itemVersions, MaterialFileStorage storage,
                               PlatformFileIntake intake) {
        this.materialService = materialService;
        this.items = items;
        this.itemVersions = itemVersions;
        this.storage = storage;
        this.intake = intake;
        this.writer = new StoredFileWriter(storage);
    }

    // ---------- 查询 ----------

    public List<MaterialItemView> listVisible(String viewerId, String materialId) {
        Material material = materialService.requireVisible(viewerId, materialId);
        return listOf(material.id());
    }

    public MaterialItemDetail detailVisible(String viewerId, String materialId, String itemId) {
        Material material = materialService.requireVisible(viewerId, materialId);
        return toDetail(requireItem(material.id(), itemId));
    }

    public List<MaterialItemVersionView> listVersions(String viewerId, String materialId, String itemId) {
        Material material = materialService.requireVisible(viewerId, materialId);
        return versionsOf(requireItem(material.id(), itemId));
    }

    /** 管理端列表：跨用户，不校验归属与可见性（端点已由管理权限保护）。 */
    public List<MaterialItemView> listAsAdmin(String materialId) {
        return listOf(materialService.requireAny(materialId).id());
    }

    public MaterialItemDetail detailAsAdmin(String materialId, String itemId) {
        Material material = materialService.requireAny(materialId);
        return toDetail(requireItem(material.id(), itemId));
    }

    public List<MaterialItemVersionView> listVersionsAsAdmin(String materialId, String itemId) {
        Material material = materialService.requireAny(materialId);
        return versionsOf(requireItem(material.id(), itemId));
    }

    private List<MaterialItemView> listOf(String materialId) {
        return items.listByMaterial(materialId).stream().map(MaterialItemView::from).toList();
    }

    private List<MaterialItemVersionView> versionsOf(MaterialItem item) {
        return itemVersions.listByItem(item.id()).stream()
                .map(version -> MaterialItemVersionView.from(version, item.currentVersion()))
                .toList();
    }

    private MaterialItemDetail toDetail(MaterialItem item) {
        MaterialItemVersion current = itemVersions.find(item.id(), item.currentVersion()).orElse(null);
        return new MaterialItemDetail(MaterialItemView.from(item),
                current == null ? null : MaterialItemVersionView.from(current, item.currentVersion()));
    }

    // ---------- 新增与重命名 ----------

    public MaterialItemDetail createItem(String ownerId, String materialId, CreateMaterialItemCommand command) {
        Material material = materialService.requireOwn(ownerId, materialId);
        return doCreateItem(material, ownerId, command);
    }

    /** 管理端代新增：不校验归属，版本记录的上传人记操作者。 */
    public MaterialItemDetail createItemAs(String operatorId, String materialId, CreateMaterialItemCommand command) {
        Material material = materialService.requireAny(materialId);
        return doCreateItem(material, operatorId, command);
    }

    private MaterialItemDetail doCreateItem(Material material, String actorId, CreateMaterialItemCommand command) {
        synchronized (lockOf(material.id())) {
            List<MaterialItem> existing = items.listByMaterial(material.id());
            if (existing.size() >= MAX_ITEMS) {
                throw new IllegalArgumentException("单个物料最多上传 " + MAX_ITEMS + " 个子物料");
            }
            String filename = MaterialService.sanitizeFilename(command.filename());
            String ext = MaterialType.extOf(filename);
            int sort = existing.stream().mapToInt(MaterialItem::sort).max().orElse(0) + 1;
            String itemId = MaterialItem.idOf(material.id(), sort);
            long now = System.currentTimeMillis();
            PluginStoredFile platform = intake.require(command.fileId());
            StoredFileWriter.StoredPayload payload = writer.write(storage.itemObjectKey(material.id(), itemId, 1), platform, ext);
            String coverKey = writer.writeCover(storage.itemCoverObjectKey(material.id(), itemId, 1), ext, payload.objectKey());
            itemVersions.save(new MaterialItemVersion(MaterialItemVersion.idOf(itemId, 1), itemId, material.id(), 1,
                    payload.objectKey(), filename, ext, payload.size(), payload.contentType(),
                    MaterialService.normalizeNote(command.note()),
                    actorId, materialService.resolveUserName(actorId), now, coverKey));
            MaterialItem item = new MaterialItem(itemId, material.id(),
                    itemName(command.name(), filename), sort, ext, MaterialType.fromExt(ext),
                    payload.size(), payload.contentType(), 1, now, now);
            items.save(item);
            materialService.applyItemCount(material, existing.size() + 1);
            return toDetail(item);
        }
    }

    public MaterialItemView renameItem(String ownerId, String materialId, String itemId, RenameMaterialItemCommand command) {
        Material material = materialService.requireOwn(ownerId, materialId);
        return doRename(requireItem(material.id(), itemId), command);
    }

    public MaterialItemView renameItemAs(String materialId, String itemId, RenameMaterialItemCommand command) {
        Material material = materialService.requireAny(materialId);
        return doRename(requireItem(material.id(), itemId), command);
    }

    private MaterialItemView doRename(MaterialItem item, RenameMaterialItemCommand command) {
        String name = command.name() == null ? "" : command.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("子物料名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("子物料名称不能超过 " + MAX_NAME_LENGTH + " 字");
        }
        MaterialItem updated = item.withName(name, System.currentTimeMillis());
        items.save(updated);
        return MaterialItemView.from(updated);
    }

    // ---------- 版本管理（各子物料独立版本链） ----------

    public MaterialItemDetail newVersion(String ownerId, String materialId, String itemId, NewItemVersionCommand command) {
        Material material = materialService.requireOwn(ownerId, materialId);
        return doNewVersion(requireItem(material.id(), itemId), ownerId, command);
    }

    public MaterialItemDetail newVersionAs(String operatorId, String materialId, String itemId, NewItemVersionCommand command) {
        Material material = materialService.requireAny(materialId);
        return doNewVersion(requireItem(material.id(), itemId), operatorId, command);
    }

    private MaterialItemDetail doNewVersion(MaterialItem item, String actorId, NewItemVersionCommand command) {
        synchronized (lockOf(item.materialId())) {
            String filename = MaterialService.sanitizeFilename(command.filename());
            String ext = MaterialType.extOf(filename);
            int next = item.currentVersion() + 1;
            long now = System.currentTimeMillis();
            PluginStoredFile platform = intake.require(command.fileId());
            StoredFileWriter.StoredPayload payload = writer.write(
                    storage.itemObjectKey(item.materialId(), item.id(), next), platform, ext);
            String coverKey = writer.writeCover(
                    storage.itemCoverObjectKey(item.materialId(), item.id(), next), ext, payload.objectKey());
            MaterialItemVersion version = new MaterialItemVersion(MaterialItemVersion.idOf(item.id(), next),
                    item.id(), item.materialId(), next, payload.objectKey(), filename, ext, payload.size(),
                    payload.contentType(), MaterialService.normalizeNote(command.note()),
                    actorId, materialService.resolveUserName(actorId), now, coverKey);
            itemVersions.save(version);
            MaterialItem updated = item.withCurrentVersion(version, now);
            items.save(updated);
            return toDetail(updated);
        }
    }

    public MaterialItemDetail restoreVersion(String ownerId, String materialId, String itemId, int targetVersion) {
        Material material = materialService.requireOwn(ownerId, materialId);
        return doRestore(requireItem(material.id(), itemId), targetVersion);
    }

    public MaterialItemDetail restoreVersionAs(String materialId, String itemId, int targetVersion) {
        Material material = materialService.requireAny(materialId);
        return doRestore(requireItem(material.id(), itemId), targetVersion);
    }

    private MaterialItemDetail doRestore(MaterialItem item, int targetVersion) {
        synchronized (lockOf(item.materialId())) {
            MaterialItemVersion version = itemVersions.find(item.id(), targetVersion)
                    .orElseThrow(() -> new IllegalArgumentException("版本 v" + targetVersion + " 不存在"));
            MaterialItem updated = item.withCurrentVersion(version, System.currentTimeMillis());
            items.save(updated);
            return toDetail(updated);
        }
    }

    public void deleteVersion(String ownerId, String materialId, String itemId, int versionNumber) {
        Material material = materialService.requireOwn(ownerId, materialId);
        doDeleteVersion(requireItem(material.id(), itemId), versionNumber);
    }

    public void deleteVersionAs(String materialId, String itemId, int versionNumber) {
        Material material = materialService.requireAny(materialId);
        doDeleteVersion(requireItem(material.id(), itemId), versionNumber);
    }

    private void doDeleteVersion(MaterialItem item, int versionNumber) {
        synchronized (lockOf(item.materialId())) {
            if (versionNumber == item.currentVersion()) {
                throw new IllegalStateException("当前版本不可删除，请先回滚到其他版本");
            }
            MaterialItemVersion version = itemVersions.find(item.id(), versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException("版本 v" + versionNumber + " 不存在"));
            itemVersions.delete(version.id());
            deleteStored(version);
        }
    }

    // ---------- 删除子物料 ----------

    public void deleteItem(String ownerId, String materialId, String itemId) {
        Material material = materialService.requireOwn(ownerId, materialId);
        doDeleteItem(material, requireItem(material.id(), itemId));
    }

    public void deleteItemAs(String materialId, String itemId) {
        Material material = materialService.requireAny(materialId);
        doDeleteItem(material, requireItem(material.id(), itemId));
    }

    private void doDeleteItem(Material material, MaterialItem item) {
        synchronized (lockOf(material.id())) {
            for (MaterialItemVersion version : itemVersions.listByItem(item.id())) {
                itemVersions.delete(version.id());
                deleteStored(version);
            }
            items.delete(item.id());
            // 两次写都是整篇覆盖，必须用上一步的返回值继续，否则后写的会把前一步的清空结果盖回去
            Material current = material;
            // 被删的正是预览主文件时必须清空指针，否则父物料预览会一直指向已不存在的子物料
            if (item.id().equals(current.previewItemId())) {
                current = materialService.applyPreviewItem(current, null);
            }
            materialService.applyItemCount(current, items.listByMaterial(material.id()).size());
        }
    }

    // ---------- 预览主文件（组合物料的「主文件」等价物） ----------

    /**
     * 指定/取消组合物料的预览主文件：itemId 为空表示取消指定（预览回退第一个子物料）。
     * 指定后父物料的在线预览、下载与库页封面都跟随该子物料的当前版本。
     */
    public MaterialItemView setPreviewItem(String ownerId, String materialId, String itemId) {
        return doSetPreviewItem(materialService.requireOwn(ownerId, materialId), itemId);
    }

    /** 管理端代指定：不校验归属（端点已由管理权限保护）。 */
    public MaterialItemView setPreviewItemAs(String materialId, String itemId) {
        return doSetPreviewItem(materialService.requireAny(materialId), itemId);
    }

    private MaterialItemView doSetPreviewItem(Material material, String itemId) {
        String normalized = itemId == null || itemId.isBlank() ? null : itemId.trim();
        if (normalized == null) {
            materialService.applyPreviewItem(material, null);
            return null;
        }
        // 必须是本物料的子物料：借 requireItem 的归属校验，避免把别的物料的子物料设成本物料主文件
        MaterialItem item = requireItem(material.id(), normalized);
        materialService.applyPreviewItem(material, item.id());
        return MaterialItemView.from(item);
    }

    /**
     * 解析组合物料的预览主文件：显式指定的子物料优先，未指定（或指针已失效）时回退第一个子物料；
     * 没有任何子物料时返回 null，由调用方给出友好提示。主文件与子物料的形态判定一律以
     * {@link Material#mainFilePresent()} 为准，带主文件的物料不走这里。
     */
    public PreviewTarget resolvePreviewTarget(Material material) {
        String previewItemId = material.previewItemId();
        if (previewItemId != null) {
            MaterialItem target = items.findById(previewItemId)
                    .filter(candidate -> material.id().equals(candidate.materialId()))
                    .orElse(null);
            PreviewTarget resolved = target == null ? null : previewTargetOf(target);
            if (resolved != null) {
                return resolved;
            }
        }
        for (MaterialItem candidate : items.listByMaterial(material.id())) {
            PreviewTarget resolved = previewTargetOf(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private PreviewTarget previewTargetOf(MaterialItem item) {
        return itemVersions.find(item.id(), item.currentVersion())
                .map(version -> new PreviewTarget(item, version))
                .orElse(null);
    }

    /** 预览主文件解析结果：子物料 + 其当前版本。 */
    public record PreviewTarget(MaterialItem item, MaterialItemVersion version) {}

    /** 父物料删除时的级联清理：子物料、其全部版本记录与文件对象一并删除。 */
    @Override
    public void deleteByMaterial(String materialId) {
        for (MaterialItemVersion version : itemVersions.listByMaterial(materialId)) {
            itemVersions.delete(version.id());
            deleteStored(version);
        }
        for (MaterialItem item : items.listByMaterial(materialId)) {
            items.delete(item.id());
        }
    }

    // ---------- 供控制器读取文件/预览 ----------

    public MaterialItem requireVisibleItem(String viewerId, String materialId, String itemId) {
        Material material = materialService.requireVisible(viewerId, materialId);
        return requireItem(material.id(), itemId);
    }

    public MaterialItem requireAnyItem(String materialId, String itemId) {
        Material material = materialService.requireAny(materialId);
        return requireItem(material.id(), itemId);
    }

    /** 子物料集合（供库页为组合物料挑选代表封面，不额外做可见性判定，调用方需自行校验父物料）。 */
    public List<MaterialItem> itemsOf(String materialId) {
        return items.listByMaterial(materialId);
    }

    public MaterialItemVersion resolveVersion(MaterialItem item, Integer versionNumber) {
        int target = versionNumber == null ? item.currentVersion() : versionNumber;
        return itemVersions.find(item.id(), target)
                .orElseThrow(() -> new NotFoundException("版本 v" + target + " 不存在"));
    }

    public byte[] readBytes(MaterialItemVersion version) {
        return writer.readBytes(version.objectKey());
    }

    public String resolveVersionFilename(MaterialItem item, MaterialItemVersion version) {
        return version.originalName() != null ? version.originalName() : item.name();
    }

    /** 确保子物料版本有库页缩略图；生成失败返回原版本，调用方不得回退签发原图。 */
    public MaterialItemVersion ensureCover(MaterialItemVersion version) {
        if (version == null || hasCoverKey(version) || !CoverImageSupport.rasterizable(version.ext())) {
            return version;
        }
        synchronized (lockOf(version.materialId())) {
            MaterialItemVersion latest = itemVersions.find(version.itemId(), version.version()).orElse(version);
            if (hasCoverKey(latest)) {
                return latest;
            }
            String coverKey = writer.writeCover(
                    storage.itemCoverObjectKey(latest.materialId(), latest.itemId(), latest.version()),
                    latest.ext(), latest.objectKey());
            if (coverKey == null) {
                return latest;
            }
            MaterialItemVersion updated = latest.withCoverObjectKey(coverKey);
            itemVersions.save(updated);
            return updated;
        }
    }

    // ---------- 内部工具 ----------

    /** 子物料必须属于该父物料，否则按不存在处理，避免泄露其他物料的子物料。 */
    private MaterialItem requireItem(String materialId, String itemId) {
        MaterialItem item = items.findById(itemId)
                .orElseThrow(() -> new NotFoundException("子物料不存在"));
        if (!materialId.equals(item.materialId())) {
            throw new NotFoundException("子物料不存在");
        }
        return item;
    }

    private static boolean hasCoverKey(MaterialItemVersion version) {
        return version.coverObjectKey() != null && !version.coverObjectKey().isBlank();
    }

    private void deleteStored(MaterialItemVersion version) {
        writer.deleteObject(version.objectKey());
        if (hasCoverKey(version)) {
            writer.deleteObject(version.coverObjectKey());
        }
    }

    /** 名称留空时取文件名去扩展名。 */
    private static String itemName(String name, String filename) {
        String trimmed = name == null ? "" : name.trim();
        if (!trimmed.isEmpty()) {
            if (trimmed.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("子物料名称不能超过 " + MAX_NAME_LENGTH + " 字");
            }
            return trimmed;
        }
        return MaterialService.displayName(null, filename);
    }

    private Object lockOf(String materialId) {
        return locks.computeIfAbsent(materialId, key -> new Object());
    }
}
