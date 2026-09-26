package online.yudream.base.plugin.mcpanel.domain.valobj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 节点接入方式值对象：归一化、是否需要地址、是否写解析、地址与记录类型换算。 */
class NodeAccessTest {

    @Test
    void unknownModeFallsBackToDirect() {
        assertEquals(NodeAccess.MODE_DIRECT, NodeAccess.normalizeMode(null));
        assertEquals(NodeAccess.MODE_DIRECT, NodeAccess.normalizeMode("route53"));
        assertEquals(NodeAccess.MODE_DIRECT, NodeAccess.normalizeMode("DIRECT"));
        assertEquals(NodeAccess.MODE_FRP, NodeAccess.normalizeMode("FRP"));
        assertEquals("节点直连", NodeAccess.label(""));
        assertEquals("FRP 入口", NodeAccess.label("frp"));
        assertEquals("启动器打洞", NodeAccess.label("p2p"));
    }

    @Test
    void hostRequirementAndDnsWritingPerMode() {
        assertTrue(NodeAccess.requiresHost("manual"));
        assertTrue(NodeAccess.requiresHost("frp"));
        assertFalse(NodeAccess.requiresHost("direct"));
        assertFalse(NodeAccess.requiresHost("p2p"));

        assertTrue(NodeAccess.writesDns("direct"));
        assertTrue(NodeAccess.writesDns("manual"));
        assertFalse(NodeAccess.writesDns("p2p"));
    }

    @Test
    void addressValidationAcceptsIpAndHostnameOnly() {
        assertTrue(NodeAccess.validAddress("203.0.113.10"));
        assertTrue(NodeAccess.validAddress("2001:db8::1"));
        assertTrue(NodeAccess.validAddress("frp.example.com"));
        assertTrue(NodeAccess.validAddress("mc-relay.example.co.uk"));

        assertFalse(NodeAccess.validAddress(""));
        assertFalse(NodeAccess.validAddress("https://frp.example.com"));
        assertFalse(NodeAccess.validAddress("frp.example.com:7000"));
        assertFalse(NodeAccess.validAddress("203.0.113.10/path"));
        assertFalse(NodeAccess.validAddress("not a host"));
        assertFalse(NodeAccess.validAddress("999.1.1.1"));
        assertFalse(NodeAccess.validAddress("localhost"));
    }

    @Test
    void recordTypeFollowsAddressLiteral() {
        assertEquals("A", NodeAccess.recordTypeOf("203.0.113.10"));
        assertEquals("AAAA", NodeAccess.recordTypeOf("2001:db8::1"));
        assertEquals("CNAME", NodeAccess.recordTypeOf("frp.example.com"));
    }
}
