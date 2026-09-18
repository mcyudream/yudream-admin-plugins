package online.yudream.base.plugin.material.interfaces;

import java.util.Map;
import online.yudream.base.plugin.material.application.MaterialItemService;
import online.yudream.base.plugin.material.application.PreviewService;
import online.yudream.base.plugin.material.application.command.CreateMaterialItemCommand;
import online.yudream.base.plugin.material.application.command.NewItemVersionCommand;
import online.yudream.base.plugin.material.application.command.RenameMaterialItemCommand;
import online.yudream.base.plugin.material.bootstrap.MaterialPlugin;
import online.yudream.base.plugin.material.domain.MaterialItem;
import online.yudream.base.plugin.material.domain.MaterialItemVersion;
import online.yudream.base.plugin.material.infrastructure.JsonSupport;
import online.yudream.base.plugin.material.interfaces.request.CreateMaterialItemRequest;
import online.yudream.base.plugin.material.interfaces.request.NewItemVersionRequest;
import online.yudream.base.plugin.material.interfaces.request.RenameMaterialItemRequest;
import online.yudream.base.plugin.material.interfaces.request.RestoreRequest;
import online.yudream.base.plugin.material.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 管理端子物料端点：跨用户维护任意物料的子物料与版本，全部由 {@code plugin:material:manage} 保护。
 * 用户端与子物料相关的读/写能力在这里成对提供，保证管理员维护闭环不缺失。
 */
public final class MaterialItemAdminController {
    private final MaterialItemService itemService;
    private final PreviewService previewService;
    private final JsonSupport json;

    public MaterialItemAdminController(MaterialItemService itemService, PreviewService previewService, JsonSupport json) {
        this.itemService = itemService;
        this.previewService = previewService;
        this.json = json;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/items", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records",
                itemService.listAsAdmin(HttpSupport.segmentAfter(request.path(), "materials")))));
    }

    /** 代新增子物料：不校验归属，版本记录的上传人记操作者。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/materials/{id}/items", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CreateMaterialItemRequest body = json.read(request.body(), CreateMaterialItemRequest.class);
            return PluginHttpResponse.ok(itemService.createItemAs(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    new CreateMaterialItemCommand(body.fileId(), body.filename(), body.name())));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/materials/{id}/items/{itemId}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse rename(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            RenameMaterialItemRequest body = json.read(request.body(), RenameMaterialItemRequest.class);
            return PluginHttpResponse.ok(itemService.renameItemAs(
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"),
                    new RenameMaterialItemCommand(body.name())));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/materials/{id}/items/{itemId}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            itemService.deleteItemAs(HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/items/{itemId}/versions", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", itemService.listVersionsAsAdmin(
                HttpSupport.segmentAfter(request.path(), "materials"),
                HttpSupport.segmentAfter(request.path(), "items")))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/materials/{id}/items/{itemId}/versions", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse newVersion(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            NewItemVersionRequest body = json.read(request.body(), NewItemVersionRequest.class);
            return PluginHttpResponse.ok(itemService.newVersionAs(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"),
                    new NewItemVersionCommand(body.fileId(), body.filename(), body.note())));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/materials/{id}/items/{itemId}/restore", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse restore(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            RestoreRequest body = json.read(request.body(), RestoreRequest.class);
            if (body.version() == null || body.version() < 1) {
                throw new IllegalArgumentException("version 必须是正整数");
            }
            return PluginHttpResponse.ok(itemService.restoreVersionAs(
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"), body.version()));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/materials/{id}/items/{itemId}/versions/{version}", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteVersion(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            int version = Integer.parseInt(HttpSupport.segmentAfter(request.path(), "versions"));
            itemService.deleteVersionAs(HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"), version);
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/items/{itemId}/download", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse download(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            MaterialItem item = itemService.requireAnyItem(HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"));
            MaterialItemVersion version = itemService.resolveVersion(item, HttpSupport.optionalVersion(request));
            byte[] bytes = itemService.readBytes(version);
            return HttpSupport.download(itemService.resolveVersionFilename(item, version), version.contentType(), bytes);
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/materials/{id}/items/{itemId}/preview", permission = MaterialPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse preview(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            MaterialItem item = itemService.requireAnyItem(HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"));
            MaterialItemVersion version = itemService.resolveVersion(item, HttpSupport.optionalVersion(request));
            return PluginHttpResponse.ok(previewService.previewItem(item, version, request));
        });
    }
}
