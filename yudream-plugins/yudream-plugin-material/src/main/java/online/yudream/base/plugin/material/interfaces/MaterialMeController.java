package online.yudream.base.plugin.material.interfaces;

import java.util.List;
import online.yudream.base.plugin.material.application.CategoryService;
import online.yudream.base.plugin.material.application.FolderImportService;
import online.yudream.base.plugin.material.application.MaterialItemService;
import online.yudream.base.plugin.material.application.MaterialService;
import online.yudream.base.plugin.material.application.NotFoundException;
import online.yudream.base.plugin.material.application.PreviewService;
import online.yudream.base.plugin.material.application.ShareService;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.FolderImportCommand;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
import online.yudream.base.plugin.material.application.dto.MaterialItemView;
import online.yudream.base.plugin.material.bootstrap.MaterialPlugin;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialItem;
import online.yudream.base.plugin.material.domain.MaterialItemVersion;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.infrastructure.JsonSupport;
import online.yudream.base.plugin.material.interfaces.request.CategoryRequest;
import online.yudream.base.plugin.material.interfaces.request.CreateMaterialRequest;
import online.yudream.base.plugin.material.interfaces.request.CreateShareRequest;
import online.yudream.base.plugin.material.interfaces.request.FolderImportRequest;
import online.yudream.base.plugin.material.interfaces.request.NewVersionRequest;
import online.yudream.base.plugin.material.interfaces.request.RestoreRequest;
import online.yudream.base.plugin.material.interfaces.request.SetPreviewItemRequest;
import online.yudream.base.plugin.material.interfaces.request.UpdateMaterialRequest;
import online.yudream.base.plugin.material.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 用户端物料端点：归属只取 principal.userId()，不存在管理员越权分支。 */
public final class MaterialMeController {
    private final MaterialService materialService;
    private final CategoryService categoryService;
    private final FolderImportService folderImportService;
    private final PreviewService previewService;
    private final ShareService shareService;
    private final MaterialItemService itemService;
    private final JsonSupport json;

