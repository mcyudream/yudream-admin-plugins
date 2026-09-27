package online.yudream.base.plugin.mcpanel.domain.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端口分配器单测：附加端口分配与变更计划（主端口不可变/池内/保留/重复/1:1/上限）。 */
class PortAllocatorTest {

    /** 内存台账：键 = nodeId:port:proto，值 = instanceId。 */
    private static final class FakePortRepo implements PortAllocationRepository {
        private final Map<String, String> ledger = new HashMap<>();

        private String key(String nodeId, int port, String proto) {
            return nodeId + ":" + port + ":" + proto;
        }

        @Override
        public boolean allocate(String nodeId, int port, String proto, String instanceId) {
            String key = key(nodeId, port, proto);
            String owner = ledger.get(key);
            if (owner != null && !owner.equals(instanceId)) {
                return false;
            }
            ledger.put(key, instanceId);
            return true;
        }

        @Override
        public boolean release(String nodeId, int port, String proto) {
            return ledger.remove(key(nodeId, port, proto)) != null;
        }

        @Override
        public Optional<String> ownerOf(String nodeId, int port, String proto) {
            return Optional.ofNullable(ledger.get(key(nodeId, port, proto)));
        }

        @Override
        public List<Record> findByInstance(String instanceId) {
            List<Record> found = new ArrayList<>();
            ledger.forEach((key, owner) -> {
                if (owner.equals(instanceId)) {
                    String[] parts = key.split(":");
                    found.add(new Record(parts[0], Integer.parseInt(parts[1]), parts[2], owner));
                }
            });
            return found;
        }

        @Override
        public long countByNode(String nodeId) {
            return ledger.keySet().stream().filter(key -> key.startsWith(nodeId + ":")).count();
        }
    }

    private static McpanelNode node(Integer tcpStart, Integer tcpEnd, List<Integer> reserved) {
        return McpanelNode.create("node-1", "节点一", "wss://127.0.0.1:9701", "pinned",
                "a".repeat(64), true, "test", true, tcpStart, tcpEnd, null, 1L)
                .withConfig("节点一", "wss://127.0.0.1:9701", "pinned", "a".repeat(64),
                        true, "test", true, tcpStart, tcpEnd, reserved, null, 1L);
    }

    private static McpanelInstance.PortMapping port(int host, String proto) {
        return new McpanelInstance.PortMapping(host, host, proto);
    }

    // ---------- allocateExtra ----------

    @Test
    void allocateExtraAutoPicksFirstFreeInPool() {
        FakePortRepo repo = new FakePortRepo();
        PortAllocator allocator = new PortAllocator(repo);
        McpanelNode node = node(25565, 25570, List.of());
        repo.allocate("node-1", 25565, "tcp", "inst-a");
        McpanelInstance.PortMapping mapping = allocator.allocateExtra(node, "tcp", null, "inst-b");
        assertEquals(25566, mapping.hostPort());
        assertEquals(mapping.hostPort(), mapping.containerPort());
    }

    @Test
    void allocateExtraAutoSkipsReservedAndOwned() {
        FakePortRepo repo = new FakePortRepo();
        PortAllocator allocator = new PortAllocator(repo);
        McpanelNode node = node(25565, 25570, List.of(25566));
        repo.allocate("node-1", 25565, "tcp", "inst-a");
        McpanelInstance.PortMapping mapping = allocator.allocateExtra(node, "tcp", null, "inst-b");
        assertEquals(25567, mapping.hostPort());
    }

