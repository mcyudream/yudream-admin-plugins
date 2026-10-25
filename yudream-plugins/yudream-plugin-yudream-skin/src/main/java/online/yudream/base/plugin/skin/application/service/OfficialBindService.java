package online.yudream.base.plugin.skin.application.service;

import online.yudream.base.plugin.skin.domain.aggregate.OfficialBind;
import online.yudream.base.plugin.skin.domain.aggregate.SkinPlayer;
import online.yudream.base.plugin.skin.infrastructure.official.MicrosoftIdentityChain;
import online.yudream.base.plugin.skin.infrastructure.repository.OfficialBindDocumentRepository;
import online.yudream.base.plugin.skin.infrastructure.repository.YuDreamSkinRepository;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 正版账号绑定（所有权经 Microsoft OAuth → Xbox → Minecraft 链验证，绝不接受手填 UUID）。
 * 自带续签：offline_access 的 refresh_token 加密存 SecretStore，站点可静默重签官方会话
 * （正版会话直通：玩家用官方 access_token 走原版登录路径，角色名即正版名，可进纯正版在线服）。
 */
public class OfficialBindService {

    public static final String SECRET_PREFIX = "official-bind:";
    public static final String SECRET_SUFFIX = ":refresh";
    /** Azure 机密客户端凭据（可选）：仅存 SecretStore，绝不落文档库。 */
    public static final String AZURE_SECRET_KEY = "official-bind:azure:client-secret";
    private static final long STATE_TTL = 10 * 60_000L;
    private static final long REBIND_COOLDOWN = 72 * 60 * 60_000L;

    private final OfficialBindDocumentRepository binds;
    private final YuDreamSkinRepository players;
    private final PluginSecretStore secrets;
    private final java.util.function.Supplier<String> redirectOrigin;
    private final online.yudream.base.plugin.skin.infrastructure.official.OfficialTextureImporter textureImporter;

    public OfficialBindService(OfficialBindDocumentRepository binds, YuDreamSkinRepository players,
                               PluginSecretStore secrets, java.util.function.Supplier<String> redirectOrigin) {
        this.binds = binds;
        this.players = players;
        this.secrets = secrets;
        this.redirectOrigin = redirectOrigin;
        this.textureImporter = new online.yudream.base.plugin.skin.infrastructure.official.OfficialTextureImporter(players);
    }

    public Map<String, Object> settings() {
        Map<String, Object> body = new LinkedHashMap<>(binds.settings());
        body.put("redirectUri", redirectUriOrNull());
        body.put("clientSecretConfigured", configuredClientSecret() != null);
        return body;
    }

    public Map<String, Object> saveSettings(boolean enabled, String clientId) {
        return saveSettings(enabled, clientId, null);
    }

    /** clientSecret 仅在传入非空值时更新；留空表示保持现有凭据不变。 */
    public Map<String, Object> saveSettings(boolean enabled, String clientId, String clientSecret) {
        if (enabled && (clientId == null || clientId.isBlank())) {
            throw new IllegalArgumentException("启用正版绑定必须先填写 Azure 应用 client_id（public client + PKCE）");
        }
        if (enabled) {
            assertSecretStoreWritable();
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            assertSecretStoreWritable();
            safeStore(AZURE_SECRET_KEY, clientSecret.trim().getBytes(StandardCharsets.UTF_8));
        }
        binds.saveSettings(enabled, clientId);
        return settings();
    }

    /** 站点实际发送给 Microsoft 的回调地址；origin 未配置时为空。 */
    private String redirectUriOrNull() {
        String origin = redirectOrigin.get();
        if (origin == null || origin.isBlank()) {
            return null;
        }
        return origin + "/api/plugins/yudream-skin/official-bind/callback";
    }

