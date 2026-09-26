package online.yudream.base.plugin.shop.api;

import java.util.Map;

/**
 * 支付前校验上下文。typeConfig 为上架时 {@link ShopProductTypeHandler#normalizeConfig} 规范化后的配置。
 */
public record ShopPurchaseContext(String productId, String productType, Map<String, Object> typeConfig,
                                  String buyerId, int quantity) {
}
