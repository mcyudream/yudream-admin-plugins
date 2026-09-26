package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.cmd.NodeCreateCmd;
import online.yudream.base.plugin.mcpanel.application.cmd.NodeQueryCmd;
import online.yudream.base.plugin.mcpanel.application.cmd.NodeUpdateCmd;
import online.yudream.base.plugin.mcpanel.application.dto.NodeDTO;
import online.yudream.base.plugin.mcpanel.application.dto.EnrollTokenDTO;
import online.yudream.base.plugin.mcpanel.application.port.NodeControlPlane;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.EnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.service.NodeEndpointPolicy;
import online.yudream.base.plugin.mcpanel.domain.valobj.EnrollToken;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeAccess;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeQuery;
import online.yudream.base.plugin.mcpanel.domain.valobj.PageResult;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 节点管理用例（admin 面）。tenantId 为 M7 预留字段，任何命令都不得写入。
 */
public class McpanelNodeAppService {

    public static final long ENROLL_TOKEN_TTL_MS = 10 * 60 * 1000L;

    private final McpanelNodeRepository nodeRepository;
    private final EnrollTokenRepository tokenRepository;
    private final NodeSecrets secrets;
    private final NodeControlPlane controlPlane;
    private final LongSupplier clock;
    /** 节点删除前的实例级联清理（生产由 McpanelInstanceAppService 提供；null 仅限测试）。 */
    private final InstanceCascadeCleaner cascadeCleaner;

    /** 接入方式变化回调（实例侧 PROXY protocol 跟随模式自动开/关）；bootstrap 装配，可空。 */
    public interface AccessModeListener {
        void onAccessModeChanged(String nodeId, String accessMode);
    }

    private volatile AccessModeListener accessModeListener;

    public void attachAccessModeListener(AccessModeListener listener) {
        this.accessModeListener = listener;
    }

    /** 整机性能历史（列表页曲线数据源）；bootstrap 装配，可空。 */
    private volatile MetricsHistoryService metricsHistory;

    /** 装配整机性能历史（后台采集的窗口点，随节点删除清理）。 */
    public void attachMetricsHistory(MetricsHistoryService history) {
        this.metricsHistory = history;
    }

    public McpanelNodeAppService(McpanelNodeRepository nodeRepository,
                                 EnrollTokenRepository tokenRepository,
                                 NodeSecrets secrets,
                                 NodeControlPlane controlPlane) {
        this(nodeRepository, tokenRepository, secrets, controlPlane, System::currentTimeMillis, null);
    }

    public McpanelNodeAppService(McpanelNodeRepository nodeRepository,
                                 EnrollTokenRepository tokenRepository,
                                 NodeSecrets secrets,
                                 NodeControlPlane controlPlane,
                                 LongSupplier clock) {
        this(nodeRepository, tokenRepository, secrets, controlPlane, clock, null);
    }

    public McpanelNodeAppService(McpanelNodeRepository nodeRepository,
                                 EnrollTokenRepository tokenRepository,
                                 NodeSecrets secrets,
                                 NodeControlPlane controlPlane,
                                 LongSupplier clock,
                                 InstanceCascadeCleaner cascadeCleaner) {
        this.nodeRepository = nodeRepository;
        this.tokenRepository = tokenRepository;
        this.secrets = secrets;
        this.controlPlane = controlPlane;
        this.clock = clock;
        this.cascadeCleaner = cascadeCleaner;
    }

    /** 节点删除级联清理端口：删除实例记录、端口占用与节点侧容器（见实例服务实现）。 */
    public interface InstanceCascadeCleaner {

        /** 清理该节点下全部实例；返回清理的实例数。 */
        int cascadeDeleteByNode(String nodeId);
    }

