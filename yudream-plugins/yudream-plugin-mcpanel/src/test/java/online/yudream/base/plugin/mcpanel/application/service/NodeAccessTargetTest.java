package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「节点接入方式 → 实例域名解析目标」取值：直连用节点广告地址，自填/FRP 用节点上填的地址，
 * 打洞不写公网解析；主机名目标写 CNAME 且 SRV 直指真实主机。
 */
class NodeAccessTargetTest {

    /** 入口配置（entry 模式用）：入口对外地址 + 默认端口。 */
    private static final PanelSettings.Entry ENTRY =
            new PanelSettings.Entry("http://127.0.0.1:8080", "entry.example.com", 25565);

    private static McpanelNode node(String accessMode, String accessHost) {
        return McpanelNode.create("n1", "节点", "wss://10.0.0.8:7000", "pkix", null, false, "", true,
                null, null, "", accessMode, accessHost, 1_700_000_000_000L);
    }

    @Test
    void directModeUsesNodeAdvertisedAddress() {
        DomainService.Target target = McpanelInstanceAppService.domainTargetFor(node("direct", ""), "203.0.113.9", ENTRY);
        assertTrue(target.assignable());
        assertEquals("direct", target.mode());
        assertEquals("节点直连", target.modeLabel());
        assertEquals("A", target.recordType());
        assertEquals("203.0.113.9", target.recordValue());
        assertNull(target.srvTarget(), "IP 目标时 SRV 指向 fqdn 自身");
    }

    @Test
    void manualAndFrpUseConfiguredAddress() {
        DomainService.Target manual = McpanelInstanceAppService.domainTargetFor(
                node("manual", "198.51.100.7"), "10.0.0.8", ENTRY);
        assertEquals("manual", manual.mode());
        assertEquals("198.51.100.7", manual.recordValue());
        assertTrue(manual.assignable());

        DomainService.Target frp = McpanelInstanceAppService.domainTargetFor(
                node("frp", "frp.example.com"), "10.0.0.8", ENTRY);
        assertEquals("frp", frp.mode());
        assertEquals("CNAME", frp.recordType());
        assertEquals("frp.example.com", frp.recordValue());
        assertEquals("frp.example.com", frp.srvTarget(), "CNAME 目标时 SRV 直指真实主机");
    }

    @Test
    void entryModePointsAtEntryAndSkipsSrvOnDefaultPort() {
        DomainService.Target target = McpanelInstanceAppService.domainTargetFor(
                node("entry", ""), "10.0.0.8", ENTRY);
        assertTrue(target.assignable());
        assertEquals("entry", target.mode());
        assertEquals("单端口入口", target.modeLabel());
        assertEquals("CNAME", target.recordType(), "入口地址是主机名 → CNAME");
        assertEquals("entry.example.com", target.recordValue());
        assertEquals(0, target.srvPort(), "入口端口 25565：默认端口无需 SRV");
    }

    @Test
    void entryModeWritesSrvWhenEntryPortIsCustom() {
        DomainService.Target target = McpanelInstanceAppService.domainTargetFor(
                node("entry", ""), "10.0.0.8",
                new PanelSettings.Entry("http://127.0.0.1:8080", "203.0.113.20", 25580));
        assertEquals("A", target.recordType());
        assertEquals(Integer.valueOf(25580), target.srvPort(), "非默认入口端口：写 SRV 指向入口端口");
    }

    @Test
    void entryModeWithoutEntryHostIsBlocked() {
        DomainService.Target target = McpanelInstanceAppService.domainTargetFor(
                node("entry", ""), "10.0.0.8", new PanelSettings.Entry("http://127.0.0.1:8080", "", 25565));
        assertFalse(target.assignable());
        assertTrue(target.blockReason().contains("未配置单端口入口地址"));
    }

    @Test
    void entryModeAcceptsOptionalBackendAddress() {
        assertEquals("127.0.0.1", McpanelNodeAppService.validateAccess("entry", "127.0.0.1"));
        assertEquals("", McpanelNodeAppService.validateAccess("entry", ""));
        assertTrue(messageOf(() -> McpanelNodeAppService.validateAccess("entry", "http://x.y"))
                .contains("入口后端地址"));
    }

    @Test
    void p2pAndMissingAddressAreBlockedWithReason() {
        DomainService.Target p2p = McpanelInstanceAppService.domainTargetFor(node("p2p", "1.2.3.4"), "10.0.0.8", ENTRY);
        assertFalse(p2p.assignable());
        assertTrue(p2p.blockReason().contains("打洞"));

        DomainService.Target manualWithoutHost = McpanelInstanceAppService.domainTargetFor(
                node("manual", ""), "10.0.0.8", ENTRY);
        assertFalse(manualWithoutHost.assignable());
        assertTrue(manualWithoutHost.blockReason().contains("未在节点上填写解析地址"));

        DomainService.Target directWithoutHost = McpanelInstanceAppService.domainTargetFor(node("direct", ""), "", ENTRY);
        assertFalse(directWithoutHost.assignable());
        assertTrue(directWithoutHost.blockReason().contains("对外地址"));
    }

    @Test
    void switchingToDirectOrP2pClearsConfiguredAddress() {
        assertEquals("", McpanelNodeAppService.validateAccess("direct", "203.0.113.10"));
        assertEquals("", McpanelNodeAppService.validateAccess("p2p", "203.0.113.10"));
        assertEquals("203.0.113.10", McpanelNodeAppService.validateAccess("manual", "203.0.113.10"));
        assertEquals("frp.example.com", McpanelNodeAppService.validateAccess("frp", " frp.example.com "));
    }

    @Test
    void invalidAddressIsRejectedOnSave() {
        assertTrue(messageOf(() -> McpanelNodeAppService.validateAccess("frp", "")).contains("需要填写解析地址"));
        assertTrue(messageOf(() -> McpanelNodeAppService.validateAccess("manual", "https://x.y"))
                .contains("只能是 IP"));
        assertTrue(messageOf(() -> McpanelNodeAppService.validateAccess("manual", "host:7000"))
                .contains("只能是 IP"));
    }

    private static String messageOf(Runnable runnable) {
        try {
            runnable.run();
        }
        catch (RuntimeException error) {
            return error.getMessage();
        }
        throw new AssertionError("应当抛出校验异常");
    }
}
