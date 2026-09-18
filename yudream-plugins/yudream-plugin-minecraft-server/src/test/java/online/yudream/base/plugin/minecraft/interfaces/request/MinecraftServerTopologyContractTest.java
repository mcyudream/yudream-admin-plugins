package online.yudream.base.plugin.minecraft.interfaces.request;

import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServerTopology;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServer;
import online.yudream.base.plugin.minecraft.interfaces.support.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire shape the Velocity bridge sends to {@code /report/topology}.
 *
 * <p>The JSON below is exactly what {@code YudreamApiClient.topologyBody} produces on the bridge
 * side, and the bridge has its own test asserting those key names. If either side renames a field,
 * the other side would otherwise drop it silently — a failure mode this project has already hit once
 * with the plugin-channel payload — so these assertions cover the parsed values, not just that
 * parsing succeeded.
 */
class MinecraftServerTopologyContractTest {

    private static final String BRIDGE_PAYLOAD = "{"
            + "\"proxy\":\"velocity\","
            + "\"proxyVersion\":\"3.5.0\","
            + "\"reportedAt\":1757850000000,"
            + "\"addresses\":[\"play.example.com\",\"play.example.com:25565\"],"
            + "\"servers\":["
            + "{\"name\":\"lobby\",\"address\":\"127.0.0.1:25566\",\"online\":3,\"sensor\":true,\"defaultServer\":true},"
            + "{\"name\":\"survival\",\"address\":\"127.0.0.1:25567\",\"online\":0,\"sensor\":false,\"defaultServer\":false}"
            + "]}";

    @Test
    void parsesEveryFieldTheBridgeSends() {
        MinecraftServerTopologyRequest request = JsonSupport.read(BRIDGE_PAYLOAD, MinecraftServerTopologyRequest.class);

        assertEquals("velocity", request.proxy());
        assertEquals("3.5.0", request.proxyVersion());
        assertEquals(1757850000000L, request.reportedAt());
        assertEquals(List.of("play.example.com", "play.example.com:25565"), request.addresses());
        assertEquals(2, request.servers().size());

        MinecraftServerTopologyRequest.Server lobby = request.servers().get(0);
        assertEquals("lobby", lobby.name());
        assertEquals("127.0.0.1:25566", lobby.address());
        assertEquals(3, lobby.online());
        assertTrue(lobby.sensor());
        assertTrue(lobby.defaultServer());
    }

    @Test
    void carriesThePayloadThroughTheDomainModel() {
        MinecraftServerTopologyRequest request = JsonSupport.read(BRIDGE_PAYLOAD, MinecraftServerTopologyRequest.class);
        List<MinecraftSubServer> servers = List.of(
                new MinecraftSubServer("survival", "127.0.0.1:25567", 0, false, false, 1),
                new MinecraftSubServer("lobby", "127.0.0.1:25566", 3, true, true, 0));

        MinecraftServerTopology topology = new MinecraftServerTopology(
                "server-1", request.proxy(), request.proxyVersion(), request.reportedAt(), servers);

        assertTrue(topology.reported());
        assertEquals(3, topology.onlinePlayers());
        assertEquals(1L, topology.sensorCount());
        assertEquals(List.of("lobby", "survival"), topology.servers().stream().map(MinecraftSubServer::name).toList());
    }

    @Test
    void treatsAMissingReportedAtAsNeverReported() {
        MinecraftServerTopology topology = MinecraftServerTopology.empty("server-1");
        assertFalse(topology.reported());
        assertEquals(0, topology.onlinePlayers());
        assertEquals(0L, topology.sensorCount());
    }

    @Test
    void rejectsASubServerWithoutAName() {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftSubServer("  ", "127.0.0.1:25566", 0, false, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftSubServer("two words", "127.0.0.1:25566", 0, false, false, 0));
    }
}
