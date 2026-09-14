package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.interfaces.http.LauncherHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 协议 v2 数据与动作端点（§4）。统一信封 + remote 动作回传。
 */
public class LauncherDataV2Controller {

    private final LauncherHttpFacade facade;

    public LauncherDataV2Controller(LauncherHttpFacade facade) {
        this.facade = facade;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/data/{providerCode}/{dataSourceCode}", wrapResult = false)
    public PluginHttpResponse fetchData(PluginHttpRequest request) {
        return facade.fetchDataV2(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/v2/action/{providerCode}/{actionCode}", wrapResult = false)
    public PluginHttpResponse executeAction(PluginHttpRequest request) {
        return facade.executeActionV2(request);
    }
}