    /** 发起绑定：安全校验 → 生成 state/verifier → 返回官方授权页 URL。 */
    public Map<String, Object> start(String ownerId, long now) {
        Map<String, Object> settings = binds.settings();
        if (!Boolean.TRUE.equals(settings.get("enabled"))) {
            throw new IllegalArgumentException("正版绑定未开放（管理面未启用或未配置 Azure 应用）");
        }
        String clientId = String.valueOf(settings.get("clientId"));
        boolean hasActiveBind = binds.findBindsByOwner(ownerId).stream()
                .anyMatch(bind -> OfficialBind.STATUS_ACTIVE.equals(bind.status()));
        if (hasActiveBind) {
            throw new IllegalArgumentException("已存在生效中的正版绑定，请先解绑");
        }
        String state = randomHex(16);
        String verifier = randomHex(48);
        String challenge = base64Url(claimSha256(verifier.getBytes(StandardCharsets.UTF_8)));
        binds.saveState(state, ownerId, verifier, now + STATE_TTL);
        MicrosoftIdentityChain chain = chain(clientId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorizeUrl", chain.authorizeUrl(state, challenge));
        body.put("expiresAt", now + STATE_TTL);
        return body;
    }

    /** 官方回调（公开端点）：一次性 state 兑换 code → 全链验证 → 落绑定与官方角色。 */
    public Map<String, Object> callback(String state, String code, long now) {
        Map<String, Object> settings = binds.settings();
        if (!Boolean.TRUE.equals(settings.get("enabled"))) {
            throw new IllegalArgumentException("正版绑定未开放");
        }
        MicrosoftIdentityChain chain = chain(String.valueOf(settings.get("clientId")));
        Optional<Map<String, Object>> stateDoc = binds.consumeState(state == null ? "" : state, now);
        if (stateDoc.isEmpty()) {
            throw new IllegalArgumentException("绑定会话无效、已过期或已被使用");
        }
        String ownerId = String.valueOf(stateDoc.get().get("ownerId"));
        String verifier = String.valueOf(stateDoc.get().get("codeVerifier"));
        MicrosoftIdentityChain.TokenGrant grant = chain.exchangeCode(code, verifier);
        MicrosoftIdentityChain.OfficialSession session = chainLogin(chain, grant);
        Map<String, Object> body = completeBind(ownerId, session, grant.refreshToken(), chain, now);
        if (grant.accountHint() != null && !grant.accountHint().isBlank()) {
            body.put("msAccount", grant.accountHint());
        }
        return body;
    }

    private Map<String, Object> completeBind(String ownerId, MicrosoftIdentityChain.OfficialSession session,
                                             String refreshToken, MicrosoftIdentityChain chain, long now) {
        MicrosoftIdentityChain.OfficialProfile profile = session.profile();
        String officialUuid = profile.id();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Microsoft 未返回 refresh_token，无法启用自带续签；请确认 Azure 应用允许 offline_access 后重试");
        }
        assertOfficialBindAvailable(ownerId, profile, now);
        // 先探测安全存储可用性，避免创建官方角色后才发现无法续签。
        assertSecretStoreWritable();
        syncOfficialPlayer(ownerId, profile, now);
        safeStore(SECRET_PREFIX + officialUuid + SECRET_SUFFIX, refreshToken.getBytes(StandardCharsets.UTF_8));
        binds.saveBind(new OfficialBind(officialUuid, ownerId, profile.name(), "microsoft-oauth",
                null, true, OfficialBind.STATUS_ACTIVE, now, now, null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bind", bindView(binds.findBindByUuid(officialUuid).orElseThrow()));
        return body;
    }

    public Map<String, Object> status(String ownerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settingsEnabled", Boolean.TRUE.equals(binds.settings().get("enabled")));
        binds.findBindsByOwner(ownerId).stream()
                .filter(bind -> OfficialBind.STATUS_ACTIVE.equals(bind.status()))
                .findFirst()
                .ifPresentOrElse(
                        bind -> body.put("bind", bindView(bind)),
                        () -> body.put("bind", null));
        return body;
    }

    /** 解绑：删除续签凭据、释放官方 UUID 角色和默认角色引用；纹理资产仍按内容寻址保留。 */
    public void unbind(String ownerId) {
        binds.findBindsByOwner(ownerId).stream()
                .filter(bind -> OfficialBind.STATUS_ACTIVE.equals(bind.status()))
                .forEach(bind -> {
                    safeDeleteSecret(SECRET_PREFIX + bind.officialUuid() + SECRET_SUFFIX);
                    players.findPlayerByUuid(bind.officialUuid())
                            .filter(player -> ownerId.equals(player.ownerId())
                                    && SkinPlayer.KIND_OFFICIAL.equals(player.profileKind()))
                            .ifPresent(player -> {
                                players.findDefaultPlayerUuid(ownerId)
                                        .filter(uuid -> uuid.equalsIgnoreCase(player.uuid()))
                                        .ifPresent(ignored -> players.saveDefaultPlayerUuid(ownerId, null));
                                players.deletePlayer(player.uuid());
                            });
                    binds.saveBind(new OfficialBind(bind.officialUuid(), bind.ownerId(), bind.officialNameLast(),
                            bind.verificationMethod(), bind.xuid(), false, OfficialBind.STATUS_UNBOUND,
                            bind.verifiedAt(), bind.createdAt(), System.currentTimeMillis()));
                });
    }

    /** 正版会话直通（自带续签）：用 SecretStore 的 refresh token 静默重领官方 access_token。 */
    public Map<String, Object> mintOfficialSession(String ownerId) {
        OfficialBind bind = binds.findBindsByOwner(ownerId).stream()
                .filter(item -> OfficialBind.STATUS_ACTIVE.equals(item.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("尚未绑定正版账号"));
        Map<String, Object> settings = binds.settings();
        MicrosoftIdentityChain chain = chain(String.valueOf(settings.get("clientId")));
        String refreshToken = safeStore(SECRET_PREFIX + bind.officialUuid() + SECRET_SUFFIX)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalArgumentException("续签凭据缺失，请重新发起一次正版绑定"));
        MicrosoftIdentityChain.TokenGrant grant = chain.refresh(refreshToken);
        MicrosoftIdentityChain.OfficialSession session = chainLogin(chain, grant);
        if (!bind.officialUuid().equalsIgnoreCase(session.profile().id())) {
            disableBindingAndReleasePlayer(bind);
            throw new IllegalArgumentException("续签账号与已绑定正版角色不一致，已停用绑定，请重新授权");
        }
        String rotatedRefresh = grant.refreshToken() == null || grant.refreshToken().isBlank()
                ? refreshToken : grant.refreshToken();
        assertOfficialBindAvailable(ownerId, session.profile(), System.currentTimeMillis());
        syncOfficialPlayer(ownerId, session.profile(), System.currentTimeMillis());
        binds.saveBind(bind.withName(session.profile().name(), bind.xuid(), true));
        safeStore(SECRET_PREFIX + bind.officialUuid() + SECRET_SUFFIX,
                rotatedRefresh.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountKind", "microsoft-java");
        body.put("minecraftAccessToken", session.accessToken());
        body.put("expiresAt", session.expiresAt());
        body.put("profile", Map.of("id", session.profile().id(), "name", session.profile().name()));
        if (grant.accountHint() != null && !grant.accountHint().isBlank()) {
            body.put("msAccount", grant.accountHint());
        }
        return body;
    }

    /** 链路失败时把完成授权的 Microsoft 账号带入错误信息，便于识别「选错账号」。 */
    private MicrosoftIdentityChain.OfficialSession chainLogin(MicrosoftIdentityChain chain,
                                                              MicrosoftIdentityChain.TokenGrant grant) {
        try {
            return chain.login(grant);
        } catch (RuntimeException e) {
            String hint = grant.accountHint();
            String detail = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new IllegalStateException(hint == null || hint.isBlank() ? detail
                    : detail + "（本次完成授权的 Microsoft 账号：" + hint + "，请核对该账号是否持有 Java 版）", e);
        }
    }

    private void disableBindingAndReleasePlayer(OfficialBind bind) {
        safeDeleteSecret(SECRET_PREFIX + bind.officialUuid() + SECRET_SUFFIX);
        players.findPlayerByUuid(bind.officialUuid())
                .filter(player -> bind.ownerId().equals(player.ownerId())
                        && SkinPlayer.KIND_OFFICIAL.equals(player.profileKind()))
                .ifPresent(player -> {
                    players.findDefaultPlayerUuid(bind.ownerId())
                            .filter(uuid -> uuid.equalsIgnoreCase(player.uuid()))
                            .ifPresent(ignored -> players.saveDefaultPlayerUuid(bind.ownerId(), null));
                    players.deletePlayer(player.uuid());
                });
        binds.saveBind(new OfficialBind(bind.officialUuid(), bind.ownerId(), bind.officialNameLast(),
                bind.verificationMethod(), bind.xuid(), false, OfficialBind.STATUS_UNBOUND,
                bind.verifiedAt(), bind.createdAt(), System.currentTimeMillis()));
    }

    /**
     * 所有权验证后的冲突裁决：任何已有离线角色/他人角色都不可被正版绑定覆盖；
     * 同一 owner 的同 UUID 官方角色仅允许原位刷新。
     */
    private void assertOfficialBindAvailable(String ownerId, MicrosoftIdentityChain.OfficialProfile profile, long now) {
        String officialUuid = profile.id();
        Optional<OfficialBind> existing = binds.findBindByUuid(officialUuid);
        if (existing.isPresent()) {
            OfficialBind bind = existing.get();
            if (!ownerId.equals(bind.ownerId()) && OfficialBind.STATUS_ACTIVE.equals(bind.status())) {
                throw new IllegalArgumentException("该正版角色已绑定在其他站点账号名下");
            }
            if (!ownerId.equals(bind.ownerId()) && bind.unboundAt() != null
                    && now - bind.unboundAt() < REBIND_COOLDOWN) {
                throw new IllegalArgumentException("该正版角色解绑后需 72 小时冷却才能换绑");
            }
        }
        players.findPlayerByUuid(officialUuid).ifPresent(player -> {
            if (!player.ownerId().equals(ownerId) || !SkinPlayer.KIND_OFFICIAL.equals(player.profileKind())) {
                throw new IllegalArgumentException("该官方 UUID 已被现有角色占用，拒绝覆盖；请联系管理员迁移处理");
            }
        });
        players.findPlayerByName(profile.name()).ifPresent(player -> {
            if (!player.ownerId().equals(ownerId)
                    || (!player.uuid().equalsIgnoreCase(officialUuid)
                    && !SkinPlayer.KIND_OFFICIAL.equals(player.profileKind()))) {
                throw new IllegalArgumentException("角色名「" + profile.name() + "」已被现有角色占用，拒绝覆盖");
            }
        });
    }

    /** 同步正版角色名与官方皮肤；皮肤导入失败不影响已验证的账号绑定。 */
    private void syncOfficialPlayer(String ownerId, MicrosoftIdentityChain.OfficialProfile profile, long now) {
        String skinHash = textureImporter.importOfficialSkin(profile.skinUrl(), profile.skinModel(), ownerId, profile.name())
                .orElseGet(() -> players.findPlayerByUuid(profile.id()).map(SkinPlayer::skinHash).orElse(null));
        players.savePlayer(new SkinPlayer(profile.id(), ownerId, profile.name(),
                profile.name().toLowerCase(java.util.Locale.ROOT), skinHash, null, null, now,
                SkinPlayer.KIND_OFFICIAL, null));
    }

    // ---- helpers ----

    public Map<String, Object> bindView(OfficialBind bind) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("officialUuid", bind.officialUuid());
        view.put("officialNameLast", bind.officialNameLast());
        view.put("verificationMethod", bind.verificationMethod());
        view.put("msRefreshStored", bind.msRefreshStored());
        view.put("status", bind.status());
        view.put("verifiedAt", bind.verifiedAt());
        return view;
    }

    protected MicrosoftIdentityChain chain(String clientId) {
        String origin = redirectOrigin.get();
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("站点未配置对外地址（app.web-url / app.base-url），无法构造回调");
        }
        String redirectUri = origin + "/api/plugins/yudream-skin/official-bind/callback";
        return new MicrosoftIdentityChain(clientId, redirectUri, configuredClientSecret(), new DefaultHttpExchange());
    }

    /** Azure 机密客户端凭据（可选）；读取失败按未配置降级为纯 PKCE。 */
    private String configuredClientSecret() {
        return safeStore(AZURE_SECRET_KEY)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .filter(secret -> !secret.isBlank())
                .orElse(null);
    }

    private void assertSecretStoreWritable() {
        String probe = SECRET_PREFIX + "probe:" + randomHex(8);
        try {
            secrets.put(probe, new byte[]{1});
            secrets.delete(probe);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("宿主不支持插件密钥存储，无法启用正版续签（请升级宿主）");
        }
    }

    private void safeStore(String key, byte[] value) {
        try {
            secrets.put(key, value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("宿主不支持插件密钥存储，无法启用正版续签（请升级宿主）");
        }
    }

    private Optional<byte[]> safeStore(String key) {
        try {
            return secrets.get(key);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void safeDeleteSecret(String key) {
        try {
            secrets.delete(key);
        } catch (RuntimeException ignored) {
            // 删除失败不阻塞解绑（密钥孤儿可由管理面清理）
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] claimSha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 默认官方链路 HTTP（java.net.http；测试注入桩）。 */
    public static final class DefaultHttpExchange implements MicrosoftIdentityChain.HttpExchange {
        private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

        @Override
        public Response send(String url, String method, Map<String, String> headers, String body) throws Exception {
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10));
            headers.forEach(builder::header);
            if ("POST".equalsIgnoreCase(method)) {
                builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            java.net.http.HttpResponse<String> response =
                    client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }
    }
}
