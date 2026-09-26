package online.yudream.base.plugin.shop.api;

/**
 * 异步发货终态回报，语义同 {@link ShopDeliveryResult}。
 */
public record ShopDeliveryUpdate(String status, String message, String content) {

    public static ShopDeliveryUpdate delivered(String message, String content) {
        return new ShopDeliveryUpdate(ShopDeliveryResult.DELIVERED, message, content);
    }

    public static ShopDeliveryUpdate failed(String message) {
        return new ShopDeliveryUpdate(ShopDeliveryResult.FAILED, message, null);
    }

    public static ShopDeliveryUpdate pending(String message) {
        return new ShopDeliveryUpdate(ShopDeliveryResult.PENDING, message, null);
    }
}
