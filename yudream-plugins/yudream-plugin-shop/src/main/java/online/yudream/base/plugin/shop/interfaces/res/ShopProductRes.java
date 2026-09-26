package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品视图。typeConfig 可能包含处理器业务配置，仅卖家本人与管理员可见（includeConfig 控制）。
 */
public record ShopProductRes(String id, String ownerId, ShopUserRes owner, String title, String summary,
                             String descriptionMd, List<String> images, String coverImage, String assetCode,
                             String assetSymbol, BigDecimal price, int stock, long soldCount, String type,
                             String typeDisplayName, Object typeConfig, String status, String statusText,
                             long createdAt, long updatedAt) {
}
