package online.yudream.base.plugin.skin;

import online.yudream.base.plugin.skin.application.service.OfficialBindService;
import online.yudream.base.plugin.skin.infrastructure.official.MicrosoftIdentityChain;
import online.yudream.base.plugin.skin.infrastructure.repository.OfficialBindDocumentRepository;
import online.yudream.base.plugin.skin.infrastructure.repository.YuDreamSkinRepository;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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

    private OfficialBindService service() {
        store = new InMemoryDocuments();
        YuDreamSkinRepository players = new YuDreamSkinRepository(store, null);
        OfficialBindDocumentRepository binds = new OfficialBindDocumentRepository(store);
        PluginSecretStore secrets = new PluginSecretStore() {
            private final Map<String, byte[]> vault = new LinkedHashMap<>();

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
        };
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

    // ---- helpers ----

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
            return session;
        }
    }
}
