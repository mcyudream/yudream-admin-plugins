package online.yudream.base.plugin.ymcl.application.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载源分发配置契约：保存校验、协议视图（snake_case）与网关密钥联动。
 */
class YmclDownloadSourceServiceTest {

    @Test
    void freshServiceReportsUnmanagedEmptyView() {
        YmclDownloadSourceService service = new YmclDownloadSourceService(
                new YmclDownloadTokenServiceTest.MemoryDocumentStore());
        assertFalse(service.isManaged());
        Map<String, Object> view = service.protocolView();
        assertEquals(1, view.get("protocol_version"));
        assertEquals(false, view.get("managed"));
        assertNotNull(view.get("sources"));
        assertNotNull(view.get("categories"));
        assertEquals("token", ((Map<?, ?>) view.get("gateway")).get("auth"));
    }

    @Test
    void validConfigRoundTripsToSnakeCaseProtocolView() {
        YmclDownloadSourceService service = service();
        String violation = service.save(validBody());
        assertNull(violation);
        assertTrue(service.isManaged());

        Map<String, Object> view = service.protocolView();
        assertEquals(true, view.get("managed"));
        assertEquals(Boolean.TRUE, view.get("official_fallback"));
        List<?> sources = (List<?>) view.get("sources");
        assertEquals(3, sources.size());
        assertEquals("gateway", ((Map<?, ?>) sources.get(0)).get("id"));
        assertEquals("gateway", ((Map<?, ?>) sources.get(0)).get("provider"));
        assertEquals("https://mirror.example.com", ((Map<?, ?>) sources.get(0)).get("base_url"));
        Map<?, ?> categories = (Map<?, ?>) view.get("categories");
        assertEquals(List.of("bmclapi", "gateway", "official"), categories.get("loaders"));
        assertNull(categories.get("modrinth"), "空类别不下发");
    }

    @Test
    void invalidBodiesAreRejected() {
        YmclDownloadSourceService service = service();

        Map<String, Object> badProvider = validBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) ((List<?>) badProvider.get("sources")).get(0);
        source.put("provider", "unknown-mirror");
        assertTrue(containsText(service.save(badProvider), "provider"));

        Map<String, Object> customWithoutUrl = validBody();
        customWithoutUrl.put("sources", List.of(Map.of("id", "my-mirror", "provider", "custom")));
        assertTrue(containsText(service.save(customWithoutUrl), "baseUrl"));

        Map<String, Object> unknownReference = validBody();
        unknownReference.put("categories", Map.of("loaders", List.of("nope")));
        assertTrue(containsText(service.save(unknownReference), "unknown source id"));

        Map<String, Object> tokenWithoutGatewayUrl = validBody();
        tokenWithoutGatewayUrl.put("gateway", Map.of("auth", "token"));
        assertTrue(containsText(service.save(tokenWithoutGatewayUrl), "gateway.baseUrl"));

        // 保存失败后配置保持未托管。
        assertFalse(service.isManaged());
    }

    @Test
    void unmanagedExplicitlyDisablesDistribution() {
        YmclDownloadSourceService service = service();
        assertNull(service.save(validBody()));
        Map<String, Object> disabled = validBody();
        disabled.put("managed", false);
        assertNull(service.save(disabled));
        assertFalse(service.isManaged());
        assertEquals(false, service.protocolView().get("managed"));
    }

    @Test
    void gatewayProviderInheritsGatewayBaseUrl() {
        YmclDownloadSourceService service = service();
        assertNull(service.save(validBody()));
        Map<?, ?> view = (Map<?, ?>) service.protocolView().get("gateway");
        assertEquals("https://mirror.example.com", view.get("base_url"));
        assertEquals("YDA 镜像", view.get("name"));
    }

    private static YmclDownloadSourceService service() {
        return new YmclDownloadSourceService(new YmclDownloadTokenServiceTest.MemoryDocumentStore());
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("managed", true);
        body.put("officialFallback", true);
        body.put("gateway", Map.of(
                "name", "YDA 镜像",
                "baseUrl", "https://mirror.example.com",
                "auth", "token"));
        // 测试会对 sources 条目做就地修改，这里保持可变容器。
        Map<String, Object> gatewaySource = new LinkedHashMap<>();
        gatewaySource.put("id", "gateway");
        gatewaySource.put("provider", "gateway");
        gatewaySource.put("name", "域网关");
        Map<String, Object> bmclapiSource = new LinkedHashMap<>();
        bmclapiSource.put("id", "bmclapi");
        bmclapiSource.put("provider", "bmclapi");
        Map<String, Object> officialSource = new LinkedHashMap<>();
        officialSource.put("id", "official");
        officialSource.put("provider", "official");
        body.put("sources", new ArrayList<>(List.of(gatewaySource, bmclapiSource, officialSource)));
        body.put("categories", Map.of(
                "minecraft_metadata", List.of("official", "bmclapi", "gateway"),
                "minecraft_files", List.of("bmclapi", "gateway"),
                "loaders", List.of("bmclapi", "gateway", "official"),
                "modrinth", List.of(),
                "curseforge", List.of("official")));
        return body;
    }

    private static boolean containsText(String value, String fragment) {
        return value != null && value.contains(fragment);
    }
}
