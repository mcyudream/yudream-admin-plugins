package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;

/** 上架资格视图：balance 仅在配置门槛且查询成功时返回（字符串避免精度丢失）。 */
public record ShopPublishQualificationRes(boolean allowed, String reason, String publishAssetCode,
                                          BigDecimal publishMinBalance, String balance, boolean walletAvailable) {
}
