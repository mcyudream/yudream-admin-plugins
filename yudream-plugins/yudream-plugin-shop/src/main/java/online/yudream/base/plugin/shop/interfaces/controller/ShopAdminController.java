package online.yudream.base.plugin.shop.interfaces.controller;

import online.yudream.base.plugin.shop.bootstrap.ShopPlugin;
import online.yudream.base.plugin.shop.interfaces.http.ShopCatalogFacade;
import online.yudream.base.plugin.shop.interfaces.http.ShopOrderFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 管理端：/admin/**，跨用户商品与订单治理，manage 权限。 */
public class ShopAdminController {

    private final ShopCatalogFacade catalog;
    private final ShopOrderFacade orders;

    public ShopAdminController(ShopCatalogFacade catalog, ShopOrderFacade orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/products", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse products(PluginHttpRequest request) {
        return catalog.adminProducts(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/products", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createProduct(PluginHttpRequest request) {
        return catalog.adminCreateProduct(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/products/{id}", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse productDetail(PluginHttpRequest request) {
        return catalog.adminProductDetail(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/products/{id}", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateProduct(PluginHttpRequest request) {
        return catalog.adminUpdateProduct(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/product-types", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse productTypes() {
        return catalog.adminProductTypes();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings() {
        return catalog.adminSettings();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/user-options", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse userOptions(PluginHttpRequest request) {
        return catalog.adminUserOptions(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return catalog.saveAdminSettings(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/products/{id}/shelf", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse setShelf(PluginHttpRequest request) {
        return catalog.adminSetShelf(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/products/{id}", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteProduct(PluginHttpRequest request) {
        return catalog.adminDeleteProduct(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/orders", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse orders(PluginHttpRequest request) {
        return orders.adminOrders(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/orders/{id}", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse orderDetail(PluginHttpRequest request) {
        return orders.adminOrderDetail(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/orders/{id}/redeliver", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse redeliver(PluginHttpRequest request) {
        return orders.adminRedeliver(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/orders/{id}/refund", permission = ShopPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse refund(PluginHttpRequest request) {
        return orders.adminRefund(request);
    }
}
