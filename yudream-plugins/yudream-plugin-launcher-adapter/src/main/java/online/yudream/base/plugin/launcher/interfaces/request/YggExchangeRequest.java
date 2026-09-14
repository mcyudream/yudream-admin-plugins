package online.yudream.base.plugin.launcher.interfaces.request;

/**
 * 站点身份兑换 ygg 会话。clientToken / profileName 均可空。
 */
public record YggExchangeRequest(
        String clientToken,
        String profileName
) {
}
