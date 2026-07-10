package online.yudream.base.plugin.alipay.interfaces.controller;

import online.yudream.base.plugin.alipay.bootstrap.AlipayPlugin;
import online.yudream.base.plugin.alipay.interfaces.http.AlipayHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class AlipayUserController {
    private final AlipayHttpFacade http;

    public AlipayUserController(AlipayHttpFacade http) { this.http = http; }

    @PluginHttpEndpoint(method = "POST", path = "/me/recharges", permission = AlipayPlugin.USER_PERMISSION)
    public PluginHttpResponse createRecharge(PluginHttpRequest request) { return http.createRecharge(request); }

    @PluginHttpEndpoint(method = "GET", path = "/me/orders/{outTradeNo}", permission = AlipayPlugin.USER_PERMISSION)
    public PluginHttpResponse order(PluginHttpRequest request) { return http.order(request); }

    @PluginHttpEndpoint(method = "GET", path = "/me/orders", permission = AlipayPlugin.USER_PERMISSION)
    public PluginHttpResponse orders(PluginHttpRequest request) { return http.myOrders(request); }
}
