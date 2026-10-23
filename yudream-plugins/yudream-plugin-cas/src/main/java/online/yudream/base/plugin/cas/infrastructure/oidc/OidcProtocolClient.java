package online.yudream.base.plugin.cas.infrastructure.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.http.HttpClientSupport;
import online.yudream.base.plugin.cas.infrastructure.support.JsonSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OidcProtocolClient implements SsoProtocolClient {

    @Override
    public SsoProtocol protocol() {
        return SsoProtocol.OIDC;
    }

    @Override
    public String authorizationUrl(SsoSettings settings, String state) {
        StringBuilder url = new StringBuilder(settings.oidcAuthorizeUrl());
        url.append("?response_type=code");
        url.append("&client_id=").append(encode(settings.clientId()));
        url.append("&redirect_uri=").append(encode(settings.callbackUrl()));
        url.append("&scope=").append(encode(settings.scopes()));
        url.append("&state=").append(encode(state));
        return url.toString();
    }

    @Override
    public ExternalIdentity exchange(SsoSettings settings, String ticket, String state, String clientSecret) {
        if (ticket == null || ticket.isBlank()) {
            throw new IllegalArgumentException("OIDC 回调缺少 code");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("OIDC 客户端密钥未配置");
        }
        JsonNode token = requestToken(settings, ticket, clientSecret);
        JsonNode jwks = fetchJwks(settings);
        String idToken = text(token, "id_token");
        JsonNode claims = OidcIdTokenVerifier.verify(idToken, jwks, settings.oidcIssuer(), settings.clientId());
        JsonNode userinfo = fetchUserinfo(settings, text(token, "access_token"));
        String sub = firstNonBlank(text(userinfo, "sub"), OidcIdTokenVerifier.text(claims, "sub"));
        if (sub.isBlank()) {
            throw new IllegalStateException("OIDC 未返回 sub");
        }
        String nickname = firstNonBlank(
                text(userinfo, "name"),
                text(userinfo, "preferred_username"),
                text(userinfo, "nickname"),
                OidcIdTokenVerifier.text(claims, "name"),
                sub
        );
        String avatar = firstNonBlank(text(userinfo, "picture"), OidcIdTokenVerifier.text(claims, "picture"));
        return new ExternalIdentity(sub, nickname, avatar, "", "", textualClaims(userinfo, claims));
    }

    /**
     * 把 userinfo（优先）与 id_token claims 中的平文本字段收进属性表，
     * 供学生信息映射使用。嵌套对象/数组跳过，只取字符串/数字/布尔。
     */
    private static Map<String, String> textualClaims(JsonNode userinfo, JsonNode claims) {
        Map<String, String> values = new LinkedHashMap<>();
        collectTextual(userinfo, values);
        collectTextual(claims, values);
        return values;
    }

    private static void collectTextual(JsonNode node, Map<String, String> values) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.fieldNames().forEachRemaining(field -> {
            JsonNode value = node.path(field);
            if (value.isValueNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    values.putIfAbsent(field, text);
                }
            }
        });
    }

    @Override
    public ConnectivityResult probe(SsoSettings settings, String clientSecret) {
        if (settings.callbackUrl().isBlank()) {
            return ConnectivityResult.fail("请先填写本站回调地址");
        }
        if (settings.clientId().isBlank()) {
            return ConnectivityResult.fail("请先填写 OIDC client_id");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            return ConnectivityResult.fail("请先填写并保存 OIDC client_secret");
        }
        try {
            HttpClientSupport.HttpResponse jwks = HttpClientSupport.get(settings.oidcJwksUrl(), null);
            if (!jwks.ok()) {
                return ConnectivityResult.fail("JWKS 不可达（HTTP " + jwks.status() + "）");
            }
            JsonNode keys = JsonSupport.tree(jwks.body());
            if (!keys.has("keys") || !keys.get("keys").isArray() || keys.get("keys").isEmpty()) {
                return ConnectivityResult.fail("JWKS 未返回公钥");
            }
            HttpClientSupport.HttpResponse authorize = HttpClientSupport.get(authorizationUrl(settings, "probe"), null);
            if (authorize.status() >= 500) {
                return ConnectivityResult.fail("授权端点不可达（HTTP " + authorize.status() + "）");
            }
            return ConnectivityResult.ok("OIDC JWKS 与授权端点可达；已读取 " + keys.get("keys").size() + " 把公钥");
        } catch (RuntimeException e) {
            return ConnectivityResult.fail(e.getMessage());
        }
    }

    public JsonNode registerClient(SsoSettings settings, String clientName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", clientName == null || clientName.isBlank() ? settings.displayName() : clientName);
        payload.put("redirect_uris", java.util.List.of(settings.callbackUrl()));
        payload.put("grant_types", java.util.List.of("authorization_code"));
        payload.put("response_types", java.util.List.of("code"));
        payload.put("token_endpoint_auth_method", "client_secret_basic");
        payload.put("scope", settings.scopes());
        payload.put("application_type", "web");
        HttpClientSupport.HttpResponse response = HttpClientSupport.postJson(settings.oidcRegisterUrl(), Map.of(), JsonSupport.write(payload));
        if (!response.ok()) {
            throw new IllegalStateException("动态注册失败（HTTP " + response.status() + "）：" + truncate(response.body()));
        }
        return JsonSupport.tree(response.body());
    }

    private JsonNode requestToken(SsoSettings settings, String code, String clientSecret) {
        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(settings.callbackUrl());
        Map<String, String> headers = Map.of("Authorization", basic(settings.clientId(), clientSecret));
        HttpClientSupport.HttpResponse response = HttpClientSupport.postForm(settings.oidcTokenUrl(), headers, body);
        if (!response.ok()) {
            throw new IllegalStateException("换取 OIDC token 失败（HTTP " + response.status() + "）：" + truncate(response.body()));
        }
        return JsonSupport.tree(response.body());
    }

    private JsonNode fetchJwks(SsoSettings settings) {
        HttpClientSupport.HttpResponse response = HttpClientSupport.get(settings.oidcJwksUrl(), null);
        if (!response.ok()) {
            throw new IllegalStateException("读取 JWKS 失败（HTTP " + response.status() + "）");
        }
        return JsonSupport.tree(response.body());
    }

    private JsonNode fetchUserinfo(SsoSettings settings, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return JsonSupport.tree("{}");
        }
        HttpClientSupport.HttpResponse response = HttpClientSupport.get(
                settings.oidcUserinfoUrl(),
                Map.of("Authorization", "Bearer " + accessToken)
        );
        if (!response.ok()) {
            return JsonSupport.tree("{}");
        }
        return JsonSupport.tree(response.body());
    }

    private static String basic(String clientId, String clientSecret) {
        String raw = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return "";
        }
        return node.path(field).asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }
}
