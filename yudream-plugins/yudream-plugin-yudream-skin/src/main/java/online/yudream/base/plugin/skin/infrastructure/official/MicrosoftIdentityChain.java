package online.yudream.base.plugin.skin.infrastructure.official;

import com.fasterxml.jackson.databind.JsonNode;
import online.yudream.base.plugin.skin.infrastructure.repository.OfficialBindDocumentRepository;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Microsoft OAuth（PKCE；public client，或 Web 机密客户端附带 client_secret；
 * XboxLive.signin + offline_access）→ Xbox → XSTS → login_with_xbox → Minecraft profile
 * 的官方所有权验证链（自带续签：refresh_token 入 SecretStore 后可静默重领官方会话）。
 * HTTP 经 {@link HttpExchange} 注入以便测试。
 */
public class MicrosoftIdentityChain {

    private static final String AUTHORIZE_BASE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String TOKEN_BASE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
    /** 主路径：官方启动器认证通道（与 YMCL 启动器实测一致，login_with_xbox 对部分账号/出口会被拒 403）。 */
    private static final String MINECRAFT_LAUNCHER_LOGIN = "https://api.minecraftservices.com/launcher/login";
    /** 回退路径：传统第三方登录端点。 */
    private static final String MINECRAFT_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MINECRAFT_PROFILE = "https://api.minecraftservices.com/player/minecraft/profile";
    /** 与 YMCL 启动器保持一致的 UA（启动器通道已验证可用）。 */
    public static final String USER_AGENT = "YMCL (YuDream Launcher)";

    public interface HttpExchange {
        record Response(int status, String body) {
        }

        Response send(String url, String method, Map<String, String> headers, String body) throws Exception;
    }

    public record TokenGrant(String accessToken, String refreshToken, String accountHint) {
        public TokenGrant(String accessToken, String refreshToken) {
            this(accessToken, refreshToken, null);
        }
    }

    public record OfficialProfile(String id, String name, String skinUrl, String skinModel) {
    }

    /** 官方会话（正版会话直通：access_token 为 Mojang 签发，可直接用于原版登录路径）。 */
    public record OfficialSession(String accessToken, long expiresAt, OfficialProfile profile) {
    }

    private final String clientId;
    private final String redirectUri;
    /** 可选：Azure「Web」平台机密客户端的 client_secret；未配置时走纯 PKCE public client。 */
    private final String clientSecret;
    private final HttpExchange http;

    public MicrosoftIdentityChain(String clientId, String redirectUri, HttpExchange http) {
        this(clientId, redirectUri, null, http);
    }

