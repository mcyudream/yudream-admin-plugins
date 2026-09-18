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
 * 用户端子物料端点：归属只取 principal.userId()，子物料准入完全跟随父物料的可见性，
 * 不存在管理员越权分支（管理端走 {@link MaterialItemAdminController}）。
 */
public final class MaterialItemMeController {
    private final MaterialItemService itemService;
    private final PreviewService previewService;
    private final JsonSupport json;

    public MaterialItemMeController(MaterialItemService itemService, PreviewService previewService, JsonSupport json) {
        this.itemService = itemService;
        this.previewService = previewService;
        this.json = json;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/items", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", itemService.listVisible(
                HttpSupport.requireUserId(request), HttpSupport.segmentAfter(request.path(), "materials")))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials/{id}/items", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CreateMaterialItemRequest body = json.read(request.body(), CreateMaterialItemRequest.class);
            return PluginHttpResponse.ok(itemService.createItem(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    new CreateMaterialItemCommand(body.fileId(), body.filename(), body.name())));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/materials/{id}/items/{itemId}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse rename(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            RenameMaterialItemRequest body = json.read(request.body(), RenameMaterialItemRequest.class);
            return PluginHttpResponse.ok(itemService.renameItem(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"),
                    new RenameMaterialItemCommand(body.name())));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/materials/{id}/items/{itemId}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            itemService.deleteItem(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/items/{itemId}/versions", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", itemService.listVersions(
                HttpSupport.requireUserId(request),
                HttpSupport.segmentAfter(request.path(), "materials"),
                HttpSupport.segmentAfter(request.path(), "items")))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials/{id}/items/{itemId}/versions", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse newVersion(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            NewItemVersionRequest body = json.read(request.body(), NewItemVersionRequest.class);
            return PluginHttpResponse.ok(itemService.newVersion(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"),
                    new NewItemVersionCommand(body.fileId(), body.filename(), body.note())));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/materials/{id}/items/{itemId}/restore", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse restore(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            RestoreRequest body = json.read(request.body(), RestoreRequest.class);
            if (body.version() == null || body.version() < 1) {
                throw new IllegalArgumentException("version 必须是正整数");
            }
            return PluginHttpResponse.ok(itemService.restoreVersion(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"), body.version()));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/materials/{id}/items/{itemId}/versions/{version}", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse deleteVersion(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            int version = Integer.parseInt(HttpSupport.segmentAfter(request.path(), "versions"));
            itemService.deleteVersion(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"), version);
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/items/{itemId}/download", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse download(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            MaterialItem item = itemService.requireVisibleItem(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"));
            MaterialItemVersion version = itemService.resolveVersion(item, HttpSupport.optionalVersion(request));
            byte[] bytes = itemService.readBytes(version);
            return HttpSupport.download(itemService.resolveVersionFilename(item, version), version.contentType(), bytes);
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/materials/{id}/items/{itemId}/preview", permission = MaterialPlugin.VIEW_PERMISSION)
    public PluginHttpResponse preview(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            MaterialItem item = itemService.requireVisibleItem(HttpSupport.requireUserId(request),
                    HttpSupport.segmentAfter(request.path(), "materials"),
                    HttpSupport.segmentAfter(request.path(), "items"));
            MaterialItemVersion version = itemService.resolveVersion(item, HttpSupport.optionalVersion(request));
            return PluginHttpResponse.ok(previewService.previewItem(item, version, request));
        });
    }
}
