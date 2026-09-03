package online.yudream.base.plugin.material.interfaces;

import online.yudream.base.plugin.material.application.AdminMaterialService;
import online.yudream.base.plugin.material.application.CategoryService;
import online.yudream.base.plugin.material.application.MaterialService;
import online.yudream.base.plugin.material.application.PreviewService;
import online.yudream.base.plugin.material.bootstrap.MaterialPlugin;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.infrastructure.JsonSupport;
import online.yudream.base.plugin.material.interfaces.request.CategoryRequest;
import online.yudream.base.plugin.material.interfaces.request.StatusRequest;
import online.yudream.base.plugin.material.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 管理端端点：跨用户物料管理与分类维护。预览设置已上移为平台能力（系统配置 > 文件预览）。 */
public final class MaterialAdminController {
    private final AdminMaterialService adminService;
    private final MaterialService materialService;
    private final CategoryService categoryService;
    private final PreviewService previewService;
    private final JsonSupport json;

    public MaterialAdminController(AdminMaterialService adminService, MaterialService materialService,
                                   CategoryService categoryService, PreviewService previewService, JsonSupport json) {
        this.adminService = adminService;
        this.materialService = materialService;
        this.categoryService = categoryService;
        this.previewService = previewService;
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
