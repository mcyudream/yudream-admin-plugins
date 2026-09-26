package online.yudream.base.plugin.shop.domain.valobj;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商店发布设置：是否允许普通用户上架商品、上架所需积分余额门槛、允许上架交易的币种白名单、
 * 官方（平台归属）商品的展示名。
 * publishAssetCode 为空表示不设积分门槛；门槛仅在货币非空且 minBalance > 0 时生效。
 * allowedAssetCodes 为空表示不限制（全部已启用钱包资产可交易）。
 * platformOwnerName 为官方商品与订单在界面上的归属展示名，留空表示不显示该行。
 * 管理端治理动作不受发布开关与门槛约束，但币种白名单对全部上架生效。
 */
public record ShopSettings(boolean allowUserPublish, String publishAssetCode, BigDecimal publishMinBalance,
                           List<String> allowedAssetCodes, String platformOwnerName, String platformOwnerAvatar) {

    /** 官方展示名默认值。 */
    public static final String DEFAULT_PLATFORM_OWNER_NAME = "官方";
    /** 官方展示名长度上限。 */
    public static final int MAX_PLATFORM_OWNER_NAME_LENGTH = 20;
    /** 官方头像地址长度上限（平台上传文件地址）。 */
    public static final int MAX_PLATFORM_OWNER_AVATAR_LENGTH = 500;

    public static ShopSettings defaults() {
        return new ShopSettings(true, null, BigDecimal.ZERO, List.of(), DEFAULT_PLATFORM_OWNER_NAME, null);
    }

    /** 规范化：货币代码去空白转大写去重；未配置货币或余额非正时清空门槛；官方展示名去空白并截断。 */
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
        String ownerName = platformOwnerName == null ? "" : platformOwnerName.trim();
        if (ownerName.length() > MAX_PLATFORM_OWNER_NAME_LENGTH) {
            ownerName = ownerName.substring(0, MAX_PLATFORM_OWNER_NAME_LENGTH);
        }
        String ownerAvatar = platformOwnerAvatar == null ? "" : platformOwnerAvatar.trim();
        if (ownerAvatar.length() > MAX_PLATFORM_OWNER_AVATAR_LENGTH) {
            ownerAvatar = ownerAvatar.substring(0, MAX_PLATFORM_OWNER_AVATAR_LENGTH);
        }
        if (!ownerAvatar.isEmpty() && !ownerAvatar.startsWith("/api/files/")) {
            throw new IllegalArgumentException("官方头像必须是通过平台上传的文件");
        }
        return new ShopSettings(allowUserPublish, hasThreshold ? code : null,
                hasThreshold ? min : BigDecimal.ZERO, List.copyOf(allowed), ownerName,
                ownerAvatar.isEmpty() ? null : ownerAvatar);
    }

    /** 官方归属展示名：留空返回 null（界面不显示归属行）。 */
    public String platformOwnerLabel() {
        return platformOwnerName == null || platformOwnerName.isBlank() ? null : platformOwnerName.trim();
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
