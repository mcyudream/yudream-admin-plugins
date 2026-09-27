package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商店设置保存请求：platformOwnerName 为官方（平台归属）商品的归属展示名，留空表示界面不显示该行。
 *
 * <p>交易手续费配置（只作用于玩家市场订单）：tradeFeeEnabled 缺省视为关闭；
 * tradeFeeRate 为成交额百分比（十进制字符串，0~100，缺省 0）；tradeFeeMinAmount 为最低手续费
 * （十进制字符串，0 表示不设下限）；tradeFeePayee 取 BURN（销毁，默认）或 PLATFORM（转给平台用户），
 * 选择 PLATFORM 时 tradeFeePayeeUserId 必填。
 */
public record ShopSettingsSaveRequest(Boolean allowUserPublish, String publishAssetCode,
                                      BigDecimal publishMinBalance, List<String> allowedAssetCodes,
                                      String platformOwnerName, String platformOwnerAvatar,
                                      Boolean tradeFeeEnabled, String tradeFeeRate, String tradeFeeMinAmount,
                                      String tradeFeePayee, String tradeFeePayeeUserId) {
}
