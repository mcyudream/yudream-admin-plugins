package online.yudream.base.plugin.shop.domain.enumerate;

/**
 * 玩家市场交易手续费的收款方式。
 *
 * <p>{@link #BURN} 是默认口径：手续费从买家账户销毁式扣减（{@code debit}），不需要额外账号；
 * {@link #PLATFORM} 把手续费转给指定的平台用户（{@code transfer} 买家 → 该用户），
 * 需要配置非空的平台用户 ID。两种方式在退款时都必须原路反向收回到买家。
 */
public enum ShopTradeFeePayee {

    /** 手续费销毁（从买家账户扣减、不产生收款方）。 */
    BURN("销毁"),

    /** 手续费转给指定平台用户。 */
    PLATFORM("平台用户");

    private final String label;

    ShopTradeFeePayee(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析配置值：空值或无法识别的值回退默认的 {@link #BURN}（历史设置文档没有该字段）。 */
    public static ShopTradeFeePayee from(String value) {
        if (value == null || value.isBlank()) {
            return BURN;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException ignored) {
            return BURN;
        }
    }
}
