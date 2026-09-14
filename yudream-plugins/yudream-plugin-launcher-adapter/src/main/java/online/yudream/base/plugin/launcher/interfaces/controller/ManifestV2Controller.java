package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.application.service.ManifestV2AppService;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.Map;

/**
 * 协议 v2 manifest 端点（§3）。匿名可得公开版，带 token 得按权限裁剪的个性化版。
 */
public class ManifestV2Controller {

    private final ManifestV2AppService manifestV2AppService;

    public ManifestV2Controller(ManifestV2AppService manifestV2AppService) {
        this.manifestV2AppService = manifestV2AppService;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/manifest", wrapResult = false)
    public PluginHttpResponse getManifest(PluginHttpRequest request) {
        try {
            return PluginHttpResponse.rawJson(200, manifestV2AppService.buildManifest(request.principal()));
        } catch (RuntimeException e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "构建 manifest 失败: " + e.getMessage()));
        }
    }
}
