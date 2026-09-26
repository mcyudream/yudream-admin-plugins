package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 启动器 P2P 会话（M6）：面板即 rendezvous，负责「校验 → 签发票据 → 通知节点开面 → 中继 ICE 候选」。
 *
 * <p>设计红线（§5.9）在实现上的落点：
 * <ul>
 *   <li><b>目的端钉死</b>：票据绑定 (userId, instanceId, 实例容器端口, 过期时间)，节点只会把流量送进该实例端口；</li>
 *   <li><b>授权</b>：全局 P2P 开关 + 实例级开关 + 可选玩家白名单；</li>
 *   <li><b>限额</b>：每用户并发会话、每实例并发会话、票据池上限；速率档随票据下发由节点本地执行；</li>
 *   <li><b>治理</b>：会话状态可查（waiting/connecting/direct/relayed/failed/closed/expired）、关闭与清理有审计。</li>
 * </ul>
 *
 * <p>本服务只做信令与会话生命周期：真实传输（UDP 打洞 + QUIC，失败回落中继）在节点侧
 * {@code p2p.*} 能力里实现；节点未上报该能力时如实返回能力缺口，不伪造成功。
 */
public class P2PSessionService implements AutoCloseable {

    /** 票据与会话 TTL：够启动器建连，短到可以被暴力猜解忽略。 */
    private static final long TTL_MS = 120_000L;
    private static final int MAX_SESSIONS = 2048;
    private static final int MAX_SESSIONS_PER_INSTANCE = 64;
    /** 会话空转回收：双方都没动静超过该时长即回收（打洞失败卡住也不会常驻）。 */
    private static final long IDLE_MS = 180_000L;

    public static final String CAP_OPEN = "p2p.session.open";
    public static final String CAP_SIGNAL = "p2p.signal";
    public static final String CAP_CLOSE = "p2p.session.close";

    /** 会话状态（玩家可见，如实反映底层路径）。 */
    public enum State {
        WAITING, CONNECTING, DIRECT, RELAYED, FAILED, CLOSED, EXPIRED
    }

    /** 节点侧能力与调用（bootstrap 装配；测试用假实现）。 */
    public interface NodePort {

        boolean supports(String nodeId, String capability);

        Map<String, Object> call(String nodeId, String method, Map<String, Object> payload);

        /** 节点对外地址（节点上报的候选主机为空时由面板补齐；取不到返回空串）。 */
        String advertisedHost(String nodeId);
    }