    public MicrosoftIdentityChain(String clientId, String redirectUri, String clientSecret, HttpExchange http) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.clientSecret = clientSecret == null || clientSecret.isBlank() ? null : clientSecret.trim();
        this.http = http;
    }

    public String authorizeUrl(String state, String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("scope", "XboxLive.signin offline_access openid profile");
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        params.put("state", state);
        params.put("prompt", "select_account");
        return AUTHORIZE_BASE + "?" + params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    public TokenGrant exchangeCode(String code, String codeVerifier) {
        return tokenRequest(grantBody("authorization_code", code, codeVerifier, null));
    }

    public TokenGrant refresh(String refreshToken) {
        return tokenRequest(grantBody("refresh_token", null, null, refreshToken));
    }

    private String grantBody(String grantType, String code, String codeVerifier, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", grantType);
        if (code != null) {
            form.put("code", code);
        }
        if (codeVerifier != null) {
            form.put("code_verifier", codeVerifier);
        }
        if (refreshToken != null) {
            form.put("refresh_token", refreshToken);
        }
        form.put("client_id", clientId);
        form.put("redirect_uri", redirectUri);
        if (clientSecret != null) {
            form.put("client_secret", clientSecret);
        }
        return form.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private TokenGrant tokenRequest(String formBody) {
        try {
            HttpExchange.Response response = http.send(TOKEN_BASE, "POST",
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "User-Agent", USER_AGENT), formBody);
            if (response.status() != 200) {
                throw new IllegalStateException("Microsoft 令牌端点返回 " + response.status()
                        + "：" + errorDescription(response.body()));
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String accessToken = text(json.get("access_token"));
            if (accessToken == null) {
                throw new IllegalStateException("Microsoft 令牌响应缺少 access_token");
            }
            return new TokenGrant(accessToken, text(json.get("refresh_token")),
                    accountHintFromIdToken(text(json.get("id_token"))));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Microsoft 令牌交换失败：" + e.getMessage(), e);
        }
    }

    /** Xbox → XSTS → Minecraft 登录链，返回官方 profile 会话。 */
    public OfficialSession login(TokenGrant grant) {
        String xblToken = xboxAuthenticate(grant.accessToken());
        String xstsPayload = xstsAuthorize(xblToken);
        return minecraftSession(xstsPayload);
    }

    private String xboxAuthenticate(String msAccessToken) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Properties", Map.of(
                    "AuthMethod", "RPS",
                    "SiteName", "user.auth.xboxlive.com",
                    "RpsTicket", "d=" + msAccessToken));
            body.put("RelyingParty", "http://auth.xboxlive.com");
            body.put("TokenType", "JWT");
            HttpExchange.Response response = postJson(XBOX_AUTH, body);
            if (response.status() != 200) {
                throw new IllegalStateException("Xbox 认证返回 " + response.status() + "：" + excerpt(response.body()));
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String token = text(json.get("Token"));
            String uhs = json.path("DisplayClaims").path("xui").get(0).path("uhs").asText("");
            if (token == null || uhs.isEmpty()) {
                throw new IllegalStateException("Xbox 认证响应缺少 Token/uhs");
            }
            return uhs + ";" + token;
        } catch (Exception e) {
            throw new IllegalStateException("Xbox 认证失败：" + e.getMessage(), e);
        }
    }

    private String xstsAuthorize(String xblCombined) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Properties", Map.of(
                    "SandboxId", "RETAIL",
                    "UserTokens", List.of(xblCombined.split(";", 2)[1])));
            // Minecraft Services 是实际 relying party；使用 xboxlive.com 会得到不能兑换
            // Java session 的 XSTS token。
            body.put("RelyingParty", "rp://api.minecraftservices.com/");
            body.put("TokenType", "JWT");
            HttpExchange.Response response = postJson(XSTS_AUTH, body);
            int status = response.status();
            if (status == 401) {
                throw new IllegalStateException("该 Microsoft 账号无法通过 Xbox 验证（XErr=" + xerrOf(response.body())
                        + "；常见原因：该账号未关联 Xbox 账号、未成年人限制或地区不支持）");
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String token = text(json.get("Token"));
            String uhs = json.path("DisplayClaims").path("xui").get(0).path("uhs").asText(xblCombined.split(";", 2)[0]);
            if (token == null) {
                throw new IllegalStateException("XSTS 授权返回 " + status + "：" + excerpt(response.body()));
            }
            return "XBL3.0 x=" + uhs + ";" + token;
        } catch (Exception e) {
            throw new IllegalStateException("XSTS 授权失败：" + e.getMessage(), e);
        }
    }

    /**
     * Xbox → XSTS 后的 Minecraft 会话：优先官方启动器通道（{@code /launcher/login} +
     * {@code platform=PC_LAUNCHER + xtoken}，与 YMCL 启动器实测一致）；被拒时回退传统
     * {@code login_with_xbox}。两端点均失败时合并两段状态与响应体，便于区分
     * 账号无 entitlement、端点策略拒绝与出口 IP 被拒。
     */
    private OfficialSession minecraftSession(String identityToken) {
        MinecraftToken token;
        try {
            token = minecraftTokenAt(MINECRAFT_LAUNCHER_LOGIN,
                    Map.of("platform", "PC_LAUNCHER", "xtoken", identityToken));
        } catch (IllegalStateException primary) {
            try {
                token = minecraftTokenAt(MINECRAFT_LOGIN, Map.of("identityToken", identityToken));
            } catch (IllegalStateException fallback) {
                IllegalStateException merged = new IllegalStateException("Minecraft 会话获取失败：" + primary.getMessage()
                        + "；回退 login_with_xbox 同样失败：" + fallback.getMessage()
                        + "。Microsoft 授权与 Xbox 验证均已通过，但 Minecraft 服务拒绝发放会话——"
                        + "常见原因：完成授权的账号未持有 Java 版（注意核对是否选错账号）、"
                        + "账号缺少 entitlement，或服务器出口被 Minecraft 服务拒绝。", primary);
                merged.addSuppressed(fallback);
                throw merged;
            }
        }
        OfficialProfile profile = fetchProfile(token.accessToken());
        return new OfficialSession(token.accessToken(),
                System.currentTimeMillis() + token.expiresInSeconds() * 1000L, profile);
    }

    private record MinecraftToken(String accessToken, long expiresInSeconds) {
    }

    /** 单个端点领取 Minecraft access_token；非 200 报状态码与响应体摘要。 */
    private MinecraftToken minecraftTokenAt(String url, Map<String, Object> body) {
        try {
            HttpExchange.Response response = postJson(url, body);
            if (response.status() != 200) {
                throw new IllegalStateException(endpointName(url) + " 返回 " + response.status()
                        + "：" + excerpt(response.body()));
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String accessToken = text(json.get("access_token"));
            if (accessToken == null) {
                throw new IllegalStateException(endpointName(url) + " 响应缺少 access_token");
            }
            return new MinecraftToken(accessToken, json.path("expires_in").asLong(3600));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(endpointName(url) + " 请求异常：" + e.getMessage(), e);
        }
    }

    public OfficialProfile fetchProfile(String minecraftAccessToken) {
        try {
            HttpExchange.Response response = http.send(MINECRAFT_PROFILE, "GET",
                    Map.of("Authorization", "Bearer " + minecraftAccessToken, "User-Agent", USER_AGENT), null);
            if (response.status() == 404) {
                throw new IllegalStateException("该 Microsoft 账号没有 Minecraft Java profile");
            }
            if (response.status() != 200) {
                throw new IllegalStateException("Minecraft profile 返回 " + response.status());
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String id = text(json.get("id"));
            String name = text(json.get("name"));
            if (id == null || name == null) {
                throw new IllegalStateException("Minecraft profile 响应缺少 id/name");
            }
            String normalizedId = id.replace("-", "").toLowerCase();
            if (!normalizedId.matches("[0-9a-f]{32}") || !name.matches("[A-Za-z0-9_]{3,16}")) {
                throw new IllegalStateException("Minecraft profile 返回的 UUID 或角色名非法");
            }
            JsonNode firstSkin = json.path("skins").path(0);
            String skinUrl = firstSkin.path("url").asText("");
            String skinModel = firstSkin.path("variant").asText("CLASSIC");
            return new OfficialProfile(normalizedId, name, skinUrl.isEmpty() ? null : skinUrl,
                    "SLIM".equalsIgnoreCase(skinModel) ? "slim" : "default");
        } catch (Exception e) {
            throw new IllegalStateException("Minecraft profile 获取失败：" + e.getMessage(), e);
        }
    }

    /** 官方 skin URL 末段 = 纹理 SHA-256（内容寻址可直接入库）。 */
    public static String skinHashFromUrl(String skinUrl) {
        if (skinUrl == null || skinUrl.isBlank()) {
            return null;
        }
        int index = skinUrl.lastIndexOf('/');
        return index >= 0 ? skinUrl.substring(index + 1) : skinUrl;
    }

    private HttpExchange.Response postJson(String url, Map<String, Object> body) throws Exception {
        return http.send(url, "POST",
                Map.of("Content-Type", "application/json", "Accept", "application/json", "User-Agent", USER_AGENT),
                online.yudream.base.plugin.skin.infrastructure.support.JsonSupport.write(body));
    }

    /** 错误信息中的响应体摘要：压平空白并截断，避免整页 HTML 刷屏。 */
    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应体)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }

    /** XSTS 401 响应中的 XErr 错误码（2148916233 无 Xbox 账号 / 2148916238 未成年人等）。 */
    private static String xerrOf(String body) {
        try {
            JsonNode json = OfficialBindDocumentRepository.parse(body);
            return json.has("XErr") && !json.get("XErr").isNull()
                    ? String.valueOf(json.get("XErr").asLong()) : "未知";
        } catch (Exception e) {
            return "未知";
        }
    }

    private static String endpointName(String url) {
        return url.endsWith("/launcher/login") ? "launcher/login" : "login_with_xbox";
    }

    /**
     * id_token（JWT）payload 中的展示用账号标识（preferred_username / email），
     * 用于把「完成授权的 Microsoft 账号」回显到绑定结果与错误信息；解析失败一律返回 null，绝不影响主链。
     */
    static String accountHintFromIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return null;
        }
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            JsonNode payload = OfficialBindDocumentRepository.parse(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            String hint = text(payload.get("preferred_username"));
            if (hint == null) {
                hint = text(payload.get("email"));
            }
            return hint == null || hint.isBlank() ? null : hint;
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorDescription(String body) {
        try {
            JsonNode json = OfficialBindDocumentRepository.parse(body);
            return text(json.get("error_description")) == null ? body : text(json.get("error_description"));
        } catch (Exception ignored) {
            return body;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
