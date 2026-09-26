package online.yudream.base.plugin.shop.domain.enumerate;

public enum ShopOrderStatus {
    PAID("已支付"),
    DELIVERING("发货中"),
    DELIVERED("已发货"),
    DELIVERY_FAILED("发货失败"),
    REFUNDED("已退款");

    private final String label;

    ShopOrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
