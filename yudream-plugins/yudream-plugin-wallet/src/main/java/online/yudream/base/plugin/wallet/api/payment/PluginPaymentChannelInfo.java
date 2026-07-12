package online.yudream.base.plugin.wallet.api.payment;

import java.util.List;

public record PluginPaymentChannelInfo(String code, String name, String icon, String description, boolean enabled,
                                       List<String> productTypes) {
}
