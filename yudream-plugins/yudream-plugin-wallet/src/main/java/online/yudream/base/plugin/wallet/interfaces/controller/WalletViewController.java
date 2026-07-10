package online.yudream.base.plugin.wallet.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.wallet.bootstrap.WalletPlugin;
import online.yudream.base.plugin.wallet.interfaces.http.WalletHttpFacade;

public class WalletViewController {
    private final WalletHttpFacade http;

    public WalletViewController(WalletHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/status", permission = WalletPlugin.VIEW_PERMISSION)
    public PluginHttpResponse status() { return http.status(); }

    @PluginHttpEndpoint(method = "GET", path = "/assets", permission = WalletPlugin.VIEW_PERMISSION)
    public PluginHttpResponse assets(PluginHttpRequest request) { return http.assets(request); }
}
