package online.yudream.base.plugin.shop.api;

import java.util.Map;

/**
 * 发货上下文。orderId 在支付成功后生成，可用于 {@link PluginShopService#completeDelivery} 异步回调。
 */
public record ShopDeliveryContext(String orderId, String productId, String productType,
                                  Map<String, Object> typeConfig, String buyerId, int quantity) {
}
