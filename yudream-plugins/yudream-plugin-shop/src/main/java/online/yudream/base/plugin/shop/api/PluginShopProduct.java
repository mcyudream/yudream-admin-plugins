package online.yudream.base.plugin.shop.api;

import java.math.BigDecimal;

/**
 * 商品跨插件视图。
 */
public record PluginShopProduct(String id, String ownerId, String title, String type, String assetCode,
                                BigDecimal price, int stock, long soldCount, String status, long createdAt) {
}
