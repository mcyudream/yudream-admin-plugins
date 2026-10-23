package online.yudream.base.plugin.union;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.union.application.service.FederationJoinService;
import online.yudream.base.plugin.union.application.service.UnionBanService;
import online.yudream.base.plugin.union.application.service.UnionDirectoryService;
import online.yudream.base.plugin.union.application.service.UnionDiscoveryService;
import online.yudream.base.plugin.union.infrastructure.PeerHttpClient;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.union.interfaces.http.UnionHttpFacade;
import online.yudream.base.plugin.union.support.UnionTestSupport;
import online.yudream.base.plugin.union.support.UnionTestSupport.FakePeerHttpClient;
import online.yudream.base.plugin.union.support.UnionTestSupport.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** admin 管理面错误映射：业务异常转结构化响应，不再穿透为宿主 500 通用错误。 */
class UnionHttpFacadeTest {

    @Test
    void adminEnrollMapsUnknownTokenToStructured400() {
        UnionHttpFacade facade = facade();
        PluginHttpResponse response = (PluginHttpResponse) facade.enrollPeer(new PluginHttpRequest(
                "POST", "/admin/peers", Map.of(), Map.of(),
                JsonSupport.write(Map.of("origin", "https://peer.test", "enrollToken", "no-such-token")), null));

        assertEquals(400, response.status());
        assertFalse(response.wrapped());
        assertEquals("auth.badToken", String.valueOf(body(response).get("error")));
        assertTrue(String.valueOf(body(response).get("message")).contains("注册令牌无效"));
    }

    @Test
    void adminSaveSettingsMapsImmutableSiteCodeToStructured400() {
        UnionHttpFacade facade = facade();
        facade.saveSettings(request(Map.of("siteCode", "yda", "siteName", "余梦")));
        PluginHttpResponse response = (PluginHttpResponse) facade.saveSettings(request(Map.of("siteCode", "other")));

        assertEquals(400, response.status());
        assertEquals("invalid-request", String.valueOf(body(response).get("error")));
        assertTrue(String.valueOf(body(response).get("message")).contains("不可变更"));
    }

    @Test
    void adminSuccessStillReturnsPlainMap() {
        UnionHttpFacade facade = facade();
        Object result = facade.saveSettings(request(Map.of("siteCode", "yda")));

        assertFalse(result instanceof PluginHttpResponse);
        assertEquals("yda", String.valueOf(((Map<?, ?>) result).get("siteCode")));
    }

    private static Map<?, ?> body(PluginHttpResponse response) {
        return (Map<?, ?>) response.body();
    }

    private static PluginHttpRequest request(Map<String, Object> payload) {
        return new PluginHttpRequest("PUT", "/admin/settings", Map.of(), Map.of(), JsonSupport.write(payload), null);
    }

    private UnionHttpFacade facade() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        UnionRepository repository = new UnionRepository(store);
        UnionCryptoService crypto = new UnionCryptoService(
                UnionTestSupport.context(new UnionTestSupport.TestConfig() {
                }, store), repository);
        UnionDiscoveryService discovery = new UnionDiscoveryService(
                UnionTestSupport.context(new UnionTestSupport.TestConfig() {
                }, store), repository, crypto);
        PeerHttpClient http = new FakePeerHttpClient();
        UnionDirectoryService directory = new UnionDirectoryService(repository, crypto, http, discovery);
        UnionBanService bans = new UnionBanService(repository, http, crypto);
        FederationJoinService federation = new FederationJoinService(
                UnionTestSupport.context(new UnionTestSupport.TestConfig() {
                }, store), repository, crypto, http, bans, discovery, Executors.newSingleThreadExecutor());
        return new UnionHttpFacade(federation, bans, directory, discovery, repository);
    }
}
