package online.yudream.base.plugin.mcpanel.infrastructure.dns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 腾讯云 DNSPod TC3-HMAC-SHA256 签名。
 *
 * <p>金标值由独立实现（Node.js crypto）按腾讯云文档算法算出（固定时间戳 1790103600 →
 * UTC 2026-09-22），与本实现输出必须逐字节一致。
 */
class TencentDnsProviderTest {

    private static final String PAYLOAD = "{\"Domain\":\"example.com\",\"Subdomain\":\"mc\",\"RecordType\":\"A\"}";

    @Test
    void authorizationMatchesReferenceImplementation() {
        String authorization = TencentDnsProvider.buildAuthorization(
                "AKIDtestSecretId", "testSecretKey", "dnspod.tencentcloudapi.com", PAYLOAD, 1790103600L);
        assertEquals("TC3-HMAC-SHA256 Credential=AKIDtestSecretId/2026-09-22/dnspod/tc3_request, "
                        + "SignedHeaders=content-type;host, "
                        + "Signature=d37a28cf04c1c051cd3d328e6f76052625bf3967d717947ab711565201ab4d8b",
                authorization);
    }

    @Test
    void authorizationCoversHostAndPayload() {
        String base = TencentDnsProvider.buildAuthorization(
                "AKIDtestSecretId", "testSecretKey", "dnspod.tencentcloudapi.com", PAYLOAD, 1790103600L);
        String otherHost = TencentDnsProvider.buildAuthorization(
                "AKIDtestSecretId", "testSecretKey", "dnspod.internal.example.com", PAYLOAD, 1790103600L);
        String otherPayload = TencentDnsProvider.buildAuthorization(
                "AKIDtestSecretId", "testSecretKey", "dnspod.tencentcloudapi.com",
                PAYLOAD.replace("mc", "mc2"), 1790103600L);
        assertTrue(base.contains("Credential=AKIDtestSecretId/2026-09-22/dnspod/tc3_request"));
        org.junit.jupiter.api.Assertions.assertNotEquals(base, otherHost);
        org.junit.jupiter.api.Assertions.assertNotEquals(base, otherPayload);
    }

    @Test
    void apiBaseOnlyContributesHost() {
        TencentDnsProvider provider = new TencentDnsProvider("example.com",
                "https://dnspod.tencentcloudapi.com/v2/", "", "", null, null);
        TencentDnsProvider plain = new TencentDnsProvider("example.com", "", "", "", null, null);
        assertEquals(plain.type(), provider.type());
        assertEquals(600, provider.minTtlSeconds());
    }

    @Test
    void missingCredentialsFailFastWithReadableMessage() {
        TencentDnsProvider provider = new TencentDnsProvider("example.com", "", "", "", null, null);
        DnsCallException error = assertThrows(DnsCallException.class,
                () -> provider.upsert("mc.example.com", "A", "203.0.113.10", 600));
        assertTrue(error.getMessage().contains("SecretId"));
    }

    @Test
    void relativeNameRejectsForeignZone() {
        DnsCallException error = assertThrows(DnsCallException.class,
                () -> DnsNames.relative("mc.other.com", "example.com"));
        assertTrue(error.getMessage().contains("不在根域名"));
    }
}
