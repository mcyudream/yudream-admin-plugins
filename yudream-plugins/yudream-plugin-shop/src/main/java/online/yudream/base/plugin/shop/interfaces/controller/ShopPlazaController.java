package online.yudream.base.plugin.shop.interfaces.controller;

import online.yudream.base.plugin.shop.bootstrap.ShopPlugin;
import online.yudream.base.plugin.shop.interfaces.http.ShopCatalogFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 广场浏览（view 权限）：只读，仅上架商品。 */
public class ShopPlazaController {

    private final ShopCatalogFacade http;

    public ShopPlazaController(ShopCatalogFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/plaza/products", permission = ShopPlugin.VIEW_PERMISSION)
    public PluginHttpResponse products(PluginHttpRequest request) {
        return http.plazaProducts(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/plaza/products/{id}", permission = ShopPlugin.VIEW_PERMISSION)
    public PluginHttpResponse productDetail(PluginHttpRequest request) {
        return http.plazaProductDetail(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/plaza/currencies", permission = ShopPlugin.VIEW_PERMISSION)
    public PluginHttpResponse currencies() {
        return http.plazaCurrencies();
    }
}
