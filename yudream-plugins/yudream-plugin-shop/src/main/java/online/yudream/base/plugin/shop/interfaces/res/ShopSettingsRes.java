package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;
import java.util.List;

/** 商店设置视图：assetOptions 为钱包当前启用的货币（供门槛与交易白名单选择）。 */
public record ShopSettingsRes(boolean allowUserPublish, String publishAssetCode, BigDecimal publishMinBalance,
                              List<String> allowedAssetCodes, boolean walletAvailable,
                              List<ShopAssetRes> assetOptions) {
}
