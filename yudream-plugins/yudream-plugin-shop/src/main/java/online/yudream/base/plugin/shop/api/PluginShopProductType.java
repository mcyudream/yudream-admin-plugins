package online.yudream.base.plugin.shop.api;

/**
 * 已注册的商品类型视图。
 */
public record PluginShopProductType(String type, String displayName, String description, boolean builtin) {
}
