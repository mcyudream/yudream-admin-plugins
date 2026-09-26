package online.yudream.base.plugin.mcpanel.infrastructure.dns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 阿里云云解析 DNS（Alidns OpenAPI，RPC 风格 + HMAC-SHA1 签名）。
 *
 * <p>鉴权用 AccessKeyId/AccessKeySecret（RAM 子账号，授权 AliyunDNSFullAccess 即可，
 * 更小权限可只给 alidns:AddDomainRecord/UpdateDomainRecord/DeleteDomainRecord/DescribeDomainRecords）。
 * 区域标识是**根域名**（如 example.com），记录按 RR 相对名写入；TTL 免费档下限 600 秒。
 *
 * <p>签名算法：参数按名排序 → RFC3986 百分号编码（空格 %20、* %2A、~ 保留）→
 * {@code Signature = Base64(HMAC-SHA1(AccessKeySecret + "&", "GET&%2F&" + 编码后的规范化查询串))}。
 */
public class AliyunDnsProvider implements DnsProvider {

    private static final String DEFAULT_BASE = "https://alidns.aliyuncs.com/";
    private static final String VERSION = "2015-01-09";
    /** 云解析免费档 TTL 下限（秒）。 */
    private static final int MIN_TTL = 600;

    private final String rootDomain;
    private final String apiBase;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public AliyunDnsProvider(String rootDomain, String apiBase, String accessKeyId, String accessKeySecret,
                             HttpClient http, ObjectMapper mapper) {
        this.rootDomain = DnsNames.normalize(rootDomain);
        this.apiBase = apiBase == null || apiBase.isBlank() ? DEFAULT_BASE : apiBase.trim();
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.trim();
        this.accessKeySecret = accessKeySecret == null ? "" : accessKeySecret.trim();
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "aliyun";
    }

    @Override
    public int minTtlSeconds() {
        return MIN_TTL;
    }

    @Override
    public void upsert(String name, String type, String value, int ttlSeconds) {
        requireCredentials();
        String rr = DnsNames.relative(name, rootDomain);
        int ttl = clampTtl(ttlSeconds);
        List<Map<String, String>> existing = find(rr, type);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("DomainName", rootDomain);
        params.put("RR", rr);
        params.put("Type", type);
        params.put("Value", value);
        params.put("TTL", String.valueOf(ttl));
        if (existing.isEmpty()) {
            call("AddDomainRecord", params);
            return;
        }
        params.put("RecordId", existing.get(0).get("RecordId"));
        call("UpdateDomainRecord", params);
    }

    @Override
    public void delete(String name, String type) {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            return;
        }
        String rr = DnsNames.relative(name, rootDomain);
        for (Map<String, String> record : find(rr, type)) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("RecordId", record.get("RecordId"));
            call("DeleteDomainRecord", params);
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

    /** TTL 收敛：下限 600 秒，并向上取整到 60 的整数倍（云解析接受 60 秒粒度的合法值）。 */
    int clampTtl(int ttlSeconds) {
        int ttl = Math.max(MIN_TTL, ttlSeconds);
        return ((ttl + 59) / 60) * 60;
    }

    /** 精确匹配同名同类型记录（RRKeyWord 是模糊匹配，必须再本地过滤一次）。 */
    private List<Map<String, String>> find(String rr, String type) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("DomainName", rootDomain);
        params.put("RRKeyWord", rr);
        params.put("TypeKeyWord", type);
        params.put("PageSize", "100");
        params.put("PageNumber", "1");
        JsonNode root = call("DescribeDomainRecords", params);
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode item : root.path("DomainRecords").path("Record")) {
            if (!rr.equalsIgnoreCase(item.path("RR").asText())) {
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

    private JsonNode call(String action, Map<String, String> actionParams) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Format", "JSON");
        params.put("Version", VERSION);
        params.put("AccessKeyId", accessKeyId);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", DnsNames.utcTimestamp());
        params.put("Action", action);
        params.putAll(actionParams);
        String canonical = canonicalQuery(params);
        String signature = sign(canonical, accessKeySecret);
        String url = apiBase + (apiBase.endsWith("/") ? "" : "/") + "?" + canonical
                + "&Signature=" + percentEncode(signature);
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) {
            throw new DnsCallException("阿里云云解析接口调用失败：" + error.getMessage(), error);
        }
        if (response.statusCode() / 100 != 2) {
            throw new DnsCallException("阿里云云解析返回 HTTP " + response.statusCode() + "：" + DnsNames.snippet(response.body()));
        }
        try {
            JsonNode root = mapper.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
            if (root.hasNonNull("Code")) {
                throw new DnsCallException("阿里云云解析拒绝请求：" + root.path("Code").asText() + " "
                        + root.path("Message").asText());
            }
            return root;
        } catch (DnsCallException error) {
            throw error;
        } catch (Exception error) {
            throw new DnsCallException("阿里云云解析响应解析失败：" + error.getMessage(), error);
        }
    }

    private void requireCredentials() {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            throw new DnsCallException("阿里云 AccessKeyId / AccessKeySecret 未配置（面板设置 → 实例域名）");
        }
        if (rootDomain.isBlank()) {
            throw new DnsCallException("阿里云驱动需要填写根域名（如 example.com）");
        }
    }

    /** 规范化查询串：参数名升序 + RFC3986 编码。 */
    static String canonicalQuery(Map<String, String> params) {
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    /** HMAC-SHA1 签名（输入为规范化查询串，输出 Base64）。 */
    static String sign(String canonicalQuery, String accessKeySecret) {
        String stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(canonicalQuery);
        byte[] digest = DnsNames.hmac("HmacSHA1",
                (accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), stringToSign);
        return Base64.getEncoder().encodeToString(digest);
    }

    /** 阿里云要求的百分号编码：URLEncoder 后把 + / * / %7E 修正为 RFC3986 形式。 */
    static String percentEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
