package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品视图。typeConfig 可能包含处理器业务配置，仅卖家本人与管理员可见（includeConfig 控制）。
 * perUserLimit 为每人限购件数（0 表示不限）。
 *
 * <p>settlement 为类型处理器声明的结算方式：{@code SELLER} 是归属真实用户的玩家商品，
 * {@code BURN} 是归属平台、没有归属用户的官方消耗商品。
 *
 * <p>归属展示：platformOwned=true 表示没有归属用户，ownerLabel 为设置里配置的官方展示名
 * （null 表示管理员留空、界面不显示归属行）；platformOwned=false 时用 owner（真实用户）。
 *
 * <p>variants 为可选型号：非空时 price/stock 分别是型号最低价与库存合计。
 */
public record ShopProductRes(String id, String ownerId, ShopUserRes owner, boolean platformOwned, String ownerLabel,
                             String ownerAvatar, String title, String summary,
                             String descriptionMd, List<String> images, String coverImage, String assetCode,
                             String assetSymbol, BigDecimal price, int stock, int perUserLimit,
                             List<ShopVariantRes> variants, long soldCount,
                             String type, String typeDisplayName, String settlement, Object typeConfig, String status,
                             String statusText, long createdAt, long updatedAt, int sortOrder) {
}
