package online.yudream.base.plugin.wallet.api.payment;

public interface PluginPaymentChannel {
    PluginPaymentChannelInfo info();
    PluginPaymentCreateResult createRecharge(PluginPaymentCreateRequest request);
}
