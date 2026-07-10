package online.yudream.base.plugin.alipay.interfaces.controller;

import online.yudream.base.plugin.alipay.interfaces.http.AlipayHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class AlipayPublicController {
    private final AlipayHttpFacade http;

    public AlipayPublicController(AlipayHttpFacade http) { this.http = http; }

    @PluginHttpEndpoint(method = "POST", path = "/notify", wrapResult = false)
    public PluginHttpResponse notify(PluginHttpRequest request) { return http.notify(request); }
}
