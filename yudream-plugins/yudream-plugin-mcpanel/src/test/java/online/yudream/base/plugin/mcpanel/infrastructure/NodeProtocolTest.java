package online.yudream.base.plugin.mcpanel.infrastructure;

import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeProtocol;
import online.yudream.base.plugin.mcpanel.infrastructure.support.StatsMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeProtocolTest {

    @Test
    void requestRoundTripsThroughDecode() {
        NodeProtocol.Envelope request = NodeProtocol.Envelope.request(
                NodeProtocol.M_NODE_HELLO, Map.of("panelVersion", NodeProtocol.PANEL_VERSION));
        String frame = NodeProtocol.encode(request);
        Optional<NodeProtocol.Envelope> decoded = NodeProtocol.decode(frame);
        assertTrue(decoded.isPresent());
        assertEquals(request.id(), decoded.get().id());
        assertEquals("req", decoded.get().t());
        assertEquals("node.hello", decoded.get().m());
        assertEquals(1, decoded.get().v());
        assertEquals(NodeProtocol.PANEL_VERSION, decoded.get().p().get("panelVersion"));
    }

    @Test
    void evtCarriesSeqWithoutId() {
        NodeProtocol.Envelope envelope = new NodeProtocol.Envelope(1, "evt", "node.stats", null, 7L,
                1000L, Map.of("cpuPercent", 1.5));
        Optional<NodeProtocol.Envelope> decoded = NodeProtocol.decode(NodeProtocol.encode(envelope));
        assertTrue(decoded.isPresent());
        assertNull(decoded.get().id());
        assertEquals(7L, decoded.get().seq());
    }

    @Test
    void malformedFramesDecodeEmpty() {
        assertTrue(NodeProtocol.decode(null).isEmpty());
        assertTrue(NodeProtocol.decode("").isEmpty());
        assertTrue(NodeProtocol.decode("not json").isEmpty());
        assertTrue(NodeProtocol.decode("[1,2,3]").isEmpty());
        assertTrue(NodeProtocol.decode("{\"v\":2,\"t\":\"req\",\"m\":\"x\"}").isEmpty());
    }

    @Test
    void ulidIs26CharsCrockfordAndUnique() {
        String first = NodeProtocol.newUlid();
        assertEquals(26, first.length());
        assertTrue(first.matches("[0-9A-HJKMNP-TV-Z]{26}"));
        assertNotEqualsUlid(first, NodeProtocol.newUlid());
    }

    private static void assertNotEqualsUlid(String a, String b) {
        if (a.equals(b)) {
            throw new AssertionError("ULID collision");
        }
    }

    @Test
    void statsWireMapsToFrozenSchema() {
        Map<String, Object> wire = Map.of(
                "cpuPercent", 12.5,
                "memUsedMb", 1024,
                "memTotalMb", 8192,
                "diskUsedGb", 51,
                "diskTotalGb", 204,
                "load1", 0.42,
                "containers", List.of(Map.of(
                        "instanceId", "i-1", "state", "running", "cpuPercent", 5.1, "memUsedMb", 256)),
                "dockerVersion", "24.0.7",
                "agentVersion", NodeProtocol.PANEL_VERSION,
                "unknownField", "ignored");
        NodeStatsSnapshot snapshot = StatsMapper.fromWire(wire, 1234L, "fallback");
        assertEquals(12.5, snapshot.cpuPercent());
        assertEquals(1024L, snapshot.memUsedMb());
        assertEquals(8192L, snapshot.memTotalMb());
        assertEquals(51L, snapshot.diskUsedGb());
        assertEquals(204L, snapshot.diskTotalGb());
        assertEquals(0.42, snapshot.load());
        assertEquals(1, snapshot.containers().size());
        assertEquals("i-1", snapshot.containers().get(0).instanceId());
        assertEquals("running", snapshot.containers().get(0).state());
        assertEquals(256L, snapshot.containers().get(0).memUsedMb());
        assertEquals("24.0.7", snapshot.dockerVersion());
        assertEquals(NodeProtocol.PANEL_VERSION, snapshot.agentVersion());
        assertEquals(1234L, snapshot.reportedAtMs());
    }

    @Test
    void statsLoad1MayBeNullAndMissingFieldsZero() {
        NodeStatsSnapshot snapshot = StatsMapper.fromWire(Map.of("cpuPercent", 3.2), 1L, NodeProtocol.PANEL_VERSION);
        assertNull(snapshot.load());
        assertEquals(3.2, snapshot.cpuPercent());
        assertEquals(0L, snapshot.memUsedMb());
        assertTrue(snapshot.containers().isEmpty());
        assertEquals(NodeProtocol.PANEL_VERSION, snapshot.agentVersion());
    }
}
