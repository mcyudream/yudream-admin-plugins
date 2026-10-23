package online.yudream.base.plugin.union.interfaces.controller;

import online.yudream.base.plugin.union.interfaces.http.UnionHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 联合认证 peer 机器面（协议 v1 §3；匿名 + 限流，协议错误体）。 */
public class UnionPeerController {

    private final UnionHttpFacade facade;

    public UnionPeerController(UnionHttpFacade facade) {
        this.facade = facade;
    }

    @PluginHttpEndpoint(method = "GET", path = "/peer/meta", wrapResult = false)
    public PluginHttpResponse meta(PluginHttpRequest request) {
        return facade.meta();
    }

    @PluginHttpEndpoint(method = "GET", path = "/peer/joins/query", wrapResult = false)
    public PluginHttpResponse joinsQuery(PluginHttpRequest request) {
        return facade.joinsQuery(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/peer/bans", wrapResult = false)
    public PluginHttpResponse bans(PluginHttpRequest request) {
        return facade.bans(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/peer/enroll", wrapResult = false)
    public PluginHttpResponse enroll(PluginHttpRequest request) {
        return facade.enroll(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/peer/bans/report", wrapResult = false)
    public PluginHttpResponse bansReport(PluginHttpRequest request) {
        return facade.bansReport(request);
    }
}