    /**
     * 四态筛选（enrolling/connecting/online/offline）以 DTO 派生状态为准：
     * repo 仅按 keyword 过滤；status 筛选在本层先派生再过滤、后切片，
     * total = 过滤后真实总数（遍历直到取尽，非对已分页结果过滤）。
     * 遍历批次必须为 100：repo 侧 NodeQuery.sizeOrDefault 上限 100，请求 200 会被
     * 钳到 100，导致 "== 批次" 终止条件在第 1 页即退出（>100 节点被截断）。
     * 无 status 筛选时直接走存储分页（真实 total，效率最优）。
     */
    public PageResult<NodeDTO> page(NodeQueryCmd cmd) {
        boolean statusFiltered = cmd.status() != null && !cmd.status().isBlank();
        if (!statusFiltered) {
            NodeQuery query = new NodeQuery(cmd.page(), cmd.size(), null, cmd.keyword());
            PageResult<McpanelNode> result = nodeRepository.page(query);
            return PageResult.of(result.records().stream().map(this::toDto).toList(),
                    result.total(), result.page(), result.size());
        }
        String status = cmd.status().trim().toLowerCase(java.util.Locale.ROOT);
        List<NodeDTO> matched = new java.util.ArrayList<>();
        int pageNo = 1;
        List<McpanelNode> batch;
        do {
            batch = nodeRepository.page(new NodeQuery(pageNo, 100, null, cmd.keyword())).records();
            for (McpanelNode node : batch) {
                NodeDTO dto = toDto(node);
                if (dto.status().equals(status)) {
                    matched.add(dto);
                }
            }
            pageNo++;
        } while (batch.size() == 100);
        int page = cmd.page() == null || cmd.page() < 1 ? 1 : cmd.page();
        int size = cmd.size() == null || cmd.size() < 1 ? 10 : Math.min(cmd.size(), 100);
        int from = Math.min((page - 1) * size, matched.size());
        int to = Math.min(from + size, matched.size());
        return PageResult.of(matched.subList(from, to), matched.size(), page, size);
    }

    public NodeDTO create(NodeCreateCmd cmd) {
        String name = requireText(cmd.name(), "name", "必须填写节点名称", 64);
        NodeEndpointPolicy.NormalizedEndpoint endpoint = NodeEndpointPolicy.validate(
                cmd.endpoint(), cmd.tlsMode(), cmd.pinSha256(), cmd.localDevelopment());
        String remark = optionalText(cmd.remark(), "remark", 255);
        String sftpHost = optionalText(cmd.sftpHost(), "sftpHost", 255);
        boolean enabled = cmd.enabled() == null || cmd.enabled();
        Integer portStart = normalizePort(cmd.portRangeStart());
        Integer portEnd = normalizePort(cmd.portRangeEnd());
        validatePortRange(portStart, portEnd);
        String id = UUID.randomUUID().toString().replace("-", "");
        secrets.ensureSecret(id);
        String accessMode = NodeAccess.normalizeMode(cmd.accessMode());
        String accessHost = validateAccess(accessMode, cmd.accessHost());
        McpanelNode node = McpanelNode.create(id, name, endpoint.endpoint(), endpoint.tlsMode(),
                endpoint.pinSha256(), cmd.localDevelopment(), remark, enabled,
                portStart, portEnd, sftpHost, accessMode, accessHost, clock.getAsLong());
        nodeRepository.save(node);
        controlPlane.syncNode(node);
        return toDto(node);
    }

