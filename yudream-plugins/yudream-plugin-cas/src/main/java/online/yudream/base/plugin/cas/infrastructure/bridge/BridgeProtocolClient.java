package online.yudream.base.plugin.cas.infrastructure.bridge;

import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.http.HttpClientSupport;
import online.yudream.base.plugin.cas.infrastructure.support.JsonSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 学院 SSO 中转协议客户端：授权跳 /sso/start，后端用 code + HMAC-SHA256 签名换身份。
 * code 60 秒一次性、secret 只在服务端使用，签名与验签全部在后端完成。
 */
public final class BridgeProtocolClient implements SsoProtocolClient {

    @Override
    public SsoProtocol protocol() {
        return SsoProtocol.BRIDGE;
    }

    @Override
    public String authorizationUrl(SsoSettings settings, String state) {
        // 开启预热时先跳本站预热页：先拿到前置网关会话 cookie，再跳真实地址（与 CAS 同一机制）。
        if (settings.loginWarmup()) {
            return settings.warmupUrl(state);
        }
        return rawAuthorizationUrl(settings, state);
    }

    @Override
    public String rawAuthorizationUrl(SsoSettings settings, String state) {
        return settings.bridgeStartUrl(state);
    }

    @Override
    public ExternalIdentity exchange(SsoSettings settings, String code, String state, String clientSecret) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("SSO 中转回调缺少 code");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("SSO 中转密钥未配置，请先在认证设置页填写");
        }
        long ts = System.currentTimeMillis();
        String sign = hmacSha256Hex(clientSecret, settings.clientId() + "." + code + "." + ts);

        Map<String, Object> payload = new HashMap<>();
        payload.put("clientId", settings.clientId());
        payload.put("code", code);
        payload.put("ts", ts);
        payload.put("sign", sign);
        HttpClientSupport.HttpResponse response =
                HttpClientSupport.postJson(settings.bridgeExchangeUrl(), null, JsonSupport.write(payload));

        if (!response.ok()) {
            throw new IllegalStateException(exchangeErrorMessage(response.status(), response.body()));
        }
        var node = JsonSupport.tree(response.body());
        if (!node.path("ok").asBoolean(false)) {
            throw new IllegalStateException("SSO 中转换票失败：" + node.path("error").asText("未知错误"));
        }
        String no = node.path("no").asText("");
        String name = node.path("name").asText("");
        long responseTs = node.path("ts").asLong(0);
        String responseSign = node.path("sign").asText("");
        if (no.isBlank() || name.isBlank()) {
            throw new IllegalStateException("SSO 中转未返回有效学工号或姓名");
        }
        // 响应验签：确认身份结果未被篡改（拼接顺序为 no|name|ts）
        String expected = hmacSha256Hex(clientSecret, no + "|" + name + "|" + responseTs);
        if (responseSign.isBlank() || !constantTimeEquals(expected, responseSign)) {
            throw new IllegalStateException("SSO 中转响应签名校验失败，已拒绝本次登录");
        }
        Map<String, String> attributes = new HashMap<>();
        attributes.put("college", node.path("college").asText(""));
        attributes.put("className", node.path("className").asText(""));
        return new ExternalIdentity(no, name, "", "", "", attributes);
    }

    @Override
    public ConnectivityResult probe(SsoSettings settings, String clientSecret) {
        if (settings.callbackUrl().isBlank()) {
            return ConnectivityResult.fail("请先填写本站回调地址");
        }
        if (settings.clientId().isBlank()) {
            return ConnectivityResult.fail("请先填写 clientId");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            return ConnectivityResult.fail("请先在认证设置页填写 SSO 中转密钥");
        }
        try {
            HttpClientSupport.HttpResponse health = HttpClientSupport.get(settings.bridgeHealthUrl(), null);
            if (!health.ok()) {
                return ConnectivityResult.fail("SSO 中转探活失败（HTTP " + health.status() + "）");
            }
            var node = JsonSupport.tree(health.body());
            if (!node.path("ok").asBoolean(false) || !node.path("enabled").asBoolean(false)) {
                return ConnectivityResult.fail("SSO 中转服务已停用或不可用");
            }
            for (var client : node.path("clients")) {
                if (settings.clientId().equals(client.asText(""))) {
                    return ConnectivityResult.ok("SSO 中转服务可用，且已登记 clientId=" + settings.clientId());
                }
            }
            return ConnectivityResult.fail("SSO 中转服务未登记 clientId=" + settings.clientId());
        } catch (RuntimeException e) {
            return ConnectivityResult.fail(e.getMessage());
        }
    }

    /** 按 HTTP 状态与响应体 error 字段把换票失败转成可读消息（code 一次性，不做重试）。 */
    private static String exchangeErrorMessage(int status, String body) {
        String error = "";
        try {
            error = JsonSupport.tree(body).path("error").asText("");
        } catch (RuntimeException ignored) {
            // 非 JSON 响应体时只按状态码提示
        }
        return switch (status) {
            case 400 -> "SSO 中转拒绝请求（" + (error.isBlank() ? "参数或 code 已失效" : error) + "），请重新发起登录";
            case 401 -> "SSO 中转签名校验失败（SIGN_INVALID），请核对密钥配置";
            case 403 -> "SSO 中转未启用该 clientId（" + (error.isBlank() ? "CLIENT_NOT_ALLOWED" : error) + "）";
            case 429 -> "SSO 中转请求过于频繁，请稍后重试";
            case 503 -> "SSO 中转服务已停用";
            default -> "SSO 中转换票失败（HTTP " + status + (error.isBlank() ? "" : " " + error) + "）";
        };
    }

    static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
