package online.yudream.base.plugin.shop.domain.enumerate;

/**
 * 商品结算方式。
 *
 * <p>{@link #SELLER} 是商店的默认语义：买家付款经钱包转给卖家（C2C 交易）。
 * {@link #BURN} 用于积分兑换类商品：从买家账户扣减资产、不产生收款方（消耗），
 * 退款时再原路退回买家。两种方式的订单生命周期一致（支付→发货→核验/取消退款）。
 */
public enum ShopSettlement {

    /** 买家 → 卖家转账。 */
    SELLER("转给卖家"),

    /** 扣减买家资产、不转给任何人。 */
    BURN("消耗");

    private final String label;

    ShopSettlement(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ShopSettlement from(String value) {
        if (value == null || value.isBlank()) {
            return SELLER;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException ignored) {
            return SELLER;
        }
    }
}