    public NodeDTO update(NodeUpdateCmd cmd) {
        // 全部校验与合并放在 repo.update 的条纹锁内基于仓内最新节点执行，
        // 与 runtime 状态写同一把锁，避免读改写竞态覆盖。
        String[] previousAccessMode = new String[1];
        McpanelNode updated = nodeRepository.update(cmd.nodeId(), current -> {
            previousAccessMode[0] = current.accessMode();
            String name = cmd.name() == null ? current.name()
                    : requireText(cmd.name(), "name", "必须填写节点名称", 64);
            String remark = cmd.remark() == null ? current.remark()
                    : optionalText(cmd.remark(), "remark", 255);
            boolean localDevelopment = cmd.localDevelopment() == null
                    ? current.localDevelopment() : cmd.localDevelopment();
            String endpoint = cmd.endpoint() == null ? current.endpoint() : cmd.endpoint();
            String tlsMode = cmd.tlsMode() == null ? current.tlsMode() : cmd.tlsMode();
            String pin = cmd.pinSha256() == null ? current.pinSha256() : cmd.pinSha256();
            NodeEndpointPolicy.NormalizedEndpoint normalized = NodeEndpointPolicy.validate(
                    endpoint, tlsMode, pin, localDevelopment);
            // 护栏：pinned 空 pin 只在注册前合法（enroll 时 TOFU 登记）；已注册节点
            // 不会再次 enroll，清空指纹等于放弃钉住，必须拒绝而非静默回落 PKIX 拨号。
            if (current.enrolled() && NodeEndpointPolicy.TLS_PINNED.equals(normalized.tlsMode())
                    && (normalized.pinSha256() == null || normalized.pinSha256().isBlank())) {
                throw McpanelBusinessException.invalid("已注册的 pinned 节点必须保留证书指纹，不能清空");
            }
            boolean enabled = cmd.enabled() == null ? current.enabled() : cmd.enabled();
            // sftpHost：null=保持现值，空串=清除覆盖，其余为显式广告地址。
            String sftpHost = cmd.sftpHost() == null ? current.sftpHost()
                    : optionalText(cmd.sftpHost(), "sftpHost", 255);
            // 端口段：null=保持现值，0/负数=清除（回落默认段），否则显式区间。
            Integer portStart = cmd.portRangeStart() == null ? current.portRangeStart()
                    : normalizePort(cmd.portRangeStart());
            Integer portEnd = cmd.portRangeEnd() == null ? current.portRangeEnd()
                    : normalizePort(cmd.portRangeEnd());
            validatePortRange(portStart, portEnd);
            // 接入方式：null=保持现值；解析地址 null=保持、空串=清除（direct/p2p 应清空）。
            String accessMode = cmd.accessMode() == null ? current.accessMode()
                    : NodeAccess.normalizeMode(cmd.accessMode());
            String accessHost = validateAccess(accessMode,
                    cmd.accessHost() == null ? current.accessHost() : cmd.accessHost());
            return current.withConfig(name, normalized.endpoint(), normalized.tlsMode(),
                    normalized.pinSha256(), localDevelopment, remark, enabled,
                    portStart, portEnd, current.reservedPorts(), sftpHost,
                    accessMode, accessHost, clock.getAsLong());
        });
        if (updated == null) {
            throw McpanelBusinessException.notFound("节点不存在");
        }
        controlPlane.syncNode(updated);
        // 接入方式变化 → 该节点上实例的 PROXY protocol 自动跟随（入口开、其它模式关）；
        // 同步失败（节点离线/配置缺失）只记录，不影响节点配置保存本身。
        AccessModeListener listener = accessModeListener;
        if (listener != null && !java.util.Objects.equals(NodeAccess.normalizeMode(previousAccessMode[0]),
                NodeAccess.normalizeMode(updated.accessMode()))) {
            try {
                listener.onAccessModeChanged(updated.id(), updated.accessMode());
            }
            catch (RuntimeException ignored) {
                // 已在同步服务内记录跳过/失败明细
            }
        }
        return toDto(updated);
    }

    public NodeDTO detail(String nodeId) {
        return toDto(find(nodeId));
    }

    /** 节点事件 SSE 流（节点必须存在）。 */
    public online.yudream.base.plugin.spi.http.PluginSseStream stream(String nodeId, Long afterEventId) {
        find(nodeId);
        return controlPlane.openEventStream(nodeId, afterEventId);
    }

