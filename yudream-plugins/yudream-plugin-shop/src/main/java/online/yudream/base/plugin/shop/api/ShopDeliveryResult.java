package online.yudream.base.plugin.shop.api;

/**
 * 发货结果。
 *
 * @param status  {@link #DELIVERED} 立即完成；{@link #PENDING} 发货中（异步推进）；
 *                {@link #FAILED} 发货失败并触发自动退款
 * @param message 给买家/卖家看的发货说明（如「命令已下发，等待玩家上线」）
 * @param content 发货内容，仅买家与管理员可见（如卡密、兑换链接、命令回执）
 */
public record ShopDeliveryResult(String status, String message, String content) {

    public static final String DELIVERED = "DELIVERED";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";

    public static ShopDeliveryResult delivered(String message, String content) {
        return new ShopDeliveryResult(DELIVERED, message, content);
    }

    public static ShopDeliveryResult pending(String message) {
        return new ShopDeliveryResult(PENDING, message, null);
    }

    public static ShopDeliveryResult failed(String message) {
        return new ShopDeliveryResult(FAILED, message, null);
    }
}
