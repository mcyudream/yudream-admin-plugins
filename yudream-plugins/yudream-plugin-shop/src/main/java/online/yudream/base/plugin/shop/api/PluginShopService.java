package online.yudream.base.plugin.shop.api;

import java.util.List;
import java.util.Optional;

/**
 * 商店插件对外暴露的服务，由商店 {@code exposeService} 注册，
 * 其他插件经 {@code PluginContext.service("shop", PluginShopService.class)} 获取。
 */
public interface PluginShopService {

    /** 当前可用的商品类型（内置 + 其他插件注册）。 */
    List<PluginShopProductType> productTypes();

    Optional<PluginShopProduct> findProduct(String productId);

    Optional<PluginShopOrder> findOrder(String orderId);

    /**
     * 异步发货终态回报：仅发货中的订单可推进。
     * DELIVERED 完成发货；FAILED 标记发货失败并自动退款；PENDING 仅更新发货说明。
     *
     * @return 订单存在且状态被推进时返回 true
     */
    boolean completeDelivery(String orderId, ShopDeliveryUpdate update);
}
