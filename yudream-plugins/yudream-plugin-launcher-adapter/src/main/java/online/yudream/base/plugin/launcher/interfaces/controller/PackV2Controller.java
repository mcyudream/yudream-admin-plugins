package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.bootstrap.LauncherAdapterPlugin;
import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 协议 v2 §5 分发端点：head/manifest/files/mrpack 对账与首装快照（匿名可达，对齐 v1 packs），
 * 以及 VANILLA 渠道版本发布（manage 权限）。
 */
public class PackV2Controller {

    private final LauncherHttpFacade facade;

    public PackV2Controller(LauncherHttpFacade facade) {
        this.facade = facade;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/packs/{packId}/versions/{versionId}/head", wrapResult = false)
    public PluginHttpResponse head(PluginHttpRequest request) {
        return facade.packHeadV2(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/packs/{packId}/versions/{versionId}/manifest", wrapResult = false)
    public PluginHttpResponse manifest(PluginHttpRequest request) {
        return facade.packManifestV2(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/packs/{packId}/versions/{versionId}/mrpack", wrapResult = false)
    public PluginHttpResponse mrpack(PluginHttpRequest request) {
        return facade.packMrpackV2(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/packs/{packId}/files/{sha1}", wrapResult = false)
    public PluginHttpResponse file(PluginHttpRequest request) {
        return facade.downloadManagedFileV2(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/v2/packs/{packId}/vanilla-versions", permission = LauncherAdapterPlugin.MANAGE_PERMISSION, wrapResult = false)
    public PluginHttpResponse publishVanilla(PluginHttpRequest request) {
        return facade.publishVanillaVersionV2(request);
    }
}