    /**
     * 幂等删除：节点 doc 缺失（重试/并发已删）时不再 404，仍清扫残留 token/secret
     * 并停控制面后返回，保证"先删 doc 后清凭据"的中断可安全重试、无不可恢复中间态。
     * 顺序（每步幂等）：0) 级联清理节点下全部实例（容器/端口/记录/回传）；
     * 1) 删 doc（此后节点不可见）；2) 清 token/secret；3) 最后停控制面连接。
     * 绝不在凭据仍在时先停控制面导致"已注册无 secret"中间态。
     */
    public void delete(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw McpanelBusinessException.invalid("必须提供节点 ID");
        }
        if (cascadeCleaner != null) {
            cascadeCleaner.cascadeDeleteByNode(nodeId);
        }
        nodeRepository.delete(nodeId);
        for (EnrollToken token : tokenRepository.findByNodeId(nodeId)) {
            tokenRepository.delete(token.id());
        }
        secrets.delete(nodeId);
        controlPlane.removeNode(nodeId);
        if (metricsHistory != null) {
            metricsHistory.evictNode(nodeId); // 节点整机历史随节点删除清理
        }
    }

    /**
     * 签发一次性注册令牌：明文只在本响应出现，落库为摘要。
     * 仅限尚未完成注册的节点：已注册节点不支持换钥（M1 无 rekey），
     * 需删除并重建节点，操作走管理端审计。
     */
    public EnrollTokenDTO issueEnrollment(String nodeId) {
        McpanelNode node = find(nodeId);
        long now = clock.getAsLong();
        // enrolled/enabled 校验必须在 withNodeLock 内基于最新节点重读（TOCTOU）：
        // 与 bootstrap 领取同一条纹锁，任意时刻"校验→吊销旧→签新"对并发领取原子，
        // 重签后旧 token 立即失效，同节点至多存在一批有效 token。
        return nodeRepository.withNodeLock(node.id(), () -> {
            McpanelNode current = nodeRepository.findById(node.id())
                    .orElseThrow(() -> McpanelBusinessException.notFound("节点不存在"));
            if (current.enrolled()) {
                throw new McpanelBusinessException("node-already-enrolled", 409,
                        "节点已完成注册，M1 不支持在线换钥；如需更换请删除并重建该节点");
            }
            if (!current.enabled()) {
                throw new McpanelBusinessException("node-disabled", 409, "节点已停用，请先启用再签发注册令牌");
            }
            for (EnrollToken old : tokenRepository.findByNodeId(node.id())) {
                tokenRepository.expireOnce(old, now);
            }
            String token = NodeSecrets.newToken();
            EnrollToken enrollToken = new EnrollToken(UUID.randomUUID().toString().replace("-", ""),
                    node.id(), NodeSecrets.sha256Hex(token), now + ENROLL_TOKEN_TTL_MS, 0L, now);
            tokenRepository.save(enrollToken);
            return new EnrollTokenDTO(token, enrollToken.expiresAtMs());
        });
    }

    /**
     * 管理员手动重连：对非在线节点立即触发拨号（清退避）；在线节点为 no-op。
     */
    public NodeDTO reconnect(String nodeId) {
        McpanelNode node = find(nodeId);
        if (!node.enabled()) {
            throw new McpanelBusinessException("node-disabled", 409, "节点已停用，请先启用");
        }
        controlPlane.syncNode(node);
        return toDto(node);
    }

    private McpanelNode find(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw McpanelBusinessException.invalid("必须提供节点 ID");
        }
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> McpanelBusinessException.notFound("节点不存在"));
    }

    private String requireText(String value, String field, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw McpanelBusinessException.invalid(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw McpanelBusinessException.invalid(field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw McpanelBusinessException.invalid(field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private Integer normalizePort(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private void validatePortRange(Integer start, Integer end) {
        if (start == null && end == null) {
            return;
        }
        if (start == null || end == null) {
            throw McpanelBusinessException.invalid("端口分配范围必须同时填写起止端口");
        }
        if (start < 1 || start > 65535 || end < 1 || end > 65535 || start > end) {
            throw McpanelBusinessException.invalid("端口分配范围不合法（1-65535 且起始不大于结束）");
        }
    }

    private NodeDTO toDto(McpanelNode node) {
        NodeControlPlane.NodeRuntime runtime = controlPlane.runtime(node.id());
        boolean online = runtime.online() || node.online();
        // enrolled 权威门禁（enrolledAtMs/上报字段）；hasSecret 仅表示"已完成注册
        // 且凭据存在"，绝不把创建期预生成的 secret 暴露成"已注册"信号。
        boolean enrolled = node.enrolled();
        boolean hasSecret = enrolled && secrets.hasSecret(node.id());
        // 四态状态（前端契约）：enrolling=未 bootstrap；connecting=拨号/握手中；
        // online=hello 确认；offline=其余。分页筛选仍按 online/offline 两态。
        String status;
        if (!enrolled) {
            status = "enrolling";
        } else if (online) {
            status = "online";
        } else if (runtime.connected()) {
            status = "connecting";
        } else {
            status = "offline";
        }
        return new NodeDTO(node.id(), node.name(), node.endpoint(), node.sftpHost(), node.tlsMode(), node.pinSha256(),
                node.localDevelopment(), node.remark(), node.enabled(),
                status,
                node.tenantId(), node.agentVersion(), node.reportedHost(), node.sessionId(),
                node.caps(), node.dockerVersion(), node.reportedCertSha256(),
                runtime.connected(), hasSecret,
                Math.max(node.lastSeenAtMs(), runtime.lastFrameAtMs()),
                node.createdAtMs(), node.updatedAtMs(),
                node.portRangeStart(), node.portRangeEnd(),
                runtime.stats() == null ? null : StatsViews.toMap(runtime.stats()),
                nodeHistoryOf(node.id()),
                node.accessMode(), node.accessHost(), NodeAccess.label(node.accessMode()));
    }

    /**
     * 校验接入方式与解析地址：
     * - manual/frp：必须给出有效地址（IP 或主机名，不含 scheme/端口/路径）；
     * - entry（单端口入口）：地址是「入口机可达的后端地址」，选填（留空用节点对外地址）；
     * - direct/p2p：一律清空地址——避免残留值在日后切回 manual 时突然生效。
     *
     * @return 归一化后的地址（direct/p2p 恒为空串）
     */
    static String validateAccess(String accessMode, String accessHost) {
        String normalized = NodeAccess.normalizeMode(accessMode);
        String host = accessHost == null ? "" : accessHost.trim();
        if (NodeAccess.MODE_ENTRY.equals(normalized)) {
            if (host.isEmpty()) {
                return "";
            }
            if (!NodeAccess.validAddress(host)) {
                throw McpanelBusinessException.invalid("入口后端地址只能是 IP（IPv4/IPv6）或主机名，不含协议、端口与路径");
            }
            return host;
        }
        if (!NodeAccess.requiresHost(normalized)) {
            return "";
        }
        if (host.isEmpty()) {
            throw McpanelBusinessException.invalid(NodeAccess.label(normalized)
                    + " 需要填写解析地址（IP 或主机名，不含协议与端口）");
        }
        if (!NodeAccess.validAddress(host)) {
            throw McpanelBusinessException.invalid("解析地址只能是 IP（IPv4/IPv6）或主机名，不含协议、端口与路径");
        }
        return host;
    }

    /** 节点整机曲线（后台 30s 采样、已落库）：列表页 sparkline 打开即有历史，
     * 不再"进页面才开始攒点"。取最近 30 分钟，最多 60 点。
     */
    private List<Map<String, Object>> nodeHistoryOf(String nodeId) {
        if (metricsHistory == null) {
            return List.of();
        }
        List<Map<String, Object>> samples = new java.util.ArrayList<>();
        for (double[] point : metricsHistory.nodePoints(nodeId, 30 * 60_000L)) {
            Map<String, Object> sample = new java.util.LinkedHashMap<>();
            sample.put("at", (long) point[0]);
            sample.put("cpuPercent", point[1]);
            sample.put("memUsedMb", point[2]);
            if (point.length > 3) {
                sample.put("memTotalMb", point[3]);
            }
            samples.add(sample);
        }
        int size = samples.size();
        return size <= 60 ? samples : new java.util.ArrayList<>(samples.subList(size - 60, size));
    }
}