    public MaterialMeController(MaterialService materialService, CategoryService categoryService,
                                FolderImportService folderImportService,
                                PreviewService previewService, ShareService shareService,
                                MaterialItemService itemService, JsonSupport json) {
        this.materialService = materialService;
        this.categoryService = categoryService;
        this.folderImportService = folderImportService;
        this.previewService = previewService;
        this.shareService = shareService;
        this.itemService = itemService;
        this.json = json;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(materialService.listVisible(
                HttpSupport.requireUserId(request),
                HttpSupport.first(request, "scope"),
                HttpSupport.first(request, "keyword"), HttpSupport.first(request, "type"),
                HttpSupport.first(request, "categoryId"), HttpSupport.first(request, "status"),
                HttpSupport.first(request, "tag"),
                HttpSupport.pageParam(request), HttpSupport.sizeParam(request, 20))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CreateMaterialRequest body = json.read(request.body(), CreateMaterialRequest.class);
            return PluginHttpResponse.ok(materialService.create(HttpSupport.requireUserId(request),
                    new CreateMaterialCommand(body.fileId(), body.filename(), body.name(), body.categoryId(),
                            body.tags(), body.visibility(), body.deptIds())));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials/import-folder", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse importFolder(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            FolderImportRequest body = json.read(request.body(), FolderImportRequest.class);
            List<FolderImportCommand.Item> items = body.items() == null ? List.of() : body.items().stream()
                    .map(item -> new FolderImportCommand.Item(item.fileId(), item.filename(), item.name(), item.tags()))
                    .toList();
            FolderImportService.FolderImportResult result = folderImportService.importFolder(
                    HttpSupport.requireUserId(request),
                    new FolderImportCommand(body.mode(), body.name(), body.categoryId(), body.categoryName(),
                            body.visibility(), body.deptIds(), body.tags(), items));
            return PluginHttpResponse.ok(java.util.Map.of(
                    "mode", result.mode(),
                    "total", result.total(),
                    "created", result.created(),
                    "categoryId", result.categoryId() == null ? "" : result.categoryId(),
                    "categoryName", result.categoryName() == null ? "" : result.categoryName(),
                    "materialId", result.materialId() == null ? "" : result.materialId(),
                    "materialName", result.materialName() == null ? "" : result.materialName(),
                    "failures", result.failures().stream()
                            .map(failure -> java.util.Map.of("filename", failure.filename(),
                                    "message", failure.message() == null ? "未知错误" : failure.message()))
                            .toList()));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(materialService.detailVisible(
                HttpSupport.requireUserId(request), HttpSupport.segmentAfter(request.path(), "materials"))));
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/materials/{id}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            UpdateMaterialRequest body = json.read(request.body(), UpdateMaterialRequest.class);
            return PluginHttpResponse.ok(materialService.updateMeta(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    new UpdateMaterialCommand(body.name(), body.categoryId(), body.tags(), body.visibility(),
                            body.deptIds())));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/materials/{id}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            materialService.deleteMine(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"));
            return PluginHttpResponse.ok(java.util.Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/versions", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(java.util.Map.of("records", materialService.listVersions(
                HttpSupport.requireUserId(request), HttpSupport.segmentAfter(request.path(), "materials")))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials/{id}/versions", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse newVersion(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            NewVersionRequest body = json.read(request.body(), NewVersionRequest.class);
            return PluginHttpResponse.ok(materialService.newVersion(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    new NewVersionCommand(body.fileId(), body.filename(), body.note())));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials/{id}/restore", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse restore(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            RestoreRequest body = json.read(request.body(), RestoreRequest.class);
            if (body.version() == null || body.version() < 1) {
                throw new IllegalArgumentException("version 必须是正整数");
            }
            return PluginHttpResponse.ok(materialService.restore(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"), body.version()));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/materials/{id}/versions/{version}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse deleteVersion(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String raw = HttpSupport.segmentAfter(request.path(), "versions");
            int version = Integer.parseInt(raw);
            materialService.deleteVersion(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"), version);
            return PluginHttpResponse.ok(java.util.Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/download", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse download(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String viewerId = HttpSupport.requireUserId(request);
            Material material = materialService.requireVisible(viewerId, HttpSupport.segmentAfter(request.path(), "materials"));
            if (!material.mainFilePresent()) {
                // 组合物料没有主版本链：按「预览主文件」所指子物料的版本取值，下载与预览看到的是同一个文件
                MaterialItemService.PreviewTarget target = requirePreviewTarget(material);
                MaterialItemVersion version = itemService.resolveVersion(target.item(), HttpSupport.optionalVersion(request));
                return HttpSupport.download(itemService.resolveVersionFilename(target.item(), version),
                        version.contentType(), itemService.readBytes(version));
            }
            MaterialVersion version = materialService.resolveVersion(material, HttpSupport.optionalVersion(request));
            byte[] bytes = materialService.readBytes(version);
            return HttpSupport.download(materialService.resolveVersionFilename(material, version),
                    version.contentType(), bytes);
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/preview", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse preview(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String viewerId = HttpSupport.requireUserId(request);
            Material material = materialService.requireVisible(viewerId, HttpSupport.segmentAfter(request.path(), "materials"));
            if (!material.mainFilePresent()) {
                MaterialItemService.PreviewTarget target = requirePreviewTarget(material);
                MaterialItemVersion version = itemService.resolveVersion(target.item(), HttpSupport.optionalVersion(request));
                return PluginHttpResponse.ok(previewService.previewItem(target.item(), version, request));
            }
            MaterialVersion version = materialService.resolveVersion(material, HttpSupport.optionalVersion(request));
            return PluginHttpResponse.ok(previewService.preview(material, version, request));
        });
    }

    /** 设置/取消组合物料的预览主文件：itemId 留空表示取消指定，预览回退第一个子物料。 */
    @PluginHttpEndpoint(method = "PUT", path = "/me/materials/{id}/preview-item", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse setPreviewItem(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            SetPreviewItemRequest body = json.read(request.body(), SetPreviewItemRequest.class);
            MaterialItemView previewItem = itemService.setPreviewItem(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"), body.itemId());
            // 取消指定时 previewItem 为 null，Map.of 不接受 null，统一空串表达「未指定」
            return PluginHttpResponse.ok(java.util.Map.of("previewItemId",
                    previewItem == null ? "" : previewItem.id()));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials/{id}/shares", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse createShare(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CreateShareRequest body = json.read(request.body(), CreateShareRequest.class);
            return PluginHttpResponse.ok(shareService.create(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"), body.expiresInHours(), body.note()));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/shares", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse listShares(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(java.util.Map.of("records", shareService.list(
                HttpSupport.requireUserId(request), HttpSupport.segmentAfter(request.path(), "materials")))));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/materials/{id}/shares/{shareId}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse revokeShare(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            shareService.revoke(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "shares"));
            return PluginHttpResponse.ok(java.util.Map.of("deleted", true));
        });
    }

    /** 当前用户加入的部门选项（可见范围选择器数据源，只暴露自己所在部门）。 */
    @PluginHttpEndpoint(method = "GET", path = "/me/departments", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse myDepartments(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(java.util.Map.of("records",
                materialService.myDepartments(HttpSupport.requireUserId(request)))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/categories", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse categories(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            HttpSupport.requireUserId(request);
            return PluginHttpResponse.ok(java.util.Map.of("records", categoryService.list()));
        });
    }

    /**
     * 上传/编辑物料时的快捷新增分类：任何有物料库使用权限的人都能加，减少「先找管理员建分类」的来回。
     * 同名（忽略大小写）视为复用已有分类，返回之而不是报错——前端也会先做一次同样判断，
     * 这里只是兜住并发提交，保证不会因为重复点击产生两条同名分类。
     */
    @PluginHttpEndpoint(method = "POST", path = "/me/categories", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse createMyCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            HttpSupport.requireUserId(request);
            CategoryRequest body = json.read(request.body(), CategoryRequest.class);
            return PluginHttpResponse.ok(categoryService.findOrCreateByName(body.name()));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/tags", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse tags(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(java.util.Map.of("records",
                materialService.listVisibleTags(HttpSupport.requireUserId(request)))));
    }

    /** 批量签发图片物料的缩略图地址；非图片、无封面、不可见或已删除的 id 静默跳过，不回退签发原图。 */
    @PluginHttpEndpoint(method = "GET", path = "/me/covers", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse covers(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String viewerId = HttpSupport.requireUserId(request);
            List<String> ids = parseIds(HttpSupport.first(request, "ids"));
            List<java.util.Map<String, String>> records = new java.util.ArrayList<>();
            for (String id : ids) {
                try {
                    Material material = materialService.requireVisible(viewerId, id);
                    String coverUrl = coverUrlOf(material);
                    if (coverUrl == null || coverUrl.isBlank()) {
                        continue;
                    }
                    records.add(java.util.Map.of("id", id, "url", coverUrl));
                }
                catch (NotFoundException e) {
                    // 已删除或不可见的 id 不出现在结果中
                }
            }
            return PluginHttpResponse.ok(java.util.Map.of("records", records));
        });
    }

