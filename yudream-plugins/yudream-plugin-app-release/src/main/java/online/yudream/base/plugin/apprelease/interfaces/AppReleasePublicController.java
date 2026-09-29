package online.yudream.base.plugin.apprelease.interfaces;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 公开端点（匿名，permission 为空）：更新检查、更新日志与更新包下载。
 * 客户端启动时检查更新并在浏览器侧直接下载 APK，均不可要求登录态。
 */
public class AppReleasePublicController {
    private final AppReleaseHttpFacade http;

    public AppReleasePublicController(AppReleaseHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/check")
    public PluginHttpResponse check(PluginHttpRequest request) {
        return http.check(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/changelogs")
    public PluginHttpResponse changelogs(PluginHttpRequest request) {
        return http.changelogs(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/download/{id}", wrapResult = false)
    public PluginHttpResponse download(PluginHttpRequest request) {
        return http.download(request);
    }
}
