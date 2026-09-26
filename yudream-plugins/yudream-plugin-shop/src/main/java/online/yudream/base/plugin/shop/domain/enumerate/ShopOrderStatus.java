package online.yudream.base.plugin.shop.domain.enumerate;

public enum ShopOrderStatus {
    PAID("已支付"),
    DELIVERING("发货中"),
    DELIVERED("已发货"),
    DELIVERY_FAILED("发货失败"),
    REFUNDED("已退款"),
    /** 买家在发货前自行取消（已原路退回）。与管理员/失败退款区分，便于对账与治理。 */
    CANCELLED("已取消");

    private final String label;

    ShopOrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
