package online.yudream.base.plugin.mcpanel.infrastructure.dns;

/** 云解析调用失败（网络/鉴权/参数/配额）：message 面向管理员可读，由应用层包装成业务错误。 */
public class DnsCallException extends RuntimeException {

    public DnsCallException(String message) {
        super(message);
    }

    public DnsCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
