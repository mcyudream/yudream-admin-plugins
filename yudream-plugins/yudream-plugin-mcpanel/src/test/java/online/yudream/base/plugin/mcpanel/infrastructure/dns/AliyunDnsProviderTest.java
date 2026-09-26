package online.yudream.base.plugin.mcpanel.infrastructure.dns;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阿里云云解析签名：规范化查询串 + HMAC-SHA1。
 *
 * <p>金标值由独立实现（Node.js crypto）按阿里云文档算法算出，与本实现的输出必须逐字节一致——
 * 签名错一个字符云商就回 SignatureDoesNotMatch，本地先把算法钉死。
 */
class AliyunDnsProviderTest {

    @Test
    void percentEncodingFollowsRfc3986() {
        assertEquals("a%20b", AliyunDnsProvider.percentEncode("a b"));
        assertEquals("a%2Ab", AliyunDnsProvider.percentEncode("a*b"));
        assertEquals("a~b", AliyunDnsProvider.percentEncode("a~b"));
        assertEquals("%2F", AliyunDnsProvider.percentEncode("/"));
        assertEquals("2026-09-23T02%3A00%3A00Z", AliyunDnsProvider.percentEncode("2026-09-23T02:00:00Z"));
    }

    @Test
    void canonicalQuerySortsByNameAndEncodesValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Timestamp", "2026-09-23T02:00:00Z");
        params.put("Action", "AddDomainRecord");
        params.put("RR", "mc test");
        assertEquals("Action=AddDomainRecord&RR=mc%20test&Timestamp=2026-09-23T02%3A00%3A00Z",
                AliyunDnsProvider.canonicalQuery(params));
    }

    @Test
    void signatureMatchesReferenceImplementation() {
        String canonical = "AccessKeyId=testAccessKeyId&Action=DescribeDomainRecords&DomainName=example.com"
                + "&Format=JSON&SignatureMethod=HMAC-SHA1&SignatureNonce=abc-123&SignatureVersion=1.0"
                + "&Timestamp=2026-09-23T02%3A00%3A00Z&Version=2015-01-09";
        assertEquals("YLv8XEljR4wkzizdWEAcFIOCoCY=", AliyunDnsProvider.sign(canonical, "testAccessKeySecret"));
    }

    @Test
    void canonicalQueryFeedsSignatureEndToEnd() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("AccessKeyId", "testAccessKeyId");
        params.put("Action", "DescribeDomainRecords");
        params.put("DomainName", "example.com");
        params.put("Format", "JSON");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", "abc-123");
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", "2026-09-23T02:00:00Z");
        params.put("Version", "2015-01-09");
        assertEquals("YLv8XEljR4wkzizdWEAcFIOCoCY=",
                AliyunDnsProvider.sign(AliyunDnsProvider.canonicalQuery(params), "testAccessKeySecret"));
    }

    @Test
    void ttlClampsToFreeTierFloor() {
        AliyunDnsProvider provider = new AliyunDnsProvider("example.com", "", "ak", "sk", null, null);
        assertEquals(600, provider.minTtlSeconds());
        assertEquals(600, provider.clampTtl(120));
        assertEquals(600, provider.clampTtl(599));
        assertEquals(660, provider.clampTtl(601));
        assertEquals(3600, provider.clampTtl(3600));
    }

    @Test
    void missingCredentialsFailFastWithReadableMessage() {
        AliyunDnsProvider provider = new AliyunDnsProvider("example.com", "", "", "", null, null);
        DnsCallException error = org.junit.jupiter.api.Assertions.assertThrows(DnsCallException.class,
                () -> provider.upsert("mc.example.com", "A", "203.0.113.10", 600));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("AccessKeyId"));
    }
}
