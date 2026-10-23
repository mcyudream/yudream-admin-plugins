package online.yudream.base.plugin.cas.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import online.yudream.base.plugin.cas.application.dto.SsoSettingsDto;
import online.yudream.base.plugin.cas.application.service.AccessControlService;
import online.yudream.base.plugin.cas.application.service.BindingQueryService;
import online.yudream.base.plugin.cas.application.service.SettingsService;
import online.yudream.base.plugin.cas.application.service.StudentInfoService;
import online.yudream.base.plugin.cas.domain.aggregate.AccessControl;
import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.aggregate.StudentArchive;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.service.StudentArchiveQuery;
import online.yudream.base.plugin.cas.infrastructure.bridge.BridgeProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.cas.CasProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.oidc.OidcProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.repository.AccessControlDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.repository.SsoSettingsDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.repository.StudentProfileDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.secret.ClientSecretStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasSsoHttpFacadeTest {

    @Test
    void gateDisabledByDefault() {
        Env env = new Env();
        env.settings.save(casReady(), null);
        Map<String, Object> gate = gate(env.facade);
        assertEquals(false, gate.get("requireBinding"));
        assertEquals("cas", gate.get("providerCode"));
        assertEquals("cas", gate.get("type"));
    }

    @Test
    void gateRequiresLoginReady() {
        Env env = new Env();
        // 未保存设置 → loginEnabled=false，开关即使打开也不生效
        env.accessControl.save(new AccessControl(true));
        assertEquals(false, gate(env.facade).get("requireBinding"));

        env.settings.save(casReady(), null);
        assertEquals(true, gate(env.facade).get("requireBinding"));
    }

    @Test
    void accessControlSaveMergesPartialBody() {
        Env env = new Env();
        env.facade.saveAccessControl(request("""
                {"requireBinding": true}
                """));
        assertEquals(true, body(env.facade.accessControl()).get("requireBinding"));

        // 只更新开关：空 body 保留原值
        env.facade.saveAccessControl(request("{}"));
        assertEquals(true, body(env.facade.accessControl()).get("requireBinding"));
    }

    @Test
    void studentsPageParsesQueryAndValidates() {
        Env env = new Env();
        env.studentInfo.record(identity("20230101", "张三", Map.of("cn", "张三", "mail", "zhang@taru.edu.cn")), SsoProtocol.CAS);
        env.studentInfo.record(identity("20230102", "李四", Map.of("cn", "李四")), SsoProtocol.CAS);

        Map<String, Object> page = body(env.facade.students(request(Map.of("page", List.of("1"), "size", List.of("10")))));
        assertEquals(2L, page.get("total"));

        Map<String, Object> search = body(env.facade.students(request(Map.of("page", List.of("1"), "size", List.of("10"), "keyword", List.of("李四")))));
        assertEquals(1L, search.get("total"));

        // 认证属性里的学院/班级不再入库，也不参与搜索
        Map<String, Object> mailSearch = body(env.facade.students(request(Map.of("page", List.of("1"), "size", List.of("10"), "keyword", List.of("zhang@")))));
        assertEquals(1L, mailSearch.get("total"));

        assertThrows(IllegalArgumentException.class, () -> env.facade.studentDetail(request(Map.of())));
        Map<String, Object> detail = body(env.facade.studentDetail(request(Map.of("socialUid", List.of("20230101")))));
        assertEquals("张三", detail.get("name"));
    }

    @Test
    void studentsPageCarriesBoundLocalAccountAndStudentArchive() {
        Env env = new Env();
        env.studentInfo.record(identity("20230106", "孙八", Map.of("cn", "孙八")), SsoProtocol.CAS);
        env.users.bound.put("cas|cas|20230106", new online.yudream.base.plugin.spi.system.user.PluginUserProfile(
                357806992028471296L, "godot", "Godot", "g@example.com", "13900000000", null, null, "NORMAL"));
        env.archives.records.put("20230106", new StudentArchive("20230106", "孙八", "计算机23-1班", "信息工程学院"));

        Map<String, Object> page = body(env.facade.students(request(Map.of("page", List.of("1"), "size", List.of("10")))));
        assertEquals(true, page.get("archiveAvailable"));
        Map<String, Object> item = firstItem(page);

        Map<String, Object> binding = mapOf(item, "binding");
        assertEquals(true, binding.get("available"));
        assertEquals(true, binding.get("bound"));
        assertEquals("godot", binding.get("username"));
        // Java Long 进 JSON 必须是字符串
        assertEquals("357806992028471296", binding.get("userId"));

        Map<String, Object> archive = mapOf(item, "archive");
        assertEquals(true, archive.get("filled"));
        assertEquals("信息工程学院", archive.get("college"));
        assertEquals("计算机23-1班", archive.get("className"));

        Map<String, Object> detail = body(env.facade.studentDetail(request(Map.of("socialUid", List.of("20230106")))));
        assertEquals("godot", mapOf(detail, "binding").get("username"));
        assertEquals("计算机23-1班", mapOf(detail, "archive").get("className"));
    }

    @Test
    void studentsPageShowsUnboundAccount() {
        Env env = new Env();
        env.studentInfo.record(identity("20230107", "周九", Map.of("cn", "周九")), SsoProtocol.CAS);

        Map<String, Object> item = firstItem(body(
                env.facade.students(request(Map.of("page", List.of("1"), "size", List.of("10"))))));

        assertEquals(true, mapOf(item, "binding").get("available"));
        assertEquals(false, mapOf(item, "binding").get("bound"));
        assertEquals(false, mapOf(item, "archive").get("filled"));
    }

    @Test
    void studentsPageDegradesWhenHostDoesNotSupportBindingLookup() {
        Env env = new Env();
        env.users.unsupported = true;
        env.studentInfo.record(identity("20230103", "王五", Map.of("cn", "王五")), SsoProtocol.CAS);

        // 宿主不支持查询时列表仍要正常返回，只在绑定字段上降级
        Map<String, Object> page = body(env.facade.students(request(Map.of("page", List.of("1"), "size", List.of("10")))));
        assertEquals(1L, page.get("total"));
        Map<String, Object> binding = mapOf(firstItem(page), "binding");
        assertEquals(false, binding.get("available"));
        assertEquals(false, binding.get("bound"));
        assertTrue(String.valueOf(binding.get("message")).contains("宿主版本不支持"), String.valueOf(binding.get("message")));
    }

    @Test
    void studentsPageDegradesWhenStudentArchivePluginMissing() {
        Env env = new Env();
        env.archiveAvailable = false;
        env.studentInfo.record(identity("20230109", "郑十一", Map.of("cn", "郑十一")), SsoProtocol.CAS);

        Map<String, Object> page = body(env.facade.students(request(Map.of("page", List.of("1"), "size", List.of("10")))));
        assertEquals(false, page.get("archiveAvailable"));
        Map<String, Object> archive = mapOf(firstItem(page), "archive");
        assertEquals(false, archive.get("available"));
        assertEquals(false, archive.get("filled"));
        assertTrue(String.valueOf(archive.get("message")).contains("学生档案插件"), String.valueOf(archive.get("message")));
    }

    @Test
    void warmupPageRebuildsTheTargetServerSide() {
        Env env = new Env();
        env.settings.save(casReady(), null);

        PluginHttpResponse response = env.facade.warmup(request(Map.of("state", List.of("st-state"))));

        assertEquals(200, response.status());
        assertEquals("text/html; charset=UTF-8", response.contentType());
        assertEquals("no-store", response.headers().get("Cache-Control"));
        String html = String.valueOf(response.body());
        // 目标由服务端按当前配置重建：URL 里只有 state，没有可被外部利用的任意跳转参数
        assertTrue(html.contains("https://cas.example/authserver/login?service="), html);
        assertTrue(html.contains("state%3Dst-state") || html.contains("state=st-state"), html);
        assertTrue(html.contains("location.replace"), html);
        assertTrue(html.contains("mode: 'no-cors'"), html);
        // 与站点一致的观感：跟随系统深浅色 + 居中卡片 + 环形进度，且不依赖外部资源
        assertTrue(html.contains("prefers-color-scheme: dark"), html);
        assertTrue(html.contains("color-scheme"), html);
        assertTrue(html.contains("class=\"card\""), html);
        assertTrue(html.contains("class=\"spinner\""), html);
        assertFalse(html.contains("http://") && html.contains("<link"), html);
        // 展示名称来自配置，进页面时必须转义
        assertTrue(html.contains("统一身份认证"), html);
    }

    @Test
    void warmupPageShowsAtrustGuideWhenWakeUrlConfigured() {
        Env env = new Env();
        SsoSettingsDto ready = casReady();
        env.settings.save(new SsoSettingsDto(ready.enabled(), ready.protocol(), ready.displayName(), ready.icon(),
                ready.casBaseUrl(), ready.loginPath(), ready.validatePath(), ready.oidcIssuer(),
                ready.oidcAuthorizePath(), ready.oidcTokenPath(), ready.oidcUserinfoPath(), ready.oidcJwksPath(),
                ready.oidcRegisterPath(), ready.clientId(), ready.clientSecretConfigured(), ready.scopes(),
                ready.callbackUrl(), ready.loginWarmup(), "atrustclient://login", "https://help.example/atrust", false), null);

        String html = String.valueOf(env.facade.warmup(request(Map.of("state", List.of("s1")))).body());
        // 引导模式：展示唤醒与继续按钮 + 帮助链接；唤醒链接经 JS 转义注入
        assertTrue(html.contains("唤醒认证客户端"), html);
        assertTrue(html.contains("我已连接，继续登录"), html);
        assertTrue(html.contains("打开下载与帮助"), html);
        assertTrue(html.contains("atrustclient://login"), html);
        assertTrue(html.contains("cas-atrust-continue"), html);
        assertFalse(html.contains("setTimeout(go, 1600)"), html);
    }

    @Test
    void warmupPageKeepsAutoForwardWhenWakeUrlBlank() {
        Env env = new Env();
        env.settings.save(casReady(), null);
        String html = String.valueOf(env.facade.warmup(request(Map.of("state", List.of("s1")))).body());
        assertFalse(html.contains("唤醒 aTrust"), html);
        assertTrue(html.contains("cas-atrust-continue") == false, html);
    }

    @Test
    void settingsSaveValidatesAtrustUrls() {
        Env env = new Env();
        SsoSettingsDto ready = casReady();
        SsoSettingsDto badWake = new SsoSettingsDto(ready.enabled(), ready.protocol(), ready.displayName(), ready.icon(),
                ready.casBaseUrl(), ready.loginPath(), ready.validatePath(), ready.oidcIssuer(),
                ready.oidcAuthorizePath(), ready.oidcTokenPath(), ready.oidcUserinfoPath(), ready.oidcJwksPath(),
                ready.oidcRegisterPath(), ready.clientId(), ready.clientSecretConfigured(), ready.scopes(),
                ready.callbackUrl(), ready.loginWarmup(), "not-a-scheme", "", false);
        IllegalArgumentException wakeError = assertThrows(IllegalArgumentException.class, () -> env.settings.save(badWake, null));
        assertTrue(wakeError.getMessage().contains("aTrust 唤醒链接"), wakeError.getMessage());

        SsoSettingsDto badGuide = new SsoSettingsDto(ready.enabled(), ready.protocol(), ready.displayName(), ready.icon(),
                ready.casBaseUrl(), ready.loginPath(), ready.validatePath(), ready.oidcIssuer(),
                ready.oidcAuthorizePath(), ready.oidcTokenPath(), ready.oidcUserinfoPath(), ready.oidcJwksPath(),
                ready.oidcRegisterPath(), ready.clientId(), ready.clientSecretConfigured(), ready.scopes(),
                ready.callbackUrl(), ready.loginWarmup(), "atrustclient://login", "javascript:alert(1)", false);
        IllegalArgumentException guideError = assertThrows(IllegalArgumentException.class, () -> env.settings.save(badGuide, null));
        assertTrue(guideError.getMessage().contains("aTrust 帮助页"), guideError.getMessage());
    }

    @Test
    void warmupPageEscapesConfiguredDisplayName() {
        Env env = new Env();
        env.settings.save(casReady("</title><script>alert(1)</script>"), null);
        String html = String.valueOf(env.facade.warmup(request(Map.of("state", List.of("s1")))).body());
        assertFalse(html.contains("<script>alert(1)</script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void warmupPageWithoutStateIsRejected() {
        Env env = new Env();
        env.settings.save(casReady(), null);
        assertEquals(400, env.facade.warmup(request(Map.of())).status());
    }

    @Test
    void warmupPageSkipsPreheatWhenDisabled() {
        Env env = new Env();
        env.settings.save(casReady(false), null);
        String html = String.valueOf(env.facade.warmup(request(Map.of("state", List.of("s1")))).body());
        assertFalse(html.contains("mode: 'no-cors'"), html);
        assertTrue(html.contains("location.replace"), html);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItem(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> item, String key) {
        return (Map<String, Object>) item.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gate(CasSsoHttpFacade facade) {
        PluginHttpResponse response = facade.gate();
        return (Map<String, Object>) response.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(PluginHttpResponse response) {
        return (Map<String, Object>) response.body();
    }

    private static PluginHttpRequest request(String body) {
        return new PluginHttpRequest("PUT", "/api/plugins/cas/api/admin/access-control", Map.of(), Map.of(), body, null);
    }

    private static PluginHttpRequest request(Map<String, List<String>> query) {
        return new PluginHttpRequest("GET", "/api/plugins/cas/api/admin/students", Map.of(), query, "", null);
    }

    private static online.yudream.base.plugin.cas.domain.service.SsoProtocolClient.ExternalIdentity identity(String uid, String nickname, Map<String, String> attributes) {
        return new online.yudream.base.plugin.cas.domain.service.SsoProtocolClient.ExternalIdentity(uid, nickname, "", "", "", attributes);
    }

    private static SsoSettingsDto casReady() {
        return casReady(true);
    }

    private static SsoSettingsDto casReady(String displayName) {
        SsoSettingsDto base = casReady();
        return new SsoSettingsDto(base.enabled(), base.protocol(), displayName, base.icon(), base.casBaseUrl(),
                base.loginPath(), base.validatePath(), base.oidcIssuer(), base.oidcAuthorizePath(), base.oidcTokenPath(),
                base.oidcUserinfoPath(), base.oidcJwksPath(), base.oidcRegisterPath(), base.clientId(),
                base.clientSecretConfigured(), base.scopes(), base.callbackUrl(), base.loginWarmup(),
                base.atrustWakeUrl(), base.atrustGuideUrl(), base.ready());
    }

    private static SsoSettingsDto casReady(boolean loginWarmup) {
        return new SsoSettingsDto(
                true, "CAS", SsoSettings.DEFAULT_DISPLAY_NAME, SsoSettings.DEFAULT_ICON,
                "https://cas.example", SsoSettings.DEFAULT_LOGIN_PATH, SsoSettings.DEFAULT_VALIDATE_PATH,
                SsoSettings.DEFAULT_OIDC_ISSUER, SsoSettings.DEFAULT_OIDC_AUTHORIZE_PATH, SsoSettings.DEFAULT_OIDC_TOKEN_PATH,
                SsoSettings.DEFAULT_OIDC_USERINFO_PATH, SsoSettings.DEFAULT_OIDC_JWKS_PATH, SsoSettings.DEFAULT_OIDC_REGISTER_PATH,
                "", false, SsoSettings.DEFAULT_SCOPES, "https://site.example/api/external-login/callback", loginWarmup,
                "", "", false);
    }

    private static final class Env {
        final MemoryDocuments documents = new MemoryDocuments();
        final FakeUsers users = new FakeUsers();
        final FakeArchives archives = new FakeArchives();
        boolean archiveAvailable = true;
        final SettingsService settings = new SettingsService(
                new SsoSettingsDocumentRepository(documents),
                new ClientSecretStore(new MemorySecrets()),
                new CasProtocolClient(),
                new OidcProtocolClient(),
                new BridgeProtocolClient()
        );
        final AccessControlService accessControl = new AccessControlService(new AccessControlDocumentRepository(documents));
        final StudentInfoService studentInfo = new StudentInfoService(
                new StudentProfileDocumentRepository(documents),
                new BindingQueryService(users.service(), "cas"),
                archives
        );
        final CasSsoHttpFacade facade = new CasSsoHttpFacade(settings, studentInfo, accessControl);

        /** 学生档案插件替身：archiveAvailable=false 模拟未安装（降级实现）。 */
        final class FakeArchives implements StudentArchiveQuery {
            final Map<String, StudentArchive> records = new ConcurrentHashMap<>();

            @Override
            public boolean available() {
                return archiveAvailable;
            }

            @Override
            public Optional<StudentArchive> findByStudentNo(String studentNo) {
                return archiveAvailable ? Optional.ofNullable(records.get(studentNo)) : Optional.empty();
            }
        }
    }

    /** 宿主用户服务的可编程替身：只实现本测试用到的 findByExternalIdentity。 */
    private static final class FakeUsers {
        final Map<String, online.yudream.base.plugin.spi.system.user.PluginUserProfile> bound = new ConcurrentHashMap<>();
        boolean unsupported;

        PluginUserService service() {
            return (PluginUserService) java.lang.reflect.Proxy.newProxyInstance(
                    CasSsoHttpFacadeTest.class.getClassLoader(),
                    new Class<?>[]{PluginUserService.class},
                    (proxy, method, args) -> {
                        if ("findByExternalIdentity".equals(method.getName())) {
                            if (unsupported) {
                                throw new UnsupportedOperationException("宿主不支持查询外部账号绑定");
                            }
                            String key = args[0] + "|" + args[1] + "|" + args[2];
                            return java.util.Optional.ofNullable(bound.get(key));
                        }
                        if ("toString".equals(method.getName())) {
                            return "FakePluginUserService";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class MemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new LinkedHashMap<>(document);
            store.put(collection + "/" + id, copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            Map<String, Object> document = store.get(collection + "/" + id);
            return document == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(document));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return store.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(collection + "/"))
                    .<Map<String, Object>>map(e -> new LinkedHashMap<>(e.getValue()))
                    .toList();
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return List.of();
        }

        @Override
        public long count(String collection) {
            return store.keySet().stream().filter(k -> k.startsWith(collection + "/")).count();
        }

        @Override
        public void delete(String collection, String id) {
            store.remove(collection + "/" + id);
        }
    }

    private static final class MemorySecrets implements PluginSecretStore {
        private final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] secret) {
            store.put(key, secret);
        }

        @Override
        public Optional<byte[]> get(String key) {
            byte[] value = store.get(key);
            return value == null ? Optional.empty() : Optional.of(value);
        }

        @Override
        public boolean delete(String key) {
            return store.remove(key) != null;
        }
    }
}
