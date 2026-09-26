package online.yudream.base.plugin.mcpanel.infrastructure.dns;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.List;

/**
 * 云解析驱动注册表：驱动标识、凭据完整性检查与实例化。
 * 新增服务商只需在此登记并实现 {@link DnsProvider}（业务侧走同一套接口）。
 */
public final class DnsProviders {

    /** 已支持驱动（顺序即设置页下拉顺序）。off 由调用方单独处理。 */
    public static final List<String> TYPES = List.of("cloudflare", "aliyun", "dnspod");

    private DnsProviders() {
    }

    public static boolean supported(String type) {
        return type != null && TYPES.contains(type.trim().toLowerCase());
    }

    public static String label(String type) {
        return switch (type == null ? "" : type.trim().toLowerCase()) {
            case "cloudflare" -> "Cloudflare";
            case "aliyun" -> "阿里云云解析";
            case "dnspod" -> "腾讯云 DNSPod";
            default -> "";
        };
    }

    /** 区域标识的字段名（设置页按驱动切换提示）：Cloudflare 是 Zone ID，其余是根域名。 */
    public static String zoneLabel(String type) {
        return "cloudflare".equals(type == null ? "" : type.trim().toLowerCase()) ? "Zone ID" : "根域名（如 example.com）";
    }

    /**
     * 缺失的凭据说明；返回 null = 齐备。
     * 设置保存时就拦住「选了驱动却没填凭据」，避免实例创建后才发现分配不了域名。
     */
    public static String missingCredentials(String type, DnsCredentials credentials) {
        DnsCredentials creds = credentials == null ? DnsCredentials.empty() : credentials;
        return switch (type == null ? "" : type.trim().toLowerCase()) {
            case "cloudflare" -> creds.cloudflareToken().isBlank() ? "Cloudflare API Token" : null;
            case "aliyun" -> creds.aliyunAccessKeyId().isBlank() || creds.aliyunAccessKeySecret().isBlank()
                    ? "阿里云 AccessKeyId / AccessKeySecret" : null;
            case "dnspod" -> creds.tencentSecretId().isBlank() || creds.tencentSecretKey().isBlank()
                    ? "腾讯云 SecretId / SecretKey" : null;
            default -> "未知驱动 " + type;
        };
    }

    public static DnsProvider create(String type, String zone, String apiBase, DnsCredentials credentials,
                                     HttpClient http, ObjectMapper mapper) {
        DnsCredentials creds = credentials == null ? DnsCredentials.empty() : credentials;
        return switch (type == null ? "" : type.trim().toLowerCase()) {
            case "cloudflare" -> new CloudflareDnsProvider(zone, apiBase, creds.cloudflareToken(), http, mapper);
            case "aliyun" -> new AliyunDnsProvider(zone, apiBase, creds.aliyunAccessKeyId(),
                    creds.aliyunAccessKeySecret(), http, mapper);
            case "dnspod" -> new TencentDnsProvider(zone, apiBase, creds.tencentSecretId(),
                    creds.tencentSecretKey(), http, mapper);
            default -> throw new DnsCallException("不支持的 DNS 驱动：" + type);
        };
    }
}
