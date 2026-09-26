package online.yudream.base.plugin.mcpanel.infrastructure.dns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯云 DNSPod（API 2021-03-23，TC3-HMAC-SHA256 签名）。
 *
 * <p>鉴权用 SecretId/SecretKey（CAM 子账号，最小权限给 DNSPod 的
 * DescribeRecordList/CreateRecord/ModifyRecord/DeleteRecord）。区域标识是**根域名**
 * （如 example.com），记录按 SubDomain 相对名写入（默认线路「默认」）；TTL 免费档下限 600 秒。
 *
 * <p>签名：{@code TC3-HMAC-SHA256}——规范请求串（POST + host/content-type 头 + 请求体 sha256）
 * → 待签串（时间戳 + date/service/tc3_request 作用域）→ 派生密钥逐级 HMAC-SHA256。
 */
public class TencentDnsProvider implements DnsProvider {

    private static final String DEFAULT_HOST = "dnspod.tencentcloudapi.com";
    private static final String SERVICE = "dnspod";
    private static final String VERSION = "2021-03-23";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    /** DNSPod 默认线路名（中文账户的默认线路）。 */
    private static final String DEFAULT_LINE = "默认";
    private static final int MIN_TTL = 600;

    private final String rootDomain;
    private final String host;
    private final String secretId;
    private final String secretKey;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public TencentDnsProvider(String rootDomain, String apiBase, String secretId, String secretKey,
                             HttpClient http, ObjectMapper mapper) {
        this.rootDomain = DnsNames.normalize(rootDomain);
        this.host = DnsNames.hostOf(apiBase, DEFAULT_HOST);
        this.secretId = secretId == null ? "" : secretId.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "dnspod";
    }

    @Override
    public int minTtlSeconds() {
        return MIN_TTL;
    }

    @Override
    public void upsert(String name, String type, String value, int ttlSeconds) {
        requireCredentials();
        String subdomain = DnsNames.relative(name, rootDomain);
        int ttl = Math.max(MIN_TTL, ttlSeconds);
        List<Map<String, String>> existing = find(subdomain, type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Domain", rootDomain);
        body.put("SubDomain", subdomain);
        body.put("RecordType", type);
        body.put("RecordLine", DEFAULT_LINE);
        body.put("Value", value);
        body.put("TTL", ttl);
        if (existing.isEmpty()) {
            call("CreateRecord", body);
            return;
        }
        body.put("RecordId", Long.parseLong(existing.get(0).get("RecordId")));
        call("ModifyRecord", body);
    }

    @Override
    public void delete(String name, String type) {
        if (secretId.isBlank() || secretKey.isBlank()) {
            return;
        }
        String subdomain = DnsNames.relative(name, rootDomain);
        for (Map<String, String> record : find(subdomain, type)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Domain", rootDomain);
            body.put("RecordId", Long.parseLong(record.get("RecordId")));
            call("DeleteRecord", body);
        }
    }

    @Override
    public List<String> values(String name, String type) {
        requireCredentials();
        List<String> out = new ArrayList<>();
        for (Map<String, String> record : find(DnsNames.relative(name, rootDomain), type)) {
            out.add(record.getOrDefault("Value", ""));
        }
        return out;
    }

    private List<Map<String, String>> find(String subdomain, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Domain", rootDomain);
        body.put("Subdomain", subdomain);
        body.put("RecordType", type);
        body.put("Limit", 100);
        body.put("Offset", 0);
        JsonNode root = call("DescribeRecordList", body);
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode item : root.path("Response").path("RecordList")) {
            if (!subdomain.equalsIgnoreCase(item.path("Name").asText())) {
                continue;
            }
            if (!type.equalsIgnoreCase(item.path("Type").asText())) {
                continue;
            }
            Map<String, String> record = new LinkedHashMap<>();
            record.put("RecordId", item.path("RecordId").asText());
            record.put("Value", item.path("Value").asText());
            out.add(record);
        }
        return out;
    }

    private JsonNode call(String action, Map<String, Object> body) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception error) {
            throw new DnsCallException("请求体序列化失败：" + error.getMessage(), error);
        }
        long timestamp = Instant.now().getEpochSecond();
        String authorization = buildAuthorization(secretId, secretKey, host, payload, timestamp);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host))
                .timeout(Duration.ofSeconds(15))
                .header("Host", host)
                .header("Content-Type", CONTENT_TYPE)
                .header("X-TC-Action", action)
                .header("X-TC-Version", VERSION)
                .header("X-TC-Timestamp", String.valueOf(timestamp))
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) {
            throw new DnsCallException("腾讯云 DNSPod 接口调用失败：" + error.getMessage(), error);
        }
        if (response.statusCode() / 100 != 2) {
            throw new DnsCallException("腾讯云 DNSPod 返回 HTTP " + response.statusCode()
                    + "：" + DnsNames.snippet(response.body()));
        }
        try {
            JsonNode root = mapper.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
            JsonNode error = root.path("Response").path("Error");
            if (error.hasNonNull("Code")) {
                throw new DnsCallException("腾讯云 DNSPod 拒绝请求：" + error.path("Code").asText() + " "
                        + error.path("Message").asText());
            }
            return root;
        } catch (DnsCallException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DnsCallException("腾讯云 DNSPod 响应解析失败：" + failure.getMessage(), failure);
        }
    }

    private void requireCredentials() {
        if (secretId.isBlank() || secretKey.isBlank()) {
            throw new DnsCallException("腾讯云 SecretId / SecretKey 未配置（面板设置 → 实例域名）");
        }
        if (rootDomain.isBlank()) {
            throw new DnsCallException("腾讯云 DNSPod 驱动需要填写根域名（如 example.com）");
        }
    }

    /** TC3-HMAC-SHA256 的 Authorization 头（签名只覆盖 content-type/host 与请求体）。 */
    static String buildAuthorization(String secretId, String secretKey, String host, String payload, long timestamp) {
        String date = DnsNames.utcDate(timestamp);
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n" + "host:" + host + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + DnsNames.sha256Hex(payload);
        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n"
                + DnsNames.sha256Hex(canonicalRequest);
        byte[] secretDate = DnsNames.hmac("HmacSHA256",
                ("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = DnsNames.hmac("HmacSHA256", secretDate, SERVICE);
        byte[] secretSigning = DnsNames.hmac("HmacSHA256", secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(DnsNames.hmac("HmacSHA256", secretSigning, stringToSign));
        return "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }
}
