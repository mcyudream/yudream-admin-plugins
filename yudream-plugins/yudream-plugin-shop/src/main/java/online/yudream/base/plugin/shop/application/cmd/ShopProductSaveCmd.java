package online.yudream.base.plugin.shop.application.cmd;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ShopProductSaveCmd(String id, String title, String summary, String descriptionMd,
                                 List<String> images, String assetCode, BigDecimal price, int stock,
                                 String type, Map<String, Object> typeConfig) {
}
