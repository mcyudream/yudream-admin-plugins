package online.yudream.base.plugin.shop.domain.valobj;

import online.yudream.base.plugin.shop.domain.enumerate.ShopTradeFeePayee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 商店发布设置：是否允许普通用户上架商品、上架所需积分余额门槛、允许上架交易的币种白名单、
 * 官方（平台归属）商品的展示名，以及玩家市场（{@code ShopSettlement.SELLER}）的交易手续费。
 *
 * <p>publishAssetCode 为空表示不设积分门槛；门槛仅在货币非空且 minBalance > 0 时生效。
 * allowedAssetCodes 为空表示不限制（全部已启用钱包资产可交易）。
 * platformOwnerName 为官方商品与订单在界面上的归属展示名，留空表示不显示该行。
 * 管理端治理动作不受发布开关与门槛约束，但币种白名单对全部上架生效。
 *
 * <p>交易手续费只作用于玩家市场订单（官方积分兑换类消耗商品不收费）：
 * 手续费 = 成交额 × tradeFeeRate%，按该商品计价货币的小数位四舍五入（HALF_UP），
 * 再取 max(该值, tradeFeeMinAmount)，最后不超过成交额（等于成交额时卖家实收 0）。
 * tradeFeeRate 为 0 或算出的手续费为 0 时完全不收费，购买链路退化成改动前的单次全额转账。
 * tradeFeePayee 决定手续费去向：{@link ShopTradeFeePayee#BURN} 销毁（默认，不需要额外账号），
 * {@link ShopTradeFeePayee#PLATFORM} 转给 tradeFeePayeeUserId 指定的平台用户（此时该字段必填）。
 * 旧设置文档没有这些字段时读出为「关闭、0 费率、不设下限、销毁」，行为与升级前一致。
 */
public record ShopSettings(boolean allowUserPublish, String publishAssetCode, BigDecimal publishMinBalance,
                           List<String> allowedAssetCodes, String platformOwnerName, String platformOwnerAvatar,
                           boolean tradeFeeEnabled, BigDecimal tradeFeeRate, BigDecimal tradeFeeMinAmount,
                           ShopTradeFeePayee tradeFeePayee, String tradeFeePayeeUserId) {

    /** 官方展示名默认值。 */
    public static final String DEFAULT_PLATFORM_OWNER_NAME = "官方";
    /** 官方展示名长度上限。 */
    public static final int MAX_PLATFORM_OWNER_NAME_LENGTH = 20;
    /** 官方头像地址长度上限（平台上传文件地址）。 */
    public static final int MAX_PLATFORM_OWNER_AVATAR_LENGTH = 500;
    /** 费率上限（百分比 100%：手续费最高等于成交额，卖家实收 0）。 */
    public static final BigDecimal MAX_TRADE_FEE_RATE = new BigDecimal("100");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public static ShopSettings defaults() {
        return new ShopSettings(true, null, BigDecimal.ZERO, List.of(), DEFAULT_PLATFORM_OWNER_NAME, null,
                false, BigDecimal.ZERO, BigDecimal.ZERO, ShopTradeFeePayee.BURN, null);
    }

    /** 兼容构造（不含交易手续费配置）：等价于「手续费关闭、0 费率、不设下限、销毁」。 */
    public ShopSettings(boolean allowUserPublish, String publishAssetCode, BigDecimal publishMinBalance,
                        List<String> allowedAssetCodes, String platformOwnerName, String platformOwnerAvatar) {
        this(allowUserPublish, publishAssetCode, publishMinBalance, allowedAssetCodes, platformOwnerName,
                platformOwnerAvatar, false, BigDecimal.ZERO, BigDecimal.ZERO, ShopTradeFeePayee.BURN, null);
    }

    /**
     * 规范化：货币代码去空白转大写去重；未配置货币或余额非正时清空门槛；官方展示名去空白并截断；
     * 交易手续费费率限定 0~100 的百分比、最低手续费不得为负，收款方式为平台用户时要求填写用户 ID。
     */
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
        BigDecimal rate = tradeFeeRate == null ? BigDecimal.ZERO : tradeFeeRate;
        if (rate.signum() < 0 || rate.compareTo(MAX_TRADE_FEE_RATE) > 0) {
            throw new IllegalArgumentException("交易手续费费率必须是 0 到 100 之间的百分比");
        }
        BigDecimal minFee = tradeFeeMinAmount == null ? BigDecimal.ZERO : tradeFeeMinAmount;
        if (minFee.signum() < 0) {
            throw new IllegalArgumentException("最低手续费不能为负数");
        }
        ShopTradeFeePayee payee = tradeFeePayee == null ? ShopTradeFeePayee.BURN : tradeFeePayee;
        String payeeUserId = tradeFeePayeeUserId == null ? "" : tradeFeePayeeUserId.trim();
        if (payee == ShopTradeFeePayee.PLATFORM && payeeUserId.isEmpty()) {
            throw new IllegalArgumentException("手续费收款方式选择「平台用户」时，必须填写平台用户 ID");
        }
        return new ShopSettings(allowUserPublish, hasThreshold ? code : null,
                hasThreshold ? min : BigDecimal.ZERO, List.copyOf(allowed), ownerName,
                ownerAvatar.isEmpty() ? null : ownerAvatar,
                tradeFeeEnabled, rate, minFee, payee, payeeUserId.isEmpty() ? null : payeeUserId);
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

    /** 是否可能对玩家市场订单收取手续费：开关打开且费率大于 0。 */
    public boolean chargesTradeFee() {
        return tradeFeeEnabled && tradeFeeRate != null && tradeFeeRate.signum() > 0;
    }

    /**
     * 交易手续费。按费率算出的值先按计价货币小数位（钱包资产 scale）四舍五入，再取最低手续费，
     * 最后不超过成交额；关闭、费率为 0、成交额非正或算出的手续费为 0 时返回 0，
     * 调用方据此退化成改动前的单次全额转账（不发 0 金额的钱包操作）。
     *
     * @param totalAmount 成交额（买家总支出）
     * @param scale       该商品计价货币的小数位（钱包资产 scale）
     */
    public BigDecimal tradeFee(BigDecimal totalAmount, int scale) {
        if (!chargesTradeFee() || totalAmount == null || totalAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int feeScale = Math.max(scale, 0);
        BigDecimal byRate = totalAmount.multiply(tradeFeeRate).divide(HUNDRED, feeScale, RoundingMode.HALF_UP);
        BigDecimal floor = tradeFeeMinAmount == null
                ? BigDecimal.ZERO
                : tradeFeeMinAmount.setScale(feeScale, RoundingMode.HALF_UP);
        BigDecimal fee = byRate.max(floor);
        if (fee.compareTo(totalAmount) > 0) {
            fee = totalAmount;
        }
        return fee.signum() <= 0 ? BigDecimal.ZERO : fee;
    }

    /** 卖家实收 = 成交额 − 手续费（手续费为 0 时即成交额）。 */
    public BigDecimal sellerAmount(BigDecimal totalAmount, int scale) {
        BigDecimal total = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        return total.subtract(tradeFee(total, scale));
    }
}
