package online.yudream.base.plugin.minecraft.infrastructure.launcher;

import online.yudream.base.plugin.minecraft.application.dto.MinecraftPageDTO;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动器下发回归：自动端口（SRV）线路不得拼接 {@code :0}，
 * 否则客户端连接到 0 端口直接失败。
 */
class MinecraftYmclContributionProviderTest {

    private final MinecraftServerAppService appService = mock(MinecraftServerAppService.class);
    private final MinecraftYmclContributionProvider provider = new MinecraftYmclContributionProvider(appService);

    @Test
    void cardAddressOmitsPortForAutomaticSrvEndpoint() {
        MinecraftServerDTO server = server(endpoint("play.example.com", 0, "JAVA"));
        stubPage(server);

        Map<String, Object> envelope = fetchEnvelope();

        Map<String, Object> card = firstRecord(envelope);
        assertEquals("play.example.com", card.get("address"));
        List<Map<String, Object>> endpoints = endpointsOf(card);
        assertEquals("play.example.com", endpoints.getFirst().get("address"));
    }

    @Test
    void cardAddressKeepsCustomPort() {
        MinecraftServerDTO server = server(endpoint("play.example.com", 25566, "JAVA"));
        stubPage(server);

        Map<String, Object> envelope = fetchEnvelope();

        Map<String, Object> card = firstRecord(envelope);
        assertEquals("play.example.com:25566", card.get("address"));
        assertEquals("play.example.com:25566", endpointsOf(card).getFirst().get("address"));
    }

    @Test
    void serverBindingsMcAddressOmitsPortForAutomaticSrvEndpoint() {
        MinecraftServerDTO server = server(endpoint("play.example.com", 0, "JAVA"));
        stubPage(server);

        Object bindings = provider.serverBindings();

        List<Map<String, Object>> servers = assertInstanceOf(List.class, bindings);
        assertFalse(servers.isEmpty());
        assertEquals("play.example.com", servers.getFirst().get("mcAddress"));
    }

    private Map<String, Object> fetchEnvelope() {
        return assertInstanceOf(Map.class,
                provider.fetchData(MinecraftYmclContributionProvider.DATA_SOURCE_SERVERS,
                        new YmclDataContext(1, 200, null)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRecord(Map<String, Object> envelope) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) envelope.get("records");
        assertFalse(records.isEmpty());
        return records.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> endpointsOf(Map<String, Object> card) {
        return (List<Map<String, Object>>) card.get("endpoints");
    }

    private void stubPage(MinecraftServerDTO... servers) {
        when(appService.pageServers(anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new MinecraftPageDTO<>(List.of(servers), servers.length));
    }

    private static MinecraftServerDTO server(MinecraftServerDTO.EndpointDTO endpoint) {
        return new MinecraftServerDTO("server-1", "生存服", "", true, 0,
                List.of(endpoint), List.of(), null, null, null, null, 0L, 0L);
    }

    private static MinecraftServerDTO.EndpointDTO endpoint(String host, int port, String edition) {
        return new MinecraftServerDTO.EndpointDTO("ep-1", "默认线路", host, port, edition, true, true, 0);
    }
}
