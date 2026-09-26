package online.yudream.base.plugin.mcpanel.domain.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SFTP 广告地址优先级：显式覆盖 > endpoint host > 节点自报 hostname。 */
class McpanelNodeTest {

    private static McpanelNode node(String endpoint, String reportedHost, String sftpHost) {
        McpanelNode base = McpanelNode.create("n1", "节点", endpoint, "pkix", null, false, null,
                true, 1000L);
        McpanelNode reported = base.withReported("0.1.0", reportedHost, null, 1500L);
        return reported.withConfig("节点", endpoint, "pkix", null, false, null, true, sftpHost, 2000L);
    }

    @Test
    void explicitOverrideWinsOverEndpointAndHostname() {
        assertEquals("sftp.example.com",
                node("wss://10.0.0.8:7443", "container-host", "sftp.example.com").advertisedSftpHost());
    }

    @Test
    void endpointHostUsedWhenNoOverride() {
        assertEquals("10.0.0.8",
                node("wss://10.0.0.8:7443", "container-host", null).advertisedSftpHost());
        assertEquals("10.0.0.8",
                node("wss://10.0.0.8:7443/control", "container-host", "").advertisedSftpHost());
        assertEquals("127.0.0.1",
                node("wss://127.0.0.1:7443", null, "  ").advertisedSftpHost());
    }

    @Test
    void fallsBackToReportedHostnameOnlyWhenNothingElse() {
        assertEquals("container-host", node(null, "container-host", null).advertisedSftpHost());
        assertEquals("", node(null, null, null).advertisedSftpHost());
    }
}
