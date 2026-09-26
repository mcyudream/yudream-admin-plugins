package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 商品保存请求：variants 为可选型号列表（同一商品可选不同型号），留空表示没有型号。 */
public record ShopProductSaveRequest(String title, String summary, String descriptionMd, List<String> images,
                                     String assetCode, BigDecimal price, Integer stock, Integer perUserLimit,
                                     String type, Map<String, Object> typeConfig,
                                     List<ShopVariantRequest> variants) {
}
