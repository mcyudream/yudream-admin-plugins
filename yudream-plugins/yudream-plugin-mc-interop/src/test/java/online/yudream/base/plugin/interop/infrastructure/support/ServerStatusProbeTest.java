package online.yudream.base.plugin.interop.infrastructure.support;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 状态探测契约：status JSON 解析、favicon 归一、地址解析（无网络）。 */
class ServerStatusProbeTest {

    @Test
    void parsesStatusJsonIntoDisplayFields() {
        String json = """
                {"version":{"name":"1.21.4"},"players":{"online":3,"max":20},
                 "description":{"extra":[{"text":"欢迎"},{"text":"加入"}],"text":"！"},
                 "favicon":"iVBORw0KGgo="}
                """;
        ServerStatusProbe.Status status = ServerStatusProbe.parseStatus(json, 42);
        assertTrue(status.online());
        assertEquals("！欢迎加入", status.motd());
        assertEquals(3, status.onlinePlayers());
        assertEquals(20, status.maxPlayers());
        assertEquals("data:image/png;base64,iVBORw0KGgo=", status.favicon());
        assertEquals("1.21.4", status.versionName());
        assertEquals(42, status.pingMs());
    }

    @Test
    void malformedJsonReportsOffline() {
        ServerStatusProbe.Status status = ServerStatusProbe.parseStatus("<html>502</html>", 5);
        assertFalse(status.online());
        assertNull(status.favicon());
        assertEquals(0, status.maxPlayers());
    }

    @Test
    void parseAddressSplitsHostPortAndDefaultsPort() {
        InetSocketAddress withPort = ServerStatusProbe.parseAddress("play.example.com:25566");
        assertEquals("play.example.com", withPort.getHostName());
        assertEquals(25566, withPort.getPort());

        InetSocketAddress bare = ServerStatusProbe.parseAddress("mc.example.org");
        assertEquals("mc.example.org", bare.getHostName());
        assertEquals(25565, bare.getPort());

        assertNull(ServerStatusProbe.parseAddress("host:notaport"));
        assertNull(ServerStatusProbe.parseAddress("host:99999"));
        assertNull(ServerStatusProbe.parseAddress("  "));
    }
}
