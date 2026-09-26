package online.yudream.base.plugin.mcpanel.domain.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 端口分配（设计 §4）：池 = 节点 portRange（默认 25565-25599，tcp）
 * 加基岩 udp 19132-19161；保留端口跳过；分配冲突顺序取下一候选（最多 5 轮）。
 * 唯一键作用域 = (nodeId, port, proto)；面板是每节点唯一分配方。
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
