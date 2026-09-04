package online.yudream.base.plugin.material.interfaces;

import online.yudream.base.plugin.material.application.AdminMaterialService;
import online.yudream.base.plugin.material.application.CategoryService;
import online.yudream.base.plugin.material.application.MaterialService;
import online.yudream.base.plugin.material.application.PreviewService;
import online.yudream.base.plugin.material.application.ShareService;
import online.yudream.base.plugin.material.application.command.NewVersionCommand;
import online.yudream.base.plugin.material.application.command.UpdateMaterialCommand;
import online.yudream.base.plugin.material.bootstrap.MaterialPlugin;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.infrastructure.JsonSupport;
import online.yudream.base.plugin.material.interfaces.request.BatchCategoryRequest;
import online.yudream.base.plugin.material.interfaces.request.BatchIdsRequest;
import online.yudream.base.plugin.material.interfaces.request.BatchStatusRequest;
import online.yudream.base.plugin.material.interfaces.request.BatchTagsRequest;
import online.yudream.base.plugin.material.interfaces.request.CategoryRequest;
import online.yudream.base.plugin.material.interfaces.request.CreateShareRequest;
import online.yudream.base.plugin.material.interfaces.request.NewVersionRequest;
import online.yudream.base.plugin.material.interfaces.request.StatusRequest;
import online.yudream.base.plugin.material.interfaces.request.UpdateMaterialRequest;
import online.yudream.base.plugin.material.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 管理端端点：跨用户物料管理、批量操作与分类维护。预览设置已上移为平台能力（系统配置 > 文件预览）。 */
public final class MaterialAdminController {
    private final AdminMaterialService adminService;
    private final MaterialService materialService;
    private final CategoryService categoryService;
    private final PreviewService previewService;
    private final ShareService shareService;
    private final JsonSupport json;

    public MaterialAdminController(AdminMaterialService adminService, MaterialService materialService,
                                   CategoryService categoryService, PreviewService previewService,
                                   ShareService shareService, JsonSupport json) {
        this.adminService = adminService;
        this.materialService = materialService;
        this.categoryService = categoryService;
        this.previewService = previewService;
        this.shareService = shareService;
        this.json = json;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(adminService.list(
                HttpSupport.first(request, "keyword"), HttpSupport.first(request, "type"),
                HttpSupport.first(request, "categoryId"), HttpSupport.first(request, "owner"),
                HttpSupport.first(request, "status"),
                HttpSupport.pageParam(request), HttpSupport.sizeParam(request, 20))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                adminService.detail(HttpSupport.segmentAfter(request.path(), "materials"))));
    }

    /** 代编辑元数据与可见范围（可见性按全量部门树解析）。 */
    @PluginHttpEndpoint(method = "PUT", path = "/admin/materials/{id}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            UpdateMaterialRequest body = json.read(request.body(), UpdateMaterialRequest.class);
            return PluginHttpResponse.ok(adminService.update(HttpSupport.segmentAfter(request.path(), "materials"),
                    new UpdateMaterialCommand(body.name(), body.categoryId(), body.tags(), body.visibility(),
                            body.deptIds())));
        });
    }

    /** 代传新版本：版本记录的上传人记操作者。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/materials/{id}/versions", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse newVersion(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            NewVersionRequest body = json.read(request.body(), NewVersionRequest.class);
            return PluginHttpResponse.ok(adminService.newVersion(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    new NewVersionCommand(body.fileId(), body.filename(), body.note())));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/materials/{id}/shares", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createShare(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CreateShareRequest body = json.read(request.body(), CreateShareRequest.class);
            return PluginHttpResponse.ok(shareService.createAs(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"), body.expiresInHours(), body.note()));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/shares", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse listShares(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(java.util.Map.of("records",
                shareService.listOf(HttpSupport.segmentAfter(request.path(), "materials")))));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/materials/{id}/shares/{shareId}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse revokeShare(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            shareService.revokeAny(HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "shares"));
            return PluginHttpResponse.ok(java.util.Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/materials/{id}/status", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse setStatus(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            StatusRequest body = json.read(request.body(), StatusRequest.class);
            return PluginHttpResponse.ok(adminService.setStatus(
                    HttpSupport.segmentAfter(request.path(), "materials"), body.status()));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/materials/{id}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            adminService.delete(HttpSupport.segmentAfter(request.path(), "materials"));
            return PluginHttpResponse.ok(java.util.Map.of("deleted", true));
        });
    }

    // ---------- 批量操作：逐项容错，返回每项失败原因 ----------

    @PluginHttpEndpoint(method = "PUT", path = "/admin/materials/batch/category", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse batchCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            BatchCategoryRequest body = json.read(request.body(), BatchCategoryRequest.class);
            return PluginHttpResponse.ok(adminService.batchCategory(body.ids(), body.categoryId()));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/materials/batch/tags", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse batchTags(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            BatchTagsRequest body = json.read(request.body(), BatchTagsRequest.class);
            return PluginHttpResponse.ok(adminService.batchTags(body.ids(), body.tags(), body.mode()));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/materials/batch/status", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse batchStatus(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            BatchStatusRequest body = json.read(request.body(), BatchStatusRequest.class);
            return PluginHttpResponse.ok(adminService.batchStatus(body.ids(), body.status()));
        });
    }

    /** POST 带 body 以避开 DELETE body 的兼容性坑。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/materials/batch/delete", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse batchDelete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            BatchIdsRequest body = json.read(request.body(), BatchIdsRequest.class);
            return PluginHttpResponse.ok(adminService.batchDelete(body.ids()));
        });
    }

    /** 全量部门树拍平选项（管理端可见范围选择器数据源）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/departments", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse departments(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(java.util.Map.of("records",
                adminService.departmentOptions(HttpSupport.first(request, "keyword")))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/download", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse download(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            Material material = materialService.requireAny(HttpSupport.segmentAfter(request.path(), "materials"));
            MaterialVersion version = materialService.resolveVersion(material, HttpSupport.optionalVersion(request));
            byte[] bytes = materialService.readBytes(version);
            return HttpSupport.download(materialService.resolveVersionFilename(material, version),
                    version.contentType(), bytes);
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/preview", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse preview(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            Material material = materialService.requireAny(HttpSupport.segmentAfter(request.path(), "materials"));
            MaterialVersion version = materialService.resolveVersion(material, HttpSupport.optionalVersion(request));
            return PluginHttpResponse.ok(previewService.preview(material, version, request));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/versions", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            Material material = materialService.requireAny(HttpSupport.segmentAfter(request.path(), "materials"));
            return PluginHttpResponse.ok(java.util.Map.of("records", materialService.listVersions(material.ownerId(), material.id())));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/categories", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse categories(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(java.util.Map.of("records", categoryService.list())));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/categories", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CategoryRequest body = json.read(request.body(), CategoryRequest.class);
            return PluginHttpResponse.ok(categoryService.create(body.name(), body.sort() == null ? 0 : body.sort()));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/categories/{id}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CategoryRequest body = json.read(request.body(), CategoryRequest.class);
            return PluginHttpResponse.ok(categoryService.update(
                    HttpSupport.segmentAfter(request.path(), "categories"), body.name(),
                    body.sort() == null ? 0 : body.sort()));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/categories/{id}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            categoryService.delete(HttpSupport.segmentAfter(request.path(), "categories"));
            return PluginHttpResponse.ok(java.util.Map.of("deleted", true));
        });
    }
}
