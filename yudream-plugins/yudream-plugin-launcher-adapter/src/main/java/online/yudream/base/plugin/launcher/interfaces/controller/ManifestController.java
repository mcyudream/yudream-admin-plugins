package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class ManifestController {

    private final LauncherHttpFacade http;

    public ManifestController(LauncherHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/manifest", wrapResult = false)
    public PluginHttpResponse getManifest(PluginHttpRequest request) {
        return http.getManifest(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/packs", wrapResult = false)
    public PluginHttpResponse listPacks(PluginHttpRequest request) {
        return http.listPacks(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/packs/{packId}", wrapResult = false)
    public PluginHttpResponse getPack(PluginHttpRequest request) {
        return http.getPack(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/packs/{packId}/versions", wrapResult = false)
    public PluginHttpResponse listVersions(PluginHttpRequest request) {
        return http.listVersions(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/packs/{packId}/versions/{versionId}/index", wrapResult = false)
    public PluginHttpResponse downloadIndex(PluginHttpRequest request) {
        return http.downloadIndex(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/packs/{packId}/versions/{versionId}/overrides-location", wrapResult = false)
    public PluginHttpResponse downloadOverridesLocation(PluginHttpRequest request) {
        return http.downloadOverridesLocation(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/packs/{packId}/versions/{versionId}/overrides", wrapResult = false)
    public PluginHttpResponse downloadOverrides(PluginHttpRequest request) {
        return http.downloadOverrides(request);
    }
}
