package online.yudream.base.plugin.mcpanel.infrastructure.dns;

/**
 * 云解析凭据（各驱动各用自己的字段；不落文档，只经 PluginSecretStore 存取）。
 * 未配置的字段为空串。
 */
public record DnsCredentials(String cloudflareToken, String aliyunAccessKeyId, String aliyunAccessKeySecret,
                             String tencentSecretId, String tencentSecretKey) {

    public static DnsCredentials empty() {
        return new DnsCredentials("", "", "", "", "");
    }

    public DnsCredentials {
        cloudflareToken = cloudflareToken == null ? "" : cloudflareToken.trim();
        aliyunAccessKeyId = aliyunAccessKeyId == null ? "" : aliyunAccessKeyId.trim();
        aliyunAccessKeySecret = aliyunAccessKeySecret == null ? "" : aliyunAccessKeySecret.trim();
        tencentSecretId = tencentSecretId == null ? "" : tencentSecretId.trim();
        tencentSecretKey = tencentSecretKey == null ? "" : tencentSecretKey.trim();
    }
}
