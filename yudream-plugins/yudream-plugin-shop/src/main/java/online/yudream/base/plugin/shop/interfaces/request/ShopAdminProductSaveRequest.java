package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 管理员新增/编辑商品请求。
 *
 * <p>ownerId 为历史遗留字段：官方商品没有归属用户（归属固定为平台），编辑既有玩家商品也不会改归属，
 * 所以后端已忽略该字段，仅保留以便旧前端请求不报错。
 */
public record ShopAdminProductSaveRequest(String ownerId, String title, String summary, String descriptionMd,
                                          List<String> images, String assetCode, BigDecimal price, Integer stock,
                                          Integer perUserLimit, String type, Map<String, Object> typeConfig,
                                          List<ShopVariantRequest> variants, Integer sortOrder) {
}
