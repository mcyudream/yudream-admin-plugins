package online.yudream.base.plugin.mcpanel.domain.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 端口分配（设计 §4）：池 = 节点 portRange（默认 25565-25599，tcp）
 * 加基岩 udp 19132-19161；保留端口跳过；分配冲突顺序取下一候选（最多 5 轮）。
 * 唯一键作用域 = (nodeId, port, proto)；面板是每节点唯一分配方。
 * 附加端口开放/回收走 {@link #planChange}（主端口不可变）+ {@link #allocateExtra}。
 */
public final class PortAllocator {

    private static final int DEFAULT_TCP_START = 25565;
    private static final int DEFAULT_TCP_END = 25699;
    private static final int UDP_START = 19132;
    private static final int UDP_END = 19261;
    private static final int MAX_ATTEMPTS = 5;

    private final PortAllocationRepository portRepository;

    public PortAllocator(PortAllocationRepository portRepository) {
        this.portRepository = portRepository;
    }

    /** 一次端口变更的执行计划：期望列表 + 相对当前台账的增删集（主端口不可变已校验）。 */
    public record ChangePlan(List<McpanelInstance.PortMapping> desired,
                             List<McpanelInstance.PortMapping> additions,
                             List<McpanelInstance.PortMapping> removals) {
        public boolean unchanged() {
            return additions.isEmpty() && removals.isEmpty();
        }
    }

    public List<McpanelInstance.PortMapping> allocate(McpanelNode node, McpanelInstance spec) {
        return allocateFor(node, spec);
    }

    public List<McpanelInstance.PortMapping> allocateFor(McpanelNode node, McpanelInstance spec) {
        List<McpanelInstance.PortMapping> mappings = new ArrayList<>();
        String proto = "bedrock".equals(spec.kind()) ? "udp" : "tcp";
        int start = "udp".equals(proto) ? UDP_START : tcpStart(node);
        int end = "udp".equals(proto) ? UDP_END : tcpEnd(node);
        for (int round = 0; round < MAX_ATTEMPTS; round++) {
            int port = nextFree(node, proto, start, end);
            if (port < 0) {
                throw new McpanelBusinessException("port.exhausted", 409, "节点端口池已耗尽：" + proto);
            }
            if (portRepository.allocate(node.id(), port, proto, spec.id())) {
                mappings.add(new McpanelInstance.PortMapping(port, port, proto));
                return mappings;
            }
            start = port + 1;
        }
        throw new McpanelBusinessException("port.conflict", 409, "端口分配冲突次数超限");
    }

    /**
     * 为实例追加一个附加端口：requested 为空时从池内顺序取空位；指定时校验池内范围/
     * 保留端口后按台账占用判定。host=container 恒 1:1。
     */
    public McpanelInstance.PortMapping allocateExtra(McpanelNode node, String proto,
                                                     Integer requested, String instanceId) {
        validateProto(proto);
        if (requested != null) {
            validatePoolMembership(node, requested, proto);
            if (!portRepository.allocate(node.id(), requested, proto, instanceId)) {
                throw new McpanelBusinessException("port.conflict", 409,
                        "端口已被占用：" + requested + "/" + proto);
            }
            return new McpanelInstance.PortMapping(requested, requested, proto);
        }
        int start = rangeOf(node, proto)[0];
        for (int round = 0; round < MAX_ATTEMPTS; round++) {
            int port = nextFree(node, proto, start, rangeOf(node, proto)[1]);
            if (port < 0) {
                throw new McpanelBusinessException("port.exhausted", 409, "节点端口池已耗尽：" + proto);
            }
            if (portRepository.allocate(node.id(), port, proto, instanceId)) {
                return new McpanelInstance.PortMapping(port, port, proto);
            }
            start = port + 1;
        }
        throw new McpanelBusinessException("port.conflict", 409, "端口分配冲突次数超限");
    }

    /**
     * 端口列表变更计划：校验期望列表（proto/池内范围/保留端口/组内重复/1:1/上限），
     * 与当前列表求差；当前列表非空时主端口（ports[0]）不可变——节点 rewrite()
     * 依赖它写 server-port，玩家侧连接地址也锚定它。
     */
    public ChangePlan planChange(McpanelNode node,
                                 List<McpanelInstance.PortMapping> current,
                                 List<McpanelInstance.PortMapping> desired) {
        List<McpanelInstance.PortMapping> currentSafe = current == null ? List.of() : current;
        List<McpanelInstance.PortMapping> desiredSafe = desired == null ? List.of() : desired;
        if (desiredSafe.size() > 16) {
            throw McpanelBusinessException.invalid("每实例最多 16 个端口");
        }
        if (!currentSafe.isEmpty()) {
            if (desiredSafe.isEmpty()) {
                throw McpanelBusinessException.invalid("端口列表不能为空：主端口不可移除");
            }
            McpanelInstance.PortMapping primary = currentSafe.get(0);
            McpanelInstance.PortMapping next = desiredSafe.get(0);
            if (next.hostPort() != primary.hostPort() || !next.proto().equals(primary.proto())) {
                throw McpanelBusinessException.invalid("主端口不可变更：" + primary.hostPort() + "/" + primary.proto());
            }
        }
        Set<String> seen = new HashSet<>();
        for (McpanelInstance.PortMapping mapping : desiredSafe) {
            validateProto(mapping.proto());
            if (mapping.hostPort() != mapping.containerPort()) {
                throw McpanelBusinessException.invalid("端口映射仅支持宿主与容器 1:1：" + mapping.hostPort());
            }
            validatePoolMembership(node, mapping.hostPort(), mapping.proto());
            if (!seen.add(mapping.hostPort() + "/" + mapping.proto())) {
                throw McpanelBusinessException.invalid("端口列表存在重复：" + mapping.hostPort() + "/" + mapping.proto());
            }
        }
        Set<String> desiredKeys = new LinkedHashSet<>();
        for (McpanelInstance.PortMapping mapping : desiredSafe) {
            desiredKeys.add(mapping.hostPort() + "/" + mapping.proto());
        }
        List<McpanelInstance.PortMapping> additions = new ArrayList<>();
        for (McpanelInstance.PortMapping mapping : desiredSafe) {
            if (!containsMapping(currentSafe, mapping)) {
                additions.add(mapping);
            }
        }
        List<McpanelInstance.PortMapping> removals = new ArrayList<>();
        for (McpanelInstance.PortMapping mapping : currentSafe) {
            if (!desiredKeys.contains(mapping.hostPort() + "/" + mapping.proto())) {
                removals.add(mapping);
            }
        }
        return new ChangePlan(desiredSafe, additions, removals);
    }

    /** 释放端口台账（删除实例/回滚共用；幂等）。 */
    public void release(String nodeId, int hostPort, String proto) {
        portRepository.release(nodeId, hostPort, proto);
    }

    /** 端口池边界（视图提示用）：tcp=节点配置段（带默认回落），udp=固定段。 */
    public int[] poolBounds(McpanelNode node, String proto) {
        return rangeOf(node, proto);
    }

    private void validatePoolMembership(McpanelNode node, int port, String proto) {
        int[] range = rangeOf(node, proto);
        if (port < range[0] || port > range[1]) {
            throw new McpanelBusinessException("port.out-of-range", 409,
                    "端口超出节点端口池范围 " + proto + " " + range[0] + "-" + range[1] + "：" + port);
        }
        if (node.reservedPorts() != null && node.reservedPorts().contains(port)) {
            throw new McpanelBusinessException("port.reserved", 409, "端口为节点保留端口：" + port);
        }
    }

    private static void validateProto(String proto) {
        if (!"tcp".equals(proto) && !"udp".equals(proto)) {
            throw McpanelBusinessException.invalid("端口协议仅支持 tcp/udp：" + proto);
        }
    }

    private int[] rangeOf(McpanelNode node, String proto) {
        return "udp".equals(proto)
                ? new int[]{UDP_START, UDP_END}
                : new int[]{tcpStart(node), tcpEnd(node)};
    }

    private static boolean containsMapping(List<McpanelInstance.PortMapping> mappings,
                                           McpanelInstance.PortMapping target) {
        return mappings.stream()
                .anyMatch(item -> item.hostPort() == target.hostPort() && item.proto().equals(target.proto()));
    }

    private int nextFree(McpanelNode node, String proto, int start, int end) {
        for (int port = start; port <= end; port++) {
            if (node.reservedPorts() != null && node.reservedPorts().contains(port)) {
                continue;
            }
            if (portRepository.ownerOf(node.id(), port, proto).isEmpty()) {
                return port;
            }
        }
        return -1;
    }

    private int tcpStart(McpanelNode node) {
        return node.portRangeStart() != null ? node.portRangeStart() : DEFAULT_TCP_START;
    }

    private int tcpEnd(McpanelNode node) {
        return node.portRangeEnd() != null ? node.portRangeEnd() : DEFAULT_TCP_END;
    }
}
