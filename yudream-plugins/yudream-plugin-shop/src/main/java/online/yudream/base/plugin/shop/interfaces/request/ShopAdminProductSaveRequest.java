package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 管理员代上架/编辑商品请求：ownerId 为可选归属用户，留空归属管理员自己。 */
public record ShopAdminProductSaveRequest(String ownerId, String title, String summary, String descriptionMd,
                                          List<String> images, String assetCode, BigDecimal price, Integer stock,
                                          String type, Map<String, Object> typeConfig) {
}
