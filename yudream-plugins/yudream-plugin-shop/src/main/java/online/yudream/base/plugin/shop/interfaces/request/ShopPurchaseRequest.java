package online.yudream.base.plugin.shop.interfaces.request;

/** 购买请求：variantId 为所选商品型号，商品无型号时留空。 */
public record ShopPurchaseRequest(String productId, String variantId, Integer quantity) {
}
