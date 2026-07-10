package online.yudream.base.plugin.wallet.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.wallet.bootstrap.WalletPlugin;
import online.yudream.base.plugin.wallet.interfaces.http.WalletHttpFacade;

public class WalletUserController {
    private final WalletHttpFacade http;

    public WalletUserController(WalletHttpFacade http) { this.http = http; }

    @PluginHttpEndpoint(method = "GET", path = "/me/recharge/options", permission = WalletPlugin.USER_PERMISSION)
    public PluginHttpResponse rechargeOptions() { return http.rechargeOptions(); }

    @PluginHttpEndpoint(method = "POST", path = "/me/recharges", permission = WalletPlugin.USER_PERMISSION)
    public PluginHttpResponse createRecharge(PluginHttpRequest request) { return http.createRecharge(request); }

    @PluginHttpEndpoint(method = "GET", path = "/me/balances", permission = WalletPlugin.USER_PERMISSION)
    public PluginHttpResponse balances(PluginHttpRequest request) { return http.balances(request); }

    @PluginHttpEndpoint(method = "POST", path = "/me/transfers", permission = WalletPlugin.USER_PERMISSION)
    public PluginHttpResponse transfer(PluginHttpRequest request) { return http.transfer(request); }

    @PluginHttpEndpoint(method = "GET", path = "/me/transactions", permission = WalletPlugin.USER_PERMISSION)
    public PluginHttpResponse transactions(PluginHttpRequest request) { return http.myTransactions(request); }
}
