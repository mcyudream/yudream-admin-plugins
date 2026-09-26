package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.service.ServerListPing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例在线玩家探测（server list ping）：
 * - 探测目标 = 节点控制端点主机（面板拨号已验证可达）+ 实例 TCP hostPort；
 *   目标全部来自服务端登记记录，不接受请求参数，无 SSRF 面；
 * - 仅运行中的 Java 版实例发起真实探测；未运行/基岩版/无 TCP 映射直接给原因；
 * - 结果带 8s TTL 缓存，多个管理员同时打开控制台也不会高频探测节点。
 */
public class InstancePlayersService {

    private static final long CACHE_TTL_MS = 8_000L;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int READ_TIMEOUT_MS = 1_500;

    private final McpanelInstanceAppService instances;
    private final McpanelNodeRepository nodeRepository;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public InstancePlayersService(McpanelInstanceAppService instances, McpanelNodeRepository nodeRepository) {
        this.instances = instances;
        this.nodeRepository = nodeRepository;
    }

    public Map<String, Object> players(String scopeKey, String instanceId) {
        McpanelInstance instance = instances.accessibleInstance(scopeKey, instanceId);
        String reason = probeSkipReason(instance);
        if (reason != null) {
            return unreachable(reason, System.currentTimeMillis());
        }
        McpanelNode node = nodeRepository.findById(instance.nodeId())
                .orElseThrow(() -> McpanelBusinessException.notFound("节点不存在"));
        String host = endpointHost(node);
        Integer port = tcpPortOf(instance);
        if (host.isBlank() || port == null) {
            return unreachable("实例无 TCP 端口映射或节点端点缺主机", System.currentTimeMillis());
        }
        String key = host + ":" + port;
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && now - cached.at < CACHE_TTL_MS) {
            return cached.payload();
        }
        Map<String, Object> payload = toDto(ServerListPing.ping(host, port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS), now);
        cache.put(key, new Cached(now, payload));
        return payload;
    }

    /** 不发起 socket 探测的场景与其原因；null = 可以探测。 */
    private String probeSkipReason(McpanelInstance instance) {
        if (!"running".equalsIgnoreCase(instance.state())) {
            return "实例未运行";
        }
        if ("bedrock".equalsIgnoreCase(instance.kind())) {
            return "基岩版实例暂不支持在线探测";
        }
        return null;
    }

    private Map<String, Object> unreachable(String reason, long now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reachable", false);
        result.put("online", 0);
        result.put("max", 0);
        result.put("players", List.of());
        result.put("latencyMs", null);
        result.put("reason", reason);
        result.put("probedAt", now);
        return result;
    }

    private Map<String, Object> toDto(ServerListPing.Result result, long now) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("reachable", result.reachable());
        if (!result.reachable()) {
            dto.put("reason", result.error() == null ? "无法连接" : result.error());
        }
        dto.put("version", result.version());
        dto.put("online", result.online());
        dto.put("max", result.max());
        dto.put("players", result.sample().stream()
                .map(sample -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", sample.name());
                    item.put("id", sample.id());
                    return item;
                })
                .toList());
        dto.put("latencyMs", result.latencyMs());
        dto.put("probedAt", now);
        return dto;
    }

    /** TCP hostPort：优先 containerPort 25565（Java 版默认），否则首条 TCP 映射。 */
    private Integer tcpPortOf(McpanelInstance instance) {
        return instance.ports().stream()
                .filter(mapping -> mapping.proto() == null || "tcp".equalsIgnoreCase(mapping.proto()))
                .filter(mapping -> mapping.containerPort() == 25565)
                .map(McpanelInstance.PortMapping::hostPort)
                .findFirst()
                .orElseGet(() -> instance.ports().stream()
                        .filter(mapping -> mapping.proto() == null || "tcp".equalsIgnoreCase(mapping.proto()))
                        .map(McpanelInstance.PortMapping::hostPort)
                        .findFirst()
                        .orElse(null));
    }

    /** 节点控制端点的 host 部分——面板每次拨号都在验证其可达性。 */
    private String endpointHost(McpanelNode node) {
        String endpoint = node.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String value = endpoint.trim()
                .replace("wss://", "")
                .replace("ws://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }

    private record Cached(long at, Map<String, Object> payload) {
    }
}
