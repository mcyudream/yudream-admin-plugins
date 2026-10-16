package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 错误文案归一：节点文件系统权限拒绝翻译成处置步骤，其余原样透出。 */
class HttpGuardsTest {

    private static String described(String code, String message) {
        return HttpGuards.described(new McpanelBusinessException(code, 409, message));
    }

    @Test
    void translatesNodeUnlinkPermissionDenied() {
        String text = described("node.error",
                "unlinkat /data/instances/inst-a/world/data/minecraft/custom_boss_events.dat: permission denied");
        // 原始 syscall 文案保留（便于对日志），并补上成因与处置步骤
        assertTrue(text.contains("unlinkat /data/instances/inst-a/world/data/minecraft/custom_boss_events.dat"));
        assertTrue(text.contains("节点程序无权读写该文件"));
        assertTrue(text.contains("重新启动一次"));
        assertTrue(text.contains("chown -R"));
    }

    @Test
    void translatesNodeOpenPermissionDenied() {
        String text = described("node.error",
                "open /data/instances/inst-b/world/level.dat: permission denied");
        assertTrue(text.contains("open /data/instances/inst-b/world/level.dat: permission denied"));
        assertTrue(text.contains("节点程序无权读写该文件"));
    }

    @Test
    void keepsUnrelatedPermissionDeniedUntouched() {
        // 网络类 permission denied 不套文件属主文案，避免误导
        String network = described("node.unreachable", "节点调用失败：dial tcp 10.0.0.2:443: connect: permission denied");
        assertFalse(network.contains("节点程序无权读写该文件"));
        assertEquals("节点调用失败：dial tcp 10.0.0.2:443: connect: permission denied", network);
    }

    @Test
    void keepsOrdinaryMessagesUntouched() {
        String plain = described(McpanelBusinessException.CODE_INSTANCE_NOT_FOUND,
                "instance.notFound：节点上不存在该实例的容器/数据。");
        assertEquals("instance.notFound：节点上不存在该实例的容器/数据。", plain);
        // 面板自身的权限文案（缺少权限）不翻译
        assertFalse(described("forbidden", "缺少权限：plugin:mcpanel:manage").contains("节点程序无权读写"));
    }
}