    @Test
    void allocateExtraAutoExhaustedPoolThrows() {
        FakePortRepo repo = new FakePortRepo();
        PortAllocator allocator = new PortAllocator(repo);
        McpanelNode node = node(25565, 25565, List.of());
        repo.allocate("node-1", 25565, "tcp", "inst-a");
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> allocator.allocateExtra(node, "tcp", null, "inst-b"));
        assertEquals("port.exhausted", error.code());
    }

    @Test
    void allocateExtraManualOccupiedThrowsConflict() {
        FakePortRepo repo = new FakePortRepo();
        PortAllocator allocator = new PortAllocator(repo);
        McpanelNode node = node(25565, 25699, List.of());
        repo.allocate("node-1", 25570, "tcp", "inst-a");
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> allocator.allocateExtra(node, "tcp", 25570, "inst-b"));
        assertEquals("port.conflict", error.code());
    }

    @Test
    void allocateExtraManualReservedOrOutOfRangeThrows() {
        FakePortRepo repo = new FakePortRepo();
        PortAllocator allocator = new PortAllocator(repo);
        McpanelNode reserved = node(25565, 25699, List.of(25580));
        assertEquals("port.reserved", assertThrows(McpanelBusinessException.class,
                () -> allocator.allocateExtra(reserved, "tcp", 25580, "inst-a")).code());
        assertEquals("port.out-of-range", assertThrows(McpanelBusinessException.class,
                () -> allocator.allocateExtra(node(25565, 25699, List.of()), "tcp", 8123, "inst-a")).code());
        assertEquals("port.out-of-range", assertThrows(McpanelBusinessException.class,
                () -> allocator.allocateExtra(node(25565, 25699, List.of()), "udp", 25570, "inst-a")).code());
        assertThrows(McpanelBusinessException.class,
                () -> allocator.allocateExtra(node(25565, 25699, List.of()), "sctp", 25570, "inst-a"));
    }

    @Test
    void allocateExtraUdpUsesFixedUdpPool() {
        FakePortRepo repo = new FakePortRepo();
        PortAllocator allocator = new PortAllocator(repo);
        McpanelInstance.PortMapping mapping = allocator.allocateExtra(
                node(null, null, List.of()), "udp", 19140, "inst-a");
        assertEquals(19140, mapping.hostPort());
    }

    // ---------- planChange ----------

    @Test
    void planChangeComputesAdditionsAndRemovals() {
        PortAllocator allocator = new PortAllocator(new FakePortRepo());
        McpanelNode node = node(25565, 25699, List.of());
        List<McpanelInstance.PortMapping> current = List.of(port(25565, "tcp"), port(25570, "tcp"));
        List<McpanelInstance.PortMapping> desired = List.of(port(25565, "tcp"), port(19140, "udp"));
        PortAllocator.ChangePlan plan = allocator.planChange(node, current, desired);
        assertEquals(List.of(port(19140, "udp")), plan.additions());
        assertEquals(List.of(port(25570, "tcp")), plan.removals());
    }

    @Test
    void planChangeSameSetIsUnchanged() {
        PortAllocator allocator = new PortAllocator(new FakePortRepo());
        McpanelNode node = node(25565, 25699, List.of());
        List<McpanelInstance.PortMapping> current = List.of(port(25565, "tcp"));
        assertTrue(allocator.planChange(node, current, List.of(port(25565, "tcp"))).unchanged());
    }

    @Test
    void planChangeRejectsEmptyDesiredAndPrimaryChange() {
        PortAllocator allocator = new PortAllocator(new FakePortRepo());
        McpanelNode node = node(25565, 25699, List.of());
        List<McpanelInstance.PortMapping> current = List.of(port(25565, "tcp"));
        assertEquals("invalid-request", assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current, List.of())).code());
        // 主端口换号拒绝。
        assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current, List.of(port(25566, "tcp"), port(25565, "tcp"))));
        // 主端口换协议同样拒绝（同号不同协议 = 另一个资源）。
        assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current, List.of(port(25565, "udp"))));
    }

    @Test
    void planChangeAllowsEmptyCurrentLegacyData() {
        PortAllocator allocator = new PortAllocator(new FakePortRepo());
        McpanelNode node = node(25565, 25699, List.of());
        PortAllocator.ChangePlan plan = allocator.planChange(node, List.of(), List.of(port(25565, "tcp")));
        assertEquals(List.of(port(25565, "tcp")), plan.additions());
    }

    @Test
    void planChangeValidatesPoolMembershipReservedDuplicateOneToOneAndCap() {
        PortAllocator allocator = new PortAllocator(new FakePortRepo());
        McpanelNode node = node(25565, 25699, List.of(25580));
        List<McpanelInstance.PortMapping> current = List.of(port(25565, "tcp"));
        // 池外 tcp。
        assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current, List.of(port(25565, "tcp"), port(8123, "tcp"))));
        // 保留端口。
        assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current, List.of(port(25565, "tcp"), port(25580, "tcp"))));
        // 组内重复。
        assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current,
                        List.of(port(25565, "tcp"), port(25570, "tcp"), port(25570, "tcp"))));
        // 非 1:1。
        assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current,
                        List.of(port(25565, "tcp"), new McpanelInstance.PortMapping(25570, 25571, "tcp"))));
        // 超过 16 个。
        List<McpanelInstance.PortMapping> tooMany = new ArrayList<>();
        tooMany.add(port(25565, "tcp"));
        for (int i = 0; i < 16; i++) {
            tooMany.add(port(25600 + i, "tcp"));
        }
        assertThrows(McpanelBusinessException.class,
                () -> allocator.planChange(node, current, tooMany));
        // 恰好 16 个合法。
        List<McpanelInstance.PortMapping> exactly16 = new ArrayList<>(tooMany.subList(0, 16));
        assertEquals(16, allocator.planChange(node, current, exactly16).desired().size());
    }
}
