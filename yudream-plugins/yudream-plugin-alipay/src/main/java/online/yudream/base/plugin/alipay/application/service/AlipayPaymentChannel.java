package online.yudream.base.plugin.alipay.application.service;

import online.yudream.base.plugin.wallet.api.payment.PluginPaymentChannel;
import online.yudream.base.plugin.wallet.api.payment.PluginPaymentChannelInfo;
import online.yudream.base.plugin.wallet.api.payment.PluginPaymentCreateRequest;
import online.yudream.base.plugin.wallet.api.payment.PluginPaymentCreateResult;

public class AlipayPaymentChannel implements PluginPaymentChannel {

    private final AlipayAppService appService;

    public AlipayPaymentChannel(AlipayAppService appService) {
        this.appService = appService;
    }

    @Override
    public PluginPaymentChannelInfo info() {
        return appService.channelInfo();
    }

    @Override
    public PluginPaymentCreateResult createRecharge(PluginPaymentCreateRequest request) {
        return appService.createRecharge(request);
    }
}
