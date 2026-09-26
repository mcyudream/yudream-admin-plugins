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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare DNS（API v4）：Bearer token + {@code /zones/{zoneId}/dns_records}。
 * 记录名用**完整名**（Cloudflare 的 name 即 FQDN），SRV 走 content 字符串
 * （{@code 优先级 权重 端口 目标}）。
 */
public class CloudflareDnsProvider implements DnsProvider {

    private static final String DEFAULT_BASE = "https://api.cloudflare.com/client/v4";

    private final String zoneId;
    private final String apiBase;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public CloudflareDnsProvider(String zoneId, String apiBase, String token, HttpClient http, ObjectMapper mapper) {
        this.zoneId = zoneId == null ? "" : zoneId.trim();
        this.apiBase = apiBase == null || apiBase.isBlank() ? DEFAULT_BASE : apiBase.trim();
        this.token = token;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "cloudflare";
    }

    @Override
    public int minTtlSeconds() {
        // Cloudflare 支持 60（1）起，低于 60 只能走 Auto。
        return 60;
    }

    @Override
    public void upsert(String name, String type, String value, int ttlSeconds) {
        requireToken();
        List<Map<String, Object>> existing = list(name, type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("name", name);
        body.put("content", value);
        body.put("ttl", Math.max(minTtlSeconds(), ttlSeconds));
        if (existing.isEmpty()) {
            send("POST", recordsUrl(), mapperBody(body));
            return;
        }
        String recordId = String.valueOf(existing.get(0).get("id"));
        send("PUT", recordsUrl() + "/" + recordId, mapperBody(body));
    }

    @Override
    public void delete(String name, String type) {
        if (token == null || token.isBlank()) {
            return;
        }
        for (Map<String, Object> record : list(name, type)) {
            send("DELETE", recordsUrl() + "/" + record.get("id"), null);
        }
    }

    @Override
    public List<String> values(String name, String type) {
        requireToken();
        List<String> out = new ArrayList<>();
        for (Map<String, Object> record : list(name, type)) {
            out.add(String.valueOf(record.getOrDefault("content", "")));
        }
        return out;
    }

    private List<Map<String, Object>> list(String name, String type) {
        String url = recordsUrl() + "?type=" + encode(type) + "&name=" + encode(name);
        JsonNode root = read(send("GET", url, null));
        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode item : root.path("result")) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", item.path("id").asText());
            record.put("content", item.path("content").asText());
            records.add(record);
        }
        return records;
    }

    private String recordsUrl() {
        return apiBase + "/zones/" + zoneId + "/dns_records";
    }

    private void requireToken() {
        if (token == null || token.isBlank()) {
            throw new DnsCallException("Cloudflare API Token 未配置（面板设置 → 实例域名）");
        }
        if (zoneId.isBlank()) {
            throw new DnsCallException("Cloudflare 驱动需要 Zone ID（面板设置 → 实例域名）");
        }
    }

    private String send(String method, String url, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            case "DELETE" -> builder.DELETE().build();
            default -> builder.GET().build();
        };
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) {
            throw new DnsCallException("Cloudflare 接口调用失败：" + error.getMessage(), error);
        }
        if (response.statusCode() / 100 != 2) {
            throw new DnsCallException("Cloudflare 接口返回 HTTP " + response.statusCode() + "：" + DnsNames.snippet(response.body()));
        }
        return response.body();
    }

    private JsonNode read(String body) {
        try {
            JsonNode root = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (root.has("success") && !root.path("success").asBoolean(true)) {
                throw new DnsCallException("Cloudflare 拒绝请求：" + DnsNames.snippet(root.path("errors").toString()));
            }
            return root;
        } catch (DnsCallException error) {
            throw error;
        } catch (Exception error) {
            throw new DnsCallException("Cloudflare 响应解析失败：" + error.getMessage(), error);
        }
    }

    private String mapperBody(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception error) {
            throw new DnsCallException("请求体序列化失败：" + error.getMessage(), error);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
