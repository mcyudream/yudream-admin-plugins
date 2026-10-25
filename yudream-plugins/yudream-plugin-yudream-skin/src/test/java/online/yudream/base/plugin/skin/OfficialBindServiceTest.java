package online.yudream.base.plugin.skin;

import online.yudream.base.plugin.skin.application.service.OfficialBindService;
import online.yudream.base.plugin.skin.infrastructure.official.MicrosoftIdentityChain;
import online.yudream.base.plugin.skin.infrastructure.repository.OfficialBindDocumentRepository;
import online.yudream.base.plugin.skin.infrastructure.repository.YuDreamSkinRepository;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 正版绑定状态机：所有权验证、冲突裁决、自带续签与解绑清理。 */
class OfficialBindServiceTest {

    private static final String OWNER = "3088506834";
    private static final String OFFICIAL_UUID = "2536a169d63d54b3b19c1ee02d1fdfc5";
    private static final String REFRESH_TOKEN = "refresh-token-abc";

    /** 测试用内存文档库（跨模块测试类不可见，本文件自持）。 */
    static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Map<String, Object>>> collections = new ConcurrentHashMap<>();

        private Map<String, Map<String, Object>> collection(String name) {
            return collections.computeIfAbsent(name, key -> new LinkedHashMap<>());
        }

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new LinkedHashMap<>(document);
            copy.put("id", id);
            collection(collection).put(id, copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            Map<String, Object> document = collection(collection).get(id);
            return document == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(document));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return new java.util.ArrayList<>(collection(collection).values()).stream()
                    .skip((long) (page - 1) * size).limit(size).toList();
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return new java.util.ArrayList<>(collection(collection).values()).stream()
                    .filter(document -> value.equals(document.get(field)))
                    .skip((long) (page - 1) * size).limit(size).toList();
        }

        @Override
        public long count(String collection) {
            return collection(collection).size();
        }

        @Override
        public boolean updateIfFieldAtMost(String collection, String id, String field, long maximum,
                                           Map<String, Object> document) {
            Map<String, Object> existing = collection(collection).get(id);
            if (existing == null || !(existing.get(field) instanceof Number number) || number.longValue() > maximum) {
                return false;
            }
            existing.putAll(document);
            return true;
        }

        @Override
        public void delete(String collection, String id) {
            collection(collection).remove(id);
        }
    }

    private InMemoryDocuments store;
    private StubChain chain;
    private Map<String, byte[]> vault;
    private MicrosoftIdentityChain.OfficialProfile launcherProfile;
    private RuntimeException launcherProfileError;

    private OfficialBindService service() {
        store = new InMemoryDocuments();
        vault = new LinkedHashMap<>();
        launcherProfile = null;
        launcherProfileError = null;
        YuDreamSkinRepository players = new YuDreamSkinRepository(store, null);
        OfficialBindDocumentRepository binds = new OfficialBindDocumentRepository(store);
        PluginSecretStore secrets = new PluginSecretStore() {
            @Override
            public void put(String key, byte[] secret) {
                vault.put(key, secret);
            }

            @Override
            public Optional<byte[]> get(String key) {
                return Optional.ofNullable(vault.get(key));
            }

            @Override
            public boolean delete(String key) {
                return vault.remove(key) != null;
            }
        };
        chain = new StubChain();
        return new OfficialBindService(binds, players, secrets, () -> "https://skin.yd.test") {
            @Override
            protected MicrosoftIdentityChain chain(String clientId) {
                return chain;
            }

            @Override
            protected MicrosoftIdentityChain.OfficialProfile verifiedProfile(String accessToken) {
                if (launcherProfileError != null) {
                    throw launcherProfileError;
                }
                return launcherProfile;
            }
        };
    }

    private String sessionSecretKey(String officialUuid) {
        return OfficialBindService.SECRET_PREFIX + officialUuid + OfficialBindService.SESSION_SECRET_SUFFIX;
    }

    @Test
    void startRequiresEnabledSettings() {
        OfficialBindService service = service();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.start(OWNER, 1_000L));
        assertTrue(error.getMessage().contains("未开放"));
    }

    @Test
    void startSavesStateAndReturnsAuthorizeUrl() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");

        Map<String, Object> body = service.start(OWNER, 1_000L);

        assertTrue(String.valueOf(body.get("authorizeUrl")).contains("state="));
        assertTrue(String.valueOf(body.get("authorizeUrl")).contains("code_challenge_method=S256"));
    }

    @Test
    void callbackCompletesBindingWithOfficialUuid() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> started = service.start(OWNER, 1_000L);
        String state = stateOf(service, started);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));

        Map<String, Object> body = service.callback(state, "official-code", 2_000L);

        @SuppressWarnings("unchecked")
        Map<String, Object> bind = (Map<String, Object>) body.get("bind");
        assertEquals(OFFICIAL_UUID, bind.get("officialUuid"));
        assertEquals("Notch", bind.get("officialNameLast"));
        assertEquals(Boolean.TRUE, bind.get("msRefreshStored"));
        assertTrue(store.collections.get("official_binds").containsKey(OFFICIAL_UUID));
        // 官方角色落库：UUID = 官方 UUID，kind = official
        var player = store.collections.get("players").get(OFFICIAL_UUID);
        assertEquals("official", player.get("profileKind"));
    }

    @Test
    void callbackRefusesUuidBoundToAnotherUser() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> first = service.start(OWNER, 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));
        service.callback(stateOf(service, first), "code", 2_000L);

        // 另一用户绑定同一官方 UUID：冷却期内拒绝
        Map<String, Object> second = service.start("user-other", 3_000L);
        assertThrows(IllegalArgumentException.class,
                () -> service.callback(stateOf(service, second), "code", 4_000L));
    }

    @Test
    void unbindClearsSecretAndMarksUnbound() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> started = service.start(OWNER, 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));
        service.callback(stateOf(service, started), "code", 2_000L);

        service.unbind(OWNER);

        assertEquals("unbound", service.status(OWNER).get("bind") == null ? "unbound"
                : ((Map<?, ?>) service.status(OWNER).get("bind")).get("status"));
        // 再次 mint：无 ACTIVE 绑定 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.mintOfficialSession(OWNER));
    }

    @Test
    void callbackStateIsSingleUse() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> started = service.start(OWNER, 1_000L);
        String state = stateOf(service, started);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));

        service.callback(state, "code", 2_000L);

        assertThrows(IllegalArgumentException.class, () -> service.callback(state, "code", 2_001L));
    }

    @Test
    void unbindReleasesOfficialPlayerUuid() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> started = service.start(OWNER, 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));
        service.callback(stateOf(service, started), "code", 2_000L);
        assertTrue(store.collections.get("players").containsKey(OFFICIAL_UUID));

        service.unbind(OWNER);

        assertFalse(store.collections.get("players").containsKey(OFFICIAL_UUID));
    }

    @Test
    void mintSessionMismatchDisablesBinding() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> started = service.start(OWNER, 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));
        service.callback(stateOf(service, started), "code", 2_000L);
        chain.session = new MicrosoftIdentityChain.OfficialSession("wrong", 3_000L,
                new MicrosoftIdentityChain.OfficialProfile("11111111111111111111111111111111", "Other", null, null));

        assertThrows(IllegalArgumentException.class, () -> service.mintOfficialSession(OWNER));
        assertEquals(null, service.status(OWNER).get("bind"));
    }

    @Test
    void mintSessionRenewsWithRefreshToken() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> started = service.start(OWNER, 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));
        service.callback(stateOf(service, started), "code", 2_000L);

        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at-2", 3_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));
        Map<String, Object> minted = service.mintOfficialSession(OWNER);

        assertEquals("mc-at-2", minted.get("minecraftAccessToken"));
        assertEquals("microsoft-java", minted.get("accountKind"));
        assertEquals("refresh", chain.lastGrantType);
    }

    // ---- 启动器推送通道（launcher-session）----

    @Test
    void launcherBindRequiresEnabledSettings() {
        OfficialBindService service = service();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.bindOfficialFromLauncher(OWNER, "mc-at", null));
        assertTrue(error.getMessage().contains("未开放"));
    }

    /** 验证失败（无 Java profile 等）映射为业务可重试错误，而非 500。 */
    @Test
    void launcherBindMapsProfileVerificationFailureToBusinessError() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        launcherProfileError = new IllegalStateException("该 Microsoft 账号没有 Minecraft Java profile");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.bindOfficialFromLauncher(OWNER, "bad-token", null));
        assertTrue(error.getMessage().contains("没有 Minecraft Java profile"));
    }

    @Test
    void launcherBindCreatesBindingAndStoresSession() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        launcherProfile = new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null);

        Map<String, Object> body = service.bindOfficialFromLauncher(OWNER, "mc-at", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> bind = (Map<String, Object>) body.get("bind");
        assertEquals(OFFICIAL_UUID, bind.get("officialUuid"));
        assertEquals(Boolean.FALSE, bind.get("msRefreshStored"));
        assertEquals("launcher-session", store.collections.get("official_binds").get(OFFICIAL_UUID)
                .get("verificationMethod"));
        assertTrue(vault.containsKey(sessionSecretKey(OFFICIAL_UUID)));
        assertEquals("official", store.collections.get("players").get(OFFICIAL_UUID).get("profileKind"));
    }

    /** 同一官方 UUID 再次推送 = 会话续推：凭据与角色名快照刷新，绑定记录不新增。 */
    @Test
    void launcherBindRepurshesSessionForSameOfficialUuid() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        launcherProfile = new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null);
        service.bindOfficialFromLauncher(OWNER, "mc-at-1", null);

        launcherProfile = new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch2", null, null);
        Map<String, Object> repushed = service.bindOfficialFromLauncher(OWNER, "mc-at-2",
                System.currentTimeMillis() + 60_000L);

        assertEquals(1, store.collections.get("official_binds").size());
        assertTrue(new String(vault.get(sessionSecretKey(OFFICIAL_UUID)), StandardCharsets.UTF_8)
                .contains("mc-at-2"));
        assertEquals("Notch2", ((Map<?, ?>) repushed.get("profile")).get("name"));
        Map<String, Object> minted = service.mintOfficialSession(OWNER);
        assertEquals("mc-at-2", minted.get("minecraftAccessToken"));
        assertEquals("microsoft-java", minted.get("accountKind"));
    }

    @Test
    void launcherBindRejectsDifferentUuidWhileActive() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        launcherProfile = new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null);
        service.bindOfficialFromLauncher(OWNER, "mc-at", null);
        launcherProfile = new MicrosoftIdentityChain.OfficialProfile("11111111111111111111111111111111",
                "Other", null, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.bindOfficialFromLauncher(OWNER, "mc-at-b", null));
        assertTrue(error.getMessage().contains("先解绑"));
    }

    @Test
    void launcherBindRefusesUuidBoundToAnotherUser() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        Map<String, Object> started = service.start("user-other", 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN);
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));
        service.callback(stateOf(service, started), "code", 2_000L);

        launcherProfile = new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.bindOfficialFromLauncher(OWNER, "mc-at", null));
        assertTrue(error.getMessage().contains("已绑定在其他站点账号名下"));
    }

    /** 直通发放：过期/缺失会话均要求启动器重新登录推送，绝不静默失败。 */
    @Test
    void launcherMintRejectsExpiredOrMissingSession() throws Exception {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        launcherProfile = new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null);
        service.bindOfficialFromLauncher(OWNER, "mc-at", null);

        vault.put(sessionSecretKey(OFFICIAL_UUID), ("{\"accessToken\":\"mc-at\",\"expiresAt\":"
                + (System.currentTimeMillis() - 1_000L) + "}").getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException expired = assertThrows(IllegalArgumentException.class,
                () -> service.mintOfficialSession(OWNER));
        assertTrue(expired.getMessage().contains("过期"));

        vault.remove(sessionSecretKey(OFFICIAL_UUID));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.mintOfficialSession(OWNER));
        assertTrue(missing.getMessage().contains("缺失"));
    }

    @Test
    void launcherUnbindClearsSessionSecret() {
        OfficialBindService service = service();
        service.saveSettings(true, "client-123");
        launcherProfile = new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null);
        service.bindOfficialFromLauncher(OWNER, "mc-at", null);

        service.unbind(OWNER);

        assertFalse(vault.containsKey(sessionSecretKey(OFFICIAL_UUID)));
        assertFalse(store.collections.get("players").containsKey(OFFICIAL_UUID));
    }

    // ---- helpers ----

    /** 令牌请求仅在配置了机密客户端凭据时附带 client_secret（Web 平台 Azure 应用必需）。 */
    @Test
    void tokenRequestIncludesClientSecretOnlyWhenConfigured() throws Exception {
        List<String> bodies = new java.util.ArrayList<>();
        MicrosoftIdentityChain.HttpExchange http = (url, method, headers, body) -> {
            bodies.add(body == null ? "" : body);
            return new MicrosoftIdentityChain.HttpExchange.Response(200,
                    "{\"access_token\":\"at\",\"refresh_token\":\"rt\"}");
        };

        new MicrosoftIdentityChain("client-1", "https://cb", http).exchangeCode("code", "verifier");
        assertFalse(bodies.get(0).contains("client_secret="));

        new MicrosoftIdentityChain("client-1", "https://cb", "azure-secret", http).refresh("rt");
        assertTrue(bodies.get(1).contains("client_id=client-1"));
        assertTrue(bodies.get(1).contains("client_secret=azure-secret"));

        // 空白密钥等价于未配置
        new MicrosoftIdentityChain("client-1", "https://cb", "   ", http).refresh("rt");
        assertFalse(bodies.get(2).contains("client_secret="));
    }

    /** 主路径：最后一跳走官方启动器通道 /launcher/login（PC_LAUNCHER + xtoken），全链带 User-Agent。 */
    @Test
    void minecraftLoginPrefersLauncherLoginEndpointWithUserAgent() throws Exception {
        ScriptedHttp http = fullChainHttp();
        http.scripted.put("launcher/login", new MicrosoftIdentityChain.HttpExchange.Response(200,
                "{\"access_token\":\"mc-at\",\"expires_in\":7200}"));

        MicrosoftIdentityChain.OfficialSession session =
                new MicrosoftIdentityChain("client-1", "https://cb", http)
                        .login(new MicrosoftIdentityChain.TokenGrant("ms-at", "rt"));

        assertEquals(4, http.calls.size());
        ScriptedHttp.Call launcher = http.calls.get(2);
        assertTrue(launcher.url().contains("/launcher/login"));
        assertTrue(launcher.body().contains("PC_LAUNCHER"));
        assertTrue(launcher.body().contains("XBL3.0 x=uhs-2;xsts-token"));
        assertEquals(MicrosoftIdentityChain.USER_AGENT, launcher.headers().get("User-Agent"));
        // 前两跳：XBL 携带 d=<MS access_token>，XSTS 面向 minecraftservices relying party
        assertTrue(http.calls.get(0).body().contains("d=ms-at"));
        assertTrue(http.calls.get(1).body().contains("rp://api.minecraftservices.com/"));
        // profile 直连：Bearer = Minecraft access_token
        ScriptedHttp.Call profile = http.calls.get(3);
        assertEquals("GET", profile.method());
        assertEquals("Bearer mc-at", profile.headers().get("Authorization"));
        assertEquals("mc-at", session.accessToken());
        assertTrue(session.expiresAt() > System.currentTimeMillis());
    }

    /** 回退：launcher/login 被拒（如 403 策略拒绝）时改走 login_with_xbox 完成登录。 */
    @Test
    void minecraftLoginFallsBackToLoginWithXboxWhenLauncherLoginRejected() throws Exception {
        ScriptedHttp http = fullChainHttp();
        http.scripted.put("launcher/login", new MicrosoftIdentityChain.HttpExchange.Response(403,
                "{\"error\":\"denied\"}"));
        http.scripted.put("login_with_xbox", new MicrosoftIdentityChain.HttpExchange.Response(200,
                "{\"access_token\":\"mc-at2\",\"expires_in\":3600}"));

        MicrosoftIdentityChain.OfficialSession session =
                new MicrosoftIdentityChain("client-1", "https://cb", http)
                        .login(new MicrosoftIdentityChain.TokenGrant("ms-at", "rt"));

        assertTrue(http.calls.get(2).url().contains("/launcher/login"));
        assertTrue(http.calls.get(3).url().contains("login_with_xbox"));
        assertEquals("mc-at2", session.accessToken());
    }

    /** 双端点均失败：错误信息合并两段状态码与响应体摘要，并给出排查指引。 */
    @Test
    void minecraftLoginFailureMergesBothEndpointsAndBodies() throws Exception {
        ScriptedHttp http = fullChainHttp();
        http.scripted.put("launcher/login", new MicrosoftIdentityChain.HttpExchange.Response(403,
                "LAUNCHER_BLOCK_PAGE"));
        http.scripted.put("login_with_xbox", new MicrosoftIdentityChain.HttpExchange.Response(403,
                "XBOX_ENTITLEMENT_DENY"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MicrosoftIdentityChain("client-1", "https://cb", http)
                        .login(new MicrosoftIdentityChain.TokenGrant("ms-at", "rt")));

        String message = error.getMessage();
        assertTrue(message.contains("Minecraft 会话获取失败"));
        assertTrue(message.contains("launcher/login 返回 403"));
        assertTrue(message.contains("LAUNCHER_BLOCK_PAGE"));
        assertTrue(message.contains("login_with_xbox 返回 403"));
        assertTrue(message.contains("XBOX_ENTITLEMENT_DENY"));
        assertEquals(1, error.getSuppressed().length);
    }

    /** XSTS 401：错误信息带上 XErr 错误码（2148916233 = 未关联 Xbox 账号）。 */
    @Test
    void xstsRejectIncludesXerrCode() throws Exception {
        ScriptedHttp http = fullChainHttp();
        http.scripted.put("xsts.auth.xboxlive.com", new MicrosoftIdentityChain.HttpExchange.Response(401,
                "{\"Identity\":\"0\",\"XErr\":2148916233,\"Message\":\"\",\"Redirect\":\"nowhere\"}"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MicrosoftIdentityChain("client-1", "https://cb", http)
                        .login(new MicrosoftIdentityChain.TokenGrant("ms-at", "rt")));

        assertTrue(error.getMessage().contains("XErr=2148916233"));
    }

    /** id_token 解析：grant 携带展示用账号标识；格式异常时安全降级为 null。 */
    @Test
    void tokenGrantExtractsAccountHintFromIdToken() throws Exception {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"preferred_username\":\"user@outlook.com\"}".getBytes(StandardCharsets.UTF_8));
        String idToken = "eyJhbGciOiJub25lIn0." + payload + ".sig";
        MicrosoftIdentityChain.HttpExchange http = (url, method, headers, body) ->
                new MicrosoftIdentityChain.HttpExchange.Response(200,
                        "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"id_token\":\"" + idToken + "\"}");

        MicrosoftIdentityChain.TokenGrant grant =
                new MicrosoftIdentityChain("client-1", "https://cb", http).exchangeCode("code", "verifier");
        assertEquals("user@outlook.com", grant.accountHint());

        MicrosoftIdentityChain.HttpExchange broken = (url, method, headers, body) ->
                new MicrosoftIdentityChain.HttpExchange.Response(200,
                        "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"id_token\":\"not-a-jwt\"}");
        MicrosoftIdentityChain.TokenGrant degraded =
                new MicrosoftIdentityChain("client-1", "https://cb", broken).exchangeCode("code", "verifier");
        assertNull(degraded.accountHint());
    }

    /** 服务层：链路失败错误信息回显授权账号；成功结果带 msAccount。 */
    @Test
    void callbackSurfacesAuthorizedMicrosoftAccount() {
        OfficialBindService failing = service();
        failing.saveSettings(true, "client-123");
        Map<String, Object> started = failing.start(OWNER, 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN, "user@outlook.com");
        chain.loginFailure = new IllegalStateException("Minecraft 会话获取失败：两通道均被拒");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> failing.callback(stateOf(failing, started), "code", 2_000L));
        assertTrue(failure.getMessage().contains("user@outlook.com"));

        OfficialBindService success = service();
        success.saveSettings(true, "client-123");
        Map<String, Object> retryStart = success.start(OWNER, 1_000L);
        chain.grant = new MicrosoftIdentityChain.TokenGrant("mc-at", REFRESH_TOKEN, "user@outlook.com");
        chain.session = new MicrosoftIdentityChain.OfficialSession("mc-at", 2_000L,
                new MicrosoftIdentityChain.OfficialProfile(OFFICIAL_UUID, "Notch", null, null));

        Map<String, Object> body = success.callback(stateOf(success, retryStart), "code", 2_000L);
        assertEquals("user@outlook.com", body.get("msAccount"));
    }

    private String stateOf(OfficialBindService service, Map<String, Object> started) {
        // state 在 authorizeUrl 中；测试直接从 states 集合取（单一 state 文档）
        var states = store.collections.get("official_bind_states");
        assertFalse(states.isEmpty());
        return states.keySet().iterator().next();
    }

    /** 可编程官方链路桩：记录最近一次 grant 类型。 */
    private static final class StubChain extends MicrosoftIdentityChain {
        TokenGrant grant;
        OfficialSession session;
        String lastGrantType = "";
        RuntimeException loginFailure;

        StubChain() {
            super("stub-client", "https://skin.yd.test/api/plugins/yudream-skin/official-bind/callback",
                    (url, method, headers, body) -> new HttpExchange.Response(200, "{}"));
        }

        @Override
        public String authorizeUrl(String state, String codeChallenge) {
            return "https://login.microsoftonline.com/authorize?client_id=stub&state=" + state
                    + "&code_challenge_method=S256";
        }

        @Override
        public TokenGrant exchangeCode(String code, String codeVerifier) {
            lastGrantType = "authorization_code";
            return grant;
        }

        @Override
        public TokenGrant refresh(String refreshToken) {
            lastGrantType = "refresh";
            return grant;
        }

        @Override
        public OfficialSession login(TokenGrant tokenGrant) {
            if (loginFailure != null) {
                throw loginFailure;
            }
            return session;
        }
    }

    /** 按 URL 关键字分发的链路 HTTP 桩：记录每次调用，便于断言端点顺序、请求体与头。 */
    static final class ScriptedHttp implements MicrosoftIdentityChain.HttpExchange {
        record Call(String url, String method, Map<String, String> headers, String body) {
        }

        final java.util.List<Call> calls = new java.util.ArrayList<>();
        final Map<String, Response> scripted = new LinkedHashMap<>();

        @Override
        public Response send(String url, String method, Map<String, String> headers, String body) {
            calls.add(new Call(url, method, Map.copyOf(headers), body));
            return scripted.entrySet().stream()
                    .filter(entry -> url.contains(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未编排的请求: " + url))
                    .getValue();
        }
    }

    /** 编排一次完整链路的默认响应（XBL → XSTS → launcher/login → profile）。 */
    private static ScriptedHttp fullChainHttp() {
        ScriptedHttp http = new ScriptedHttp();
        http.scripted.put("user.auth.xboxlive.com", new MicrosoftIdentityChain.HttpExchange.Response(200,
                "{\"Token\":\"xbl-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"uhs-1\"}]}}"));
        http.scripted.put("xsts.auth.xboxlive.com", new MicrosoftIdentityChain.HttpExchange.Response(200,
                "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"uhs-2\"}]}}"));
        http.scripted.put("launcher/login", new MicrosoftIdentityChain.HttpExchange.Response(200,
                "{\"access_token\":\"mc-at\",\"expires_in\":7200}"));
        http.scripted.put("minecraft/profile", new MicrosoftIdentityChain.HttpExchange.Response(200,
                "{\"id\":\"2536a169d63d54b3b19c1ee02d1fdfc5\",\"name\":\"Notch\",\"skins\":[]}"));
        return http;
    }
}
