package online.yudream.base.plugin.mcpanel.interfaces.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 端口回收路由解析回归：端口段取自 ports 之后（曾误取实例 ID 段导致「端口无效：inst-…」）。 */
class McpanelInstanceFacadeTest {

    @Test
    void portSegmentReadsPortAfterPortsCollection() {
        assertEquals("41003", McpanelInstanceFacade.portSegment("/admin/instances/inst-1/ports/41003"));
        assertEquals("41003", McpanelInstanceFacade.portSegment("/plugins/mcpanel/admin/instances/inst-1/ports/41003"));
        assertEquals("41003", McpanelInstanceFacade.portSegment("mcpanel/admin/instances/inst-1/ports/41003"));
        assertEquals("41003", McpanelInstanceFacade.portSegment("/admin/instances/inst-1/ports/41003/"));
    }

    @Test
    void portSegmentMissingPortReturnsNull() {
        assertNull(McpanelInstanceFacade.portSegment("/admin/instances/inst-1/ports"));
        assertNull(McpanelInstanceFacade.portSegment("/admin/instances/inst-1/ports/"));
    }
}
