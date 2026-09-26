package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;

/**
 * 商品型号视图。有型号时价格区间与库存合计由商品视图的 price/stock 给出，
 * 这里逐型号展示价格与库存，供买家在详情页选择型号。
 */
public record ShopVariantRes(String id, String name, BigDecimal price, int stock, String image, boolean soldOut) {
}
