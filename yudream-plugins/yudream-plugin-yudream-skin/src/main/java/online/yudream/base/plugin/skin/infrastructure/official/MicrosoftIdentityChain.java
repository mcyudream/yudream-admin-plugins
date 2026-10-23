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
    private static final String MINECRAFT_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MINECRAFT_PROFILE = "https://api.minecraftservices.com/player/minecraft/profile";

    public interface HttpExchange {
        record Response(int status, String body) {
        }

        Response send(String url, String method, Map<String, String> headers, String body) throws Exception;
    }

    public record TokenGrant(String accessToken, String refreshToken) {
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
                    Map.of("Content-Type", "application/x-www-form-urlencoded"), formBody);
            if (response.status() != 200) {
                throw new IllegalStateException("Microsoft 令牌端点返回 " + response.status()
                        + "：" + errorDescription(response.body()));
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String accessToken = text(json.get("access_token"));
            if (accessToken == null) {
                throw new IllegalStateException("Microsoft 令牌响应缺少 access_token");
            }
            return new TokenGrant(accessToken, text(json.get("refresh_token")));
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
                throw new IllegalStateException("该 Microsoft 账号无法通过 Xbox 验证（可能无 Xbox 资格或未成年人限制）");
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String token = text(json.get("Token"));
            String uhs = json.path("DisplayClaims").path("xui").get(0).path("uhs").asText(xblCombined.split(";", 2)[0]);
            if (token == null) {
                throw new IllegalStateException("XSTS 授权响应缺少 Token");
            }
            return "XBL3.0 x=" + uhs + ";" + token;
        } catch (Exception e) {
            throw new IllegalStateException("XSTS 授权失败：" + e.getMessage(), e);
        }
    }

    private OfficialSession minecraftSession(String identityToken) {
        try {
            HttpExchange.Response response = postJson(MINECRAFT_LOGIN, Map.of("identityToken", identityToken));
            if (response.status() != 200) {
                throw new IllegalStateException("Minecraft 登录返回 " + response.status() + "（该账号可能未拥有 Java 版）");
            }
            JsonNode json = OfficialBindDocumentRepository.parse(response.body());
            String accessToken = text(json.get("access_token"));
            if (accessToken == null) {
                throw new IllegalStateException("Minecraft 登录响应缺少 access_token");
            }
            OfficialProfile profile = fetchProfile(accessToken);
            long expiresIn = json.path("expires_in").asLong(3600);
            return new OfficialSession(accessToken, System.currentTimeMillis() + expiresIn * 1000L, profile);
        } catch (Exception e) {
            throw new IllegalStateException("Minecraft 会话获取失败：" + e.getMessage(), e);
        }
    }

    public OfficialProfile fetchProfile(String minecraftAccessToken) {
        try {
            HttpExchange.Response response = http.send(MINECRAFT_PROFILE, "GET",
                    Map.of("Authorization", "Bearer " + minecraftAccessToken), null);
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
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                online.yudream.base.plugin.skin.infrastructure.support.JsonSupport.write(body));
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