    /**
     * 库页代表封面：带主文件的物料取自己当前版本的缩略图（仅图片）；
     * 组合物料优先取「预览主文件」所指子物料的缩略图，其次第一个图片子物料，都没有则返回 null 由前端回退类型图标。
     */
    private String coverUrlOf(Material material) {
        if (material.mainFilePresent()) {
            if (material.type() != MaterialType.IMAGE) {
                return null;
            }
            return previewService.signedCoverPath(materialService.ensureCover(
                    materialService.resolveVersion(material, null)));
        }
        String designated = designatedCoverUrl(material);
        if (designated != null) {
            return designated;
        }
        for (MaterialItem item : itemService.itemsOf(material.id())) {
            if (item.type() != MaterialType.IMAGE) {
                continue;
            }
            String url = previewService.signedItemCoverPath(itemService.ensureCover(
                    itemService.resolveVersion(item, null)));
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }

    /** 预览主文件所指子物料的缩略图；未指定、已失效或该格式生成不出缩略图时返回 null 交给上层回退。 */
    private String designatedCoverUrl(Material material) {
        if (material.previewItemId() == null) {
            return null;
        }
        MaterialItemService.PreviewTarget target = itemService.resolvePreviewTarget(material);
        if (target == null || !material.previewItemId().equals(target.item().id())) {
            return null;
        }
        String url = previewService.signedItemCoverPath(itemService.ensureCover(target.version()));
        return url == null || url.isBlank() ? null : url;
    }

    /** 组合物料的主文件等价物：预览主文件所指子物料（未指定时回退第一个子物料），一个都没有则明确报错。 */
    private MaterialItemService.PreviewTarget requirePreviewTarget(Material material) {
        MaterialItemService.PreviewTarget target = itemService.resolvePreviewTarget(material);
        if (target == null) {
            throw new NotFoundException("组合物料还没有子物料，请先新增子物料或指定预览主文件");
        }
        return target;
    }

    private static List<String> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> ids = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
            if (ids.size() >= 60) {
                break;
            }
        }
        return ids;
    }
}
