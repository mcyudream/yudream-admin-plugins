package online.yudream.base.plugin.shop.interfaces.request;

import java.math.BigDecimal;
import java.util.List;

public record ShopSettingsSaveRequest(Boolean allowUserPublish, String publishAssetCode,
                                      BigDecimal publishMinBalance, List<String> allowedAssetCodes) {
}
