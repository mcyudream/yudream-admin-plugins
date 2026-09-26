package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;
import java.util.List;

/** 商店设置保存请求：platformOwnerName 为官方（平台归属）商品的归属展示名，留空表示界面不显示该行。 */
public record ShopSettingsSaveRequest(Boolean allowUserPublish, String publishAssetCode,
                                      BigDecimal publishMinBalance, List<String> allowedAssetCodes,
                                      String platformOwnerName, String platformOwnerAvatar) {
}
