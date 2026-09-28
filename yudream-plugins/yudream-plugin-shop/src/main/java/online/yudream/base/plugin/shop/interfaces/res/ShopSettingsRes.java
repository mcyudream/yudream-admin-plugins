package online.yudream.base.plugin.shop.interfaces.res;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商店设置视图：assetOptions 为钱包当前启用的货币（供门槛与交易白名单选择）。
 * platformOwnerName 为官方（平台归属）商品的归属展示名，空串表示界面不显示归属行。
 *
 * <p>交易手续费配置（只作用于玩家市场订单）：tradeFeeEnabled 为总开关，
 * tradeFeeRate 为成交额百分比（十进制字符串，0~100），tradeFeeMinAmount 为最低手续费
 * （十进制字符串，0 表示不设下限），tradeFeePayee 取 BURN（销毁）或 PLATFORM（转给平台用户），
 * tradeFeePayeeUserId 在 PLATFORM 时必填。
 */
public record ShopSettingsRes(boolean allowUserPublish, String publishAssetCode, BigDecimal publishMinBalance,
                              List<String> allowedAssetCodes, String platformOwnerName, String platformOwnerAvatar,
                              boolean tradeFeeEnabled, String tradeFeeRate, String tradeFeeMinAmount,
                              String tradeFeePayee, String tradeFeePayeeUserId,
                              boolean walletAvailable,
                              List<ShopAssetRes> assetOptions) {
}
