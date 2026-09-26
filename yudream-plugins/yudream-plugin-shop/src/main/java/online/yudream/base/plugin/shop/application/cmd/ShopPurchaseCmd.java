package online.yudream.base.plugin.shop.application.cmd;

/** 购买入参：有型号的商品必须带 variantId。 */
public record ShopPurchaseCmd(String productId, String variantId, int quantity) {
}
