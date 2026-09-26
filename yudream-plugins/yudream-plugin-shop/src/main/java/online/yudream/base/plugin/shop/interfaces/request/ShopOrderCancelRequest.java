package online.yudream.base.plugin.shop.interfaces.request;

/** 买家取消订单请求：reason 为可选的取消原因。 */
public record ShopOrderCancelRequest(String reason) {
}
