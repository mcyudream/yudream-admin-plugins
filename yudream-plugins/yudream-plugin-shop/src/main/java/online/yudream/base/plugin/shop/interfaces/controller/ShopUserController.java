package online.yudream.base.plugin.shop.interfaces.controller;

import online.yudream.base.plugin.shop.bootstrap.ShopPlugin;
import online.yudream.base.plugin.shop.interfaces.http.ShopCatalogFacade;
import online.yudream.base.plugin.shop.interfaces.http.ShopOrderFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 用户自助端：/me/**，归属一律取 request.principal()，卖家与买家各自只见自己的数据。 */
public class ShopUserController {

    private final ShopCatalogFacade catalog;
    private final ShopOrderFacade orders;

    public ShopUserController(ShopCatalogFacade catalog, ShopOrderFacade orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    // ---------- 卖家（publish 权限） ----------

    @PluginHttpEndpoint(method = "GET", path = "/me/currencies", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse currencies() {
        return catalog.myCurrencies();
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/product-types", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse productTypes() {
        return catalog.myProductTypes();
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/publish-qualification", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse publishQualification(PluginHttpRequest request) {
        return catalog.publishQualification(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/products", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse myProducts(PluginHttpRequest request) {
        return catalog.myProducts(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/products", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse createProduct(PluginHttpRequest request) {
        return catalog.createProduct(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/products/{id}", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse myProductDetail(PluginHttpRequest request) {
        return catalog.myProductDetail(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/me/products/{id}", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse updateProduct(PluginHttpRequest request) {
        return catalog.updateProduct(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/products/{id}/shelf", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse setShelf(PluginHttpRequest request) {
        return catalog.setMyProductShelf(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/products/{id}", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse deleteProduct(PluginHttpRequest request) {
        return catalog.deleteMyProduct(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/sales", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse mySales(PluginHttpRequest request) {
        return orders.mySales(request);
    }

    // ---------- 买家（use 权限） ----------

    @PluginHttpEndpoint(method = "POST", path = "/me/orders", permission = ShopPlugin.USE_PERMISSION)
    public PluginHttpResponse purchase(PluginHttpRequest request) {
        return orders.purchase(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/purchases", permission = ShopPlugin.USE_PERMISSION)
    public PluginHttpResponse myPurchases(PluginHttpRequest request) {
        return orders.myPurchases(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/orders/{id}", permission = ShopPlugin.USE_PERMISSION)
    public PluginHttpResponse myOrderDetail(PluginHttpRequest request) {
        return orders.myOrderDetail(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/wallet/balance", permission = ShopPlugin.USE_PERMISSION)
    public PluginHttpResponse myBalance(PluginHttpRequest request) {
        return orders.myBalance(request);
    }

    // ---------- 发货凭证核验 ----------

    @PluginHttpEndpoint(method = "POST", path = "/me/orders/{id}/voucher", permission = ShopPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse submitVoucher(PluginHttpRequest request) {
        return orders.submitVoucher(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/orders/{id}/verify", permission = ShopPlugin.USE_PERMISSION)
    public PluginHttpResponse verifyDelivery(PluginHttpRequest request) {
        return orders.verifyDelivery(request);
    }
}
