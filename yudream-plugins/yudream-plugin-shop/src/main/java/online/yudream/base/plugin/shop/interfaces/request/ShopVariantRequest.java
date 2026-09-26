package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;

/** 型号请求体：id 留空表示新建型号；stock 为空按不限库存处理。 */
public record ShopVariantRequest(String id, String name, BigDecimal price, Integer stock, String image) {
}
