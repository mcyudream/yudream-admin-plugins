package online.yudream.base.plugin.mcpanel.domain;

import online.yudream.base.plugin.mcpanel.domain.service.NodeEndpointPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeEndpointPolicyTest {

    @Test
    void pkixWssEndpointNormalizes() {
        var result = NodeEndpointPolicy.validate(" WSS://Node.example.com:9701 ", null, null, false);
        assertEquals("WSS://Node.example.com:9701", result.endpoint().trim());
        assertEquals("pkix", result.tlsMode());
        assertNull(result.pinSha256());
    }

    @Test
    void endpointIsRequired() {
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("", "pkix", null, false));
        assertEquals(400, error.httpStatus());
    }

    @Test
    void wsPlaintextNeverAllowedEvenWithLocalDevelopment() {
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("ws://127.0.0.1:9701", "pkix", null, true));
        assertEquals(400, error.httpStatus());
    }

    @Test
    void loopbackRequiresExplicitLocalDevelopment() {
        assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("wss://localhost:9701", "pkix", null, false));
        var allowed = NodeEndpointPolicy.validate("wss://localhost:9701", "pkix", null, true);
        assertEquals("pkix", allowed.tlsMode());
    }

    @Test
    void linkLocalAndMetadataAlwaysRejected() {
        assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("wss://169.254.169.254:9701", "pkix", null, true));
        assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("wss://fe80::1:9701", "pkix", null, true));
    }

    @Test
    void pinnedAllowsBlankPinForEnrollTofuAndRejectsMalformed() {
        // 空 pin 合法：pinned 节点注册（enroll）时以上报指纹登记初始 pin（TOFU）。
        var blank = NodeEndpointPolicy.validate("wss://node.example.com", "pinned", null, false);
        assertEquals("pinned", blank.tlsMode());
        assertTrue(blank.pinSha256() == null || blank.pinSha256().isBlank());
        // 残缺指纹仍拒绝：留空与完整 64 位 hex 之外的值没有合法语义。
        assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("wss://node.example.com", "pinned", "abc", false));
        var pinned = NodeEndpointPolicy.validate("wss://node.example.com", "pinned", "A".repeat(64), false);
        assertEquals("a".repeat(64), pinned.pinSha256());
    }

    @Test
    void pkixRejectsPin() {
        assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("wss://node.example.com", "pkix", "a".repeat(64), false));
    }

    @Test
    void pathInEndpointRejected() {
        assertThrows(McpanelBusinessException.class,
                () -> NodeEndpointPolicy.validate("wss://node.example.com/control", "pkix", null, false));
    }

    @Test
    void controlUriAppendsFixedPath() {
        assertEquals("wss://node.example.com:9701/control",
                NodeEndpointPolicy.controlUri("wss://node.example.com:9701/").toString());
    }
}
