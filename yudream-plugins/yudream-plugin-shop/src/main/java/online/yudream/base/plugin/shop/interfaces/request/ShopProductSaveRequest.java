package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ShopProductSaveRequest(String title, String summary, String descriptionMd, List<String> images,
                                     String assetCode, BigDecimal price, Integer stock, String type,
                                     Map<String, Object> typeConfig) {
}
