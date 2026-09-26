package online.yudream.base.plugin.mcpanel.domain.aggregate;

import online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeAccess;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;

import java.util.List;

/**
 * 面板节点聚合。endpoint/tls 配置由管理员预配置（SSRF 边界）；
 * reported* 字段由节点 bootstrap 上报；status/lastStats 由控制信道运行时维护；
 * tenantId 自 M1 预留，M7 前恒为 null，任何请求都不得写入。
 *
 * <p>enrolledAtMs 为注册权威标记：0 = 未注册；bootstrap 原子 CAS（见
 * McpanelNodeRepository#markEnrolledOnce）唯一写入点。enrolled() 对历史文档
 * （enrolledAtMs 尚无值的旧记录）按 reportedHost/agentVersion 兜底。
 */
public record McpanelNode(
        String id,
        String name,
        String endpoint,
        String tlsMode,
        String pinSha256,
        boolean localDevelopment,
        String remark,
        boolean enabled,
        String tenantId,
        String status,
        String agentVersion,
        String reportedHost,
        String sessionId,
        List<String> caps,
        String dockerVersion,
        String reportedCertSha256,
        NodeStatsSnapshot lastStats,
        long enrolledAtMs,
        long lastSeenAtMs,
        long createdAtMs,
        long updatedAtMs,
        Integer portRangeStart,
        Integer portRangeEnd,
        List<Integer> reservedPorts,
        String sftpHost,
        /** 玩家接入方式（见 {@link NodeAccess}）：决定实例域名解析记录怎么取值。 */
        String accessMode,
        /** 解析地址：manual/frp 时必填（IP 或主机名）；direct 时忽略。 */
        String accessHost) {

    public McpanelNode {
        caps = caps == null ? List.of() : List.copyOf(caps);
        reservedPorts = reservedPorts == null ? List.of() : List.copyOf(reservedPorts);
        accessMode = NodeAccess.normalizeMode(accessMode);
        accessHost = accessHost == null ? "" : accessHost.trim();
    }

    public static McpanelNode create(String id, String name, String endpoint, String tlsMode,
                                     String pinSha256, boolean localDevelopment, String remark,
                                     boolean enabled, long nowMs) {
        return create(id, name, endpoint, tlsMode, pinSha256, localDevelopment, remark,
                enabled, null, nowMs);
    }

    public static McpanelNode create(String id, String name, String endpoint, String tlsMode,
                                     String pinSha256, boolean localDevelopment, String remark,
                                     boolean enabled, String sftpHost, long nowMs) {
        return create(id, name, endpoint, tlsMode, pinSha256, localDevelopment, remark,
                enabled, null, null, sftpHost, nowMs);
    }

    public static McpanelNode create(String id, String name, String endpoint, String tlsMode,
                                     String pinSha256, boolean localDevelopment, String remark,
                                     boolean enabled, Integer portRangeStart, Integer portRangeEnd,
                                     String sftpHost, long nowMs) {
        return create(id, name, endpoint, tlsMode, pinSha256, localDevelopment, remark,
                enabled, portRangeStart, portRangeEnd, sftpHost, null, null, nowMs);
    }

    public static McpanelNode create(String id, String name, String endpoint, String tlsMode,
                                     String pinSha256, boolean localDevelopment, String remark,
                                     boolean enabled, Integer portRangeStart, Integer portRangeEnd,
                                     String sftpHost, String accessMode, String accessHost, long nowMs) {
        return new McpanelNode(id, name, endpoint, tlsMode, pinSha256, localDevelopment, remark,
                enabled, null, NodeStatus.OFFLINE.code(), null, null, null,
                List.of(), null, null, null, 0L, 0L, nowMs, nowMs,
                portRangeStart, portRangeEnd, List.of(), sftpHost, accessMode, accessHost);
    }

    public McpanelNode withConfig(String name, String endpoint, String tlsMode, String pinSha256,
                                  boolean localDevelopment, String remark, boolean enabled, long nowMs) {
        return withConfig(name, endpoint, tlsMode, pinSha256, localDevelopment, remark, enabled,
                sftpHost, nowMs);
    }

    public McpanelNode withConfig(String name, String endpoint, String tlsMode, String pinSha256,
                                  boolean localDevelopment, String remark, boolean enabled,
                                  String sftpHost, long nowMs) {
        return withConfig(name, endpoint, tlsMode, pinSha256, localDevelopment, remark, enabled,
                portRangeStart, portRangeEnd, reservedPorts, sftpHost, nowMs);
    }

    public McpanelNode withConfig(String name, String endpoint, String tlsMode, String pinSha256,
                                  boolean localDevelopment, String remark, boolean enabled,
                                  Integer portStart, Integer portEnd, List<Integer> reserved, long nowMs) {
        return withConfig(name, endpoint, tlsMode, pinSha256, localDevelopment, remark, enabled,
                portStart, portEnd, reserved, sftpHost, nowMs);
    }

    public McpanelNode withConfig(String name, String endpoint, String tlsMode, String pinSha256,
                                  boolean localDevelopment, String remark, boolean enabled,
                                  Integer portStart, Integer portEnd, List<Integer> reserved,
                                  String sftpHost, long nowMs) {
        return withConfig(name, endpoint, tlsMode, pinSha256, localDevelopment, remark, enabled,
                portStart, portEnd, reserved, sftpHost, accessMode, accessHost, nowMs);
    }

    /** 配置更新：接入方式与解析地址随配置一起保存（direct 时地址留空）。 */
    public McpanelNode withConfig(String name, String endpoint, String tlsMode, String pinSha256,
                                  boolean localDevelopment, String remark, boolean enabled,
                                  Integer portStart, Integer portEnd, List<Integer> reserved,
                                  String sftpHost, String newAccessMode, String newAccessHost, long nowMs) {
        return new McpanelNode(id, name, endpoint, tlsMode, pinSha256, localDevelopment, remark,
                enabled, tenantId, status, agentVersion, reportedHost, sessionId, caps,
                dockerVersion, reportedCertSha256, lastStats, enrolledAtMs, lastSeenAtMs,
                createdAtMs, nowMs, portStart, portEnd, reserved, sftpHost, newAccessMode, newAccessHost);
    }

    /** bootstrap 上报：填充展示与能力字段并盖注册时间戳（enrolledAtMs 唯一写点为 markEnrolledOnce 的 CAS）。 */
    public McpanelNode withReported(String agentVersion, String reportedHost,
                                    String reportedCertSha256, long nowMs) {
        return new McpanelNode(id, name, endpoint, tlsMode, pinSha256, localDevelopment, remark,
                enabled, tenantId, status, agentVersion, reportedHost, sessionId, caps,
                dockerVersion, reportedCertSha256, lastStats, enrolledAtMs == 0 ? nowMs : enrolledAtMs,
                lastSeenAtMs, createdAtMs, nowMs, portRangeStart, portRangeEnd, reservedPorts, sftpHost,
                accessMode, accessHost);
    }

    /**
     * 注册时 TOFU 登记初始 pin（enroll 自签简化）：pinned 节点管理员未预填指纹时，
     * 以节点 bootstrap 上报的证书指纹作为初始 pinSha256。仅允许从空白写入，
     * 绝不覆盖管理员已设指纹（唯一调用点 markEnrolledOnce 的注册 CAS）。
     */
    public McpanelNode withRegisteredPin(String registeredPinSha256) {
        return new McpanelNode(id, name, endpoint, tlsMode, registeredPinSha256, localDevelopment,
                remark, enabled, tenantId, status, agentVersion, reportedHost, sessionId, caps,
                dockerVersion, reportedCertSha256, lastStats, enrolledAtMs, lastSeenAtMs,
                createdAtMs, updatedAtMs, portRangeStart, portRangeEnd, reservedPorts, sftpHost,
                accessMode, accessHost);
    }

    public McpanelNode withRuntime(NodeStatus status, String agentVersion, String reportedHost,
                                   String sessionId, List<String> caps, String dockerVersion,
                                   NodeStatsSnapshot lastStats, long lastSeenAtMs, long nowMs) {
        return new McpanelNode(id, name, endpoint, tlsMode, pinSha256, localDevelopment, remark,
                enabled, tenantId, status.code(), agentVersion, reportedHost, sessionId, caps,
                dockerVersion, reportedCertSha256, lastStats, enrolledAtMs, lastSeenAtMs,
                createdAtMs, nowMs, portRangeStart, portRangeEnd, reservedPorts, sftpHost,
                accessMode, accessHost);
    }

    /** 解析地址（manual/frp 填的 IP 或主机名；direct/p2p 为空）。 */
    public String accessTarget() {
        return accessHost == null ? "" : accessHost.trim();
    }

    /**
     * SFTP 通道对管理员广告的主机：显式 sftpHost 覆盖 > endpoint 的 host 部分
     * （面板拨号已验证可达）> 节点自报 hostname（仅兜底，容器内常不可路由）。
     * 该地址只用于展示与客户端直连，面板自身绝不向其发起连接（无 SSRF 面）。
     */
    public String advertisedSftpHost() {
        if (sftpHost != null && !sftpHost.isBlank()) {
            return sftpHost.trim();
        }
        if (endpoint != null && !endpoint.isBlank()) {
            String value = endpoint.replace("wss://", "").replace("ws://", "");
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            int colon = value.indexOf(':');
            String host = colon > 0 ? value.substring(0, colon) : value;
            if (!host.isBlank()) {
                return host;
            }
        }
        return reportedHost == null ? "" : reportedHost;
    }

    public boolean online() {
        return NodeStatus.ONLINE.code().equals(status);
    }

    /** 是否已完成 bootstrap 注册：enrolledAtMs 权威，历史文档按上报字段兜底。 */
    public boolean enrolled() {
        return enrolledAtMs > 0
                || (reportedHost != null && !reportedHost.isBlank())
                || (agentVersion != null && !agentVersion.isBlank());
    }
}
