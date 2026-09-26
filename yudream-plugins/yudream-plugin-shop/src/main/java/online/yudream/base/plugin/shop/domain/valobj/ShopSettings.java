package online.yudream.base.plugin.shop.domain.valobj;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商店发布设置：是否允许普通用户上架商品、上架所需积分余额门槛、允许上架交易的币种白名单。
 * publishAssetCode 为空表示不设积分门槛；门槛仅在货币非空且 minBalance > 0 时生效。
 * allowedAssetCodes 为空表示不限制（全部已启用钱包资产可交易）。
 * 管理端治理动作不受发布开关与门槛约束，但币种白名单对全部上架生效。
 */
public record ShopSettings(boolean allowUserPublish, String publishAssetCode, BigDecimal publishMinBalance,
                           List<String> allowedAssetCodes) {

    public static ShopSettings defaults() {
        return new ShopSettings(true, null, BigDecimal.ZERO, List.of());
    }

    /** 规范化：货币代码去空白转大写去重；未配置货币或余额非正时清空门槛。 */
    public ShopSettings normalized() {
        String code = publishAssetCode == null || publishAssetCode.isBlank()
                ? null
                : publishAssetCode.trim().toUpperCase();
        BigDecimal min = publishMinBalance == null ? BigDecimal.ZERO : publishMinBalance.max(BigDecimal.ZERO);
        boolean hasThreshold = code != null && min.signum() > 0;
        List<String> allowed = allowedAssetCodes == null ? List.of() : allowedAssetCodes.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toUpperCase())
                .distinct()
                .toList();
        return new ShopSettings(allowUserPublish, hasThreshold ? code : null,
                hasThreshold ? min : BigDecimal.ZERO, List.copyOf(allowed));
    }

    /** 是否配置了积分余额门槛。 */
    public boolean requiresBalance() {
        return publishAssetCode != null && publishMinBalance != null && publishMinBalance.signum() > 0;
    }

    /** 指定货币是否允许上架交易；白名单为空视为全部允许。 */
    public boolean assetAllowed(String assetCode) {
        if (allowedAssetCodes.isEmpty()) {
            return true;
        }
        return assetCode != null && allowedAssetCodes.contains(assetCode.trim().toUpperCase());
    }
}