    private final McpanelInstanceRepository instances;
    private final Supplier<PanelSettings.P2p> settings;
    private final NodePort nodes;
    private final McpanelInstanceAppService.AuditRecorder audit;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService sweeper =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "mcpanel-p2p-sweeper");
                thread.setDaemon(true);
                return thread;
            });

    /** 会话（内部可变状态由 volatile 与 synchronized 保护）。 */
    public static final class Session {
        private final String id;
        private final String ticket;
        private final long userId;
        private final String instanceId;
        private final String nodeId;
        private final int targetPort;
        private final int rateKbps;
        private final long issuedAt;
        private final long expiresAt;
        private volatile long lastActivityAt;
        private volatile State state = State.WAITING;
        private volatile String reason = "";
        private volatile List<Map<String, Object>> nodeCandidates = List.of();
        private volatile List<Map<String, Object>> peerCandidates = List.of();
        private volatile long bytesSent;
        private volatile long bytesReceived;

        Session(String id, String ticket, long userId, String instanceId, String nodeId, int targetPort,
                int rateKbps, long issuedAt, long expiresAt) {
            this.id = id;
            this.ticket = ticket;
            this.userId = userId;
            this.instanceId = instanceId;
            this.nodeId = nodeId;
            this.targetPort = targetPort;
            this.rateKbps = rateKbps;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.lastActivityAt = issuedAt;
        }

        public String id() {
            return id;
        }

        public String ticket() {
            return ticket;
        }

        public long userId() {
            return userId;
        }

        public String instanceId() {
            return instanceId;
        }

        public String nodeId() {
            return nodeId;
        }

        public int targetPort() {
            return targetPort;
        }

        public int rateKbps() {
            return rateKbps;
        }

        public long issuedAt() {
            return issuedAt;
        }

        public long expiresAt() {
            return expiresAt;
        }

        public State state() {
            return state;
        }

        public String reason() {
            return reason;
        }

        public long bytesSent() {
            return bytesSent;
        }

        public long bytesReceived() {
            return bytesReceived;
        }

        /** 节点侧候选（面板已补齐 host）。 */
        public List<Map<String, Object>> nodeCandidates() {
            return nodeCandidates;
        }

        /** 玩家侧候选（打洞路径互见用）。 */
        public List<Map<String, Object>> peerCandidates() {
            return peerCandidates;
        }

        boolean expired(long now) {
            return now >= expiresAt || now - lastActivityAt >= IDLE_MS;
        }
    }

    public P2PSessionService(McpanelInstanceRepository instances, Supplier<PanelSettings.P2p> settings,
                             NodePort nodes, McpanelInstanceAppService.AuditRecorder audit) {
        this.instances = instances;
        this.settings = settings;
        this.nodes = nodes;
        this.audit = audit;
        this.sweeper.scheduleWithFixedDelay(this::sweepQuietly, 30_000L, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 全局是否开启（跨插件契约的可用性判据）。 */
    public boolean enabled() {
        PanelSettings.P2p config = settings.get();
        return config != null && config.enabled();
    }

    /** 实例是否已开放 P2P（不泄露白名单内容）。 */
    public boolean instanceOpen(String instanceId) {
        return instances.findById(instanceId == null ? "" : instanceId)
                .map(McpanelInstance::p2pEnabled)
                .orElse(false);
    }

    // ---------- 玩家面：开会话 / 中继候选 / 查询 / 关闭 ----------

    /**
     * 开启 P2P 会话：校验授权与限额 → 签发票据 → 通知节点开面（节点据此准备监听/打洞）。
     * 节点不支持时返回能力缺口（不抛错），由调用方展示"暂不可用"。
     */
    public Map<String, Object> open(long userId, String instanceId) {
        PanelSettings.P2p config = settings.get();
        if (config == null || !config.enabled()) {
            throw new McpanelBusinessException("p2p.disabled", 403, "P2P 直连未开启");
        }
        McpanelInstance instance = instances.findById(instanceId == null ? "" : instanceId)
                .orElseThrow(() -> new McpanelBusinessException("instance.not-found", 404, "实例不存在"));
        if (!instance.p2pEnabled()) {
            throw new McpanelBusinessException("p2p.instance-disabled", 403, "该实例未开启 P2P 接入");
        }
        List<String> whitelist = instance.p2pWhitelist();
        if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(String.valueOf(userId))) {
            throw new McpanelBusinessException("p2p.not-allowed", 403, "不在该实例的 P2P 白名单内");
        }
        int targetPort = targetPortOf(instance);
        if (targetPort <= 0) {
            throw new McpanelBusinessException("p2p.no-port", 409, "实例没有可用的 TCP 端口");
        }
        if (!"running".equalsIgnoreCase(instance.state())) {
            throw new McpanelBusinessException("p2p.not-running", 409, "实例未运行：请先启动服务器再连接");
        }
        Map<String, Object> capability = capabilityOf(instance.nodeId());
        if (capability != null) {
            return capability; // 节点未实现 p2p.*：如实返回能力缺口
        }
        return view(openSession(userId, instanceId), true);
    }

    /**
     * 打开会话并返回会话对象（跨插件契约用）：与 HTTP 路径共用同一套校验与开面逻辑，
     * 差别只在能力缺口——这里抛 {@code p2p.node-capability}，由调用方向玩家如实说明。
     */
    public Session openSession(long userId, String instanceId) {
        PanelSettings.P2p config = settings.get();
        if (config == null || !config.enabled()) {
            throw new McpanelBusinessException("p2p.disabled", 403, "P2P 直连未开启");
        }
        McpanelInstance instance = instances.findById(instanceId == null ? "" : instanceId)
                .orElseThrow(() -> McpanelBusinessException.notFound("实例不存在"));
        if (!instance.p2pEnabled()) {
            throw new McpanelBusinessException("p2p.instance-disabled", 403, "该实例未开启 P2P 接入");
        }
        List<String> whitelist = instance.p2pWhitelist();
        if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(String.valueOf(userId))) {
            throw new McpanelBusinessException("p2p.not-allowed", 403, "不在该实例的 P2P 白名单内");
        }
        int targetPort = targetPortOf(instance);
        if (targetPort <= 0) {
            throw new McpanelBusinessException("p2p.no-port", 409, "实例没有可用的 TCP 端口");
        }
        if (!"running".equalsIgnoreCase(instance.state())) {
            throw new McpanelBusinessException("p2p.not-running", 409, "实例未运行：请先启动服务器再连接");
        }
        Map<String, Object> capability = capabilityOf(instance.nodeId());
        if (capability != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> gap = (Map<String, Object>) capability.get("capabilityGap");
            throw new McpanelBusinessException("p2p.node-capability", 409,
                    String.valueOf(gap.getOrDefault("message", "节点尚未支持 P2P 会话")));
        }
        long now = System.currentTimeMillis();
        sweepQuietly();
        if (sessions.size() >= MAX_SESSIONS) {
            throw new McpanelBusinessException("p2p.busy", 503, "P2P 会话数已达上限，请稍后重试");
        }
        long mine = sessions.values().stream().filter(item -> item.userId == userId).count();
        if (mine >= config.maxSessionsPerUser()) {
            throw new McpanelBusinessException("p2p.limit", 429,
                    "并发会话数已达上限（" + config.maxSessionsPerUser() + "）");
        }
        long onInstance = sessions.values().stream().filter(item -> instanceId.equals(item.instanceId)).count();
        if (onInstance >= MAX_SESSIONS_PER_INSTANCE) {
            throw new McpanelBusinessException("p2p.limit", 429, "该实例的并发 P2P 会话已满");
        }
        Session session = new Session(newToken(16), newToken(32), userId, instanceId, instance.nodeId(),
                targetPort, (int) Math.max(0, config.defaultRateKbps()), now, now + TTL_MS);
        sessions.put(session.id, session);
        try {
            nodes.call(instance.nodeId(), CAP_OPEN, Map.of(
                    "sessionId", session.id,
                    "ticket", session.ticket,
                    "instanceId", instance.id(),
                    "targetPort", targetPort,
                    "rateKbps", session.rateKbps,
                    "expiresAt", session.expiresAt));
        }
        catch (RuntimeException error) {
            sessions.remove(session.id);
            throw new McpanelBusinessException("p2p.node-unreachable", 502,
                    "节点不可达，无法建立 P2P 会话：" + error.getMessage());
        }
        record(userId, "p2p.session.open", instance, session, "目标端口 " + targetPort
                + "，速率档 " + session.rateKbps + "kbps，票据 " + shortTicket(session));
        return session;
    }

    /**
     * 中继 ICE 候选：玩家侧提交的候选转给节点，同时返回节点已上报的候选（打洞双方互见）。
     * 打洞成功/失败由节点裁定并通过会话状态回报。
     */
    public Map<String, Object> signal(long userId, String sessionId, List<Map<String, Object>> candidates) {
        Session session = signalSession(userId, sessionId, candidates);
        Map<String, Object> result = view(session, false);
        result.put("nodeCandidates", session.nodeCandidates);
        return result;
    }

    /** 中继候选并返回会话对象（跨插件契约用，语义与 HTTP 路径一致）。 */
    public Session signalSession(long userId, String sessionId, List<Map<String, Object>> candidates) {
        Session session = require(sessionId, userId);
        if (session.state == State.CLOSED || session.state == State.EXPIRED) {
            throw new McpanelBusinessException("p2p.closed", 409, "会话已结束");
        }
        List<Map<String, Object>> peer = candidates == null ? List.of() : candidates;
        session.peerCandidates = List.copyOf(peer);
        session.lastActivityAt = System.currentTimeMillis();
        if (!peer.isEmpty()) {
            session.state = State.CONNECTING;
            nodes.call(session.nodeId, CAP_SIGNAL, Map.of(
                    "sessionId", session.id,
                    "ticket", session.ticket,
                    "candidates", peer));
        }
        return session;
    }

    /** 归属校验后的会话查询（跨插件契约用；不存在或不属于该玩家返回 empty）。 */
    public Optional<Session> findOwned(long userId, String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        return session != null && session.userId == userId ? Optional.of(session) : Optional.empty();
    }

    public Map<String, Object> status(long userId, String sessionId) {
        return view(require(sessionId, userId), false);
    }

    /** 玩家主动关闭（启动器退出/切服）：通知节点回收该会话的监听与连接。 */
    public Map<String, Object> close(long userId, String sessionId, String reason) {
        Session session = require(sessionId, userId);
        return closeInternal(session, reason == null || reason.isBlank() ? "玩家关闭" : reason, State.CLOSED);
    }

    // ---------- 节点事件：状态回报 ----------

    /**
     * 节点回报会话状态（{@code p2p.session.state} 事件）：
     * state ∈ connecting/direct/relayed/failed/closed；附带候选、字节数与失败原因。
     */
    public void onNodeState(String nodeId, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String sessionId = text(payload.get("sessionId"));
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || !session.nodeId.equals(nodeId)) {
            return; // 未知会话/跨节点伪造：忽略
        }
        session.lastActivityAt = System.currentTimeMillis();
        Object candidates = payload.get("candidates");
        if (candidates instanceof List<?> rows) {
            List<Map<String, Object>> parsed = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((key, value) -> item.put(String.valueOf(key), value));
                    parsed.add(item);
                }
            }
            session.nodeCandidates = fillCandidateHosts(session.nodeId, parsed);
        }
        session.bytesSent = Math.max(session.bytesSent, longValue(payload.get("bytesSent")));
        session.bytesReceived = Math.max(session.bytesReceived, longValue(payload.get("bytesReceived")));
        String state = text(payload.get("state"));
        String reason = text(payload.get("reason"));
        if (reason != null && !reason.isBlank()) {
            session.reason = reason;
        }
        if (state == null) {
            return;
        }
        session.state = switch (state.toLowerCase()) {
            case "connecting" -> State.CONNECTING;
            case "direct" -> State.DIRECT;
            case "relayed" -> State.RELAYED;
            case "failed" -> State.FAILED;
            case "closed" -> State.CLOSED;
            default -> session.state;
        };
        if (session.state == State.CLOSED || session.state == State.FAILED) {
            sessions.remove(session.id);
            record(session.userId, session.state == State.FAILED ? "p2p.session.failed" : "p2p.session.closed",
                    instances.findById(session.instanceId).orElse(null), session,
                    session.reason.isBlank() ? "节点回收" : session.reason);
        }
    }

    // ---------- 管理端视图与清理 ----------

    /** 活跃会话（管理端/排障用；不含票据明文）。 */
    public List<Map<String, Object>> activeSessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Session session : sessions.values()) {
            list.add(view(session, false));
        }
        return list;
    }

    /** 管理员/运维强制断流：吊销票据并通知节点回收。 */
    public Map<String, Object> forceClose(String sessionId, String reason) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Map.of("closed", false);
        }
        return closeInternal(session, reason == null || reason.isBlank() ? "管理员断开" : reason, State.CLOSED);
    }

    private Map<String, Object> closeInternal(Session session, String reason, State state) {
        session.state = state;
        session.reason = reason;
        sessions.remove(session.id);
        try {
            nodes.call(session.nodeId, CAP_CLOSE, Map.of(
                    "sessionId", session.id, "ticket", session.ticket, "reason", reason));
        }
        catch (RuntimeException ignored) {
            // 节点不可达：节点侧会按 TTL 自行回收
        }
        record(session.userId, "p2p.session.close", instances.findById(session.instanceId).orElse(null),
                session, reason + "（发出 " + session.bytesSent + " / 接收 " + session.bytesReceived + " 字节）");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("closed", true);
        result.put("sessionId", session.id);
        result.put("state", session.state.name().toLowerCase());
        return result;
    }

    private void sweepQuietly() {
        long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            if (session.expired(now)) {
                session.state = State.EXPIRED;
                if (sessions.remove(session.id, session)) {
                    try {
                        nodes.call(session.nodeId, CAP_CLOSE, Map.of(
                                "sessionId", session.id, "ticket", session.ticket, "reason", "过期回收"));
                    }
                    catch (RuntimeException ignored) {
                        // 下一轮/节点 TTL 兜底
                    }
                    record(session.userId, "p2p.session.expired",
                            instances.findById(session.instanceId).orElse(null), session, "票据/空闲超时");
                }
            }
        }
    }

    /** 节点能力缺口：返回可读说明，null = 该节点支持 P2P 会话。 */
    private Map<String, Object> capabilityOf(String nodeId) {
        if (nodes.supports(nodeId, CAP_OPEN) && nodes.supports(nodeId, CAP_SIGNAL)) {
            return null;
        }
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("nodeCapability", "unavailable");
        gap.put("message", "节点程序尚未支持 P2P 会话（需 mcpanel-node 升级到含 p2p.* 能力的版本）");
        return Map.of("capabilityGap", gap);
    }

    private Session require(String sessionId, long userId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || session.userId != userId) {
            // 不存在与「不属于该玩家」返回同一错误：不泄露他人会话是否存在
            throw new McpanelBusinessException("p2p.not-found", 404, "P2P 会话不存在或已结束");
        }
        return session;
    }

    /**
     * 候选主机补齐：节点只知道自己监听的端口（不知道公网地址/映射后的地址），
     * 由此处按节点对外地址填上——启动器拿到的候选必须是可以直连的 host:port。
     */
    private List<Map<String, Object>> fillCandidateHosts(String nodeId, List<Map<String, Object>> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        String host = null;
        List<Map<String, Object>> filled = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String candidateHost = text(candidate.get("host"));
            if (candidateHost == null || candidateHost.isBlank()) {
                if (host == null) {
                    host = nodes.advertisedHost(nodeId);
                }
                Map<String, Object> copy = new LinkedHashMap<>(candidate);
                copy.put("host", host == null ? "" : host);
                filled.add(copy);
            }
            else {
                filled.add(candidate);
            }
        }
        return List.copyOf(filled);
    }

    private Map<String, Object> view(Session session, boolean withTicket) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.id);
        result.put("instanceId", session.instanceId);
        result.put("nodeId", session.nodeId);
        result.put("state", session.state.name().toLowerCase());
        result.put("expiresAt", session.expiresAt);
        result.put("rateKbps", session.rateKbps);
        result.put("nodeCandidates", session.nodeCandidates);
        result.put("bytesSent", session.bytesSent);
        result.put("bytesReceived", session.bytesReceived);
        if (!session.reason.isBlank()) {
            result.put("reason", session.reason);
        }
        if (withTicket) {
            // 票据只在开会话响应里下发一次：启动器用它完成与节点的握手（目的端钉死）
            result.put("ticket", session.ticket);
            result.put("targetPort", session.targetPort);
        }
        return result;
    }

    /**
     * 票据钉死的目标端口 = 面板分配的**宿主端口**：容器端口经 Docker 端口映射发布到节点宿主，
     * 节点侧只需 dial 127.0.0.1:宿主端口 即可进实例（节点无法直达容器网络，也不该直达）。
     */
    private static int targetPortOf(McpanelInstance instance) {
        return instance.ports().stream()
                .filter(mapping -> "tcp".equals(mapping.proto()))
                .mapToInt(McpanelInstance.PortMapping::hostPort)
                .findFirst()
                .orElse(0);
    }

    private String newToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String shortTicket(Session session) {
        return session.ticket.substring(0, Math.min(6, session.ticket.length())) + "…";
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private void record(long userId, String action, McpanelInstance instance, Session session, String detail) {
        if (audit == null) {
            return;
        }
        audit.record("user:" + userId, action, "instance", session.instanceId,
                (instance == null ? session.instanceId : instance.name()) + "：" + detail,
                instance == null ? null : instance.tenantId());
    }

    /** 会话数（运维/测试观察点）。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 按会话 ID 查（管理端强制断开用）。 */
    public Optional<Session> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void close() {
        sweeper.shutdown();
    }
}
