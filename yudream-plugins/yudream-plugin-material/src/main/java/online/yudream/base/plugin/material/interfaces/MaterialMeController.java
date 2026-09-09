package online.yudream.base.plugin.material.interfaces;

import java.util.List;
import online.yudream.base.plugin.material.application.CategoryService;
import online.yudream.base.plugin.material.application.FolderImportService;
import online.yudream.base.plugin.material.application.MaterialService;
import online.yudream.base.plugin.material.application.NotFoundException;
import online.yudream.base.plugin.material.application.PreviewService;
import online.yudream.base.plugin.material.application.ShareService;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.FolderImportCommand;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
import online.yudream.base.plugin.material.bootstrap.MaterialPlugin;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.infrastructure.JsonSupport;
import online.yudream.base.plugin.material.interfaces.request.CreateMaterialRequest;
import online.yudream.base.plugin.material.interfaces.request.CreateShareRequest;
import online.yudream.base.plugin.material.interfaces.request.FolderImportRequest;
import online.yudream.base.plugin.material.interfaces.request.NewVersionRequest;
import online.yudream.base.plugin.material.interfaces.request.RestoreRequest;
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
    private final JsonSupport json;

    public MaterialMeController(MaterialService materialService, CategoryService categoryService,
                                FolderImportService folderImportService,
                                PreviewService previewService, ShareService shareService, JsonSupport json) {
        this.materialService = materialService;
        this.categoryService = categoryService;
        this.folderImportService = folderImportService;
        this.previewService = previewService;
        this.shareService = shareService;
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
                    new FolderImportCommand(body.categoryId(), body.categoryName(), body.visibility(),
                            body.deptIds(), body.tags(), items));
            return PluginHttpResponse.ok(java.util.Map.of(
                    "total", result.total(),
                    "created", result.created(),
                    "categoryId", result.categoryId() == null ? "" : result.categoryId(),
                    "categoryName", result.categoryName() == null ? "" : result.categoryName(),
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
            MaterialVersion version = materialService.resolveVersion(material, HttpSupport.optionalVersion(request));
            return PluginHttpResponse.ok(previewService.preview(material, version, request));
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
                    if (material.type() != MaterialType.IMAGE) {
                        continue;
                    }
                    MaterialVersion version = materialService.ensureCover(
                            materialService.resolveVersion(material, null));
                    String coverUrl = previewService.signedCoverPath(version);
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
