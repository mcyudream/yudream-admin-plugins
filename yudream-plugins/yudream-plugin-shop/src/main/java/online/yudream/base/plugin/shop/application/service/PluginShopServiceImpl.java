package online.yudream.base.plugin.shop.application.service;

import online.yudream.base.plugin.shop.api.PluginShopOrder;
import online.yudream.base.plugin.shop.api.PluginShopProduct;
import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.api.PluginShopService;
import online.yudream.base.plugin.shop.api.ShopDeliveryUpdate;

import java.util.List;
import java.util.Optional;

/** {@link PluginShopService} 的插件内实现，由 bootstrap 组装并 exposeService。 */
public class PluginShopServiceImpl implements PluginShopService {

    private final ShopCatalogService catalogService;
    private final ShopOrderService orderService;
    private final ShopProductTypeRegistry typeRegistry;

    public PluginShopServiceImpl(ShopCatalogService catalogService, ShopOrderService orderService,
                                 ShopProductTypeRegistry typeRegistry) {
        this.catalogService = catalogService;
        this.orderService = orderService;
        this.typeRegistry = typeRegistry;
    }

    @Override
    public List<PluginShopProductType> productTypes() {
        return typeRegistry.types();
    }

    @Override
    public Optional<PluginShopProduct> findProduct(String productId) {
        return catalogService.findProduct(productId);
    }

    @Override
    public Optional<PluginShopOrder> findOrder(String orderId) {
        return orderService.findOrder(orderId);
    }

    @Override
    public boolean completeDelivery(String orderId, ShopDeliveryUpdate update) {
        return orderService.completeDelivery(orderId, update);
    }
}
