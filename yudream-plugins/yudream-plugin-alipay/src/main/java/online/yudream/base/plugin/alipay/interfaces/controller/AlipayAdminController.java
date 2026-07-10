package online.yudream.base.plugin.alipay.interfaces.controller;

import online.yudream.base.plugin.alipay.bootstrap.AlipayPlugin;
import online.yudream.base.plugin.alipay.interfaces.http.AlipayHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class AlipayAdminController {
    private final AlipayHttpFacade http;

    public AlipayAdminController(AlipayHttpFacade http) { this.http = http; }

    @PluginHttpEndpoint(method = "GET", path = "/admin/config", permission = AlipayPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse config() { return http.config(); }
    @PluginHttpEndpoint(method = "PUT", path = "/admin/config", permission = AlipayPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveConfig(PluginHttpRequest request) { return http.saveConfig(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/orders", permission = AlipayPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse orders(PluginHttpRequest request) { return http.orders(request); }
    @PluginHttpEndpoint(method = "GET", path = "/admin/orders/{outTradeNo}", permission = AlipayPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse order(PluginHttpRequest request) { return http.adminOrder(request); }
    @PluginHttpEndpoint(method = "POST", path = "/admin/notify/debug", permission = AlipayPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse notifyDebug(PluginHttpRequest request) { return http.notifyDebug(request); }
}
